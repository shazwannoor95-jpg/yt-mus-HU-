// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Gravity;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class VideoFixtureActivity extends Activity {
    private PlaybackService service;
    private DemoMedia media;
    private boolean bound, visible;
    private final ExecutorService loading = Executors.newSingleThreadExecutor();
    VisibleVideoPanel panel;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((PlaybackService.LocalBinder) binder).service();
            showPlayer();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
            panel = null;
            message("Playback service disconnected.");
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!DemoPlaybackPolicy.permitted(this)) {
            message("Song / Video Demo requires a non-automotive Android SDK emulator.");
            return;
        }
        message("Preparing bundled test media…");
        loading.execute(() -> {
            try {
                DemoMedia loaded = DemoMedia.load(this);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    media = loaded;
                    showPlayer();
                });
            } catch (Exception unavailable) {
                runOnUiThread(() -> { if (!isDestroyed()) message("Bundled test media could not load."); });
            }
        });
    }

    @Override protected void onStart() {
        super.onStart();
        visible = true;
        if (DemoPlaybackPolicy.permitted(this)) {
            bound = bindService(new Intent(this, PlaybackService.class), connection, BIND_AUTO_CREATE);
            if (!bound) message("Playback service could not start.");
        }
    }

    private void showPlayer() {
        if (!visible || !bound || service == null || media == null || panel != null) return;
        service.installDemoAudio(media.audio);
        panel = new VisibleVideoPanel(this, service, media);
        setContentView(panel);
        service.play(media.queue, 0);
    }

    private void message(String status) {
        TextView text = new TextView(this);
        text.setText(status);
        text.setGravity(Gravity.CENTER);
        text.setTextSize(18);
        setContentView(text);
    }

    @Override public void onBackPressed() {
        if (panel == null || !panel.exitFullscreen()) super.onBackPressed();
    }

    @Override protected void onStop() {
        visible = false;
        if (panel != null) {
            message("Preparing bundled test media…");
            panel = null;
        }
        if (bound) unbindService(connection);
        bound = false;
        service = null;
        super.onStop();
    }

    @Override protected void onDestroy() {
        loading.shutdownNow();
        super.onDestroy();
    }
}
