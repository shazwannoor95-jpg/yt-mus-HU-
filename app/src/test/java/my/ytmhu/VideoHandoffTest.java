// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.res.AssetFileDescriptor;
import android.graphics.ImageFormat;
import android.media.ImageReader;
import android.media.MediaPlayer;
import android.media.session.PlaybackState;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowMediaPlayer;
import org.robolectric.shadows.util.DataSource;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 27, shadows = VideoHandoffTest.VideoMediaPlayerShadow.class)
public final class VideoHandoffTest {
    @Implements(MediaPlayer.class)
    public static class VideoMediaPlayerShadow extends ShadowMediaPlayer {
        @Override @Implementation protected int getVideoWidth() { return 320; }
        @Override @Implementation protected int getVideoHeight() { return 180; }
    }

    @Test public void rejectsMismatchedRecordingAndRecoversAtPositionAfterDecoderError() throws Exception {
        org.robolectric.android.controller.ServiceController<PlaybackService> controller =
                Robolectric.buildService(PlaybackService.class).create();
        PlaybackService service = controller.get();
        File clip = File.createTempFile("ytm-video", ".mp4");
        try (ImageReader output = ImageReader.newInstance(320, 180, ImageFormat.PRIVATE, 3);
             AssetFileDescriptor video = new AssetFileDescriptor(
                     ParcelFileDescriptor.open(clip, ParcelFileDescriptor.MODE_READ_ONLY), 0, 100)) {
            MediaPlayer song = installPlayingAudio(service);
            Object session = field("session").get(service);
            DataSource input = DataSource.toDataSource(video.getFileDescriptor(), 0, 100);
            ShadowMediaPlayer.addMediaInfo(input, new ShadowMediaPlayer.MediaInfo(4000, 30));

            assertFalse(service.offerOffscreenVideo("different-id", video, output));
            assertTrue(song.isPlaying());
            assertTrue(service.offerOffscreenVideo("fixture-id", video, output));
            assertTrue(service.videoPreparing());
            assertSame(song, field("player").get(service));
            assertTrue(song.isPlaying());
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
            assertTrue(service.videoActive());
            assertFalse(service.videoPreparing());
            MediaPlayer candidate = (MediaPlayer) field("player").get(service);
            assertTrue(candidate.isPlaying());
            assertFalse(song.isPlaying());

            service.seekTo(2200);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
            assertTrue(service.position() >= 2200);
            shadowOf(candidate).invokeErrorListener(MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
            assertFalse(service.videoActive());
            assertSame(song, field("player").get(service));
            assertSame(session, field("session").get(service));
            assertEquals("fixture-id", service.currentTrack().id);
            assertTrue(song.isPlaying());
            assertTrue(service.position() >= 2200);
            assertEquals(ShadowMediaPlayer.State.END, shadowOf(candidate).getState());
        } finally {
            controller.destroy();
            clip.delete();
        }
    }

    @Test public void mismatchedDurationLeavesOriginalAudioPlaying() throws Exception {
        org.robolectric.android.controller.ServiceController<PlaybackService> controller =
                Robolectric.buildService(PlaybackService.class).create();
        PlaybackService service = controller.get();
        File clip = File.createTempFile("ytm-video", ".mp4");
        try (ImageReader output = ImageReader.newInstance(320, 180, ImageFormat.PRIVATE, 3);
             AssetFileDescriptor video = new AssetFileDescriptor(
                     ParcelFileDescriptor.open(clip, ParcelFileDescriptor.MODE_READ_ONLY), 0, 100)) {
            MediaPlayer song = installPlayingAudio(service);
            ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(video.getFileDescriptor(), 0, 100),
                    new ShadowMediaPlayer.MediaInfo(8000, 30));
            assertTrue(service.offerOffscreenVideo("fixture-id", video, output));
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(130));
            assertFalse(service.videoActive());
            assertNull(field("video").get(service));
            assertSame(song, field("player").get(service));
            assertTrue(song.isPlaying());
        } finally {
            controller.destroy();
            clip.delete();
        }
    }

    @Test public void localRenditionWaitsForIdentityAndReleasesWhenTheSurfaceOwnerEndsIt() throws Exception {
        org.robolectric.android.controller.ServiceController<PlaybackService> controller =
                Robolectric.buildService(PlaybackService.class).create();
        PlaybackService service = controller.get();
        File clip = File.createTempFile("ytm-visible-video", ".mp4");
        try (ImageReader output = ImageReader.newInstance(320, 180, ImageFormat.PRIVATE, 3);
             AssetFileDescriptor video = new AssetFileDescriptor(
                     ParcelFileDescriptor.open(clip, ParcelFileDescriptor.MODE_READ_ONLY), 0, 100)) {
            MediaPlayer song = installPlayingAudio(service);
            ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(video.getFileDescriptor(), 0, 100),
                    new ShadowMediaPlayer.MediaInfo(4000, 30));
            assertFalse(service.canOfferVideo("unrelated"));
            assertFalse(service.offerLocalVideo("fixture-id", video, null));
            assertTrue(service.offerLocalVideo("fixture-id", video, output.getSurface()));
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(120));
            assertTrue(service.videoActive());
            MediaPlayer movie = (MediaPlayer) field("player").get(service);
            service.endVideo();
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80));
            assertFalse(service.videoActive());
            assertSame(song, field("player").get(service));
            assertTrue(song.isPlaying());
            assertEquals(ShadowMediaPlayer.State.END, shadowOf(movie).getState());
        } finally {
            controller.destroy();
            clip.delete();
        }
    }

    private static MediaPlayer installPlayingAudio(PlaybackService service) throws Exception {
        service.enqueue(new MusicApi.Track("fixture-id", "Local Song", "Fixture", "", ""), false);
        field("index").setInt(service, 0);
        MediaPlayer song = new MediaPlayer();
        String fixture = "handoff-fixture.m4a";
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(fixture), new ShadowMediaPlayer.MediaInfo(4000, 0));
        song.setDataSource(fixture);
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
