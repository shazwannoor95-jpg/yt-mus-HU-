// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class PlaylistEdits {
    static final int TITLE_LIMIT = 150;
    static final int DESCRIPTION_LIMIT = 5000;

    static boolean isPlaylistId(String id) { return id.matches("PL[A-Za-z0-9_-]{2,188}"); }

    static boolean validTitle(String title) {
        return !title.trim().isEmpty() && title.length() <= TITLE_LIMIT && !title.contains("<") && !title.contains(">");
    }

    static JSONObject create(String title, String description) throws JSONException {
        if (title == null) throw new IllegalArgumentException("Playlist name required");
        check(title, description);
        return new JSONObject().put("title", title.trim()).put("description", description).put("privacyStatus", "PRIVATE");
    }

    static JSONObject update(String id, String title, String description) throws JSONException {
        if (!isPlaylistId(id) || title == null && description == null) throw new IllegalArgumentException("Invalid playlist edit");
        check(title, description);
        JSONArray actions = new JSONArray();
        if (title != null) actions.put(new JSONObject().put("action", "ACTION_SET_PLAYLIST_NAME").put("playlistName", title.trim()));
        if (description != null) actions.put(new JSONObject().put("action", "ACTION_SET_PLAYLIST_DESCRIPTION").put("playlistDescription", description));
        return new JSONObject().put("playlistId", id).put("actions", actions);
    }

    private static void check(String title, String description) {
        if (title != null && !validTitle(title) || description != null && description.length() > DESCRIPTION_LIMIT) {
            throw new IllegalArgumentException("Invalid playlist text");
        }
    }
}
