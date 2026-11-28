// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Locale;

final class AppSettings {
    static final String DEFAULT_LYRIC_TARGET = "en";
    private final SharedPreferences values;

    AppSettings(Context context) {
        values = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    boolean flag(String key, boolean fallback) { return values.getBoolean(key, fallback); }
    int number(String key, int fallback) { return values.getInt(key, fallback); }
    String text(String key, String fallback) { return values.getString(key, fallback); }
    void set(String key, boolean value) { values.edit().putBoolean(key, value).apply(); }
    void set(String key, int value) { values.edit().putInt(key, value).apply(); }
    void set(String key, String value) { values.edit().putString(key, value).apply(); }

    String sidebar() {
        return text("sidebar", flag("collapseSidebar", false) ? "rail" : "auto");
    }

    void resetLayout() {
        values.edit().remove("layoutDensity").remove("sidebar").remove("collapseSidebar").remove("zoom").apply();
    }

    String language() { return text("songLanguage", "en"); }
    String country() { return text("songCountry", "MY"); }
    float speed() { return flag("speedEnabled", false) ? number("speed", 100) / 100f : 1f; }
    int lyricOffset() { return number("lyricsOffset", 0) + number("lineTrim", 0); }

    String lyricTarget() {
        String chosen = text("lyricTarget", DEFAULT_LYRIC_TARGET);
        if (!"auto".equals(chosen)) return chosen;
        String language = Locale.getDefault().getLanguage();
        switch (language) {
            case "zh": return "zh-CN";
            case "in": return "id";
            case "ms": case "id": case "ja": case "ko": case "ru": return language;
            default: return DEFAULT_LYRIC_TARGET;
        }
    }
}
