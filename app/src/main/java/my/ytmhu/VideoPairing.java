// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

import static my.ytmhu.MusicApi.array;
import static my.ytmhu.MusicApi.child;
import static my.ytmhu.MusicApi.item;

/** Validates provider metadata separately from entitlement, delivery and display policy. */
final class VideoPairing {
    private static final int MAX_DURATION = 48 * 60 * 60 * 1000;
    private static final int DURATION_SLACK = 1000;

    static VideoPairing declared(JSONObject response, MusicApi.Track song) {
        if (!song.id.matches("[A-Za-z0-9_-]{11}") || song.artists.isEmpty()) return null;
        JSONObject next = child(child(response, "contents"), "singleColumnMusicWatchNextResultsRenderer");
        JSONObject tabbed = child(child(next, "tabbedRenderer"), "watchNextTabbedResultsRenderer");
        JSONObject tab = child(child(item(array(tabbed, "tabs"), 0), "tabRenderer"), "content");
        JSONObject queue = child(child(child(tab, "musicQueueRenderer"), "content"), "playlistPanelRenderer");
        JSONArray rows = array(queue, "contents");
        if (rows.length() > 512) return null;
        VideoPairing found = null;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject wrapper = child(item(rows, i), "playlistPanelVideoWrapperRenderer");
            JSONObject primary = child(child(wrapper, "primaryRenderer"), "playlistPanelVideoRenderer");
            if (!song.id.equals(primary.optString("videoId")) || !watchMatches(primary, "MUSIC_VIDEO_TYPE_ATV")) continue;
            JSONArray counterparts = array(wrapper, "counterpart");
            if (counterparts.length() > 8) return null;
            for (int j = 0; j < counterparts.length(); j++) {
                JSONObject pair = item(counterparts, j);
                JSONObject video = child(child(pair, "counterpartRenderer"), "playlistPanelVideoRenderer");
                String id = video.optString("videoId");
                if (!id.matches("[A-Za-z0-9_-]{11}") || id.equals(song.id)
                        || !watchMatches(video, "MUSIC_VIDEO_TYPE_OMV") || !artistMatches(song, video)) continue;
                VideoPairing declared = timeline(song.id, id, array(child(pair, "segmentMap"), "segment"));
                if (declared == null || found != null && !found.sameMap(declared)) return null;
                found = declared;
            }
        }
        return found;
    }

    private static boolean watchMatches(JSONObject row, String type) {
        JSONObject endpoint = child(child(row, "navigationEndpoint"), "watchEndpoint");
        JSONObject config = child(child(endpoint, "watchEndpointMusicSupportedConfigs"), "watchEndpointMusicConfig");
        return row.optString("videoId").equals(endpoint.optString("videoId")) && type.equals(config.optString("musicVideoType"));
    }

    private static boolean artistMatches(MusicApi.Track song, JSONObject video) {
        Set<String> known = new HashSet<>();
        for (MusicApi.ArtistCredit credit : song.artists) known.add(credit.browseId);
        for (String byline : new String[]{"longBylineText", "shortBylineText"}) {
            MusicApi.Track credits = MusicApi.trackWithByline(video.optString("videoId"), "", "", false,
                    array(child(video, byline), "runs"));
            if (!credits.artists.isEmpty()) {
                for (MusicApi.ArtistCredit credit : credits.artists) if (!known.contains(credit.browseId)) return false;
                return song.explicit || !MusicApi.explicit(video);
            }
        }
        return false;
    }

    private static VideoPairing timeline(String song, String video, JSONArray ranges) {
        if (ranges.length() == 0 || ranges.length() > 32) return null;
        int songStart = -1, videoStart = -1, songEnd = -1, videoEnd = -1;
        for (int i = 0; i < ranges.length(); i++) {
            JSONObject range = item(ranges, i);
            int a = millis(range, "primaryVideoStartTimeMilliseconds");
            int b = millis(range, "counterpartVideoStartTimeMilliseconds");
            int span = millis(range, "durationMilliseconds");
            if (a < 0 || b < 0 || span <= 0 || a + span > MAX_DURATION || b + span > MAX_DURATION) return null;
            if (i == 0) {
                songStart = a;
                videoStart = b;
            } else if (a != songEnd || b != videoEnd) return null;
            songEnd = a + span;
            videoEnd = b + span;
        }
        return new VideoPairing(song, video, songStart, videoStart, songEnd, videoEnd);
    }

    private static int millis(JSONObject object, String field) {
        String value = object.optString(field);
        if (!value.matches("[0-9]{1,9}")) return -1;
        return Integer.parseInt(value);
    }

    final String songId, videoId;
    final int songStart, videoStart, songEnd, videoEnd;

    private VideoPairing(String songId, String videoId, int songStart, int videoStart, int songEnd, int videoEnd) {
        this.songId = songId;
        this.videoId = videoId;
        this.songStart = songStart;
        this.videoStart = videoStart;
        this.songEnd = songEnd;
        this.videoEnd = videoEnd;
    }

    private boolean sameMap(VideoPairing other) {
        return videoId.equals(other.videoId) && songStart == other.songStart && songEnd == other.songEnd
                && videoStart == other.videoStart && videoEnd == other.videoEnd;
    }

    int videoPosition(int songPosition) {
        return songPosition < songStart || songPosition > songEnd ? -1 : videoStart + songPosition - songStart;
    }

    // The existing service can safely hand off only when both files use the same song clock.
    boolean fitsSharedClock(int songMillis, int videoMillis) {
        return songStart == 0 && videoStart == 0 && songMillis >= songEnd && videoMillis >= videoEnd
                && songMillis - songEnd <= DURATION_SLACK && videoMillis - videoEnd <= DURATION_SLACK
                && equalDuration(songMillis, videoMillis);
    }

    static boolean equalDuration(int songMillis, int videoMillis) {
        return songMillis > 0 && videoMillis > 0 && Math.abs((long) songMillis - videoMillis) <= DURATION_SLACK;
    }
}
