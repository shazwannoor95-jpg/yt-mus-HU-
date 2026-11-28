// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Local recording positions and optional notes. Accessed from playback menus on the main thread. */
final class PlaybackBookmarks {
    static final int LIMIT = 64;
    static final int NOTE_LIMIT = 160;
    private static final Pattern TRACK_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private final SharedPreferences preferences;

    PlaybackBookmarks(Context context) {
        preferences = context.getSharedPreferences("playback_bookmarks", Context.MODE_PRIVATE);
    }

    static final class Bookmark {
        final String recording;
        final int position;
        final String note;

        Bookmark(String recording, int position, String note) {
            this.recording = recording;
            this.position = position;
            this.note = note;
        }
    }

    static boolean supports(MusicApi.Track recording) {
        return recording != null && TRACK_ID.matcher(recording.id).matches();
    }

    List<Bookmark> forRecording(String id) {
        List<Bookmark> result = read();
        result.removeIf(bookmark -> !bookmark.recording.equals(id));
        result.sort(Comparator.comparingInt(bookmark -> bookmark.position));
        return result;
    }

    boolean save(String id, int position, String note) {
        if (!TRACK_ID.matcher(id).matches() || position < 0 || note.length() > NOTE_LIMIT) return false;
        List<Bookmark> entries = read();
        entries.removeIf(bookmark -> bookmark.recording.equals(id) && bookmark.position == position);
        if (entries.size() == LIMIT) return false;
        entries.add(new Bookmark(id, position, note.trim()));
        write(entries);
        return true;
    }

    void delete(Bookmark entry) {
        List<Bookmark> entries = read();
        if (entries.removeIf(bookmark -> bookmark.recording.equals(entry.recording)
                && bookmark.position == entry.position)) write(entries);
    }

    private List<Bookmark> read() {
        ArrayList<Bookmark> entries = new ArrayList<>();
        try {
            String stored = preferences.getString("items", "[]");
            if (stored.length() > 96_000) return entries;
            JSONArray records = new JSONArray(stored);
            for (int i = 0; i < Math.min(records.length(), LIMIT); i++) {
                JSONObject record = records.optJSONObject(i);
                if (record == null) continue;
                String id = record.optString("recording");
                int position = record.optInt("position", -1);
                String note = record.optString("note");
                if (TRACK_ID.matcher(id).matches() && position >= 0 && note.length() <= NOTE_LIMIT) {
                    entries.add(new Bookmark(id, position, note));
                }
            }
        } catch (JSONException | ClassCastException invalid) {
            // Invalid local data does not affect the player or its queue.
        }
        return entries;
    }

    private void write(List<Bookmark> entries) {
        JSONArray stored = new JSONArray();
        for (Bookmark bookmark : entries) {
            try {
                stored.put(new JSONObject().put("recording", bookmark.recording)
                        .put("position", bookmark.position).put("note", bookmark.note));
            } catch (JSONException unexpected) {
                throw new IllegalStateException("Could not encode bookmark", unexpected);
            }
        }
        preferences.edit().putString("items", stored.toString()).apply();
    }
}
