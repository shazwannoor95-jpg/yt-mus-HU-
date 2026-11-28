// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Color;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 27)
public final class VideoAmbientLightTest {
    @Test public void samplingStopsOnConstrainedProfiles() {
        assertEquals(0, VideoAmbientLight.intervalMs(PerformancePolicy.Profile.ULTRA_LITE));
        assertEquals(0, VideoAmbientLight.intervalMs(PerformancePolicy.Profile.LITE));
        assertEquals(750, VideoAmbientLight.intervalMs(PerformancePolicy.Profile.BALANCED));
        assertEquals(375, VideoAmbientLight.intervalMs(PerformancePolicy.Profile.HIGH_QUALITY));
    }

    @Test public void derivesAndEasesColoursWithoutBrightFlashes() {
        int initial = VideoAmbientLight.dimAndBlend(Color.BLACK, Color.RED);
        assertTrue(Color.red(initial) > 0 && Color.red(initial) < 114);
        assertEquals(0, Color.blue(initial));
        int next = VideoAmbientLight.dimAndBlend(initial, Color.BLUE);
        assertTrue(Color.red(next) > 0 && Color.red(next) < Color.red(initial));
        assertTrue(Color.blue(next) > Color.red(next));
        for (int i = 0; i < 20; i++) next = VideoAmbientLight.dimAndBlend(next, Color.WHITE);
        assertTrue(Color.red(next) <= 114 && Color.green(next) <= 114 && Color.blue(next) <= 114);
    }
}
