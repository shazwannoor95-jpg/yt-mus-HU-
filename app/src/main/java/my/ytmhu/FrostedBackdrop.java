// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;

final class FrostedBackdrop extends Drawable {
    private static final int SAMPLE = 24;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final GradientDrawable fallback;
    private final int tint;
    private final int scrimAlpha, brightness;
    private Bitmap blur;
    private GradientDrawable albumShade;

    FrostedBackdrop(int base, int end, double scrim, int brightness) {
        this.brightness = brightness;
        fallback = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Appearance.brightness(base, brightness), Appearance.brightness(end, brightness)});
        tint = base;
        scrimAlpha = Math.max(45, Math.min(210, (int) (scrim * 255)));
    }

    void setAlbum(Bitmap art) {
        if (art == null) {
            blur = null;
        } else {
            Bitmap scaled = Bitmap.createScaledBitmap(art, SAMPLE, SAMPLE, true);
            int[] pixels = new int[SAMPLE * SAMPLE];
            scaled.getPixels(pixels, 0, SAMPLE, 0, 0, SAMPLE, SAMPLE);
            int[] soft = smooth(pixels, SAMPLE);
            blur = Bitmap.createBitmap(soft, SAMPLE, SAMPLE, Bitmap.Config.ARGB_8888);
            int shade = tint & 0x00ffffff;
            albumShade = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{Math.max(scrimAlpha, 138) << 24 | shade, 0xe0000000 | shade});
        }
        invalidateSelf();
    }

    static int[] smooth(int[] pixels, int size) {
        int[] input = pixels.clone();
        int[] output = new int[pixels.length];
        for (int pass = 0; pass < 4; pass++) {
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int red = 0, green = 0, blue = 0;
                    for (int dy = -2; dy <= 2; dy++) {
                        int row = Math.max(0, Math.min(size - 1, y + dy)) * size;
                        for (int dx = -2; dx <= 2; dx++) {
                            int colour = input[row + Math.max(0, Math.min(size - 1, x + dx))];
                            red += (colour >>> 16) & 255;
                            green += (colour >>> 8) & 255;
                            blue += colour & 255;
                        }
                    }
                    output[y * size + x] = 0xff000000 | red / 25 << 16 | green / 25 << 8 | blue / 25;
                }
            }
            int[] swap = input;
            input = output;
            output = swap;
        }
        return input;
    }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (blur == null) {
            fallback.setBounds(bounds);
            fallback.draw(canvas);
            return;
        }
        paint.setAlpha(255);
        canvas.drawBitmap(blur, null, bounds, paint);
        albumShade.setBounds(bounds);
        albumShade.draw(canvas);
        if (brightness != 100) {
            paint.setColor(brightness < 100 ? Color.BLACK : Color.WHITE);
            paint.setAlpha(brightness < 100 ? (100 - brightness) * 255 / 100 : (brightness - 100) * 2);
            canvas.drawRect(bounds, paint);
        }
    }

    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter filter) {}
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
