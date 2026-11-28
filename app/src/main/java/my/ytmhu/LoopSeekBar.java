// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.widget.SeekBar;

/** Draws the selected A/B bounds without changing the platform seek or progress behavior. */
final class LoopSeekBar extends SeekBar {
    private final String label;
    private final int marker;
    private final int outline;
    private final boolean bottomTrack;
    private final float density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int start = -1;
    private int end = -1;

    LoopSeekBar(Context context, String label, int marker, boolean bottomTrack) {
        super(context);
        this.label = label;
        this.marker = marker;
        outline = (Color.red(marker) * 299 + Color.green(marker) * 587 + Color.blue(marker) * 114) > 128_000
                ? Color.BLACK : Color.WHITE;
        this.bottomTrack = bottomTrack;
        density = getResources().getDisplayMetrics().density;
        setContentDescription(label);
    }

    void setLoopBounds(int start, int end, String range) {
        if (this.start == start && this.end == end) return;
        this.start = start;
        this.end = end;
        setContentDescription(label + range);
        invalidate();
    }

    @Override protected synchronized void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (start < 0 || getMax() <= 0) return;
        float edge = 1.5f * density;
        float left = Math.max(edge, getPaddingLeft());
        float right = getWidth() - Math.max(edge, getPaddingRight());
        if (right <= left) return;
        float y = bottomTrack ? getHeight() - getPaddingBottom() - density
                : (getHeight() + getPaddingTop() - getPaddingBottom()) / 2f;
        float a = left + (right - left) * Math.min(start, getMax()) / getMax();
        if (end > start) {
            float b = left + (right - left) * Math.min(end, getMax()) / getMax();
            paint.setColor((marker & 0x00ffffff) | 0x60000000);
            canvas.drawRoundRect(a, y - density, b, y + density, density, density, paint);
            tick(canvas, b, y);
        }
        tick(canvas, a, y);
    }

    private void tick(Canvas canvas, float x, float y) {
        float top = y - 5f * density;
        float bottom = bottomTrack ? getHeight() - getPaddingBottom() : y + 5f * density;
        paint.setColor(outline);
        canvas.drawRoundRect(x - 1.5f * density, top, x + 1.5f * density, bottom, density, density, paint);
        paint.setColor(marker);
        canvas.drawRoundRect(x - 0.75f * density, top + density, x + 0.75f * density, bottom - density,
                density, density, paint);
    }
}
