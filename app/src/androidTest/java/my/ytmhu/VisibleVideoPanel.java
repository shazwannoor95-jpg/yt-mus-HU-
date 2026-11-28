// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.app.Activity;
import android.content.res.AssetFileDescriptor;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.media.MediaFormat;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.IOException;
import java.util.List;

/** The isolated emulator fixture exercises the real shared playback service. */
final class VisibleVideoPanel extends LinearLayout implements TextureView.SurfaceTextureListener {
    interface Assets { AssetFileDescriptor open(MediaFormat rendition) throws IOException; }

    final Button song, video, fit, full, previous, next, quality;
    final PlayPauseButton play;
    final SeekBar seek;
    private final Activity activity;
    private final PlaybackService service;
    private final String recording;
    private final List<MediaFormat> renditions;
    private final Assets assets;
    private final FrameLayout frame;
    private final TextureView picture;
    private final TextView caption;
    private Surface surface;
    private MediaFormat chosen, delivered;
    private int attempt;
    private boolean wantVideo, offered, hadVideo, hasFrame, fill, fullscreen, tracking;
    private String error = "";
    private final Runnable clock = new Runnable() {
        @Override public void run() {
            render();
            postDelayed(this, 160);
        }
    };

    VisibleVideoPanel(Activity activity, PlaybackService service, String recording, List<MediaFormat> renditions, Assets assets) {
        super(activity);
        this.activity = activity;
        this.service = service;
        this.recording = recording;
        this.renditions = renditions;
        this.assets = assets;
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
        });
        full = action(modes, "Fullscreen", this::toggleFullscreen);
        addView(modes);

        frame = new FrameLayout(activity);
        frame.setBackgroundColor(Color.BLACK);
        frame.setClipChildren(true);
        LayoutParams stage = new LayoutParams(-1, 0, 1);
        stage.setMargins(dp(24), dp(8), dp(24), dp(8));
        addView(frame, stage);
        picture = new TextureView(activity);
        picture.setSurfaceTextureListener(this);
        frame.addView(picture, new FrameLayout.LayoutParams(-1, -1));
        caption = new TextView(activity);
        caption.setTextColor(Color.WHITE);
        caption.setGravity(Gravity.CENTER);
        caption.setTextSize(16);
        frame.addView(caption, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout transport = new LinearLayout(activity);
        transport.setGravity(Gravity.CENTER_VERTICAL);
        previous = action(transport, "Previous", () -> skip(-1));
        play = new PlayPauseButton(activity);
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
        addView(transport);
    }

    private Button action(LinearLayout row, String text, Runnable run) {
        Button button = new Button(activity);
        button.setAllCaps(false);
        button.setText(text);
        button.setOnClickListener(view -> run.run());
        row.addView(button, new LayoutParams(-2, dp(48)));
        return button;
    }

    private void fitLabel() { fit.setText(fill ? "Fit" : "Fill"); }

    private void chooseQuality() {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(activity, quality);
        menu.getMenu().add("Auto").setOnMenuItemClickListener(item -> { setQuality(null); return true; });
        for (MediaFormat format : renditions) {
            menu.getMenu().add(label(format)).setOnMenuItemClickListener(item -> { setQuality(format); return true; });
        }
        menu.show();
    }

    private void setQuality(MediaFormat selected) {
        boolean restart = wantVideo;
        if (restart) returnToSong();
        chosen = selected;
        if (restart) startVideo();
        render();
    }

    private void startVideo() {
        if (surface == null || renditions.isEmpty()) return;
        boolean resumingPaused = !service.playing();
        if (resumingPaused) return;
        error = "";
        wantVideo = true;
        offered = hadVideo = hasFrame = false;
        delivered = null;
        attempt = 0;
        render();
    }

    private void returnToSong() {
        wantVideo = offered = hadVideo = hasFrame = false;
        delivered = null;
        error = "";
        service.endVideo();
        if (fullscreen) toggleFullscreen();
        render();
    }

    private void skip(int direction) {
        returnToSong();
        service.skip(direction);
    }

    private void render() {
        boolean active = service.videoActive();
        boolean preparing = service.videoPreparing();
        if (wantVideo && !active && !preparing) {
            if (hadVideo || service.currentTrack() == null || !recording.equals(service.currentTrack().id)) {
                wantVideo = false;
                error = "Video ended. Song mode is available.";
            } else if (service.canOfferVideo(recording)) {
                if (!offered || chosen == null && attempt < renditions.size()) offerNext();
                else {
                    wantVideo = false;
                    error = "Video could not play. Audio continues.";
                }
                preparing = service.videoPreparing();
            }
        }
        hadVideo = active;
        if (!wantVideo && fullscreen) toggleFullscreen();
        boolean visible = active && hasFrame;
        caption.setVisibility(visible ? GONE : VISIBLE);
        caption.setBackgroundColor(active ? Color.TRANSPARENT : Color.BLACK);
        caption.setText(wantVideo ? "Loading video…" : error.isEmpty() ? service.title() + " · Song" : error);
        song.setSelected(!wantVideo);
        video.setSelected(wantVideo);
        video.setEnabled(!wantVideo && surface != null && !renditions.isEmpty() && service.canOfferVideo(recording));
        fit.setEnabled(active);
        full.setEnabled(active);
        String qualityLabel = chosen == null ? "Auto" : label(chosen);
        quality.setText(qualityLabel + (active && chosen == null ? " · " + label(delivered) : ""));
        quality.setEnabled(!renditions.isEmpty());
        previous.setEnabled(service.canSkip(-1));
        next.setEnabled(service.canSkip(1));
        play.setEnabled(service.canPlay());
        play.showPlaying(service.playing());
        if (!tracking) {
            seek.setMax(Math.max(1, service.duration()));
            seek.setProgress(service.position());
        }
    }

    private void offerNext() {
        MediaFormat candidate = chosen == null ? renditions.get(attempt++) : chosen;
        offered = true;
        try (AssetFileDescriptor file = assets.open(candidate)) {
            if (service.offerLocalVideo(recording, file, surface)) {
                delivered = candidate;
                framePicture();
            }
        } catch (IOException unavailable) {
            // A later tick tries the next verified rendition in Auto mode.
        }
    }

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
    }

    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
}
