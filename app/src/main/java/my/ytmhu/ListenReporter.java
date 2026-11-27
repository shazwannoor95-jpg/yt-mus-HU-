// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ListenReporter {
    private final AppSettings prefs;
    private final Handler timer = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final Runnable check = this::checkThreshold;
    private MusicApi.Track song;
    private String session = "";
    private int duration;
    private long began, segment, elapsed;
    private boolean sent;

    ListenReporter(AppSettings prefs) {
        this.prefs = prefs;
    }

    void play(MusicApi.Track track, int length) {
        if (track == null || track.artist.isEmpty() || length <= 0) return;
        String current = ListenBrainz.session(prefs);
        if (current.isEmpty()) return;
        if (song != track || !session.equals(current)) {
            reset();
            song = track;
            duration = length;
            session = current;
            began = System.currentTimeMillis() / 1000;
            submit(true);
        }
        segment = SystemClock.elapsedRealtime();
        if (!sent && duration >= 30000) {
            timer.postDelayed(check, Math.max(1, threshold(duration) - elapsed));
        }
    }

    void pause() {
        if (segment != 0) elapsed += SystemClock.elapsedRealtime() - segment;
        segment = 0;
        timer.removeCallbacks(check);
        checkThreshold();
    }

    void reset() {
        pause();
        song = null;
        session = "";
        duration = 0;
        elapsed = 0;
        sent = false;
    }

    void close() {
        reset();
        work.shutdown();
    }

    static long threshold(int duration) {
        return Math.min(240000L, duration / 2L);
    }

    private void checkThreshold() {
        long played = elapsed + (segment == 0 ? 0 : SystemClock.elapsedRealtime() - segment);
        if (!sent && song != null && duration >= 30000 && played >= threshold(duration)) {
            sent = true;
            submit(false);
        }
    }

    private void submit(boolean playingNow) {
        MusicApi.Track track = song;
        String owner = session;
        int length = duration;
        long timestamp = began;
        work.execute(() -> {
            try {
                ListenBrainz.submit(prefs, owner, track, length, timestamp, playingNow);
            } catch (Exception unavailable) {
                // Playback is independent of the optional scrobbling connection.
            }
        });
    }
}
