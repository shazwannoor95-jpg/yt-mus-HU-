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
    final Typeface font;
    final List<JSONObject> themes;
    final boolean solid, albumTint;

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
            int base = hex(selected.optString("solid"), 0xff171318);
            int highlight = hex(selected.optString("accent"), 0xffb69bbe);
            colours = new int[]{base, mix(base, Color.WHITE, 0.04), mix(base, Color.WHITE, 0.08),
                    mix(base, Color.WHITE, 0.17), mix(base, highlight, 0.13), base, highlight};
        }
        String mode = selected.optString("mode");
        solid = "solid".equals(mode);
        albumTint = "ytm-rs".equals(chosen) || "same".equals(mode) || "static".equals(mode) || mode.isEmpty();
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
    }

    private static List<JSONObject> readThemes(Context context) {
        List<JSONObject> order = new ArrayList<>();
        try (InputStream data = context.getAssets().open("theme-palettes.json")) {
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

    private static int brightness(int color, int value) {
        return Color.rgb(Math.min(255, Color.red(color) * value / 100),
                Math.min(255, Color.green(color) * value / 100),
                Math.min(255, Color.blue(color) * value / 100));
    }
}
