// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class PlaylistEdits {
    static final int TITLE_LIMIT = 150;
    static final int DESCRIPTION_LIMIT = 5000;

    static boolean isPlaylistId(String id) { return id.matches("PL[A-Za-z0-9_-]{2,188}"); }
    static boolean isEntryId(String id) { return id.matches("[A-Za-z0-9_+=/-]{1,512}"); }

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

    static JSONObject remove(String id, String videoId, String entryId) throws JSONException {
        if (!videoId.matches("[A-Za-z0-9_-]{11}")) throw new IllegalArgumentException("Invalid recording");
        return entryMutation(id, entryId, "ACTION_REMOVE_VIDEO", new JSONObject().put("removedVideoId", videoId));
    }

    static JSONObject move(String id, String entryId, String successor) throws JSONException {
        if (entryId.equals(successor) || !isEntryId(successor)) throw new IllegalArgumentException("Invalid successor");
        return entryMutation(id, entryId, "ACTION_MOVE_VIDEO_BEFORE", new JSONObject().put("movedSetVideoIdSuccessor", successor));
    }

    private static JSONObject entryMutation(String id, String entryId, String action, JSONObject fields) throws JSONException {
        if (!isPlaylistId(id) || !isEntryId(entryId)) throw new IllegalArgumentException("Invalid playlist entry");
        fields.put("action", action).put("setVideoId", entryId);
        return new JSONObject().put("playlistId", id).put("actions", new JSONArray().put(fields));
    }

    static boolean confirmsDeletion(String id, JSONObject response) {
        JSONObject command = MusicApi.child(MusicApi.child(response, "command"), "commandExecutorCommand");
        JSONArray commands = MusicApi.array(command, "commands");
        for (int i = 0; i < commands.length(); i++) {
            if (id.equals(MusicApi.child(MusicApi.item(commands, i), "handlePlaylistDeletionCommand").optString("playlistId"))) return true;
        }
        return false;
    }

    private static void check(String title, String description) {
        if (title != null && !validTitle(title) || description != null && description.length() > DESCRIPTION_LIMIT) {
            throw new IllegalArgumentException("Invalid playlist text");
        }
    }
}
