// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 27)
public class PlaybackBookmarksTest {
    private static final String FIRST = "abcdefghijk";
    private static final String SECOND = "1234567890_";

    @Test public void restoresSortedRecordingPositionsAndAllowsEditsAndDeletion() {
        Context context = RuntimeEnvironment.getApplication();
        PlaybackBookmarks store = new PlaybackBookmarks(context);
        assertTrue(store.save(FIRST, 14_328, "Verse"));
        assertTrue(store.save(SECOND, 500, "Other recording"));
        assertTrue(store.save(FIRST, 500, "  Intro "));
        assertTrue(store.save(FIRST, 14_328, "Verse two"));

        PlaybackBookmarks restored = new PlaybackBookmarks(context);
        List<PlaybackBookmarks.Bookmark> first = restored.forRecording(FIRST);
        assertEquals(2, first.size());
        assertEquals("Intro", first.get(0).note);
        assertEquals(14_328, first.get(1).position);
        assertEquals("Verse two", first.get(1).note);
        restored.delete(first.get(0));
        assertEquals(1, restored.forRecording(FIRST).size());
        assertEquals("Other recording", restored.forRecording(SECOND).get(0).note);
    }

    @Test public void rejectsInvalidEntriesAndAvoidsEvictingExistingNotes() {
        Context context = RuntimeEnvironment.getApplication();
        PlaybackBookmarks store = new PlaybackBookmarks(context);
        assertFalse(store.save("invalid", 50, ""));
        assertFalse(store.save(FIRST, -1, ""));
        for (int i = 0; i < PlaybackBookmarks.LIMIT; i++) assertTrue(store.save(FIRST, i, ""));
        assertFalse(store.save(SECOND, 20, ""));
        assertTrue(store.save(FIRST, 0, "Edited at capacity"));
        assertEquals("Edited at capacity", store.forRecording(FIRST).get(0).note);
        assertEquals(PlaybackBookmarks.LIMIT, store.forRecording(FIRST).size());
    }
}
