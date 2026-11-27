// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import static my.ytmhu.PerformancePolicy.Profile.*;
import static org.junit.Assert.*;

import org.junit.Test;

public final class PerformancePolicyTest {
    private PerformancePolicy policy(int memory, int heap, int gpu, int free) {
        return new PerformancePolicy(new PerformancePolicy.Capabilities(memory, heap, gpu, false),
                "AUTO", free, 60, false, 0);
    }

    @Test public void affordableDevicesReachBalancedAndHighQuality() {
        PerformancePolicy low = policy(512, 64, 0x20000, 150);
        assertEquals(BALANCED, low.effective());
        assertTrue(low.cacheMiB() <= 2);
        assertEquals(BALANCED, policy(1024, 96, 0x20000, 315).effective());
        assertEquals(BALANCED, policy(1920, 128, 0x20000, 300).effective());
        assertEquals(HIGH_QUALITY, policy(1920, 128, 0x20000, 550).effective());
        for (int size : new int[]{3072, 4096, 6144, 8192}) {
            assertEquals(HIGH_QUALITY, policy(size, 256, 0x30000, 700).effective());
        }
    }

    @Test public void weakDevicesAndMemoryPressureFallBackWithoutFlapping() {
        assertEquals(LITE, policy(1024, 96, 0x10000, 250).effective());
        assertEquals(LITE, policy(512, 40, 0x20000, 250).effective());
        assertEquals(ULTRA_LITE, policy(512, 64, 0x20000, 42).effective());
        PerformancePolicy ceiling = policy(1920, 128, 0x20000, 580);
        ceiling.memoryPressure(false, 1_000);
        ceiling.memoryPressure(false, 5_000);
        assertEquals(BALANCED, ceiling.effective());
        ceiling.recover(700, 60, false, 30_000);
        assertEquals(BALANCED, ceiling.effective());
        ceiling.recover(350, 60, false, 100_000);
        assertEquals(BALANCED, ceiling.effective());
        ceiling.recover(550, 60, false, 105_000);
        assertEquals(HIGH_QUALITY, ceiling.effective());
    }

    @Test public void repeatedFrameStallsAndManualOverridesRespectGuards() {
        PerformancePolicy balanced = policy(1024, 96, 0x20000, 315);
        balanced.choose("HIGH_QUALITY", 0);
        assertEquals(BALANCED, balanced.effective());
        assertTrue(balanced.summary().contains("capability"));
        balanced.frames(11, 11, 315, 60, false, 3_000);
        balanced.frames(25, 10, 315, 60, false, 6_000);
        assertEquals(BALANCED, balanced.effective());
        balanced.frames(25, 11, 315, 60, false, 9_000);
        assertEquals(LITE, balanced.effective());
        balanced.choose("HIGH_QUALITY", 12_000);
        assertEquals(LITE, balanced.effective());
        balanced.choose("ULTRA_LITE", 13_000);
        balanced.recover(700, 60, false, 200_000);
        assertEquals(ULTRA_LITE, balanced.effective());
        balanced.choose("AUTO", 200_000);
        assertEquals(BALANCED, balanced.effective());
        balanced.memoryPressure(true, 201_000);
        balanced.recover(700, 60, false, 300_000);
        assertEquals(LITE, balanced.effective());
    }

    @Test public void densityChangesArtworkSpacingAndKeepsTouchTargets() {
        Viewport reference = new Viewport(1024, 552, 1, 160, "auto", "auto");
        assertEquals(85, reference.densityPercent);
        assertEquals(68, reference.railWidth);
        assertEquals(48, reference.target);
        assertTrue(reference.splitSettings);
        assertEquals(6, reference.shelfColumns(900, reference.gap, reference.albumCard, 8));
        Viewport expanded = new Viewport(1024, 552, 1, 160, "large", "expanded");
        assertEquals(168, expanded.railWidth);
        assertTrue(expanded.albumCard > reference.albumCard);
        assertEquals(reference.target, expanded.target);
        Viewport shortWindow = new Viewport(560, 320, 1.8f, 320, "ultra", "expanded");
        assertTrue(shortWindow.drawerSidebar);
        assertTrue(shortWindow.twoLinePlayer);
        assertFalse(shortWindow.splitSettings);
    }
}
