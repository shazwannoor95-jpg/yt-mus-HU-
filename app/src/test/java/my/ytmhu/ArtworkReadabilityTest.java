// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
public final class ArtworkReadabilityTest {
    @Test @Config(sdk = 27)
    public void customFlatColourIsOpaqueReadableAndOnlyAffectsFlatMode() {
        AppSettings prefs = new AppSettings(RuntimeEnvironment.getApplication());
        prefs.set("flatColour", "#FFFFFF");
        int base = 0xff241a2b;
        int end = 0xff180a1d;
        assertArrayEquals(new int[]{base, end}, background(prefs, base, end));
        prefs.set("flat", true);
        int[] flat = background(prefs, base, end);
        assertEquals(flat[0], flat[1]);
        assertEquals(255, Color.alpha(flat[0]));
        assertTrue(Appearance.luma(flat[0]) <= 0.305f);
        prefs.set("flatColour", "#102030");
        assertArrayEquals(new int[]{0xff102030, 0xff102030}, background(prefs, base, end));
        prefs.set("flatColour", "invalid");
        assertArrayEquals(new int[]{base, base}, background(prefs, base, end));
        assertEquals(1f, Appearance.readableDim(0.2f), 0.001f);
    }

    @Test @Config(sdk = 31) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void automaticDimmingChangesBrightFrostedPixelsAndPreservesDarkArt() {
        AppSettings prefs = new AppSettings(RuntimeEnvironment.getApplication());
        Bitmap art = Bitmap.createBitmap(18, 18, Bitmap.Config.ARGB_8888);
        try {
            art.eraseColor(Color.WHITE);
            int dimmed = backdropPixel(prefs, art, true);
            int original = backdropPixel(prefs, art, false);
            assertTrue(Appearance.luma(dimmed) + 0.04f < Appearance.luma(original));
            art.eraseColor(0xff17151b);
            assertEquals(backdropPixel(prefs, art, false), backdropPixel(prefs, art, true));
        } finally {
            art.recycle();
        }
    }

    private static int[] background(AppSettings prefs, int base, int end) {
        return Appearance.background(prefs, GradientDrawable.Orientation.TL_BR, base, end).getColors();
    }

    private static int backdropPixel(AppSettings prefs, Bitmap source, boolean dim) {
        prefs.set("dimBright", dim);
        FrostedBackdrop backdrop = new FrostedBackdrop(0xff191520, 0xff130a1d, 0.4, prefs);
        backdrop.setBounds(0, 0, 80, 80);
        backdrop.setAlbum(source);
        Bitmap frame = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888);
        try {
            backdrop.draw(new Canvas(frame));
            return frame.getPixel(4, 4);
        } finally {
            frame.recycle();
        }
    }
}
