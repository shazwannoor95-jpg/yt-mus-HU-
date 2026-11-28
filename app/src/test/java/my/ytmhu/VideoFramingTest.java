// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import static org.junit.Assert.*;

import android.graphics.RectF;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 27)
public final class VideoFramingTest {
    @Test public void fitLetterboxesAndFillCropsAroundTheSameCentre() {
        RectF fit = new RectF(0, 0, 400, 400);
        VideoFraming.transform(400, 400, 320, 180, false).mapRect(fit);
        assertEquals(400, fit.width(), 0.01f);
        assertEquals(225, fit.height(), 0.01f);
        assertEquals(200, fit.centerY(), 0.01f);

        RectF fill = new RectF(0, 0, 400, 400);
        VideoFraming.transform(400, 400, 320, 180, true).mapRect(fill);
        assertEquals(400, fill.height(), 0.01f);
        assertEquals(400 * 16 / 9f, fill.width(), 0.01f);
        assertEquals(200, fill.centerX(), 0.01f);
    }
}
