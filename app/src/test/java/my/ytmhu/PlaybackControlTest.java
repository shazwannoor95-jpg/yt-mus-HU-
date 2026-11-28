// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import static org.junit.Assert.*;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.view.View;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 27, qualifiers = "w1024dp-h600dp-land-mdpi")
public final class PlaybackControlTest {
    @Test public void mainAndMiniFollowTheServiceState() throws Exception {
        Bundle state = new Bundle();
        state.putString("page", "Account");
        try (org.robolectric.android.controller.ActivityController<MainActivity> activity =
                     Robolectric.buildActivity(MainActivity.class).create(state)) {
            org.robolectric.android.controller.ServiceController<PlaybackService> controller =
                    Robolectric.buildService(PlaybackService.class).create();
            try {
                MainActivity screen = activity.get();
                PlaybackService service = controller.get();
                service.enqueue(new MusicApi.Track("fixture", "Song", "Artist", "", ""), false);
                field("playback").set(screen, service);
                PlayPauseButton primary = (PlayPauseButton) field("play").get(screen);
                PlayPauseButton mini = (PlayPauseButton) field("miniPlay").get(screen);
                java.lang.reflect.Method render = MainActivity.class.getDeclaredMethod("renderPlayer");
                render.setAccessible(true);
                service.changed = () -> {
                    try { render.invoke(screen); }
                    catch (Exception failed) { throw new AssertionError(failed); }
                };
                java.lang.reflect.Method publish = PlaybackService.class.getDeclaredMethod("publish", int.class, String.class);
                publish.setAccessible(true);
                int[] states = {PlaybackState.STATE_BUFFERING, PlaybackState.STATE_PLAYING,
                        PlaybackState.STATE_PAUSED, PlaybackState.STATE_PLAYING, PlaybackState.STATE_STOPPED};
                for (int playbackState : states) {
                    publish.invoke(service, playbackState, "");
                    String expected = playbackState == PlaybackState.STATE_PLAYING ? "Pause" : "Play";
                    assertEquals(expected, primary.getContentDescription());
                    assertEquals(expected, mini.getContentDescription());
                    assertEquals(playbackState != PlaybackState.STATE_BUFFERING, primary.isEnabled());
                    assertEquals(primary.isEnabled(), mini.isEnabled());
                }
                View root = screen.findViewById(android.R.id.content);
                AdaptiveUiTest.layout(root, 1024, 600);
                assertTrue(primary.getWidth() >= 48 && primary.getHeight() >= 48);
            } finally {
                controller.get().changed = null;
                controller.destroy();
            }
        }
    }

    @Test public void seekThumbPreferenceIsAppliedToPlayableTrack() throws Exception {
        Bundle state = new Bundle();
        state.putString("page", "Account");
        try (org.robolectric.android.controller.ActivityController<MainActivity> activity =
                     Robolectric.buildActivity(MainActivity.class).create(state)) {
            org.robolectric.android.controller.ServiceController<PlaybackService> controller =
                    Robolectric.buildService(PlaybackService.class).create();
            try {
                MainActivity screen = activity.get();
                PlaybackService service = controller.get();
                android.media.MediaPlayer media = new android.media.MediaPlayer();
                String fixture = "fixture.m4a";
                org.robolectric.shadows.ShadowMediaPlayer.addMediaInfo(
                        org.robolectric.shadows.util.DataSource.toDataSource(fixture),
                        new org.robolectric.shadows.ShadowMediaPlayer.MediaInfo(12_000, 0));
                media.setDataSource(fixture);
                media.prepare();
                java.lang.reflect.Field player = PlaybackService.class.getDeclaredField("player");
                player.setAccessible(true);
                player.set(service, media);
                java.lang.reflect.Field prepared = PlaybackService.class.getDeclaredField("prepared");
                prepared.setAccessible(true);
                prepared.setBoolean(service, true);
                field("playback").set(screen, service);

                android.widget.SeekBar seek = (android.widget.SeekBar) field("seek").get(screen);
                AppSettings prefs = new AppSettings(screen);
                java.lang.reflect.Method render = MainActivity.class.getDeclaredMethod("renderProgress");
                render.setAccessible(true);
                prefs.set("seekKnob", false);
                render.invoke(screen);
                assertTrue(seek.isEnabled());
                assertEquals(12_000, seek.getMax());
                assertEquals(0, seek.getThumb().getAlpha());
                prefs.set("seekKnob", true);
                render.invoke(screen);
                assertEquals(255, seek.getThumb().getAlpha());
            } finally {
                controller.destroy();
            }
        }
    }

    private static java.lang.reflect.Field field(String name) throws Exception {
        java.lang.reflect.Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
