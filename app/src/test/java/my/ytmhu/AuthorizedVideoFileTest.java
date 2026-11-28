// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.graphics.ImageFormat;
import android.media.ImageReader;
import android.media.MediaFormat;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Looper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowMediaPlayer;
import org.robolectric.shadows.util.DataSource;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 27)
public final class AuthorizedVideoFileTest {
    @Test public void regularLocalVideoRequiresANonAutomotiveAndroidSdk() {
        assertTrue(DemoPlaybackPolicy.localPlaybackPermitted("my.ytmhu", "ranchu", "sdk_gphone_x86", false));
        assertFalse(DemoPlaybackPolicy.localPlaybackPermitted("my.ytmhu", "ranchu", "sdk_car_x86", true));
        assertFalse(DemoPlaybackPolicy.localPlaybackPermitted("my.ytmhu", "mtk", "sdk_headunit", false));
        assertFalse(DemoPlaybackPolicy.localPlaybackPermitted("my.ytmhu", "ranchu", "headunit", false));
        assertFalse(DemoPlaybackPolicy.localPlaybackPermitted(DemoPlaybackPolicy.PACKAGE, "ranchu", "sdk", false));
    }

    @Test public void stagingRejectsNonMp4AndCopiesWithinTheByteBudget() throws Exception {
        byte[] file = {0, 0, 0, 9, 'f', 't', 'y', 'p', 'a'};
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AuthorizedVideoFile.copyMp4(new ByteArrayInputStream(file), output, 9);
        assertArrayEquals(file, output.toByteArray());
        assertThrows(IOException.class, () -> AuthorizedVideoFile.copyMp4(new ByteArrayInputStream(file), output, 8));
        file[4] = 's';
        assertThrows(IOException.class, () -> AuthorizedVideoFile.copyMp4(new ByteArrayInputStream(file), output, 32));
        assertEquals("Safe.mp4", AuthorizedVideoFile.safeLabel(" Safe\n\u202e.mp4 "));
    }

    @Test public void surfaceSwitchKeepsThePlayerAndClosingRestoresTheSongQueue() throws Exception {
        ReflectionHelpers.setStaticField(Build.class, "HARDWARE", "ranchu");
        ReflectionHelpers.setStaticField(Build.class, "PRODUCT", "sdk_gphone_x86");
        MusicApi.Track old = new MusicApi.Track("abcdefghijk", "Existing song", "Artist", "", "");
        new QueueStore(RuntimeEnvironment.getApplication()).save(Collections.singletonList(old),
                Collections.singletonList(old), 0, 3200, 0, false);
        org.robolectric.android.controller.ServiceController<PlaybackService> controller =
                Robolectric.buildService(PlaybackService.class).create();
        try (ImageReader output = ImageReader.newInstance(320, 240, ImageFormat.PRIVATE, 3)) {
            PlaybackService service = controller.get();
            File source = File.createTempFile("authorized-video-", ".mp4", service.getCacheDir());
            Constructor<AuthorizedVideoFile> constructor = AuthorizedVideoFile.class.getDeclaredConstructor(
                    File.class, String.class, MediaFormat.class);
            constructor.setAccessible(true);
            try (AuthorizedVideoFile file = constructor.newInstance(source, "Local clip", MediaFormat.createVideoFormat("video/avc", 320, 240))) {
                ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(service, Uri.fromFile(source)),
                        new ShadowMediaPlayer.MediaInfo(8000, 25));
                assertTrue(service.beginLocalVideo(file));
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
                MediaPlayer player = ReflectionHelpers.getField(service, "player");
                assertSame(file.track, service.currentTrack());
                assertTrue(service.playing());
                assertTrue(service.setLocalVideoSurface(file, output.getSurface()));
                assertTrue(service.setLocalVideoSurface(file, null));
                assertSame(player, ReflectionHelpers.getField(service, "player"));
                service.finishLocalVideo(file);
                assertFalse(source.exists());
                assertEquals(old.id, service.currentTrack().id);
                assertEquals(3200, service.position());
                assertFalse(service.playing());
            }
        } finally {
            controller.destroy();
        }
    }
}
