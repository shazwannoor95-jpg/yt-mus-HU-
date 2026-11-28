// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import static org.robolectric.util.ReflectionHelpers.ClassParameter.from;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowPopupMenu;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 27, qualifiers = "w1024dp-h600dp-land-mdpi")
public final class TrackActionsTest {
    private static final String VIDEO = "abcdefghijk";
    private static final String FIRST = "UCabcdefghijklmnopqrstuv";
    private static final String SECOND = "UC1234567890123456789012";
    private static final String ALBUM = "MPREb1234";

    @Test public void exactRecordingMetadataExcludesRecommendationsAndSurvivesQueueRestore() throws Exception {
        MusicApi.Track seed = track(VIDEO, Collections.emptyList(), null);
        JSONObject unrelated = panelSong("12345678901", new JSONArray().put(credit("Other", "UC1111111111111111111111", "ARTIST")));
        JSONObject exact = panelSong(VIDEO, new JSONArray().put(credit("First", FIRST, "ARTIST"))
                .put(credit("Second", SECOND, "USER_CHANNEL")).put(credit("Album", ALBUM, "ALBUM")));
        JSONArray entries = new JSONArray().put(unrelated).put(exact);
        MusicApi.Track enriched = MusicApi.detailsForRecording(seed,
                new JSONObject().put("playlistPanelRenderer", new JSONObject().put("contents", entries)));
        assertEquals(Arrays.asList(FIRST, SECOND), Arrays.asList(enriched.artists.get(0).browseId, enriched.artists.get(1).browseId));
        assertEquals(ALBUM, enriched.album.browseId);
        assertSame(seed, MusicApi.detailsForRecording(seed, new JSONObject().put("playlistPanelRenderer",
                new JSONObject().put("contents", new JSONArray().put(unrelated)))));
        try (ActivityController<MainActivity> controller = activity()) {
            List<MusicApi.Track> entriesToSave = Arrays.asList(seed, enriched);
            new QueueStore(controller.get()).save(entriesToSave, entriesToSave, 1, 2000, 0, false);
            List<MusicApi.Track> restored = new QueueStore(controller.get()).load(false).queue;
            assertTrue(restored.get(0).artists.isEmpty());
            assertEquals(SECOND, restored.get(1).artists.get(1).browseId);
            assertEquals(ALBUM, restored.get(1).album.browseId);
            assertTrue(overflow(controller.get(), restored.get(1)).performClick());
            openMenuItem("Go to album");
            assertEquals(ALBUM, ReflectionHelpers.getField(controller.get(), "collectionId"));
        }
    }

    @Test public void visibleOverflowOpensSingleArtistAndAllowsChoosingMultipleArtists() {
        MusicApi.ArtistCredit first = new MusicApi.ArtistCredit("First", FIRST);
        MusicApi.ArtistCredit second = new MusicApi.ArtistCredit("Second", SECOND);
        try (ActivityController<MainActivity> controller = activity()) {
            MainActivity screen = controller.get();
            View single = overflow(screen, track(VIDEO, Collections.singletonList(first), null));
            assertTrue(single.isClickable());
            assertTrue(single.performClick());
            openMenuItem("Go to artist");
            assertEquals("Collection", ReflectionHelpers.getField(screen, "page"));
            assertEquals(FIRST, ReflectionHelpers.getField(screen, "collectionId"));
            View multiple = overflow(screen, track(VIDEO, Arrays.asList(first, second), null));
            assertTrue(multiple.performClick());
            openMenuItem("Go to artist");
            AlertDialog picker = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals("Go to artist", shadowOf(picker).getTitle());
            shadowOf(picker).clickOnItem(1);
            assertEquals(SECOND, ReflectionHelpers.getField(screen, "collectionId"));
        }
    }

    @Test public void missingArtistIdOffersExplicitSearchAndOpensOnlyArtistResults() throws Exception {
        try (ActivityController<MainActivity> controller = activity()) {
            MainActivity screen = controller.get();
            assertTrue(overflow(screen, track("fixture", Collections.emptyList(), null)).performClick());
            openMenuItem("Go to artist");
            Future<?> task = ReflectionHelpers.getField(screen, "trackDetailsTask");
            task.get(2, TimeUnit.SECONDS);
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals("Account", ReflectionHelpers.getField(screen, "page"));
            AlertDialog search = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals("Artist page unavailable", shadowOf(search).getTitle());
            assertEquals("Search for artist", search.getButton(AlertDialog.BUTTON_POSITIVE).getText());
            search.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals("Search", ReflectionHelpers.getField(screen, "page"));
            assertEquals(Browse.Search.Artists, ReflectionHelpers.getField(screen, "searchType"));
        }
    }

    private static ActivityController<MainActivity> activity() {
        Bundle state = new Bundle();
        state.putString("page", "Account");
        return Robolectric.buildActivity(MainActivity.class).create(state);
    }

    private static View overflow(MainActivity screen, MusicApi.Track track) {
        Browse.Card card = new Browse.Card(track.title, track.artist, "", "", track);
        ViewGroup row = ReflectionHelpers.callInstanceMethod(screen, "songRow", from(Browse.Card.class, card),
                from(List.class, Collections.singletonList(track)), from(int.class, 0));
        View overflow = row.getChildAt(row.getChildCount() - 1);
        assertEquals("Actions for Song", overflow.getContentDescription());
        return overflow;
    }

    private static void openMenuItem(String title) {
        PopupMenu popup = ShadowPopupMenu.getLatestPopupMenu();
        assertNotNull(popup);
        for (int i = 0; i < popup.getMenu().size(); i++) {
            MenuItem item = popup.getMenu().getItem(i);
            if (title.contentEquals(item.getTitle())) {
                assertTrue((Boolean) ReflectionHelpers.callInstanceMethod(item, "invoke"));
                return;
            }
        }
        fail("Missing menu item: " + title);
    }

    private static MusicApi.Track track(String id, List<MusicApi.ArtistCredit> artists, MusicApi.AlbumCredit album) {
        return new MusicApi.Track(id, "Song", "First", "", false, artists, album);
    }

    private static JSONObject credit(String title, String id, String type) throws Exception {
        return new JSONObject().put("text", title).put("navigationEndpoint", new JSONObject().put("browseEndpoint",
                new JSONObject().put("browseId", id).put("browseEndpointContextSupportedConfigs",
                        new JSONObject().put("browseEndpointContextMusicConfig",
                                new JSONObject().put("pageType", "MUSIC_PAGE_TYPE_" + type)))));
    }

    private static JSONObject panelSong(String id, JSONArray lines) throws Exception {
        return new JSONObject().put("playlistPanelVideoRenderer", new JSONObject().put("videoId", id)
                .put("longBylineText", new JSONObject().put("runs", lines)));
    }
}
