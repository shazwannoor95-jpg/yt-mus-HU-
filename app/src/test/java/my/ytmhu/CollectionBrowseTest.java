// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 27)
public final class CollectionBrowseTest {
    @Test public void albumHeaderAndSecondaryTrackShelfMayLiveInDifferentColumns() throws Exception {
        String art = "https://i.ytimg.com/cover.jpg";
        JSONObject header = new JSONObject().put("title", label("An album"))
                .put("straplineTextOne", label("The Artist")).put("subtitle", label("Album · 2025"))
                .put("thumbnail", new JSONObject().put("croppedSquareThumbnailRenderer",
                        new JSONObject().put("thumbnails", new JSONArray().put(new JSONObject().put("url", art)))));
        JSONObject body = new JSONObject().put("contents", new JSONObject().put("twoColumnBrowseResultsRenderer",
                new JSONObject().put("tabs", new JSONArray().put(new JSONObject().put("tabRenderer",
                        new JSONObject().put("content", sections(new JSONObject().put("musicResponsiveHeaderRenderer", header))))))
                        .put("secondaryContents", sections(new JSONObject().put("musicPlaylistShelfRenderer",
                                new JSONObject().put("contents", new JSONArray().put(song("First track", "The Artist"))))))));
        Browse.CollectionHeader parsed = Browse.collectionHeader(body, "MPREb1234");
        assertFalse(parsed.artist);
        assertEquals("An album", parsed.title);
        assertEquals("The Artist · Album · 2025", parsed.subtitle);
        assertEquals(art, parsed.image);
        List<Browse.Shelf> shelves = Browse.collectionShelves(body, false);
        assertEquals(1, shelves.size());
        assertEquals("First track", shelves.get(0).cards.get(0).track.title);
        assertTrue(Browse.collectionShelves(new JSONObject(), false).isEmpty());
    }

    @Test public void artistHeaderUsesProviderProfileAndDoesNotGuessMissingFields() throws Exception {
        JSONObject profile = new JSONObject().put("title", label("NMB48")).put("description", label("Artist bio"));
        JSONObject page = new JSONObject().put("header", new JSONObject().put("musicImmersiveHeaderRenderer", profile));
        Browse.CollectionHeader header = Browse.collectionHeader(page, "UCabcdefghijklmnopqrstuv");
        assertTrue(header.artist);
        assertEquals("NMB48", header.title);
        assertEquals("Artist bio", header.description);
        assertEquals("", header.image);
        assertEquals(MusicApi.MUSIC + "/channel/UCabcdefghijklmnopqrstuv",
                Browse.collectionLink("UCabcdefghijklmnopqrstuv"));
        assertEquals(MusicApi.MUSIC + "/playlist?list=PL_readable", Browse.collectionLink("VLPL_readable"));
        assertEquals("", Browse.collectionLink("VLtest&redirect=1"));
    }

    @Test public void localSortPreservesDuplicateEntriesAndFindsAccentedMetadata() {
        Browse.Card first = card("Zebra", "Björk");
        Browse.Card second = card("Élan", "Other");
        List<Browse.Card> entries = Arrays.asList(first, second, first);
        assertEquals(Arrays.asList(first, first), CollectionTracks.visible(entries, "bjork", CollectionTracks.Order.TITLE));
        assertEquals(Arrays.asList(second, first, first), CollectionTracks.visible(entries, "", CollectionTracks.Order.TITLE));
        assertEquals(Collections.singletonList(second), CollectionTracks.visible(entries, "elan", CollectionTracks.Order.ORIGINAL));
        assertEquals(first, entries.get(0));
    }

    private static JSONObject sections(JSONObject renderer) throws Exception {
        return new JSONObject().put("sectionListRenderer", new JSONObject().put("contents", new JSONArray().put(renderer)));
    }

    private static JSONObject song(String title, String artist) throws Exception {
        return new JSONObject().put("musicResponsiveListItemRenderer", new JSONObject()
                .put("playlistItemData", new JSONObject().put("videoId", "abcdefghijk"))
                .put("flexColumns", new JSONArray().put(column(title)).put(column(artist))));
    }

    private static JSONObject column(String value) throws Exception {
        return new JSONObject().put("musicResponsiveListItemFlexColumnRenderer",
                new JSONObject().put("text", new JSONObject().put("runs", new JSONArray().put(new JSONObject().put("text", value)))));
    }

    private static JSONObject label(String text) throws Exception { return new JSONObject().put("simpleText", text); }

    private static Browse.Card card(String title, String artist) {
        return new Browse.Card(title, artist, "", "", new MusicApi.Track("abcdefghijk", title, artist, "", ""));
    }
}
