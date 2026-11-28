// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.media.session.PlaybackState;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowMediaPlayer;
import org.robolectric.shadows.util.DataSource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 27, qualifiers = "w1024dp-h600dp-land-mdpi")
public final class SyncedLyricsRuntimeTest {
    @Test public void timedProviderWinsOverPlainWithoutMixingLyrics() throws Exception {
        Lyrics plain = new Lyrics(Collections.emptyList(), "some text", "YouTube Music", "");
        Lyrics timed = new Lyrics(Lyrics.parse("[00:03.000]timed text"), "", "Unison", "ja");
        List<String> visited = new ArrayList<>();
        Lyrics selected = Lyrics.select(new String[]{"youtube", "lrclib", "unison"}, 10_000, source -> {
            visited.add(source);
            return "unison".equals(source) ? timed : plain;
        });
        assertEquals(Arrays.asList("lrclib", "unison"), visited);
        assertSame(timed, selected);
        assertEquals(-1, selected.activeAt(2_999));
        assertEquals(0, selected.activeAt(3_000));
        assertSame(plain, Lyrics.select(new String[]{"youtube", "unison"}, 1000,
                source -> "youtube".equals(source) ? plain : timed));
        assertEquals(-1, plain.displayLines().get(0).millis);
    }

    @Test public void lrclibRejectsWrongDurationAndMismatchedRecordings() throws Exception {
        JSONArray options = new JSONArray("["
                + "{\"trackName\":\"Song (Live)\",\"artistName\":\"Artist\",\"duration\":270,\"syncedLyrics\":\"[00:02]live\"},"
                + "{\"trackName\":\"Song\",\"artistName\":\"Artist\",\"duration\":239,\"syncedLyrics\":\"[00:02]short\"},"
                + "{\"trackName\":\"Song\",\"artistName\":\"Artist\",\"duration\":270,\"plainLyrics\":\"plain\"},"
                + "{\"trackName\":\"Song\",\"artistName\":\"Artist\",\"duration\":271.5,\"syncedLyrics\":\"[00:02]timed\"}]");
        assertEquals("[00:02]timed", Lyrics.bestMatch(options, "Song", "Artist", 270_000).getString("syncedLyrics"));
        assertEquals("plain", Lyrics.bestMatch(options, "Song", "Artist", 0).getString("plainLyrics"));
    }

