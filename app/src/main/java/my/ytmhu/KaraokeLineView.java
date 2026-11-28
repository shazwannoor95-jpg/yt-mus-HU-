// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.Layout;
import android.widget.TextView;

import java.util.List;

/** Sweeps provider-aligned text ranges across the laid-out rows. */
final class KaraokeLineView extends TextView {
    private final List<Lyrics.Word> words;
    private final int highlight;
    private final Path sung = new Path();
    private final Path active = new Path();
    private final Path sweep = new Path();
    private final Path row = new Path();
    private final RectF bounds = new RectF();
    private Layout cachedLayout;
    private int cachedIndex = -1;
    private int index = -1;
    private float fraction;
    private float width;
    private int firstRow, lastRow;

    KaraokeLineView(Context context, List<Lyrics.Word> words, int highlight) {
        super(context);
        this.words = words;
        this.highlight = highlight;
    }

    int activeSpan() { return index; }
    int fillPercent() { return Math.round(fraction * 100); }

    void position(int millis) {
        int low = 0, high = words.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (words.get(mid).start <= millis) low = mid + 1;
            else high = mid;
        }
        int span = low - 1;
        float fill = 0;
        if (span >= 0) {
            Lyrics.Word word = words.get(span);
            fill = Math.min(1f, (float) (millis - word.start) / (word.end - word.start));
        }
        if (index == span && fraction == fill) return;
        index = span;
        fraction = fill;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Layout layout = getLayout();
        if (index < 0 || layout == null) {
            cachedLayout = null;
            return;
        }
        if (layout != cachedLayout || index != cachedIndex) prepare(layout);
        sweep.rewind();
        sweep.addPath(sung);
        if (fraction >= 1) sweep.addPath(active);
        else if (fraction > 0 && width > 0) {
            float remaining = width * fraction;
            for (int line = firstRow; line <= lastRow && remaining > 0; line++) {
                setRowBounds(layout, line);
                float fill = Math.min(remaining, bounds.width());
                if (layout.isRtlCharAt(Math.max(words.get(index).from, layout.getLineStart(line)))) {
                    bounds.left = bounds.right - fill;
                } else {
                    bounds.right = bounds.left + fill;
                }
                sweep.addRect(bounds, Path.Direction.CW);
                remaining -= fill;
            }
        }
        if (sweep.isEmpty()) return;
        int save = canvas.save();
        canvas.translate(getTotalPaddingLeft(), getTotalPaddingTop());
        canvas.clipPath(sweep);
        int old = getPaint().getColor();
        getPaint().setColor(highlight);
        layout.draw(canvas);
        getPaint().setColor(old);
        canvas.restoreToCount(save);
    }

    private void prepare(Layout layout) {
        cachedLayout = layout;
        cachedIndex = index;
        sung.rewind();
        for (int i = 0; i < index; i++) {
            Lyrics.Word word = words.get(i);
            row.rewind();
            layout.getSelectionPath(word.from, word.to, row);
            sung.addPath(row);
        }
        Lyrics.Word word = words.get(index);
        active.rewind();
        layout.getSelectionPath(word.from, word.to, active);
        firstRow = layout.getLineForOffset(word.from);
        lastRow = layout.getLineForOffset(word.to - 1);
        width = 0;
        for (int line = firstRow; line <= lastRow; line++) {
            setRowBounds(layout, line);
            width += bounds.width();
        }
    }

    private void setRowBounds(Layout layout, int line) {
        Lyrics.Word word = words.get(index);
        int from = Math.max(word.from, layout.getLineStart(line));
        int to = Math.min(word.to, layout.getLineVisibleEnd(line));
        if (to <= from) {
            bounds.setEmpty();
            return;
        }
        float a = layout.getPrimaryHorizontal(from);
        float b = to == layout.getLineEnd(line) && line + 1 < layout.getLineCount()
                ? (layout.getParagraphDirection(line) < 0 ? layout.getLineLeft(line) : layout.getLineRight(line))
                : layout.getPrimaryHorizontal(to);
        bounds.set(Math.min(a, b), layout.getLineTop(line), Math.max(a, b), layout.getLineBottom(line));
    }
}
