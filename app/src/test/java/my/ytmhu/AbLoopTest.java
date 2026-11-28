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
public final class AbLoopTest {
    @Test public void appliesOriginalMinimumLengthAndSpeedAdjustedEndGuard() {
        AbLoop loop = new AbLoop();
        loop.setStart(2000);
        assertTrue(loop.setEnd(7000, 7000, 1.5f));
        assertEquals(6250, loop.end(7000, 1.5f));
        assertEquals(6000, loop.end(7000, 2f));
        loop.setStart(5400);
        assertEquals(-1, loop.end(7000, 1.5f));
        assertFalse(loop.setEnd(7400, 7000, 1.5f));
        assertEquals(5400, loop.start());
        assertTrue(loop.setEnd(2200, 7000, 1.5f));
        assertEquals(0, loop.start());
    }

    @Test public void serviceSeeksBackWithOnePlayerAndClearsWhenQueueStops() throws Exception {
        org.robolectric.android.controller.ServiceController<PlaybackService> controller =
                Robolectric.buildService(PlaybackService.class).create();
        try {
            PlaybackService service = controller.get();
            service.enqueue(new MusicApi.Track("abcdefghijk", "Track", "Artist", "", ""), false);
            field("index").setInt(service, 0);
            String fixture = "loop-audio-fixture.m4a";
            ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(fixture), new ShadowMediaPlayer.MediaInfo(15000, 0));
            MediaPlayer player = new MediaPlayer();
            player.setDataSource(fixture);
            player.prepare();
            player.start();
            ShadowMediaPlayer decoder = shadowOf(player);
            decoder.setSeekDelay(50);
            field("player").set(service, player);
            field("prepared").setBoolean(service, true);
            Method publish = PlaybackService.class.getDeclaredMethod("publish", int.class, String.class);
            publish.setAccessible(true);
            publish.invoke(service, PlaybackState.STATE_PLAYING, "");
            decoder.setCurrentPosition(1000);
            service.setLoopStart();
            decoder.setCurrentPosition(4500);
            assertTrue(service.setLoopEnd());
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(120));
            assertTrue(service.playing());
            assertSame(player, field("player").get(service));
            assertTrue(service.position() >= 1000 && service.position() < 1500);
            service.stopForSignOut();
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6));
            assertEquals(-1, service.loopStart());
            assertEquals(-1, service.loopEnd());
            assertFalse(service.playing());
        } finally {
            controller.destroy();
        }
    }

    private static Field field(String name) throws Exception {
        Field field = PlaybackService.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
