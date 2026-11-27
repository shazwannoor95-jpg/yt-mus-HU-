// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.content.Context;
import android.content.SharedPreferences;

final class AppSettings {
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

    String language() { return text("songLanguage", "en"); }
    String country() { return text("songCountry", "MY"); }
    float speed() { return flag("speedEnabled", false) ? number("speed", 100) / 100f : 1f; }
    int lyricOffset() { return number("lyricsOffset", 0) + number("lineTrim", 0); }
}
