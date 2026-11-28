// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Stores only bounded audio queue metadata. Media URLs and account data never enter this file. */
final class QueueStore {
    static final int LIMIT = 200;
    private static final int MAX_JSON = 524_288;
    private final SharedPreferences disk;
    private List<MusicApi.Track> lastWindow = Collections.emptyList();
    private List<Integer> lastRanks = Collections.emptyList();
    private int lastIndex = -2, lastPosition = -1, lastRepeat = -1;
    private boolean lastShuffle;

    QueueStore(Context context) {
        disk = context.getSharedPreferences("playback_queue", Context.MODE_PRIVATE);
    }

    static final class Snapshot {
        final List<MusicApi.Track> queue, original;
        final int index, position, repeat;
        final boolean shuffle;

        Snapshot(List<MusicApi.Track> queue, List<MusicApi.Track> original, int index, int position,
                 int repeat, boolean shuffle) {
            this.queue = queue;
            this.original = original;
            this.index = index;
            this.position = position;
            this.repeat = repeat;
            this.shuffle = shuffle;
        }
    }

    Snapshot load(boolean hideExplicit) {
        try {
            String source = disk.getString("entries", "");
            if (source.isEmpty() || source.length() > MAX_JSON) return null;
            JSONArray stored = new JSONArray(source);
            if (stored.length() > LIMIT) return null;
            int selected = disk.getInt("index", -1);
            int index = -1;
            List<MusicApi.Track> queue = new ArrayList<>();
            Map<MusicApi.Track, Integer> ranks = new IdentityHashMap<>();
            for (int i = 0; i < stored.length(); i++) {
                JSONObject item = stored.optJSONObject(i);
                if (item == null || !videoId(item.optString("id")) || hideExplicit && item.optBoolean("explicit")) continue;
                MusicApi.Track track = decode(item);
                if (i == selected) index = queue.size();
                queue.add(track);
                ranks.put(track, item.optInt("rank", i));
            }
            if (queue.isEmpty()) return null;
            List<MusicApi.Track> original = new ArrayList<>(queue);
            original.sort(Comparator.comparingInt(ranks::get));
            int position = index < 0 ? 0 : Math.max(0, disk.getInt("position", 0));
            int repeat = Math.min(2, Math.max(0, disk.getInt("repeat", 0)));
            return new Snapshot(queue, original, index, position, repeat, disk.getBoolean("shuffle", false));
        } catch (JSONException | ClassCastException invalid) {
            return null;
        }
    }

    void save(List<MusicApi.Track> queue, List<MusicApi.Track> original, int selected, int position,
              int repeat, boolean shuffle) {
        if (queue.isEmpty()) { clear(); return; }
        int start = Math.max(0, Math.min(selected - LIMIT / 4, queue.size() - LIMIT));
        List<MusicApi.Track> window = new ArrayList<>(LIMIT);
        List<Integer> ranks = new ArrayList<>(LIMIT);
        Map<MusicApi.Track, Integer> originalRanks = new IdentityHashMap<>();
        for (int i = 0; i < original.size(); i++) originalRanks.putIfAbsent(original.get(i), i);
        int relativeIndex = -1;
        for (int i = start; i < Math.min(queue.size(), start + LIMIT); i++) {
            MusicApi.Track track = queue.get(i);
            if (!videoId(track.id)) continue;
            if (i == selected) relativeIndex = window.size();
            window.add(track);
            ranks.add(originalRanks.getOrDefault(track, i));
        }
        if (window.isEmpty()) { clear(); return; }
        if (relativeIndex < 0) position = 0;
        boolean changed = !window.equals(lastWindow) || !ranks.equals(lastRanks);
        if (!changed && relativeIndex == lastIndex && position == lastPosition && repeat == lastRepeat
                && shuffle == lastShuffle) return;
        SharedPreferences.Editor edit = disk.edit();
        if (changed) {
            JSONArray entries = new JSONArray();
            try {
                for (int i = 0; i < window.size(); i++) entries.put(encode(window.get(i), ranks.get(i)));
            } catch (JSONException invalid) {
                return;
            }
            edit.putString("entries", entries.toString());
            lastWindow = window;
            lastRanks = ranks;
        }
        edit.putInt("index", relativeIndex).putInt("position", position)
                .putInt("repeat", repeat).putBoolean("shuffle", shuffle).apply();
        lastIndex = relativeIndex;
        lastPosition = position;
        lastRepeat = repeat;
        lastShuffle = shuffle;
    }

    private void clear() {
        if (disk.contains("entries")) disk.edit().clear().apply();
        lastWindow = Collections.emptyList();
        lastRanks = Collections.emptyList();
        lastIndex = -2;
    }

    private static JSONObject encode(MusicApi.Track track, int rank) throws JSONException {
        JSONArray credits = new JSONArray();
        for (MusicApi.ArtistCredit credit : track.artists) {
            if (credits.length() == 8) break;
            if (credit.browseId.matches("UC[A-Za-z0-9_-]{22}")) {
                credits.put(new JSONObject().put("name", bounded(credit.name)).put("id", credit.browseId));
            }
        }
        JSONObject saved = new JSONObject().put("id", track.id).put("title", bounded(track.title))
                .put("artist", bounded(track.artist)).put("art", artwork(track.artwork))
                .put("explicit", track.explicit).put("rank", rank).put("credits", credits);
        if (track.album != null) {
            saved.put("album", bounded(track.album.name));
            if (MusicApi.AlbumCredit.isBrowseId(track.album.browseId)) saved.put("albumId", track.album.browseId);
        }
        return saved;
    }

    private static MusicApi.Track decode(JSONObject item) {
        List<MusicApi.ArtistCredit> credits = new ArrayList<>();
        JSONArray values = item.optJSONArray("credits");
        for (int i = 0; values != null && i < Math.min(values.length(), 8); i++) {
            JSONObject value = values.optJSONObject(i);
            if (value == null) continue;
            String id = value.optString("id");
            if (id.matches("UC[A-Za-z0-9_-]{22}")) credits.add(new MusicApi.ArtistCredit(bounded(value.optString("name")), id));
        }
        String albumName = bounded(item.optString("album"));
        String albumId = item.optString("albumId");
        MusicApi.AlbumCredit album = albumName.isEmpty() ? null : new MusicApi.AlbumCredit(albumName,
                MusicApi.AlbumCredit.isBrowseId(albumId) ? albumId : "");
        return new MusicApi.Track(item.optString("id"), bounded(item.optString("title")),
                bounded(item.optString("artist")), artwork(item.optString("art")), item.optBoolean("explicit"), credits, album);
    }

    private static String artwork(String input) {
        if (input.length() > 512) return "";
        Uri url = Uri.parse(input);
        String host = url.getHost();
        if (!"https".equals(url.getScheme()) || url.getUserInfo() != null || url.getPort() != -1 || host == null
                || !(host.equals("i.ytimg.com") || host.equals("yt3.ggpht.com") || host.equals("lh3.googleusercontent.com"))) {
            return "";
        }
        return url.buildUpon().clearQuery().fragment(null).build().toString();
    }

    private static boolean videoId(String id) { return id.matches("[A-Za-z0-9_-]{11}"); }
    private static String bounded(String value) { return value.length() <= 180 ? value : value.substring(0, 180); }
}
