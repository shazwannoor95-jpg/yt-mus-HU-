// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DemoPlaybackPolicyTest {
    @Test public void onlyIsolatedSdkEmulatorCanStartDemoPlayback() {
        String demo = DemoPlaybackPolicy.PACKAGE;
        assertTrue(DemoPlaybackPolicy.permitted(demo, "ranchu", "sdk_gphone_x86", false));
        assertTrue(DemoPlaybackPolicy.permitted(demo, "goldfish", "sdk_phone_x86", false));
        assertFalse(DemoPlaybackPolicy.permitted(demo, "ranchu", "sdk_car_x86", true));
        assertFalse(DemoPlaybackPolicy.permitted(demo, "8227l", "TEST9278B", false));
        assertFalse(DemoPlaybackPolicy.permitted("my.ytmhu", "ranchu", "sdk_phone_x86", false));
        assertFalse(DemoPlaybackPolicy.permitted(demo, "ranchu", "vendor_device", false));
    }
}
