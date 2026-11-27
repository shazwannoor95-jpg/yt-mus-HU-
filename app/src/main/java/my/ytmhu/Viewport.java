// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Build;
import android.view.WindowInsets;
import android.view.WindowMetrics;

final class Viewport {
    static final int TOUCH = 48;
    final int width, height, densityPercent, densityDpi;
    final float fontScale;
    final boolean shortScreen, compactPlayer, twoLinePlayer, splitSettings, stackedSettingsRows, drawerSidebar;
    final int railWidth, gutter, playerHeight, target = TOUCH, settingsWidth, gap, albumCard;
    final int titleSp, sectionSp, songSp, metadataSp;
    private final boolean collapsed;

    static Viewport current(Activity activity, AppSettings settings) {
        Configuration config = activity.getResources().getConfiguration();
        android.util.DisplayMetrics display = activity.getResources().getDisplayMetrics();
        int width = config.screenWidthDp > 0 ? config.screenWidthDp : Math.round(display.widthPixels / display.density);
        int height = config.screenHeightDp > 0 ? config.screenHeightDp : Math.round(display.heightPixels / display.density);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowMetrics metrics = activity.getWindowManager().getCurrentWindowMetrics();
            Rect bounds = metrics.getBounds();
            Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            if (bounds.width() > insets.left + insets.right && bounds.height() > insets.top + insets.bottom) {
                width = Math.round((bounds.width() - insets.left - insets.right) / display.density);
                height = Math.round((bounds.height() - insets.top - insets.bottom) / display.density);
            }
        }
        return new Viewport(width, height, config.fontScale, display.densityDpi,
                settings.text("layoutDensity", "auto"), settings.sidebar());
    }

    Viewport(int width, int height, float fontScale, int dpi, String density, String sidebar) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.fontScale = fontScale;
        densityDpi = dpi;
        shortScreen = height < 480;
        switch (density) {
            case "ultra": densityPercent = 70; break;
            case "compact": densityPercent = 85; break;
            case "standard": densityPercent = 100; break;
            case "large": densityPercent = 115; break;
            default: densityPercent = width <= 1120 || height <= 640 || (dpi > 240 && width < 1280) ? 85 : 100;
        }
        collapsed = "rail".equals(sidebar) || ("auto".equals(sidebar) && (width <= 1120 || height < 500));
        drawerSidebar = width < 720;
        railWidth = iconRail() ? (width < 480 ? 56 : 68) : width >= 1440 ? 180 : 168;
        gutter = Math.max(8, scale(15));
        gap = Math.max(8, scale(14));
        albumCard = Math.max(96, scale(151));
        settingsWidth = Math.max(176, Math.min(200, scale(188)));
        int paneWidth = this.width - railWidth - gutter * 2;
        splitSettings = paneWidth >= 560 && fontScale <= 1.35f;
        int detail = paneWidth - (splitSettings ? settingsWidth : 0);
        stackedSettingsRows = detail < 440 || fontScale > 1.35f;
        compactPlayer = width < 1120 || fontScale > 1.30f;
        twoLinePlayer = width < 700 || fontScale > 1.45f;
        playerHeight = twoLinePlayer ? (width < 430 ? 150 : 102)
                : (height < 440 ? 64 : 68 + (densityPercent >= 100 ? 4 : 0));
        titleSp = densityPercent >= 100 ? 26 : 24;
        sectionSp = densityPercent >= 100 ? 22 : 20;
        songSp = densityPercent >= 100 ? 16 : 14;
        metadataSp = densityPercent >= 100 ? 14 : 12;
    }

    boolean iconRail() { return collapsed || drawerSidebar; }
    int scale(int value) { return Math.round(value * densityPercent / 100f); }
    int shelfColumns(int width, int gap, int preferred, int maximum) {
        if (width <= 0 || maximum <= 0) return 1;
        return Math.max(1, Math.min(maximum, (width + gap) / (preferred + gap)));
    }

    static int shelfCell(int width, int gap, int columns) {
        return Math.max(1, (width - gap * (columns - 1)) / columns);
    }
}
