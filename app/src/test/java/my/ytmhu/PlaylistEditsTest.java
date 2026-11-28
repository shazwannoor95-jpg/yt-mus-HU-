// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 27, qualifiers = "w1024dp-h600dp-land-mdpi")
public final class PlaylistEditsTest {
    @Test public void creationIsPrivateAndEditsIncludeOnlyTouchedFields() throws Exception {
        JSONObject create = PlaylistEdits.create("  Road trip  ", "Notes");
        assertEquals("PRIVATE", create.getString("privacyStatus"));
        assertEquals("Road trip", create.getString("title"));
        assertEquals("Notes", create.getString("description"));
        JSONArray rename = PlaylistEdits.update("PLowned", "Road trip 2", null).getJSONArray("actions");
        assertEquals(1, rename.length());
        assertEquals("ACTION_SET_PLAYLIST_NAME", rename.getJSONObject(0).getString("action"));
        JSONArray clearDescription = PlaylistEdits.update("PLowned", null, "").getJSONArray("actions");
        assertEquals(1, clearDescription.length());
        assertEquals("", clearDescription.getJSONObject(0).getString("playlistDescription"));
        assertThrows(IllegalArgumentException.class, () -> PlaylistEdits.create("<invalid>", ""));
        assertThrows(IllegalArgumentException.class, () -> PlaylistEdits.update("LM", "No", ""));
    }

    @Test public void editableRendererMustBeInTheMatchingPlaylistHeader() throws Exception {
        JSONObject owned = page(true, "PLowned");
        Browse.CollectionHeader parsed = Browse.collectionHeader(owned, "VLPLowned");
        assertEquals("PLowned", parsed.editablePlaylistId);
        assertEquals("Playlist name", parsed.title);
        assertEquals("", Browse.collectionHeader(owned, "VLPLother").editablePlaylistId);
        assertEquals("", Browse.collectionHeader(owned, "VLLM").editablePlaylistId);
        assertEquals("", Browse.collectionHeader(page(false, "PLowned"), "VLPLowned").editablePlaylistId);
    }

    @Test public void duplicateSongsMoveAndRemoveByDistinctProviderEntryIds() throws Exception {
        JSONObject sortOption = new JSONObject().put("selected", true).put("serviceEndpoint", new JSONObject()
                .put("playlistEditEndpoint", new JSONObject().put("actions", new JSONArray()
                        .put(new JSONObject().put("playlistVideoOrder", 0)))));
        JSONObject sort = new JSONObject().put("musicSideAlignedItemRenderer", new JSONObject().put("startItems", new JSONArray()
                .put(new JSONObject().put("sortFilterSubMenuRenderer", new JSONObject().put("subMenuItems", new JSONArray().put(sortOption))))));
        JSONObject shelf = new JSONObject().put("header", sort).put("contents", new JSONArray()
                .put(entry("abcdefghijk", "set-first")).put(entry("abcdefghijk", "set-second"))
                .put(entry("12345678901", "set-third")));
        JSONObject response = new JSONObject().put("sectionListRenderer", new JSONObject().put("contents",
                new JSONArray().put(new JSONObject().put("musicPlaylistShelfRenderer", shelf))));
        Browse.Shelf parsed = Browse.collectionShelves(response, false).get(0);
        assertTrue(parsed.manualOrder);
        assertEquals(parsed.cards.get(0).track.id, parsed.cards.get(1).track.id);
        assertEquals("set-third", Browse.moveSuccessor(parsed, parsed.cards.get(0), 1));
        assertEquals("set-first", Browse.moveSuccessor(parsed, parsed.cards.get(1), -1));
        assertNull(Browse.moveSuccessor(parsed, parsed.cards.get(1), 1));
        sortOption.put("selected", false);
        Browse.Shelf sorted = Browse.collectionShelves(response, false).get(0);
        assertNull(Browse.moveSuccessor(sorted, sorted.cards.get(0), 1));

        JSONObject removed = PlaylistEdits.remove("PLowned", "abcdefghijk", "set-second").getJSONArray("actions").getJSONObject(0);
        assertEquals("set-second", removed.getString("setVideoId"));
        JSONObject moved = PlaylistEdits.move("PLowned", "set-first", "set-third").getJSONArray("actions").getJSONObject(0);
        assertEquals("set-third", moved.getString("movedSetVideoIdSuccessor"));
        assertThrows(IllegalArgumentException.class, () -> PlaylistEdits.remove("PLowned", "abcdefghijk", ""));
    }