    @Test public void naturalTransitionMovesWholeGroupsAtDifferentRatesAndSeekSnaps() throws Exception {
        try (org.robolectric.android.controller.ActivityController<Activity> activity =
                     Robolectric.buildActivity(Activity.class).setup()) {
            org.robolectric.android.controller.ServiceController<PlaybackService> controller =
                    Robolectric.buildService(PlaybackService.class).create();
            PlaybackService service = controller.get();
            Artwork art = new Artwork();
            AppSettings prefs = new AppSettings(activity.get());
            prefs.set("lyricTranslation", false);
            Appearance appearance = new Appearance(activity.get(), prefs);
            LyricMotion motion = appearance.lyricMotion;
            assertEquals(166, motion.scaleMs);
            assertEquals(195, motion.regular.millis);
            assertTrue(motion.scrollDuration(1, 100) > motion.scrollDuration(-1, 100));
            assertEquals(motion.scrollDuration(-1, 100), motion.scrollDuration(1, -100));
            JSONObject override = new JSONObject("{\"rest_scale\":0.972,\"active_scale\":1.012,"
                    + "\"scale_secs\":0.41,\"scroll_curve\":[620,16,650,58,12]}");
            LyricMotion styled = new LyricMotion(override);
            assertEquals(410, styled.scaleMs);
            assertEquals(1.012f, styled.activeScale, 0.0001f);
            assertEquals(650, styled.scrollDuration(0, 100));

            NowPlayingView player = new NowPlayingView(activity.get(), prefs, appearance, art,
                    (anchor, track, index) -> {}, () -> {});
            try {
                activity.get().setContentView(player);
                service.enqueue(new MusicApi.Track("motion", "Song", "Artist", "", ""), false);
                field(PlaybackService.class, "index").setInt(service, 0);
                MediaPlayer media = new MediaPlayer();
                ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource("motion-fixture.m4a"),
                        new ShadowMediaPlayer.MediaInfo(30_000, 0));
                media.setDataSource("motion-fixture.m4a");
                media.prepare();
                field(PlaybackService.class, "player").set(service, media);
                field(PlaybackService.class, "prepared").setBoolean(service, true);
                service.changed = () -> player.update(service);
                List<Lyrics.Line> display = new ArrayList<>();
                for (int i = 0; i < 18; i++) {
                    display.add(new Lyrics.Line(i * 1000, "Motion " + i, "Reading " + i,
                            Collections.emptyMap()));
                }
                shadowOf(media).setCurrentPosition(8_900);
                setLyrics(player, service, new Lyrics(display, "", "Motion fixture", "ja"));
                layout(player);
                publish(service, PlaybackState.STATE_PLAYING);
                View outgoing = (View) AdaptiveUiTest.text(player, "Motion 8").getParent();
                View incoming = (View) AdaptiveUiTest.text(player, "Motion 9").getParent();
                View upcoming = (View) AdaptiveUiTest.text(player, "Motion 10").getParent();
                assertSame(incoming, AdaptiveUiTest.text(player, "Reading 9").getParent());
                assertEquals(1f, outgoing.getScaleX(), 0.001f);
                assertEquals(0.95f, incoming.getScaleX(), 0.001f);
                assertEquals(0f, incoming.getPivotX(), 0.001f);
                ScrollView scroll = (ScrollView) field(NowPlayingView.class, "scroll").get(player);
                int before = scroll.getScrollY();
                assertTrue(service.playing());
                assertEquals(8900, field(NowPlayingView.class, "lastLyricTime").getInt(player));
                shadowOf(media).setCurrentPosition(9_010);
                player.syncPosition();
                layout(player);
                assertActive(player, 9, 9_000);
                assertTrue(player.diagnostics() + "\nScroll " + before + " → " + scroll.getScrollY()
                        + ", row " + incoming.getTop() + ", height " + scroll.getHeight() + ", translate=" + incoming.getTranslationY(),
                        incoming.getTranslationY() > 0f);
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
                assertTrue(incoming.getScaleX() > 0.95f && incoming.getScaleX() < 1f);
                assertTrue(outgoing.getScaleX() > 0.95f && outgoing.getScaleX() < 1f);
                assertTrue(incoming.getAlpha() > 0.82f && incoming.getAlpha() < 1f);
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(260));
                assertTrue(upcoming.getTranslationY() > outgoing.getTranslationY());
                assertEquals(1f, incoming.getScaleX(), 0.001f);
                assertEquals(0.95f, outgoing.getScaleX(), 0.001f);
                service.seekTo(3_400);
                layout(player);
                assertActive(player, 3, 3_000);
                assertEquals(0f, incoming.getTranslationY(), 0.001f);
                assertEquals(0.95f, incoming.getScaleX(), 0.001f);
                assertEquals(1f, ((View) AdaptiveUiTest.text(player, "Motion 3").getParent()).getScaleX(), 0.001f);
            } finally {
                player.close();
                art.close();
                controller.destroy();
            }
        }
    }

    @Test public void mediaClockDrivesThreeLayersSeekAndManualRecoveryOnLowMemory() throws Exception {
        try (org.robolectric.android.controller.ActivityController<Activity> activity =
                     Robolectric.buildActivity(Activity.class).setup()) {
            org.robolectric.android.controller.ServiceController<PlaybackService> serviceController =
                    Robolectric.buildService(PlaybackService.class).create();
            PlaybackService service = serviceController.get();
            Artwork art = new Artwork();
            AppSettings prefs = new AppSettings(activity.get());
            prefs.set("lyricRoman", true);
            prefs.set("lyricTranslation", true);
            NowPlayingView player = new NowPlayingView(activity.get(), prefs, new Appearance(activity.get(), prefs), art,
                    (anchor, track, index) -> {}, () -> {});
            try {
                player.setProfile(PerformancePolicy.Profile.ULTRA_LITE);
                activity.get().setContentView(player);
                service.enqueue(new MusicApi.Track("track1", "Song", "Artist", "", ""), false);
                // Select the fixture queue entry without making a stream resolver request.
                field(PlaybackService.class, "index").setInt(service, 0);
                MediaPlayer media = new MediaPlayer();
                String uri = "sync-fixture.m4a";
                ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(uri), new ShadowMediaPlayer.MediaInfo(30_000, 0));
                media.setDataSource(uri);
                media.prepare();
                ShadowMediaPlayer mediaState = shadowOf(media);
                mediaState.setSeekDelay(100);
                field(PlaybackService.class, "player").set(service, media);
                field(PlaybackService.class, "prepared").setBoolean(service, true);
                service.changed = () -> player.update(service);

                List<Lyrics.Line> display = new ArrayList<>();
                for (int i = 0; i < 24; i++) {
                    display.add(new Lyrics.Line((i + 1) * 1000, "Source " + i, "Reading " + i,
                            Collections.singletonMap("en", "Translation " + i)));
                }
                Lyrics timed = new Lyrics(display, "", "LRCLIB fixture", "ja");
                setLyrics(player, service, timed);
                layout(player);

                mediaState.setCurrentPosition(13_300);
                publish(service, PlaybackState.STATE_PLAYING);
                layout(player);
                assertActive(player, 12, 13_000);
                assertEquals(Color.WHITE, ((TextView) AdaptiveUiTest.text(player, "Source 12")).getCurrentTextColor());
                assertEquals(0xfff5eef5, ((TextView) AdaptiveUiTest.text(player, "Reading 12")).getCurrentTextColor());
                assertEquals(0xfff5eef5, ((TextView) AdaptiveUiTest.text(player, "Translation 12")).getCurrentTextColor());

                ScrollView scroll = (ScrollView) field(NowPlayingView.class, "scroll").get(player);
                assertTrue(scroll.getScrollY() > 0);
                swipe(scroll);
                scroll.scrollTo(0, 0);
                publish(service, PlaybackState.STATE_PAUSED);
                assertActive(player, 12, 13_000);
                service.seekTo(21_200);
                assertActive(player, 20, 21_000);
                service.seekTo(5_100);
                assertActive(player, 4, 5000);
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));
                assertEquals(5_100, service.position());
                assertEquals(0, scroll.getScrollY());
                assertTrue(player.diagnostics().contains("paused by manual scroll"));
                View go = AdaptiveUiTest.text(player, "Return to current lyric");
                assertNotNull(go);
                go.performClick();
                layout(player);
                assertTrue(scroll.getScrollY() > 0);

                Method firstVisible = NowPlayingView.class.getDeclaredMethod("firstVisibleGroup", int.class);
                firstVisible.setAccessible(true);
                View anchor = (View) firstVisible.invoke(player, scroll.getScrollY());
                int anchorOffset = scroll.getScrollY() - anchor.getTop();
                Method showExtras = NowPlayingView.class.getDeclaredMethod("showExtras", List.class,
                        JSONObject.class, List.class, boolean.class, boolean.class, String.class);
                showExtras.setAccessible(true);
                JSONObject values = new JSONObject().put("Source 4",
                        new JSONObject().put("translation", "A translated fixture line").put("provider", "Unison"));
                showExtras.invoke(player, display, values, Collections.emptyList(), true, true, "ms");
                layout(player);
                assertActive(player, 4, 5000);
                assertEquals(anchorOffset, scroll.getScrollY() - anchor.getTop());
                assertEquals(0xfff5eef5, ((TextView) AdaptiveUiTest.text(player, "A translated fixture line")).getCurrentTextColor());

                prefs.set("lyricsOffset", 400);
                player.syncPosition();
                assertActive(player, 3, 4000);
                assertTrue(((View) AdaptiveUiTest.text(player, "Source 15").getParent()).performClick());
                assertActive(player, 15, 16_000);
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150));
                publish(service, PlaybackState.STATE_BUFFERING);
                assertActive(player, 15, 16_000);
                publish(service, PlaybackState.STATE_PLAYING);
                shadowOf(Looper.getMainLooper()).idle();
                assertActive(player, 15, 16_000);
                swipe(scroll);
                assertTrue(player.diagnostics().contains("paused by manual scroll"));
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8));
                player.syncPosition();
                assertTrue(player.diagnostics().contains("Automatic follow: on"));

                player.hideLyrics();
                player.show(service, true);
                layout(player);
                assertActive(player, 15, 16_000);

                String diagnosticFile = System.getProperty("ytm.lyrics.diagFixture", "");
                if (!diagnosticFile.isEmpty()) {
                    java.nio.file.Files.write(java.nio.file.Paths.get(diagnosticFile),
                            player.diagnostics().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }

                service.enqueue(new MusicApi.Track("track2", "Second song", "Artist", "", ""), false);
                field(PlaybackService.class, "index").setInt(service, 1);
                field(PlaybackService.class, "prepared").setBoolean(service, false);
                // Duration is unknown during track startup, so the view must wait before selecting a provider.
                publish(service, PlaybackState.STATE_BUFFERING);
                assertNull(AdaptiveUiTest.text(player, "Source 15"));
                assertTrue((boolean) field(NowPlayingView.class, "waitingForDuration").get(player));
                assertEquals(-1, field(NowPlayingView.class, "activeLine").getInt(player));
                prefs.set("lyricRoman", false);
                prefs.set("lyricTranslation", false);
                setLyrics(player, service, new Lyrics(Collections.emptyList(), "Plain line", "YouTube Music", ""));
                assertNotNull(AdaptiveUiTest.text(player, "Synced lyrics unavailable — showing plain lyrics."));
                assertTrue(player.diagnostics().contains("Valid timestamps: 0"));
            } finally {
                service.changed = null;
                player.close();
                art.close();
                serviceController.destroy();
            }
        }
    }

    private static void setLyrics(NowPlayingView player, PlaybackService service, Lyrics lyrics) throws Exception {
        field(NowPlayingView.class, "track").set(player, service.currentTrack());
        field(NowPlayingView.class, "lyricsMode").setBoolean(player, true);
        field(NowPlayingView.class, "lyrics").set(player, lyrics);
        player.show(service, true);
    }

    private static void publish(PlaybackService service, int state) throws Exception {
        Method publish = PlaybackService.class.getDeclaredMethod("publish", int.class, String.class);
        publish.setAccessible(true);
        publish.invoke(service, state, "");
    }

    private static void layout(NowPlayingView player) {
        AdaptiveUiTest.layout(player, 1024, 600);
        player.getViewTreeObserver().dispatchOnPreDraw();
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static void swipe(ScrollView scroll) {
        long now = android.os.SystemClock.uptimeMillis();
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(now, now, action, 120,
                    action == MotionEvent.ACTION_DOWN ? 250 : 180, 0);
            try { scroll.dispatchTouchEvent(event); } finally { event.recycle(); }
        }
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void assertActive(NowPlayingView player, int index, int time) {
        assertTrue(player.diagnostics(), player.diagnostics().contains("Active index (zero-based): " + index + "\n"));
        assertTrue(player.diagnostics(), player.diagnostics().contains("Active timestamp: " + time + " ms"));
    }
}
