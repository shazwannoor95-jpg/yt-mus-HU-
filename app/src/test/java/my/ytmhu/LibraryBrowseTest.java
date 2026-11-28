// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import static org.junit.Assert.*;
import static org.robolectric.util.ReflectionHelpers.ClassParameter.from;

import android.os.Bundle;
import android.view.View;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 27, qualifiers = "w1024dp-h600dp-land-mdpi")
public final class LibraryBrowseTest {
    private static final String ARTIST = "UCabcdefghij123456789012";
    private static final String OTHER = "UCabcdefghijklmnopqrstuv";

    @Test public void gridContinuationRetainsOrderAndRejectsOtherCardTypes() throws Exception {
        JSONObject firstGrid = new JSONObject().put("gridRenderer", new JSONObject().put("items", new JSONArray()
                .put(card("One", "MPREb1234")).put(card("Unrelated", ARTIST)).put(cursor("page2"))));
        JSONObject firstPage = new JSONObject().put("continuationContents", new JSONObject().put("sectionListContinuation",
                new JSONObject().put("contents", new JSONArray().put(firstGrid))));
        Browse.Shelf first = Browse.libraryShelf(firstPage, Browse.Library.Albums, false);
        assertEquals(1, first.cards.size());
        assertEquals("page2", first.cursor);
        JSONObject appended = new JSONObject().put("appendContinuationItemsAction", new JSONObject().put("continuationItems",
                new JSONArray().put(card("One", "MPREb1234")).put(card("Two", "MPREb5678")).put(cursor("page2"))));
        Browse.Shelf next = Browse.libraryPage(appended, Browse.Library.Albums, false);
        Browse.Shelf merged = Browse.appendLibraryPage(Browse.Library.Albums, first, next);
        assertEquals(2, merged.cards.size());
        assertEquals("Two", merged.cards.get(1).title);
        assertEquals("", merged.cursor);
        assertNull(Browse.libraryPage(new JSONObject(), Browse.Library.Albums, false));
    }

    @Test public void historyRetainsOnlyUniqueTracksInServiceOrderAndUsesItsOwnEndpoint() throws Exception {
        JSONObject day1 = new JSONObject().put("musicShelfRenderer", new JSONObject().put("contents",
                new JSONArray().put(song("abcdefghijk", "First")).put(song("12345678901", "Second"))));
        JSONObject day2 = new JSONObject().put("musicShelfRenderer", new JSONObject().put("contents",
                new JSONArray().put(song("abcdefghijk", "Repeat")).put(cursor("unused"))));
        JSONObject body = new JSONObject().put("sectionListRenderer", new JSONObject().put("contents", new JSONArray().put(day1).put(day2)));
        Browse.Shelf result = Browse.libraryShelf(body, Browse.Library.History, false);
        assertEquals("FEmusic_history", Browse.Library.History.request().getString("browseId"));
        assertEquals("FEmusic_liked_videos", Browse.Library.Songs.request().getString("browseId"));
        assertEquals("FEmusic_library_corpus_artists", Browse.Library.Artists.request().getString("browseId"));
        assertEquals(2, result.cards.size());
        assertEquals("First", result.cards.get(0).track.title);
        assertEquals("Second", result.cards.get(1).track.title);
        assertEquals("", result.cursor);
    }

