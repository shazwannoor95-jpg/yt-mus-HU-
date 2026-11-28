// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.app.Activity;
import android.content.res.AssetFileDescriptor;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.MediaFormat;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/** The interactive emulator demo and its optional instrumentation use the same panel. */
final class VisibleVideoPanel extends LinearLayout implements TextureView.SurfaceTextureListener {
    private static final URI LICENSED_RENDITION = URI.create("https://raw.githubusercontent.com/mdn/learning-area/"
            + "21973263ba859ff3477919087b488157f9d618a6/html/multimedia-and-embedding/video-and-audio-content/rabbit320.mp4");
    private static final String LICENSED_SHA256 = "d0502ba7824940e90424847cd6094c858bab778703e382a0fbb71db533e4ad30";
    final Button song, video, fit, full, previous, next, quality;
    final PlayPauseButton play;
    final SeekBar seek;
    private final Activity activity;
    private final PlaybackService service;
    private final DemoMedia media;
    private final ExecutorService loading;
    private final DevicePerformance performance;
    private final VideoAmbientLight ambient;
    private final Button light;
    private final FrameLayout frame;
    private final TextureView picture;
    final TextView caption;
    private final TextView time;
    private final ImageView artwork;
    private Surface surface;
    private DemoMedia.Recording recording;
    private String recordingId = "";
    private MediaFormat delivered;
    private int attempt, chosenHeight;
    private long activeSince;
    private boolean wantVideo, offered, hadVideo, hasFrame, fill, fullscreen, tracking;
    private boolean ambientEnabled = true, ambientActive;
    private Future<?> networkRequest;
    private int networkGeneration;
    private File networkVideo;
    private String error = "";
    private final Runnable clock = new Runnable() {
        @Override public void run() {
            render();
            postDelayed(this, 160);
        }
    };

