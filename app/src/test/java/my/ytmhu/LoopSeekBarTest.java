// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.widget.SeekBar;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 27, qualifiers = "mdpi")
public class LoopSeekBarTest {
    @Test public void markersDescribeTheLoopWithoutChangingProgress() {
        LoopSeekBar seek = new LoopSeekBar(RuntimeEnvironment.getApplication(), "Track progress", Color.WHITE, false);
        seek.setMax(10_000);
        seek.setProgress(4000);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar view, int progress, boolean fromUser) {
                throw new AssertionError("Marker update changed playback progress");
            }
            @Override public void onStartTrackingTouch(SeekBar view) {}
            @Override public void onStopTrackingTouch(SeekBar view) {}
        });
        seek.setLoopBounds(2000, -1, ", loop start 0:02");
        assertEquals("Track progress, loop start 0:02", seek.getContentDescription());
        seek.setLoopBounds(2000, 6000, ", loop from 0:02 to 0:06");
        assertEquals("Track progress, loop from 0:02 to 0:06", seek.getContentDescription());
        seek.setLoopBounds(-1, -1, "");
        assertEquals("Track progress", seek.getContentDescription());
        assertEquals(4000, seek.getProgress());
    }

    @Test
    @Config(sdk = 31, qualifiers = "mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void drawsMarkersAtThePaddedTrackPositionsAndClearsThem() {
        LoopSeekBar seek = new LoopSeekBar(RuntimeEnvironment.getApplication(), "Track progress", Color.GREEN, false);
        seek.setPadding(10, 0, 10, 0);
        seek.setMax(100);
        AdaptiveUiTest.layout(seek, 220, 48);
        Bitmap plain = draw(seek);
        seek.setLoopBounds(25, -1, ", loop start");
        Bitmap start = draw(seek);
        assertNotEquals(plain.getPixel(60, 21), start.getPixel(60, 21));
        assertEquals(plain.getPixel(160, 21), start.getPixel(160, 21));
        seek.setLoopBounds(25, 75, ", loop range");
        Bitmap both = draw(seek);
        assertNotEquals(plain.getPixel(160, 21), both.getPixel(160, 21));
        assertNotEquals(plain.getPixel(110, 24), both.getPixel(110, 24));
        seek.setLoopBounds(-1, -1, "");
        assertEquals(plain.getPixel(160, 21), draw(seek).getPixel(160, 21));
    }

    private static Bitmap draw(LoopSeekBar seek) {
        Bitmap bitmap = Bitmap.createBitmap(seek.getWidth(), seek.getHeight(), Bitmap.Config.ARGB_8888);
        seek.draw(new Canvas(bitmap));
        return bitmap;
    }
}
