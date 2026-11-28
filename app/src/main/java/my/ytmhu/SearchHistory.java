// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

final class SearchHistory {
    private static final String KEY = "recentMusicSearches";
    private static final int LIMIT = 8;

    private SearchHistory() {}

    static List<String> recent(AppSettings prefs) {
        List<String> results = new ArrayList<>();
        try {
            JSONArray saved = new JSONArray(prefs.text(KEY, "[]"));
            for (int i = 0; i < saved.length() && results.size() < LIMIT; i++) {
                Object value = saved.opt(i);
                if (value instanceof String && valid((String) value)) results.add((String) value);
            }
        } catch (JSONException ignored) {
            // Malformed local history opens as empty.
        }
        return results;
    }

    static void record(AppSettings prefs, String submitted) {
        String query = submitted.trim();
        if (!valid(query)) return;
        JSONArray list = new JSONArray().put(query);
        for (String prior : recent(prefs)) {
            if (list.length() == LIMIT) break;
            if (!prior.equalsIgnoreCase(query)) list.put(prior);
        }
        prefs.set(KEY, list.toString());
    }

    static void clear(AppSettings prefs) { prefs.set(KEY, "[]"); }

    private static boolean valid(String value) { return !value.trim().isEmpty() && value.length() <= 160; }
}
