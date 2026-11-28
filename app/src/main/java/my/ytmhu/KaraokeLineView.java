// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.text.Layout;
import android.widget.TextView;

import java.util.List;

/** Paints only the active provider-aligned word over the normal TextView layout. */
final class KaraokeLineView extends TextView {
    private final List<Lyrics.Word> words;
    private final int highlight;
    private final Path clip = new Path();
    private int index = -1;
    private float fraction;

    KaraokeLineView(Context context, List<Lyrics.Word> words, int highlight) {
        super(context);
        this.words = words;
        this.highlight = highlight;
    }

    void position(int millis, boolean progressive) {
        int low = 0, high = words.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (words.get(mid).start <= millis) low = mid + 1;
            else high = mid;
        }
        int active = low - 1;
        float fill = 0;
        if (active >= 0) {
            Lyrics.Word word = words.get(active);
            fill = progressive ? Math.min(1f, (float) (millis - word.start) / (word.end - word.start)) : 1;
        }
        if (index == active && fraction == fill) return;
        index = active;
        fraction = fill;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Layout layout = getLayout();
        if (index < 0 || fraction <= 0 || layout == null) return;
        Lyrics.Word word = words.get(index);
        int save = canvas.save();
        canvas.translate(getTotalPaddingLeft(), getTotalPaddingTop());
        clip.reset();
        layout.getSelectionPath(word.from, word.to, clip);
        canvas.clipPath(clip);
        int line = layout.getLineForOffset(word.from);
        if (fraction < 1 && layout.getLineForOffset(word.to - 1) == line && layout.getParagraphDirection(line) == 1) {
            float x = layout.getPrimaryHorizontal(word.from);
            float end = layout.getPrimaryHorizontal(word.to);
            if (end > x) canvas.clipRect(x, layout.getLineTop(line), x + (end - x) * fraction, layout.getLineBottom(line));
        }
        int old = getPaint().getColor();
        getPaint().setColor(highlight);
        layout.draw(canvas);
        getPaint().setColor(old);
        canvas.restoreToCount(save);
    }
}
