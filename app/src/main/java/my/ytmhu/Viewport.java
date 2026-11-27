// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

final class Viewport {
    final int width, height;
    final float fontScale;
    final boolean shortScreen, compactPlayer, splitSettings, stackedSettingsRows;
    final int railWidth, gutter, playerHeight, target;

    Viewport(int width, int height, float fontScale, boolean manuallyCollapsed) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.fontScale = fontScale;
        shortScreen = height < 480;
        boolean iconRail = manuallyCollapsed || width < 710 || (width < 900 && fontScale > 1.25f);
        railWidth = iconRail ? 56 : width < 920 ? 126 : 154;
        gutter = shortScreen || width < 850 ? 10 : 16;
        int paneWidth = this.width - railWidth - gutter * 2;
        compactPlayer = width < 830 || fontScale > 1.30f;
        splitSettings = paneWidth >= 640 && height >= 470 && fontScale <= 1.35f;
        int settingsDetailWidth = paneWidth - (splitSettings ? 150 : 0);
        stackedSettingsRows = settingsDetailWidth < 480 || fontScale > 1.35f;
        target = 48;
        playerHeight = Math.max(shortScreen ? 52 : 60, Math.min(72, Math.round(32 * fontScale)));
    }

    boolean iconRail() { return railWidth == 56; }
    boolean inlineSeek() { return width >= (compactPlayer ? 560 : 785); }

    int shelfColumns(int width, int gap, int preferred, int maximum) {
        if (width <= 0 || maximum <= 0) return 1;
        return Math.max(1, Math.min(maximum, (width + gap) / (preferred + gap)));
    }

    static int shelfCell(int width, int gap, int columns) {
        return Math.max(1, (width - gap * (columns - 1)) / columns);
    }
}
