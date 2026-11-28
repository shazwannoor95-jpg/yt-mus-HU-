// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 27)
public final class VideoPairingTest {
    private static final String SONG = "abcdefghijk", VIDEO = "12345678901";
    private static final String ARTIST = "UCabcdefghijklmnopqrstuv", OTHER = "UC1234567890123456789012";

    @Test public void verifiesAnExactContiguousCounterpartAndItsClock() throws Exception {
        VideoPairing valid = VideoPairing.declared(response(SONG, pair(VIDEO, ARTIST, segments(0, 0))), song());
        assertNotNull(valid);
        assertEquals(SONG, valid.songId);
        assertEquals(VIDEO, valid.videoId);
        assertEquals(16_000, valid.videoPosition(16_000));
        assertEquals(-1, valid.videoPosition(24_001));
        assertTrue(valid.fitsSharedClock(24_300, 24_500));
        assertFalse(valid.fitsSharedClock(30_000, 30_000));
        assertFalse(valid.fitsSharedClock(23_999, 24_500));
    }

    @Test public void rejectsWrongWrapperUnlinkedArtistsAndCompetingPartners() throws Exception {
        assertNull(VideoPairing.declared(response("otherSong01", pair(VIDEO, ARTIST, segments(0, 0))), song()));
        assertNull(VideoPairing.declared(response(SONG, pair(VIDEO, OTHER, segments(0, 0))), song()));
        assertNull(VideoPairing.declared(response(SONG, pair(VIDEO, "", segments(0, 0))), song()));
        assertNull(VideoPairing.declared(response(SONG, pair(VIDEO, ARTIST, segments(0, 0)),
                pair("09876543210", ARTIST, segments(0, 0))), song()));
    }

    @Test public void unavailableOrDifferentMappingsNeverImplySameTimeline() throws Exception {
        assertNull(VideoPairing.declared(response(SONG, pair(VIDEO, ARTIST, new JSONArray())), song()));
        JSONArray gap = new JSONArray().put(segment(0, 0, 8000)).put(segment(8100, 8100, 12_000));
        assertNull(VideoPairing.declared(response(SONG, pair(VIDEO, ARTIST, gap)), song()));
        VideoPairing offset = VideoPairing.declared(response(SONG, pair(VIDEO, ARTIST, segments(0, 927))), song());
        assertNotNull(offset);
        assertEquals(10_927, offset.videoPosition(10_000));
        assertFalse(offset.fitsSharedClock(24_000, 24_927));
    }

    private static MusicApi.Track song() { return new MusicApi.Track(SONG, "Song", "Artist", "", ARTIST); }

    static VideoPairing declareFixture(MusicApi.Track song, String videoId, int offsetMs, int durationMs) throws Exception {
        JSONArray timeline = new JSONArray().put(segment(0, offsetMs, durationMs));
        return VideoPairing.declared(response(song.id, pair(videoId, ARTIST, timeline)), song);
    }

    private static JSONArray segments(int songStart, int videoStart) throws Exception {
        return new JSONArray().put(segment(songStart, videoStart, 12_000))
                .put(segment(songStart + 12_000, videoStart + 12_000, 12_000));
    }

    private static JSONObject segment(int songStart, int videoStart, int duration) throws Exception {
        return new JSONObject().put("primaryVideoStartTimeMilliseconds", Integer.toString(songStart))
                .put("counterpartVideoStartTimeMilliseconds", Integer.toString(videoStart))
                .put("durationMilliseconds", Integer.toString(duration));
    }

    private static JSONObject pair(String id, String artist, JSONArray segments) throws Exception {
        return new JSONObject().put("counterpartRenderer", object("playlistPanelVideoRenderer", video(id, "OMV", artist)))
                .put("segmentMap", object("segment", segments));
    }

    private static JSONObject video(String id, String type, String artist) throws Exception {
        JSONObject watch = new JSONObject().put("videoId", id).put("watchEndpointMusicSupportedConfigs",
                object("watchEndpointMusicConfig", object("musicVideoType", "MUSIC_VIDEO_TYPE_" + type)));
        JSONObject credit = object("text", "Artist").put("navigationEndpoint", object("browseEndpoint", object("browseId", artist)));
        return new JSONObject().put("videoId", id).put("navigationEndpoint", object("watchEndpoint", watch))
                .put("longBylineText", object("runs", new JSONArray().put(credit)));
    }

    private static JSONObject response(String id, JSONObject... counterparts) throws Exception {
        JSONObject wrapper = object("primaryRenderer", object("playlistPanelVideoRenderer", video(id, "ATV", ARTIST)))
                .put("counterpart", new JSONArray(java.util.Arrays.asList(counterparts)));
        JSONObject queue = object("playlistPanelRenderer", object("contents", new JSONArray()
                .put(object("playlistPanelVideoWrapperRenderer", wrapper))));
        JSONObject tab = object("tabRenderer", object("content", object("musicQueueRenderer", object("content", queue))));
        JSONObject watch = object("tabbedRenderer", object("watchNextTabbedResultsRenderer", object("tabs", new JSONArray().put(tab))));
        return object("contents", object("singleColumnMusicWatchNextResultsRenderer", watch));
    }

    private static JSONObject object(String key, Object value) throws Exception { return new JSONObject().put(key, value); }
}