    @Test public void renderedArtistTabsUseOneCachedArtistAndBackRestoresOverview() throws Exception {
        Bundle state = new Bundle();
        state.putString("page", "Account");
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).create(state)) {
            MainActivity screen = controller.get();
            List<Browse.Shelf> shelves = Arrays.asList(
                    new Browse.Shelf("Albums", Collections.singletonList(new Browse.Card("Album", "", "", "MPREb1234", null)), true),
                    new Browse.Shelf("Singles", Collections.singletonList(new Browse.Card("Single", "", "", "MPREb5678", null)), true),
                    new Browse.Shelf("Fans might like", Arrays.asList(new Browse.Card("Artist", "", "", ARTIST, null),
                            new Browse.Card("Other", "", "", OTHER, null)), true));
            assertEquals(2, Browse.artistShelves(shelves, ARTIST, true).size());
            assertEquals(OTHER, Browse.artistShelves(shelves, ARTIST, false).get(0).cards.get(0).browseId);
            putPage(screen, "Collection\nfalse\nfalse\n" + ARTIST, shelves,
                    new Browse.CollectionHeader("Artist", "", "", "", true));
            ReflectionHelpers.setField(screen, "collectionId", ARTIST);
            ReflectionHelpers.callInstanceMethod(screen, "navigate", from(String.class, "Collection"));
            View releases = rendered(screen, "Discography");
            assertNotNull(releases);
            releases.performClick();
            assertEquals("Discography", ReflectionHelpers.getField(screen, "page"));
            assertNotNull(rendered(screen, "Albums"));
            assertNotNull(rendered(screen, "Singles"));
            assertNull(rendered(screen, "Fans might like"));
            screen.onBackPressed();
            assertEquals("Collection", ReflectionHelpers.getField(screen, "page"));
        }
    }

    @Test public void signedOutLibraryRemainsGatedAndCachedTabsSwitchContent() throws Exception {
        Bundle state = new Bundle();
        state.putString("page", "Account");
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).create(state)) {
            MainActivity screen = controller.get();
            ReflectionHelpers.callInstanceMethod(screen, "navigate", from(String.class, "Library"));
            assertNull(rendered(screen, "History"));
            ReflectionHelpers.setField(screen, "account", "Fixture account");
            putPage(screen, "Library\nfalse\nfalse\nPlaylists", Collections.singletonList(
                    new Browse.Shelf("Playlists", new ArrayList<>(), true)), null);
            MusicApi.Track track = new MusicApi.Track("abcdefghijk", "Played song", "Fixture artist", "", "");
            putPage(screen, "Library\nfalse\nfalse\nHistory", Collections.singletonList(
                    new Browse.Shelf("History", Collections.singletonList(new Browse.Card(track.title, track.artist, "", "", track)), false)), null);
            ReflectionHelpers.callInstanceMethod(screen, "navigate", from(String.class, "Library"));
            View history = rendered(screen, "History");
            assertNotNull(history);
            history.performClick();
            assertEquals(Browse.Library.History, ReflectionHelpers.getField(screen, "libraryTab"));
            assertNotNull(rendered(screen, "Played song"));
        }
    }

    private static JSONObject card(String title, String id) throws Exception {
        return new JSONObject().put("musicTwoRowItemRenderer", new JSONObject().put("title", label(title))
                .put("navigationEndpoint", new JSONObject().put("browseEndpoint", new JSONObject().put("browseId", id))));
    }

    private static JSONObject song(String id, String title) throws Exception {
        return new JSONObject().put("musicResponsiveListItemRenderer", new JSONObject()
                .put("playlistItemData", new JSONObject().put("videoId", id))
                .put("flexColumns", new JSONArray().put(new JSONObject().put("musicResponsiveListItemFlexColumnRenderer",
                        new JSONObject().put("text", label(title))))));
    }

    private static JSONObject label(String value) throws Exception {
        return new JSONObject().put("runs", new JSONArray().put(new JSONObject().put("text", value)));
    }

    private static JSONObject cursor(String token) throws Exception {
        return new JSONObject().put("continuationItemRenderer", new JSONObject().put("continuationEndpoint",
                new JSONObject().put("continuationCommand", new JSONObject().put("token", token))));
    }

    private static void putPage(MainActivity screen, String key, List<Browse.Shelf> shelves, Browse.CollectionHeader header)
            throws Exception {
        Class<?> type = Class.forName("my.ytmhu.MainActivity$BrowsePage");
        java.lang.reflect.Constructor<?> ctor = type.getDeclaredConstructor(List.class, List.class, List.class, Browse.CollectionHeader.class);
        ctor.setAccessible(true);
        Map<String, Object> pages = ReflectionHelpers.getField(screen, "pages");
        pages.put(key, ctor.newInstance(shelves, Collections.emptyList(), null, header));
    }

    private static View rendered(MainActivity screen, String text) {
        List<Supplier<View>> rows = ReflectionHelpers.getField(screen, "results");
        for (Supplier<View> row : rows) {
            View found = AdaptiveUiTest.text(row.get(), text);
            if (found != null) return found;
        }
        return null;
    }
}
