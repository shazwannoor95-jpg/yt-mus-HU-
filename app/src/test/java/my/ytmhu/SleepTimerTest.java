// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.media.MediaPlayer;
import android.media.session.PlaybackState;
import android.os.Looper;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowMediaPlayer;
import org.robolectric.shadows.util.DataSource;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 27)
public final class SleepTimerTest {
    @Test public void cubicFadeOnlySchedulesNearDeadline() {
        SleepTimer timer = new SleepTimer();
        timer.set(15, 100L);
        assertEquals(840_000L, timer.delayToNextStep(100L));
        assertEquals(1f, timer.gain(840_100L), 0.001f);
        assertEquals(0.125f, timer.gain(870_100L), 0.001f);
        assertEquals(0f, timer.gain(900_100L), 0.001f);
        assertTrue(timer.due(900_100L));
        timer.set(SleepTimer.OFF, 901_100L);
        assertEquals(1f, timer.gain(901_100L), 0.001f);
        assertFalse(timer.due(910_100L));
    }

    @Test public void deadlinePausesCurrentPlayerAndCancellationRestoresGain() throws Exception {
        org.robolectric.android.controller.ServiceController<PlaybackService> controller =
                Robolectric.buildService(PlaybackService.class).create();
        try {
            PlaybackService service = controller.get();
            MediaPlayer song = playingFixture(service, "timer");
            service.setSleepTimer(1);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30));
            assertTrue(song.isPlaying());
            assertEquals(0.125f, shadowOf(song).getLeftVolume(), 0.02f);
            service.setSleepTimer(SleepTimer.OFF);
            assertEquals(1f, shadowOf(song).getLeftVolume(), 0.01f);
            service.setSleepTimer(1);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61));
            assertFalse(service.playing());
            assertFalse(song.isPlaying());
            assertEquals(SleepTimer.OFF, service.sleepSelection());
        } finally {
            controller.destroy();
        }
    }

    @Test public void trackEndHoldsNextEntryPausedAcrossRepeatAll() throws Exception {
        org.robolectric.android.controller.ServiceController<PlaybackService> controller =
                Robolectric.buildService(PlaybackService.class).create();
        try {
            PlaybackService service = controller.get();
            MediaPlayer song = playingFixture(service, "end");
            service.toggleRepeat();
            service.setSleepTimer(SleepTimer.SONG_END);
            Method ended = PlaybackService.class.getDeclaredMethod("trackCompleted", MediaPlayer.class, int.class);
            ended.setAccessible(true);
            ended.invoke(service, song, 0);
            assertEquals(1, service.queueIndex());
            assertEquals("Queue next", service.currentTrack().title);
            assertFalse(service.playing());
            assertNull(field("player").get(service));
            assertEquals(SleepTimer.OFF, service.sleepSelection());
        } finally {
            controller.destroy();
        }
    }

    private static MediaPlayer playingFixture(PlaybackService service, String name) throws Exception {
        service.enqueue(new MusicApi.Track("abcdefghijk", "Song", "Artist", "", ""), false);
        service.enqueue(new MusicApi.Track("lmnopqrstuv", "Queue next", "Artist", "", ""), false);
        field("index").setInt(service, 0);
        MediaPlayer song = new MediaPlayer();
        String input = name + ".m4a";
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(input), new ShadowMediaPlayer.MediaInfo(360_000, 0));
        song.setDataSource(input);
        song.prepare();
        song.start();
        field("player").set(service, song);
        field("prepared").setBoolean(service, true);
        Method publish = PlaybackService.class.getDeclaredMethod("publish", int.class, String.class);
        publish.setAccessible(true);
        publish.invoke(service, PlaybackState.STATE_PLAYING, "");
        return song;
    }

    private static Field field(String name) throws Exception {
        Field field = PlaybackService.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