    @Test public void deletionMustBeAcknowledgedForTheSamePlaylist() throws Exception {
        JSONObject response = new JSONObject().put("command", new JSONObject().put("commandExecutorCommand",
                new JSONObject().put("commands", new JSONArray().put(new JSONObject().put("handlePlaylistDeletionCommand",
                        new JSONObject().put("playlistId", "PLowned"))))));
        assertTrue(PlaylistEdits.confirmsDeletion("PLowned", response));
        assertFalse(PlaylistEdits.confirmsDeletion("PLother", response));
        assertFalse(PlaylistEdits.confirmsDeletion("PLowned", new JSONObject().put("status", "STATUS_SUCCEEDED")));
    }

    @Test public void visibleEditControlRequiresAnOwnedHeaderAndDoesNotSubmitInvalidText() throws Exception {
        Bundle state = new Bundle();
        state.putString("page", "Account");
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).create(state)) {
            MainActivity screen = controller.get();
            ReflectionHelpers.setField(screen, "collectionId", "VLPLowned");
            ReflectionHelpers.setField(screen, "account", "Account fixture");
            assertNull(AdaptiveUiTest.text(header(screen, Browse.collectionHeader(page(false, "PLowned"), "VLPLowned")), "Edit playlist"));
            View edit = AdaptiveUiTest.text(header(screen, Browse.collectionHeader(page(true, "PLowned"), "VLPLowned")), "Edit playlist");
            assertNotNull(edit);
            assertNotNull(AdaptiveUiTest.text(header(screen, Browse.collectionHeader(page(true, "PLowned"), "VLPLowned")), "Delete playlist"));
            assertTrue(edit.performClick());
            shadowOf(Looper.getMainLooper()).idle();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals("Edit playlist", shadowOf(dialog).getTitle());
            ViewGroup form = (ViewGroup) shadowOf(dialog).getView();
            EditText title = (EditText) form.getChildAt(0);
            assertEquals("Playlist name", title.getText().toString());
            title.setText(" ");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertNotNull(title.getError());
            assertTrue(dialog.isShowing());
        }
    }

    private static View header(MainActivity screen, Browse.CollectionHeader header) throws Exception {
        Class<?> type = Class.forName("my.ytmhu.MainActivity$BrowsePage");
        Constructor<?> ctor = type.getDeclaredConstructor(List.class, List.class, List.class, Browse.CollectionHeader.class);
        ctor.setAccessible(true);
        Method render = MainActivity.class.getDeclaredMethod("collectionHeader", type);
        render.setAccessible(true);
        return (View) render.invoke(screen, ctor.newInstance(Collections.emptyList(), Collections.emptyList(), null, header));
    }

    private static JSONObject page(boolean editable, String id) throws Exception {
        JSONObject title = new JSONObject().put("runs", new JSONArray().put(new JSONObject().put("text", "Playlist name")));
        JSONObject header = new JSONObject().put("musicResponsiveHeaderRenderer", new JSONObject().put("title", title));
        JSONObject row = editable ? new JSONObject().put("musicEditablePlaylistDetailHeaderRenderer",
                new JSONObject().put("playlistId", id).put("header", header)) : header;
        JSONObject sections = new JSONObject().put("sectionListRenderer", new JSONObject().put("contents", new JSONArray().put(row)));
        JSONObject tab = new JSONObject().put("tabRenderer", new JSONObject().put("content", sections));
        return new JSONObject().put("contents", new JSONObject().put("twoColumnBrowseResultsRenderer",
                new JSONObject().put("tabs", new JSONArray().put(tab))));
    }

    private static JSONObject entry(String videoId, String setId) throws Exception {
        JSONObject title = new JSONObject().put("runs", new JSONArray().put(new JSONObject().put("text", "Song")));
        JSONObject column = new JSONObject().put("musicResponsiveListItemFlexColumnRenderer", new JSONObject().put("text", title));
        return new JSONObject().put("musicResponsiveListItemRenderer", new JSONObject()
                .put("playlistItemData", new JSONObject().put("videoId", videoId).put("playlistSetVideoId", setId))
                .put("flexColumns", new JSONArray().put(column)));
    }
}
