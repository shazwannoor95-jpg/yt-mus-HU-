// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.Surface;

/** Draws a dimmed sample of the current video only. It never drives playback. */
final class VideoAmbientLight extends Drawable {
    private static final int WIDTH = 12, HEIGHT = 8;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private Bitmap capture, backdrop;
    private int[] incoming, smoothed;
    private long nextSample;
    private int generation, failures;
    private boolean visible, pending;

    static int intervalMs(PerformancePolicy.Profile profile) {
        if (profile == PerformancePolicy.Profile.HIGH_QUALITY) return 375;
        return profile == PerformancePolicy.Profile.BALANCED ? 750 : 0;
    }

    void sample(Surface surface, PerformancePolicy.Profile profile) {
        int interval = intervalMs(profile);
        long now = SystemClock.uptimeMillis();
        if (interval == 0 || pending || failures >= 3 || now < nextSample || surface == null || !surface.isValid()) return;
        if (capture == null) {
            capture = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
            backdrop = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
            incoming = new int[WIDTH * HEIGHT];
            smoothed = new int[incoming.length];
        }
        nextSample = now + interval;
        pending = true;
        int requested = generation;
        try {
            PixelCopy.request(surface, capture, result -> finishSample(requested, result), main);
        } catch (IllegalArgumentException unavailable) {
            finishSample(requested, PixelCopy.ERROR_SOURCE_INVALID);
        }
    }

    private void finishSample(int requested, int result) {
        pending = false;
        if (requested != generation) return;
        if (result != PixelCopy.SUCCESS) {
            if (++failures == 3 && visible) {
                visible = false;
                invalidateSelf();
            }
            return;
        }
        failures = 0;
        capture.getPixels(incoming, 0, WIDTH, 0, 0, WIDTH, HEIGHT);
        for (int i = 0; i < incoming.length; i++) {
            smoothed[i] = dimAndBlend(visible ? smoothed[i] : Color.BLACK, incoming[i]);
        }
        backdrop.setPixels(smoothed, 0, WIDTH, 0, 0, WIDTH, HEIGHT);
        visible = true;
        invalidateSelf();
    }

    static int dimAndBlend(int previous, int current) {
        return Color.rgb(blend(Color.red(previous), Color.red(current)),
                blend(Color.green(previous), Color.green(current)), blend(Color.blue(previous), Color.blue(current)));
    }

    private static int blend(int previous, int current) {
        return (previous * 35 + current * 29 + 50) / 100;
    }

    void reset() {
        generation++;
        nextSample = 0;
        failures = 0;
        if (visible) {
            visible = false;
            invalidateSelf();
        }
    }

    @Override public void draw(Canvas canvas) {
        if (visible) canvas.drawBitmap(backdrop, null, getBounds(), paint);
        else canvas.drawRect(getBounds(), paint);
    }

    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter filter) {}
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