    VisibleVideoPanel(Activity activity, PlaybackService service, DemoMedia media, ExecutorService loading,
            DevicePerformance performance) {
        super(activity);
        this.activity = activity;
        this.service = service;
        this.media = media;
        this.loading = loading;
        this.performance = performance;
        ambient = new VideoAmbientLight(loading);
        setOrientation(VERTICAL);
        setBackgroundColor(0xff18131b);
        LinearLayout modes = new LinearLayout(activity);
        modes.setGravity(Gravity.CENTER);
        song = action(modes, "Song", this::returnToSong);
        video = action(modes, "Video", this::startVideo);
        quality = action(modes, "Auto quality", this::chooseQuality);
        fit = action(modes, "Fill", () -> {
            fill = !fill;
            fitLabel();
            framePicture();
            refreshAmbient();
        });
        full = action(modes, "Fullscreen", this::toggleFullscreen);
        light = action(modes, "Ambient", () -> {
            ambientEnabled = !ambientEnabled;
            refreshAmbient();
        });
        addView(modes);

        frame = new FrameLayout(activity);
        frame.setBackground(ambient);
        frame.setClipChildren(true);
        LayoutParams stage = new LayoutParams(-1, 0, 1);
        stage.setMargins(dp(24), dp(8), dp(24), dp(8));
        addView(frame, stage);
        picture = new TextureView(activity);
        picture.setSurfaceTextureListener(this);
        frame.addView(picture, new FrameLayout.LayoutParams(-1, -1));
        artwork = new ImageView(activity);
        artwork.setScaleType(ImageView.ScaleType.FIT_CENTER);
        artwork.setBackgroundColor(0xff18131b);
        artwork.setPadding(dp(12), dp(12), dp(12), dp(62));
        artwork.setContentDescription("Recording artwork");
        frame.addView(artwork, new FrameLayout.LayoutParams(-1, -1));
        caption = new TextView(activity);
        caption.setTextColor(Color.WHITE);
        caption.setGravity(Gravity.CENTER);
        caption.setTextSize(16);
        caption.setPadding(dp(8), dp(10), dp(8), dp(10));
        frame.addView(caption, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout transport = new LinearLayout(activity);
        transport.setGravity(Gravity.CENTER_VERTICAL);
        previous = action(transport, "Previous", () -> skip(-1));
        play = new PlayPauseButton(activity);
        style(play);
        play.setOnClickListener(view -> service.toggle());
        transport.addView(play, new LayoutParams(dp(56), dp(48)));
        next = action(transport, "Next", () -> skip(1));
        seek = new SeekBar(activity);
        seek.setContentDescription("Playback position");
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) {}
            @Override public void onStartTrackingTouch(SeekBar bar) { tracking = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                tracking = false;
                service.seekTo(bar.getProgress());
            }
        });
        transport.addView(seek, new LayoutParams(0, dp(48), 1));
        time = new TextView(activity);
        time.setTextColor(Color.WHITE);
        time.setGravity(Gravity.CENTER);
        transport.addView(time, new LayoutParams(dp(94), dp(48)));
        addView(transport);
    }

    private Button action(LinearLayout row, String text, Runnable run) {
        Button button = new Button(activity);
        button.setAllCaps(false);
        button.setText(text);
        style(button);
        button.setOnClickListener(view -> run.run());
        row.addView(button, new LayoutParams(-2, dp(48)));
        return button;
    }

    private void style(Button button) {
        button.setTextColor(Color.WHITE);
        button.setMinWidth(dp(48));
        button.setMinimumWidth(dp(48));
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, buttonSurface(0xff29262e));
        states.addState(new int[]{android.R.attr.state_pressed}, buttonSurface(0xff795163));
        states.addState(new int[]{android.R.attr.state_selected}, buttonSurface(0xff744858));
        states.addState(new int[0], buttonSurface(0xff3a333f));
        button.setBackground(states);
    }

    private GradientDrawable buttonSurface(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setCornerRadius(dp(12));
        drawable.setColor(color);
        drawable.setStroke(dp(1), 0xff18131b);
        return drawable;
    }

    private void fitLabel() { fit.setText(fill ? "Fit" : "Fill"); }

    private void chooseQuality() {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(activity, quality);
        if (recording == null) return;
        menu.getMenu().add("Auto").setOnMenuItemClickListener(item -> { setQuality(0); return true; });
        for (MediaFormat format : recording.renditions) {
            int height = format.getInteger(MediaFormat.KEY_HEIGHT);
            menu.getMenu().add(label(format)).setOnMenuItemClickListener(item -> { setQuality(height); return true; });
        }
        if (networkEligible()) {
            menu.getMenu().add("240p · verified HTTPS").setOnMenuItemClickListener(item -> {
                requestNetworkVideo();
                return true;
            });
        }
        menu.show();
    }

    void setQuality(int selected) {
        selectQuality(selected, null);
    }

    private void selectQuality(int selected, File staged) {
        cancelNetworkVideo();
        boolean restart = wantVideo;
        if (restart) service.endVideo();
        chosenHeight = selected;
        networkVideo = staged;
        if (restart) {
            ambient.reset();
            preparePresentation();
            render();
        } else if (staged != null) startVideo();
        else render();
    }

    private boolean networkEligible() {
        return DemoMedia.LICENSED_ID.equals(recordingId) && rendition(240) != null;
    }

    private void requestNetworkVideo() {
        if (networkRequest != null || !networkEligible()) return;
        int pending = ++networkGeneration;
        networkRequest = loading.submit(() -> {
            File staged = null;
            try {
                staged = VerifiedVideoDownload.fetch(activity.getCacheDir(), LICENSED_RENDITION,
                        "raw.githubusercontent.com", 834563, LICENSED_SHA256);
            } catch (IOException | SecurityException unavailable) {
                // The bundled renditions remain available when the host is offline.
            }
            File result = staged;
            activity.runOnUiThread(() -> {
                if (pending != networkGeneration) return;
                networkRequest = null;
                MusicApi.Track current = service.currentTrack();
                if (current == null || !DemoMedia.LICENSED_ID.equals(current.id) || !networkEligible()) return;
                if (result == null) {
                    Toast.makeText(activity, "Network rendition unavailable. Local playback remains available.", Toast.LENGTH_SHORT).show();
                    render();
                } else {
                    selectQuality(240, result);
                }
            });
        });
        render();
    }

    private void cancelNetworkVideo() {
        networkGeneration++;
        if (networkRequest != null) networkRequest.cancel(true);
        networkRequest = null;
    }

    private void startVideo() {
        if (surface == null || recording == null || recording.renditions.isEmpty()) return;
        if (!service.playing() && service.canPlay()) service.toggle();
        preparePresentation();
        render();
    }

    private void preparePresentation() {
        error = "";
        wantVideo = true;
        offered = hadVideo = hasFrame = false;
        delivered = null;
        attempt = 0;
        activeSince = 0;
    }

    private void returnToSong() {
        cancelNetworkVideo();
        ambient.reset();
        wantVideo = offered = hadVideo = hasFrame = false;
        delivered = null;
        error = "";
        service.endVideo();
        if (fullscreen) toggleFullscreen();
        render();
    }

    private void skip(int direction) {
        boolean resumeVideo = wantVideo;
        returnToSong();
        service.skip(direction);
        if (resumeVideo) wantVideo = true;
        render();
    }

    private void render() {
        MusicApi.Track track = service.currentTrack();
        String id = track == null ? "" : track.id;
        if (!recordingId.equals(id)) {
            cancelNetworkVideo();
            ambient.reset();
            networkVideo = null;
            recordingId = id;
            error = "";
            recording = media.recordings.get(id);
            if (rendition(chosenHeight) == null) chosenHeight = 0;
            artwork.setImageBitmap(recording == null ? null : recording.cover);
            offered = hadVideo = hasFrame = false;
            attempt = 0;
            activeSince = 0;
            delivered = null;
        }
        boolean active = service.videoActive();
        boolean preparing = service.videoPreparing();
        if (wantVideo && !active && !preparing) {
            if (recording == null) {
                wantVideo = false;
            } else if (hadVideo || recording.renditions.isEmpty()) {
                wantVideo = false;
                error = hadVideo ? "Video ended. Song mode is available." : "No compatible video. Audio continues.";
            } else if (service.canOfferVideo(recordingId)) {
                if (!offered || chosenHeight == 0 && attempt < recording.renditions.size()) offerNext();
                else if (networkVideo != null) {
                    networkVideo = null;
                    chosenHeight = 0;
                    offerNext();
                }
                else {
                    wantVideo = false;
                    error = "Video could not play. Audio continues.";
                }
                preparing = service.videoPreparing();
            }
        }
        if (active && !hasFrame) {
            if (activeSince == 0) activeSince = SystemClock.uptimeMillis();
            else if (SystemClock.uptimeMillis() - activeSince > 5000) {
                returnToSong();
                error = "Video produced no frame. Audio continues.";
                return;
            }
        }
        if (!active && hadVideo) ambient.reset();
        hadVideo = active;
        if (!wantVideo && fullscreen) toggleFullscreen();
        boolean visible = active && hasFrame;
        artwork.setVisibility(visible ? GONE : VISIBLE);
        caption.setVisibility(visible ? GONE : VISIBLE);
        caption.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        String status = service.status();
        caption.setText(wantVideo ? (service.playing() ? "Loading video…" : "Resume playback to load video.")
                : service.title() + "\n" + (error.isEmpty() ? (status.isEmpty() && track != null ? track.artist : status) : error));
        song.setSelected(!wantVideo);
        video.setSelected(wantVideo);
        boolean available = recording != null && !recording.renditions.isEmpty();
        video.setEnabled(!wantVideo && surface != null && available && service.canPlay());
        fit.setEnabled(active);
        full.setEnabled(active || fullscreen);
        refreshAmbient();
        String qualityLabel = chosenHeight == 0 ? "Auto" : chosenHeight + "p";
        quality.setText(networkRequest != null ? "Fetching 240p…" : networkVideo != null ? "240p · HTTPS"
                : qualityLabel + (active && chosenHeight == 0 && delivered != null ? " · " + label(delivered) : ""));
        quality.setEnabled(available);
        previous.setEnabled(service.canSkip(-1));
        next.setEnabled(service.canSkip(1));
        play.setEnabled(service.canPlay());
        play.showPlaying(service.playing());
        if (!tracking) {
            seek.setMax(Math.max(1, service.duration()));
            seek.setProgress(service.position());
        }
        time.setText(timestamp(service.position()) + " / " + timestamp(service.duration()));
    }

    private static String timestamp(int millis) {
        int seconds = Math.max(0, millis / 1000);
        return seconds / 60 + ":" + (seconds % 60 < 10 ? "0" : "") + seconds % 60;
    }

    private void offerNext() {
        MediaFormat candidate = chosenHeight == 0 ? recording.renditions.get(attempt++) : rendition(chosenHeight);
        offered = true;
        if (candidate == null) return;
        try (AssetFileDescriptor file = networkVideo == null ? recording.open(activity, candidate)
                : new AssetFileDescriptor(ParcelFileDescriptor.open(networkVideo, ParcelFileDescriptor.MODE_READ_ONLY),
                        0, networkVideo.length())) {
            if (service.offerLocalVideo(recordingId, file, surface)) {
                delivered = candidate;
                framePicture();
            }
        } catch (IOException unavailable) {
            // A later tick tries the next verified rendition in Auto mode.
        }
    }

    private MediaFormat rendition(int height) {
        if (recording != null) {
            for (MediaFormat format : recording.renditions) {
                if (format.getInteger(MediaFormat.KEY_HEIGHT) == height) return format;
            }
        }
        return null;
    }

    boolean exitFullscreen() {
        if (!fullscreen) return false;
        toggleFullscreen();
        return true;
    }

    int renderedHeight() { return delivered == null || !hasVideoFrame() ? 0 : delivered.getInteger(MediaFormat.KEY_HEIGHT); }

    private static String label(MediaFormat format) { return format.getInteger(MediaFormat.KEY_HEIGHT) + "p"; }

    private void toggleFullscreen() {
        fullscreen = !fullscreen;
        activity.getWindow().getDecorView().setSystemUiVisibility(fullscreen ? SYSTEM_UI_FLAG_FULLSCREEN
                | SYSTEM_UI_FLAG_HIDE_NAVIGATION | SYSTEM_UI_FLAG_IMMERSIVE_STICKY : 0);
        LayoutParams layout = (LayoutParams) frame.getLayoutParams();
        layout.setMargins(fullscreen ? 0 : dp(24), fullscreen ? 0 : dp(8), fullscreen ? 0 : dp(24), fullscreen ? 0 : dp(8));
        frame.setLayoutParams(layout);
        full.setText(fullscreen ? "Exit fullscreen" : "Fullscreen");
    }

    private void framePicture() {
        if (delivered != null) picture.setTransform(VideoFraming.transform(picture.getWidth(), picture.getHeight(),
                delivered.getInteger(MediaFormat.KEY_WIDTH), delivered.getInteger(MediaFormat.KEY_HEIGHT), fill));
    }

    void refreshAmbient() {
        boolean supported = VideoAmbientLight.intervalMs(performance.profile()) > 0;
        boolean available = supported && service.videoActive() && !fill;
        if (light.isEnabled() != available) light.setEnabled(available);
        boolean active = available && ambientEnabled;
        if (active == ambientActive) return;
        ambientActive = active;
        light.setSelected(active);
        light.setContentDescription(active ? "Turn ambient lighting off" : "Turn ambient lighting on");
        if (!active) ambient.reset();
    }

    boolean hasVideoFrame() { return service.videoActive() && hasFrame; }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(clock);
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(clock);
        returnToSong();
        super.onDetachedFromWindow();
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        surface = new Surface(texture);
        framePicture();
    }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) { framePicture(); }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        returnToSong();
        if (surface != null) surface.release();
        surface = null;
        return true;
    }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        if (!hasFrame && service.videoActive()) {
            hasFrame = true;
            render();
        }
        if (ambientActive && service.videoActive() && service.playing()) {
            ambient.sample(surface, performance.profile());
        }
    }

    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
}
