// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class Appearance {
    final int window, sidebar, surface, control, start, end, accent;
    final Typeface font, icons;
    final List<JSONObject> themes;
    final boolean solid, albumTint, glass, centerNowPlaying, barePlay;
    final String progressColour;
    final int thumbRadius, cardRadius, searchRadius, coverRadius, barAlpha, lyricsSize, titleSize, artistSize;
    final double scrim;
    final String themeId, themeName;

    Appearance(Context context, AppSettings prefs) {
        themes = readThemes(context);
        String chosen = prefs.text("theme", "ytm-rs");
        JSONObject selected = new JSONObject();
        for (JSONObject preset : themes) {
            if (chosen.equals(preset.optString("id"))) {
                selected = preset;
                break;
            }
        }
        int[] colours = {0xff120c16, 0xff1b111d, 0xff211721, 0xff392c39, 0xff281520, 0xff100d17, 0xfff83b61};
        if (!"ytm-rs".equals(chosen)) {
            int base = hex(string(selected.optJSONObject("background"), "solid"), 0xff171318);
            int highlight = hex(selected.optString("accent"), 0xffb69bbe);
            colours = new int[]{base, mix(base, Color.WHITE, 0.04), mix(base, Color.WHITE, 0.08),
                    mix(base, Color.WHITE, 0.17), mix(base, highlight, 0.13), base, highlight};
        }
        themeId = selected.optString("id", "ytm-rs");
        themeName = selected.optString("name", "ytm-rs");
        JSONObject background = selected.optJSONObject("background");
        JSONObject app = selected.optJSONObject("app");
        JSONObject chrome = selected.optJSONObject("chrome");
        JSONObject lyrics = selected.optJSONObject("lyrics");
        JSONObject cover = selected.optJSONObject("cover");
        JSONObject now = selected.optJSONObject("now_playing");
        String mode = string(app, "page_bg");
        solid = "solid".equals(mode);
        String glassChoice = prefs.text("glass", "theme");
        boolean themeGlass = "glass".equals(string(app, "sidebar"))
                || (app != null && app.optJSONObject("glass") != null);
        glass = "on".equals(glassChoice) || ("theme".equals(glassChoice) && themeGlass);
        albumTint = "on".equals(glassChoice) || "ytm-rs".equals(themeId)
                || "same".equals(mode) || "static".equals(mode) || mode.isEmpty();
        barAlpha = (int) Math.max(115, Math.min(235, (app == null ? 0.70 : app.optDouble("bar_alpha", 0.70)) * 255));
        scrim = prefs.flag("flat", false) ? 1.0 : Math.max(0.25, Math.min(0.85,
                background == null ? 0.47 : background.optDouble("scrim", 0.47)));
        thumbRadius = bounded(app, "thumb_radius", 5, 30);
        cardRadius = bounded(app, "card_radius", 8, 24);
        searchRadius = bounded(app, "search_radius", 21, 32);
        coverRadius = bounded(cover, "radius", 12, 30);
        lyricsSize = Math.max(16, Math.min(34, bounded(lyrics, "rem", 16, 27) * 3 / 2));
        titleSize = Math.max(17, bounded(now, "title_px", 24, 35));
        artistSize = Math.max(12, bounded(now, "artist_px", 16, 21));
        centerNowPlaying = "center".equals(string(now, "title_align"));
        barePlay = "bare".equals(string(chrome, "play_button"));
        progressColour = string(chrome, "progress_color");
        if (chrome != null) colours[0] = hex(chrome.optString("bar_color"), colours[0]);
        window = colours[0];
        sidebar = colours[1];
        surface = colours[2];
        control = colours[3];
        start = colours[4];
        end = colours[5];
        switch (prefs.text("accent", "theme")) {
            case "red": accent = 0xffff526f; break;
            case "amber": accent = 0xffffba65; break;
            case "blue": accent = 0xff8ab8ff; break;
            case "green": accent = 0xff80d8ad; break;
            default: accent = colours[6];
        }
        String type = prefs.text("font", "system");
        Typeface chosenFont = Typeface.DEFAULT;
        if ("Inter".equals(type) || "DMSans".equals(type) || "Figtree".equals(type)) {
            try { chosenFont = Typeface.createFromAsset(context.getAssets(), "fonts/" + type + "-Regular.ttf"); }
            catch (RuntimeException unavailable) { /* Keep the Android font. */ }
        }
        font = chosenFont;
        icons = Typeface.createFromAsset(context.getAssets(), "fonts/lucide.ttf");
    }

    int progress(int accent) {
        if ("white".equals(progressColour)) return Color.WHITE;
        return hex(progressColour, accent);
    }

    int panel(int color, int opacity) {
        return glass ? (color & 0x00ffffff) | opacity << 24 : color;
    }

    private static String string(JSONObject object, String key) {
        return object == null ? "" : object.optString(key);
    }

    private static int bounded(JSONObject object, String key, int fallback, int max) {
        return object == null ? fallback : Math.max(0, Math.min(max, object.optInt(key, fallback)));
    }

    private static List<JSONObject> readThemes(Context context) {
        List<JSONObject> order = new ArrayList<>();
        try (InputStream data = context.getAssets().open("theme-presets.json")) {
            ByteArrayOutputStream text = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            for (int length; (length = data.read(buffer)) != -1;) text.write(buffer, 0, length);
            JSONArray list = new JSONArray(new String(text.toByteArray(), StandardCharsets.UTF_8));
            for (int i = 0; i < list.length(); i++) order.add(list.getJSONObject(i));
        } catch (Exception unavailable) {
            return order;
        }
        order.sort(Comparator.comparing(preset -> preset.optString("name"), String.CASE_INSENSITIVE_ORDER));
        return order;
    }

    private static int hex(String value, int fallback) {
        return value.matches("#[0-9A-Fa-f]{6}") ? Color.parseColor(value) : fallback;
    }

    private static int mix(int a, int b, double amount) {
        return Color.rgb((int) (Color.red(a) * (1 - amount) + Color.red(b) * amount),
                (int) (Color.green(a) * (1 - amount) + Color.green(b) * amount),
                (int) (Color.blue(a) * (1 - amount) + Color.blue(b) * amount));
    }

    static GradientDrawable background(AppSettings prefs, GradientDrawable.Orientation direction, int from, int to) {
        int level = prefs.number("brightness", 100);
        if (prefs.flag("flat", false)) return new GradientDrawable(direction, new int[]{brightness(from, level), brightness(from, level)});
        return new GradientDrawable(direction, new int[]{brightness(from, level), brightness(to, level)});
    }

    static int brightness(int color, int value) {
        return Color.rgb(Math.min(255, Color.red(color) * value / 100),
                Math.min(255, Color.green(color) * value / 100),
                Math.min(255, Color.blue(color) * value / 100));
    }
}
