// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.media.MediaPlayer;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Looper;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowMediaPlayer;
import org.robolectric.shadows.util.DataSource;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 27)
public final class QueueRestoreTest {
    @Test public void clearAndUndoKeepTheRecordingClockAndRestoreBothQueueOrders() throws Exception {
        org.robolectric.android.controller.ServiceController<PlaybackService> controller =
                queueWithSelectedCopy();
        try {
            PlaybackService service = controller.get();
            MusicApi.Track current = service.currentTrack();
            List<MusicApi.Track> before = service.queueSnapshot();
            Object original = field("originalQueue").get(service);
            int generation = field("generation").getInt(service);
            assertTrue(service.clearQueue());
            assertEquals(Collections.singletonList(current), service.queueSnapshot());
            assertEquals(0, service.queueIndex());
            assertEquals(2300, service.position());
            assertTrue(service.undoClearQueue());
            assertEquals(before, service.queueSnapshot());
            assertSame(original, field("originalQueue").get(service));
            assertSame(current, service.currentTrack());
            assertEquals(1, service.queueIndex());
            assertEquals(2300, service.position());
            assertEquals(generation, field("generation").getInt(service));
        } finally {
            controller.destroy();
        }
    }

    @Test public void clearingUndoExpiresAndCannotOverwriteAnEditedQueue() throws Exception {
        org.robolectric.android.controller.ServiceController<PlaybackService> controller =
                queueWithSelectedCopy();
        try {
            PlaybackService service = controller.get();
            assertTrue(service.clearQueue());
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(15));
            assertFalse(service.undoClearQueue());
            service.enqueue(track("nextsong001", "New entry", false), false);
            assertTrue(service.clearQueue());
            assertTrue(service.canUndoClearQueue());
            service.enqueue(track("nextsong002", "Different entry", false), false);
            assertFalse(service.undoClearQueue());
            assertEquals("Different entry", service.queueSnapshot().get(1).title);
        } finally {
            controller.destroy();
        }
    }

    private static org.robolectric.android.controller.ServiceController<PlaybackService> queueWithSelectedCopy() {
        MusicApi.Track first = track("abcdefghijk", "First", false);
        MusicApi.Track selected = track("abcdefghijk", "Second copy", false);
        MusicApi.Track last = track("lmnopqrstuv", "Last", false);
        new QueueStore(RuntimeEnvironment.getApplication()).save(Arrays.asList(last, selected, first),
                Arrays.asList(first, selected, last), 1, 2300, 0, true);
        return Robolectric.buildService(PlaybackService.class).create();
    }

    @Test public void restoresDuplicateQueueEntriesAndOriginalOrderWithoutPlaying() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        QueueStore store = new QueueStore(app);
        MusicApi.Track original = track("abcdefghijk", "First", false);
        MusicApi.Track duplicate = track("abcdefghijk", "Second copy", false);
        MusicApi.Track explicit = track("lmnopqrstuv", "Explicit", true);
        store.save(Arrays.asList(explicit, duplicate, original), Arrays.asList(original, duplicate, explicit),
                1, 2300, 2, true);
        QueueStore.Snapshot filtered = store.load(true);
        assertEquals(2, filtered.queue.size());
        assertEquals(0, filtered.index);
        assertEquals(2300, filtered.position);
        assertSame(filtered.queue.get(1), filtered.original.get(0));
        assertFalse(filtered.queue.get(0).artwork.contains("queryMarker"));
        assertEquals(2, filtered.repeat);
        assertTrue(filtered.shuffle);
        org.robolectric.android.controller.ServiceController<PlaybackService> controller =
                Robolectric.buildService(PlaybackService.class).create();
        try {
            PlaybackService service = controller.get();
            assertFalse(service.playing());
            assertNull(field("player").get(service));
            assertEquals("Second copy", service.currentTrack().title);
            assertEquals(2300, service.position());
            assertTrue(service.isRepeatOne());
            service.stopForSignOut();
            assertNull(store.load(false));
        } finally {
            controller.destroy();
        }
    }

    @Test public void retainsSelectedEntryAndBoundsLargeQueue() {
        QueueStore store = new QueueStore(RuntimeEnvironment.getApplication());
        List<MusicApi.Track> large = new ArrayList<>();
        for (int i = 0; i < 360; i++) large.add(track(String.format(java.util.Locale.ROOT, "s%010d", i), "Track " + i, false));
        store.save(large, large, 260, 11600, 0, false);
        QueueStore.Snapshot snapshot = store.load(false);
        assertEquals(QueueStore.LIMIT, snapshot.queue.size());
        assertEquals("Track 260", snapshot.queue.get(snapshot.index).title);
        assertEquals(11600, snapshot.position);
    }

    @Test public void smartShuffleSpreadsKnownArtistsWithoutDroppingEntries() {
        MusicApi.Track a1 = new MusicApi.Track("abcdefghij1", "A1", "Artist A", "", "");
        MusicApi.Track a2 = new MusicApi.Track("abcdefghij2", "A2", "Artist A", "", "");
        MusicApi.Track b1 = new MusicApi.Track("abcdefghij3", "B1", "Artist B", "", "");
        MusicApi.Track b2 = new MusicApi.Track("abcdefghij4", "B2", "Artist B", "", "");
        MusicApi.Track x = new MusicApi.Track("abcdefghij5", "Hidden", "Artist A", "", "", true);
        for (int seed = 0; seed < 12; seed++) {
            List<MusicApi.Track> playable = new ArrayList<>(Arrays.asList(a1, a2, b1, b2, x));
            SmartShuffle.spread(playable, a1, true, new java.util.Random(seed));
            assertEquals(5, playable.size());
            assertTrue(playable.containsAll(Arrays.asList(a1, a2, b1, b2, x)));
            assertEquals("Artist B", playable.get(0).artist);
            assertSame(x, playable.get(4));
            for (int i = 1; i < 4; i++) assertNotEquals(playable.get(i - 1).artist, playable.get(i).artist);
        }
    }

    @Test public void preparedResumeSeeksBeforeStartingAndRepeatOneUsesPlatformLoop() throws Exception {
        QueueStore store = new QueueStore(RuntimeEnvironment.getApplication());
        MusicApi.Track song = track("abcdefghijk", "Song", false);
        List<MusicApi.Track> queue = Collections.singletonList(song);
        store.save(queue, queue, 0, 2200, 0, false);
        org.robolectric.android.controller.ServiceController<PlaybackService> controller =
                Robolectric.buildService(PlaybackService.class).create();
        try {
            PlaybackService service = controller.get();
            // Feed the response that would follow the user's Play action, using a local
            // media fixture so no account or stream provider is contacted.
            field("restoredQueue").setBoolean(service, false);
            field("resumeGeneration").setInt(service, 0);
            field("state").setInt(service, PlaybackState.STATE_BUFFERING);
            String source = "file:///queue-fixture.m4a";
            ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(service, Uri.parse(source)),
                    new ShadowMediaPlayer.MediaInfo(10_000, 30));
            Method prepare = PlaybackService.class.getDeclaredMethod("prepare", List.class, int.class, int.class);
            prepare.setAccessible(true);
            prepare.invoke(service, Collections.singletonList(new MusicApi.AudioSource(source, "", 0, 0)), 0, 0);
            MediaPlayer player = (MediaPlayer) field("player").get(service);
            shadowOf(player).setSeekDelay(100);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
            assertFalse(player.isPlaying());
            assertEquals(2200, service.position());
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150));
            assertTrue(player.isPlaying());
            assertTrue(service.position() >= 2200);
            service.toggleRepeat();
            assertTrue(service.isRepeat());
            assertFalse(player.isLooping());
            service.toggleRepeat();
            assertTrue(service.isRepeatOne());
            assertTrue(player.isLooping());
            assertSame(player, field("player").get(service));
            service.toggleRepeat();
            assertFalse(service.isRepeat());
            assertFalse(player.isLooping());
        } finally {
            controller.destroy();
        }
    }

    private static MusicApi.Track track(String id, String label, boolean explicit) {
        return new MusicApi.Track(id, label, "Artist", "https://i.ytimg.com/cover.jpg?queryMarker=1", "", explicit);
    }

    private static Field field(String name) throws Exception {
        Field field = PlaybackService.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
