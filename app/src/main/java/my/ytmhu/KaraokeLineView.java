// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
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
    private final Matrix shaderMatrix = new Matrix();
    private LinearGradient gradient;
    private LyricMotion style;
    private boolean soft, enhanced;
    private float exitOpacity = 1f;
    private int elapsed;

    KaraokeLineView(Context context, List<Lyrics.Word> words, int highlight) {
        super(context);
        this.words = words;
        this.highlight = highlight;
    }

    int activeSpan() { return index; }
    int fillPercent() { return Math.round(fraction * 100); }

    void setEffects(LyricMotion next, PerformancePolicy.Profile profile) {
        boolean useSoft = profile.ordinal() >= PerformancePolicy.Profile.BALANCED.ordinal();
        boolean useEnhanced = profile == PerformancePolicy.Profile.HIGH_QUALITY;
        if (style == next && soft == useSoft && enhanced == useEnhanced) return;
        style = next;
        soft = useSoft;
        enhanced = useEnhanced;
        invalidate();
    }

    void setExitOpacity(float value) {
        if (exitOpacity == value) return;
        exitOpacity = value;
        invalidate();
    }

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
        int age = span < 0 ? 0 : millis - words.get(span).start;
        boolean moving = enhanced && style != null && style.wordPopSeconds > 0f
                && elapsed < style.wordPopSeconds * 1000f && age != elapsed;
        if (index == span && fraction == fill && !moving) return;
        elapsed = age;
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
        int save = canvas.save();
        canvas.translate(getTotalPaddingLeft(), getTotalPaddingTop());
        if (!sung.isEmpty()) paint(canvas, layout, sung, null);
        if (fraction > 0f) {
            int wordCanvas = canvas.save();
            applyWordPop(canvas, layout);
            canvas.clipPath(active);
            if (fraction >= 1) paint(canvas, layout, active, null);
            else paintSweep(canvas, layout);
            canvas.restoreToCount(wordCanvas);
        }
        canvas.restoreToCount(save);
    }

    private void paintSweep(Canvas canvas, Layout layout) {
        float remaining = width * fraction;
        sweep.rewind();
        for (int line = firstRow; line <= lastRow && remaining > 0; line++) {
            setRowBounds(layout, line);
            float length = bounds.width();
            if (length <= 0f) continue;
            boolean rtl = layout.isRtlCharAt(Math.max(words.get(index).from, layout.getLineStart(line)));
            if (remaining >= length) {
                sweep.addRect(bounds, Path.Direction.CW);
            } else {
                float start = rtl ? bounds.right : bounds.left;
                float end = rtl ? start - remaining : start + remaining;
                bounds.left = Math.min(start, end);
                bounds.right = Math.max(start, end);
                if (soft) {
                    if (!sweep.isEmpty()) paint(canvas, layout, sweep, null);
                    sweep.rewind();
                    sweep.addRect(bounds, Path.Direction.CW);
                    if (gradient == null) {
                        gradient = new LinearGradient(0, 0, 1, 0, highlight, highlight & 0xffffff,
                                Shader.TileMode.CLAMP);
                    }
                    float feather = Math.min(remaining, Math.max(1f, width * 0.10f));
                    shaderMatrix.setScale(rtl ? -feather : feather, 1f);
                    shaderMatrix.postTranslate(end + (rtl ? feather : -feather), bounds.top);
                    gradient.setLocalMatrix(shaderMatrix);
                    paint(canvas, layout, sweep, gradient);
                    return;
                }
                sweep.addRect(bounds, Path.Direction.CW);
            }
            remaining -= length;
        }
        if (!sweep.isEmpty()) paint(canvas, layout, sweep, null);
    }

    private void applyWordPop(Canvas canvas, Layout layout) {
        if (!enhanced || style == null || firstRow != lastRow || style.wordPopSeconds <= 0f) return;
        float progress = elapsed / (style.wordPopSeconds * 1000f);
        if (progress <= 0 || progress >= 1) return;
        float p = LyricMotion.EASE.getInterpolation(progress);
        float crest = p < 0.125f ? p / 0.125f
                : p < 0.75f ? 1f - LyricMotion.EASE_IN_OUT.getInterpolation((p - 0.125f) / 0.625f) : 0f;
        if (crest <= 0) return;
        setRowBounds(layout, firstRow);
        float direction = layout.isRtlCharAt(words.get(index).from) ? -1f : 1f;
        canvas.translate(direction * getTextSize() * 0.05f * crest, 0f);
        canvas.scale(1f + 0.025f * crest, 1f, bounds.centerX(), bounds.centerY());
    }

    private void paint(Canvas canvas, Layout layout, Path clip, Shader shader) {
        int save = canvas.save();
        canvas.clipPath(clip);
        Paint ink = getPaint();
        int oldColor = ink.getColor();
        Shader oldShader = ink.getShader();
        ink.setShader(shader);
        ink.setColor(highlight);
        ink.setAlpha(Math.round(Color.alpha(highlight) * exitOpacity));
        if (enhanced && style != null && shader != null && style.glowEm > 0f && style.glowAlpha > 0f) {
            int alpha = Math.round(60 * style.glowAlpha * exitOpacity);
            ink.setShadowLayer(getTextSize() * style.glowEm * 0.14f, 0, 0, (highlight & 0xffffff) | (alpha << 24));
        }
        layout.draw(canvas);
        ink.clearShadowLayer();
        ink.setShader(oldShader);
        ink.setColor(oldColor);
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
