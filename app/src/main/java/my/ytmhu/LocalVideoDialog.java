// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Local video presentation inside regular Now Playing, using its existing playback service. */
final class LocalVideoDialog extends Dialog implements TextureView.SurfaceTextureListener {
    private final PlaybackService service;
    private final AuthorizedVideoFile file;
    private final Supplier<PerformancePolicy.Profile> profile;
    private final VideoAmbientLight ambient;
    private final FrameLayout stage;
    private final TextureView picture;
    private final Button song, video, fit, full;
    private final PlayPauseButton play;
    private final TextView poster, time;
    private final SeekBar seek;
    private Surface surface;
    private boolean requested, attached, drawn, fill, fullscreen, seeking;
    private long attachedAt;
    private String feedback = "";
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            update();
            stage.postDelayed(this, 200);
        }
    };

    LocalVideoDialog(Context context, PlaybackService service, AuthorizedVideoFile file, Executor readback,
            Supplier<PerformancePolicy.Profile> profile) {
        super(context, android.R.style.Theme_Material_NoActionBar);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        this.service = service;
        this.file = file;
        this.profile = profile;
        ambient = new VideoAmbientLight(readback);

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(0xff18131b);
        LinearLayout modes = row(layout);
        song = button(modes, "Song", this::showSong);
        video = button(modes, "Video", () -> { requested = true; feedback = ""; update(); });
        fit = button(modes, "Fill", this::toggleFit);
        full = button(modes, "Fullscreen", this::toggleFullscreen);
        button(modes, "Close", this::dismiss);

        stage = new FrameLayout(context);
        stage.setBackground(ambient);
        LinearLayout.LayoutParams stageSize = new LinearLayout.LayoutParams(-1, 0, 1);
        stageSize.setMargins(dp(12), 0, dp(12), 0);
        layout.addView(stage, stageSize);
        picture = new TextureView(context);
        picture.setOpaque(false);
        picture.setSurfaceTextureListener(this);
        stage.addView(picture, new FrameLayout.LayoutParams(-1, -1));
        poster = text();
        poster.setTextSize(18);
        poster.setBackgroundColor(0xff18131b);
        stage.addView(poster, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout transport = row(layout);
        play = new PlayPauseButton(context);
        play.setTextColor(Color.WHITE);
        play.setOnClickListener(view -> service.toggle());
        transport.addView(play, new LinearLayout.LayoutParams(dp(56), dp(48)));
        seek = new SeekBar(context);
        seek.setContentDescription("Playback position");
        transport.addView(seek, new LinearLayout.LayoutParams(0, dp(48), 1));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser && !seeking) service.seekTo(value);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { seeking = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                seeking = false;
                service.seekTo(bar.getProgress());
            }
        });
        time = text();
        transport.addView(time, new LinearLayout.LayoutParams(dp(96), dp(48)));
        setContentView(layout);
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER);
        parent.addView(row, new LinearLayout.LayoutParams(-1, dp(48)));
        return row;
    }

    private Button button(LinearLayout row, String label, Runnable action) {
        Button button = new Button(getContext());
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(view -> action.run());
        row.addView(button, new LinearLayout.LayoutParams(-2, -1));
        return button;
    }

    private TextView text() {
        TextView view = new TextView(getContext());
        view.setTextColor(Color.WHITE);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    @Override protected void onStart() {
        super.onStart();
        getWindow().setLayout(-1, -1);
        stage.post(tick);
    }

    @Override protected void onStop() {
        stage.removeCallbacks(tick);
        detachOutput();
        service.finishLocalVideo(file);
        super.onStop();
    }

    @Override public void onBackPressed() {
        if (fullscreen) toggleFullscreen();
        else super.onBackPressed();
    }

    private void showSong() {
        requested = false;
        feedback = "";
        detachOutput();
        if (fullscreen) toggleFullscreen();
        update();
    }

    private void detachOutput() {
        if (attached) service.setLocalVideoSurface(file, null);
        attached = drawn = false;
        ambient.reset();
    }

    private void update() {
        boolean current = service.currentTrack() == file.track;
        int duration = current ? service.duration() : 0;
        if (attached && duration == 0) {
            detachOutput();
        } else if (requested && !attached && duration > 0 && surface != null) {
            attached = service.setLocalVideoSurface(file, surface);
            if (attached) attachedAt = SystemClock.uptimeMillis();
            else videoUnavailable();
        } else if (attached && !drawn) {
            if (!service.playing()) attachedAt = SystemClock.uptimeMillis();
            else if (SystemClock.uptimeMillis() - attachedAt > 5000) videoUnavailable();
        }
        if (VideoAmbientLight.intervalMs(profile.get()) == 0) ambient.reset();
        song.setActivated(!requested);
        video.setActivated(requested);
        fit.setEnabled(attached);
        full.setEnabled(drawn || fullscreen);
        poster.setVisibility(drawn ? View.GONE : View.VISIBLE);
        String status = !feedback.isEmpty() ? feedback : requested && current && !drawn
                ? (service.playing() ? "Loading video…" : "Resume playback to load video.") : service.status();
        if (status.isEmpty()) status = "Local video";
        poster.setText(file.track.title + "\n" + file.height + "p · " + status);
        play.setEnabled(service.canPlay());
        play.showPlaying(service.playing());
        seek.setEnabled(duration > 0);
        if (!seeking) {
            seek.setMax(Math.max(1, duration));
            seek.setProgress(current ? service.position() : 0);
        }
        time.setText(time(current ? service.position() : 0) + " / " + time(duration));
    }

    private void videoUnavailable() {
        requested = false;
        detachOutput();
        feedback = "Video unavailable. Song mode is available.";
        if (fullscreen) toggleFullscreen();
    }

    private void toggleFit() {
        fill = !fill;
        if (fill) ambient.reset();
        fit.setText(fill ? "Fit" : "Fill");
        frame();
    }

    private void toggleFullscreen() {
        fullscreen = !fullscreen;
        getWindow().getDecorView().setSystemUiVisibility(fullscreen ? View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY : 0);
        LinearLayout.LayoutParams size = (LinearLayout.LayoutParams) stage.getLayoutParams();
        size.setMargins(fullscreen ? 0 : dp(12), 0, fullscreen ? 0 : dp(12), 0);
        stage.setLayoutParams(size);
        full.setText(fullscreen ? "Exit fullscreen" : "Fullscreen");
    }

    private void frame() {
        picture.setTransform(VideoFraming.transform(picture.getWidth(), picture.getHeight(), file.width, file.height, fill));
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        surface = new Surface(texture);
        frame();
    }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) { frame(); }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        detachOutput();
        if (surface != null) surface.release();
        surface = null;
        return true;
    }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        if (!attached) return;
        if (!drawn) {
            drawn = true;
            update();
        }
        if (!fill && service.playing()) ambient.sample(surface, profile.get());
    }

    private static String time(int millis) {
        int seconds = millis / 1000;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private int dp(int value) { return Math.round(getContext().getResources().getDisplayMetrics().density * value); }
}
