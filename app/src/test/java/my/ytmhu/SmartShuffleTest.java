// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.Test;

public class SmartShuffleTest {
    @Test public void alternatesAlbumsByBrowseIdAndAvoidsThePreviousAlbum() {
        MusicApi.Track a1 = track("A1", "First", "MPREbfirst");
        MusicApi.Track a2 = track("A2", "First (display alias)", "MPREbfirst");
        MusicApi.Track b1 = track("B1", "Second", "MPREbsecond");
        MusicApi.Track b2 = track("B2", "Second", "MPREbsecond");
        MusicApi.Track previous = track("A0", "First", "MPREbfirst");
        for (int seed = 0; seed < 24; seed++) {
            List<MusicApi.Track> queue = new ArrayList<>(Arrays.asList(a1, a2, b1, b2));
            SmartShuffle.spread(queue, previous, false, new Random(seed));
            assertEquals("MPREbsecond", queue.get(0).album.browseId);
            for (int i = 1; i < queue.size(); i++) {
                assertNotEquals(queue.get(i - 1).album.browseId, queue.get(i).album.browseId);
            }
        }
    }

    @Test public void usesNormalizedNamesForLegacyCreditsAndKeepsDuplicateEntries() {
        MusicApi.Track a1 = track("A1", "First", "");
        MusicApi.Track a2 = track("A2", " FIRST ", "");
        MusicApi.Track b1 = track("B1", "Second", "");
        MusicApi.Track previous = track("A0", "First", "");
        for (int seed = 0; seed < 24; seed++) {
            List<MusicApi.Track> queue = new ArrayList<>(Arrays.asList(a1, a2, b1, b1));
            SmartShuffle.spread(queue, previous, false, new Random(seed));
            assertEquals(4, queue.size());
            assertEquals(1, Collections.frequency(queue, a1));
            assertEquals(1, Collections.frequency(queue, a2));
            assertEquals(2, Collections.frequency(queue, b1));
            assertEquals(b1, queue.get(0));
            assertEquals(b1, queue.get(2));
        }
    }

    private static MusicApi.Track track(String title, String album, String browseId) {
        return new MusicApi.Track(title, title, "Artist", "", false, Collections.emptyList(),
                new MusicApi.AlbumCredit(album, browseId));
    }
}
