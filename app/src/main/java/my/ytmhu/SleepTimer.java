// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

/** Uses an elapsed clock, with the cubic final-minute envelope from ytm-rs audio.rs. */
final class SleepTimer {
    static final int OFF = 0, SONG_END = -1;
    private static final long FADE_MS = 60_000L;
    private long deadline, fade;
    private boolean songEnd;

    void set(int minutes, long now) {
        songEnd = minutes == SONG_END;
        deadline = minutes > 0 ? now + Math.min(minutes, 60) * 60_000L : 0;
        fade = timed() ? Math.min(FADE_MS, deadline - now) : 0;
    }

    boolean songEnd() { return songEnd; }
    boolean timed() { return deadline != 0; }
    boolean due(long now) { return timed() && now >= deadline; }

    int minutesLeft(long now) {
        if (songEnd) return SONG_END;
        return timed() ? (int) ((Math.max(0, deadline - now) + 59_999L) / 60_000L) : OFF;
    }

    float gain(long now) {
        if (!timed() || deadline - now >= fade) return 1f;
        float fraction = Math.max(0, deadline - now) / (float) fade;
        return fraction * fraction * fraction;
    }

    long delayToNextStep(long now) {
        long left = Math.max(0, deadline - now);
        return left > fade ? left - fade : Math.min(1000L, left);
    }
}
