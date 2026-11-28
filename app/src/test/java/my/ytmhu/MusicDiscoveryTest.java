// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import static org.junit.Assert.*;

import android.app.Activity;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 27)
public final class MusicDiscoveryTest {
    @Test public void artistCreditsExcludeAlbumAndSurviveQueueInsertion() throws Exception {
        String a = "UCaaaaaaaaaaaaaaaaaaaaaa";
        String b = "UCbbbbbbbbbbbbbbbbbbbbbb";
        JSONArray byline = new JSONArray().put(run("First artist", a, "MUSIC_PAGE_TYPE_ARTIST"))
                .put(new JSONObject().put("text", " & "))
                .put(run("Guest artist", b, "MUSIC_PAGE_TYPE_ARTIST"))
                .put(run("Repeated artist", a, "MUSIC_PAGE_TYPE_ARTIST"))
                .put(run("Album", "MPREb_album", "MUSIC_PAGE_TYPE_ALBUM"))
                .put(run("Unsupported page", "UCcccccccccccccccccccccc", "MUSIC_PAGE_TYPE_ALBUM"));
        JSONObject trackData = new JSONObject().put("playlistItemData", new JSONObject().put("videoId", "abcdefghijk"))
                .put("flexColumns", new JSONArray().put(column(new JSONArray().put(new JSONObject().put("text", "Song"))))
                        .put(column(byline)));
        MusicApi.Track track = MusicApi.track(trackData);
        assertNotNull(track);
        assertEquals("First artist", track.artist);
        assertEquals(2, track.artists.size());
        assertEquals(a, track.artists.get(0).browseId);
        assertEquals(b, track.artists.get(1).browseId);
        assertEquals("Guest artist", track.artists.get(1).name);

        org.robolectric.android.controller.ServiceController<PlaybackService> controller =
                Robolectric.buildService(PlaybackService.class).create();
        try {
            PlaybackService service = controller.get();
            service.enqueue(track, false);
            MusicApi.Track queued = service.queueSnapshot().get(0);
            assertNotSame(track, queued);
            assertEquals(2, queued.artists.size());
            assertEquals(b, queued.artists.get(1).browseId);
        } finally {
            controller.destroy();
        }
    }

    @Test public void recentQueriesPersistDeduplicateAndStayBounded() {
        try (org.robolectric.android.controller.ActivityController<Activity> controller =
                     Robolectric.buildActivity(Activity.class).setup()) {
            AppSettings prefs = new AppSettings(controller.get());
            SearchHistory.record(prefs, "  UVERworld  ");
            SearchHistory.record(prefs, "AAA");
            SearchHistory.record(prefs, "uverworld");
            assertEquals(Arrays.asList("uverworld", "AAA"), SearchHistory.recent(new AppSettings(controller.get())));
            for (int i = 0; i < 20; i++) SearchHistory.record(prefs, "Test " + i);
            assertEquals(8, SearchHistory.recent(prefs).size());
            assertEquals("Test 19", SearchHistory.recent(prefs).get(0));
            SearchHistory.clear(prefs);
            assertTrue(SearchHistory.recent(prefs).isEmpty());
        }
    }

    @Test public void mixedSearchPreservesProviderTopResultAndArtistTarget() throws Exception {
        String artist = "UCaaaaaaaaaaaaaaaaaaaaaa";
        JSONObject top = new JSONObject().put("title", new JSONObject().put("runs",
                new JSONArray().put(run("Top artist", artist, "MUSIC_PAGE_TYPE_ARTIST"))));
        JSONObject page = new JSONObject().put("sectionListRenderer", new JSONObject().put("contents",
                new JSONArray().put(new JSONObject().put("musicCardShelfRenderer", top))));
        java.util.List<Browse.Shelf> shelves = Browse.shelves(page);
        assertEquals(1, shelves.size());
        assertEquals("Top result", shelves.get(0).title);
        assertEquals(artist, shelves.get(0).cards.get(0).browseId);
        assertEquals("", Browse.Search.All.params);
    }

    private static JSONObject run(String name, String id, String type) throws Exception {
        return new JSONObject().put("text", name).put("navigationEndpoint", new JSONObject().put("browseEndpoint",
                new JSONObject().put("browseId", id).put("browseEndpointContextSupportedConfigs",
                        new JSONObject().put("browseEndpointContextMusicConfig", new JSONObject().put("pageType", type)))));
    }

    private static JSONObject column(JSONArray runs) throws Exception {
        return new JSONObject().put("musicResponsiveListItemFlexColumnRenderer",
                new JSONObject().put("text", new JSONObject().put("runs", runs)));
    }
}
