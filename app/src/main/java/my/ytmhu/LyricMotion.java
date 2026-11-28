// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import org.json.JSONArray;
import org.json.JSONObject;

/** Now Playing motion parameters from the pinned ytm-rs LyricStyle and karaoke renderer. */
final class LyricMotion {
    static final Interpolator EASE = new PathInterpolator(0.25f, 0.1f, 0.25f, 1f);
    static final Interpolator EASE_IN_OUT = new PathInterpolator(0.42f, 0f, 0.58f, 1f);
    private static final Interpolator SCROLL = new PathInterpolator(0.86f, 0f, 0.2f, 1f);
    private static final float[] PAST = {0.72f, 0.57f, 0.46f, 0.39f};
    private static final float[] UPCOMING = {0.82f, 0.68f, 0.55f, 0.39f};

    final float restScale, activeScale, lineOpacity, wordPopSeconds, glowEm, glowAlpha;
    final int scaleMs, wordFadeMs, wordFadeDelay;
    final Interpolator enter, exit, opacity, scroll;
    final Timing regular, past, active;
    private final float[] pastLines, upcomingLines, pastWords, upcomingWords;
    private final int scrollMs;
    private final float[] scrollCurve;

    LyricMotion(JSONObject preset) {
        JSONObject style = preset == null ? new JSONObject() : preset;
        restScale = number(style, "rest_scale", 0.95f, 0.1f, 1.5f);
        activeScale = number(style, "active_scale", 1f, 0.1f, 1.5f);
        lineOpacity = number(style, "active_opacity_line_synced", 1f, 0f, 1f);
        scaleMs = Math.round(1000 * number(style, "scale_secs", 0.166f, 0.001f, 2f));
        scrollMs = Math.round(1000 * number(style, "scroll_secs", 0.75f, 0.01f, 2f));
        scrollCurve = tuple(style.optJSONArray("scroll_curve"), 5);
        enter = bezier(style, "scale_ease", EASE);
        exit = bezier(style, "scale_exit_ease", enter);
        opacity = bezier(style, "line_ease", EASE);
        scroll = bezier(style, "scroll_ease", SCROLL);
        regular = timing(style, "opacity_transition", new Timing(0, 200, 0));
        past = timing(style, "past_opacity_transition", regular);
        active = timing(style, "active_opacity_transition", regular);
        pastWords = ladder(style.optJSONArray("past"), PAST);
        upcomingWords = ladder(style.optJSONArray("upcoming"), UPCOMING);
        pastLines = ladder(style.optJSONArray("past_line_synced"), pastWords);
        upcomingLines = ladder(style.optJSONArray("upcoming_line_synced"), upcomingWords);
        float[] wordFade = tuple(style.optJSONArray("exit_fade"), 2);
        wordFadeMs = wordFade == null ? 500 : Math.max(10, Math.round(wordFade[0] * 1000));
        wordFadeDelay = wordFade == null ? 0 : Math.max(0, Math.round(wordFade[1] * 1000));
        wordPopSeconds = style.optBoolean("wobble", true)
                ? number(style, "wobble_secs", 1f, 0.01f, 2f) : 0f;
        glowEm = number(style, "glow_rem", 0.8f, 0f, 2f);
        glowAlpha = number(style, "glow_alpha", 0.5f, 0f, 1f);
    }

    float lineOpacity(int relative, boolean rich) {
        if (relative == 0) return rich ? 1f : lineOpacity;
        float[] table = relative < 0 ? (rich ? pastWords : pastLines) : (rich ? upcomingWords : upcomingLines);
        return table[Math.min(table.length, Math.abs(relative)) - 1];
    }

    int dimRadius(boolean rich) {
        return rich ? Math.max(pastWords.length, upcomingWords.length)
                : Math.max(pastLines.length, upcomingLines.length);
    }

    int scrollDuration(int relative, float delta) {
        int distance = Math.abs(relative);
        boolean above = relative < 0 && delta > 0 || relative > 0 && delta < 0;
        double time;
        if (scrollCurve != null) {
            double log = Math.log1p(distance);
            time = above ? scrollCurve[0] + log * scrollCurve[1]
                    : scrollCurve[2] + log * scrollCurve[3] + distance * scrollCurve[4];
        } else {
            time = above ? scrollMs + Math.min(6, distance) * 20
                    : scrollMs + Math.log(distance + 2.0) * 80 + (distance + 1) * 20;
        }
        return (int) Math.max(10, Math.min(time, 2000));
    }

    private static float[] ladder(JSONArray values, float[] fallback) {
        if (values == null || values.length() == 0) return fallback;
        float[] result = new float[Math.min(values.length(), 16)];
        for (int i = 0; i < result.length; i++) {
            JSONArray pair = values.optJSONArray(i);
            double alpha = pair == null ? 1 : pair.optDouble(0, 1);
            // Themes with blur can make a neighboring line nearly transparent in the
            // desktop renderer. Retain legible text on a touch display without that blur.
            result[i] = (float) Math.max(0.22, Math.min(1, Double.isFinite(alpha) ? alpha : 1));
        }
        return result;
    }

    private static Interpolator bezier(JSONObject style, String key, Interpolator fallback) {
        float[] curve = tuple(style.optJSONArray(key), 4);
        if (curve == null || curve[0] < 0f || curve[0] > 1f || curve[2] < 0f || curve[2] > 1f) return fallback;
        return new PathInterpolator(curve[0], curve[1], curve[2], curve[3]);
    }

    private static float[] tuple(JSONArray array, int length) {
        if (array == null || array.length() != length) return null;
        float[] result = new float[length];
        for (int i = 0; i < length; i++) {
            result[i] = (float) array.optDouble(i, Double.NaN);
            if (!Float.isFinite(result[i])) return null;
        }
        return result;
    }

    private static float number(JSONObject style, String key, float fallback, float min, float max) {
        double value = style.optDouble(key, fallback);
        return (float) Math.max(min, Math.min(max, Double.isFinite(value) ? value : fallback));
    }

    private static Timing timing(JSONObject style, String key, Timing fallback) {
        JSONObject item = style.optJSONObject(key);
        if (item == null) return fallback;
        return new Timing(number(item, "frac", 0f, 0f, 2f),
                Math.round(1000 * number(item, "secs", 0.2f, 0f, 2f)),
                Math.round(1000 * number(item, "delay", 0f, 0f, 2f)));
    }

    static final class Timing {
        final float fraction;
        final int millis, delay;
        Timing(float fraction, int millis, int delay) {
            this.fraction = fraction;
            this.millis = millis;
            this.delay = delay;
        }
        int duration(int lineMillis) {
            return Math.max(1, Math.min(2000, Math.round(lineMillis * fraction) + millis));
        }
    }

    static final class Transition {
        private final float from, to;
        private final long start;
        private final int duration;
        private final Interpolator easing;

        Transition(float from, float to, long start, int duration, Interpolator easing) {
            this.from = from;
            this.to = to;
            this.start = start;
            this.duration = duration;
            this.easing = easing;
        }

        float value(long now) {
            if (now <= start) return from;
            if (finished(now)) return to;
            return from + (to - from) * easing.getInterpolation((float) (now - start) / duration);
        }

        boolean finished(long now) { return now >= start + duration; }
        boolean targets(float value) { return Math.abs(to - value) <= 0.0001f; }
    }
}
