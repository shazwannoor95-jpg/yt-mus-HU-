// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

/** Audio range rules adapted from ytm-rs ui/ab_loop.rs. */
final class AbLoop {
    private static final int MIN_LENGTH_MS = 2000;
    private int start = -1, end = -1;

    int start() { return Math.max(0, start); }
    boolean hasStart() { return start >= 0; }

    void setStart(int at) {
        start = Math.max(0, at);
        if (end - start < MIN_LENGTH_MS) end = -1;
    }

    boolean setEnd(int at, int duration, float speed) {
        int constrained = Math.min(at, latest(duration, speed));
        if (constrained < at && at - start() >= MIN_LENGTH_MS && constrained - start() < MIN_LENGTH_MS) {
            return false;
        }
        if (constrained - start() < MIN_LENGTH_MS) start = 0;
        end = constrained >= MIN_LENGTH_MS ? constrained : -1;
        return end >= 0;
    }

    int end(int duration, float speed) {
        int limit = Math.min(end, latest(duration, speed));
        return limit - start() >= MIN_LENGTH_MS ? limit : -1;
    }

    void clear() { start = end = -1; }

    private static int latest(int duration, float speed) {
        return Math.max(0, duration - Math.round(500 * speed));
    }
}
