// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import static org.junit.Assert.assertEquals;

import android.media.MediaFormat;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 27)
public final class VideoRenditionsTest {
    @Test public void balancedKeepsAVCFallbackWithinDisplayAndBandwidth() {
        MediaFormat hd = format("video/avc", 720, 2_400_000, 30);
        MediaFormat wide = format("video/hevc", 480, 2_000_000, 30);
        MediaFormat main = format("video/avc", 360, 650_000, 30);
        MediaFormat fallback = format("video/avc", 180, 250_000, 15);
        assertEquals(Arrays.asList(main, fallback), VideoRenditions.rank(Arrays.asList(hd, wide, fallback, main),
                PerformancePolicy.Profile.BALANCED, 600, 1_300_000, format -> VideoRenditions.Decoder.HARDWARE));
    }

    @Test public void litePrefersHardwareAndRejectsUnprovedOrCostlyFormats() {
        MediaFormat unsupported = format("video/av01", 240, 300_000, 30);
        MediaFormat heavy = format("video/avc", 240, 400_000, 60);
        MediaFormat software = format("video/avc", 240, 350_000, 30);
        MediaFormat hardware = format("video/avc", 180, 250_000, 15);
        assertEquals(Arrays.asList(hardware, software), VideoRenditions.rank(
                Arrays.asList(unsupported, heavy, software, hardware), PerformancePolicy.Profile.LITE, 600, 0,
                candidate -> candidate == unsupported ? VideoRenditions.Decoder.UNSUPPORTED : candidate == hardware
                        ? VideoRenditions.Decoder.HARDWARE : VideoRenditions.Decoder.SOFTWARE));
        assertEquals(Collections.emptyList(), VideoRenditions.rank(Collections.singletonList(software),
                PerformancePolicy.Profile.HIGH_QUALITY, 144, 0, candidate -> VideoRenditions.Decoder.UNKNOWN));
    }

    private static MediaFormat format(String mime, int height, int bits, int fps) {
        MediaFormat format = MediaFormat.createVideoFormat(mime, height * 16 / 9, height);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bits);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        return format;
    }
}
