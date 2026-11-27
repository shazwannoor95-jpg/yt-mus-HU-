// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import static org.junit.Assert.*;
import android.app.Activity;
import android.view.View;
import android.widget.TextView;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.Arrays;
import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 27)
public final class LyricsParityTest {
    private static final String FIXTURE = "<tt xmlns='http://www.w3.org/ns/ttml' xml:lang='ja'>"
            + "<head><metadata><translations><translation xml:lang='ms'><text for='L1'>Selamat pagi</text></translation>"
            + "<translation xml:lang='en'><text for='L1'>Good morning</text></translation></translations>"
            + "<transliterations><transliteration><text for='L1'>ohayou</text></transliteration></transliterations>"
            + "</metadata></head><body><div><p begin='00:01.500' key='L1'>おはよう</p>"
            + "<p begin='3.2s' key='L2'>World</p></div></body></tt>";

    @Test public void rendersOriginalAndSuppliedAlternatesAtTheSameTimestamp() throws Exception {
        Lyrics lyrics = TtmlLyrics.parse(FIXTURE);
        assertEquals(-1, lyrics.activeAt(1499));
        assertEquals(0, lyrics.activeAt(1500));
        assertEquals(1, lyrics.activeAt(3200));
        assertEquals(-1, TtmlLyrics.millis("invalid"));
        assertThrows(java.io.IOException.class, () -> TtmlLyrics.parse("<!DOCTYPE foo><tt/>"));
        try (org.robolectric.android.controller.ActivityController<Activity> controller =
                     Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            AppSettings prefs = new AppSettings(activity);
            prefs.set("lyricRoman", true);
            prefs.set("lyricTranslation", true);
            prefs.set("lyricTarget", "ms");
            prefs.set("lyricRomanSize", 60);
            prefs.set("lyricTranslationSize", 100);
            Artwork artwork = new Artwork();
            NowPlayingView player = new NowPlayingView(activity, prefs, new Appearance(activity, prefs),
                    artwork, (v,t,i) -> {}, () -> {});
            java.lang.reflect.Field value = NowPlayingView.class.getDeclaredField("lyrics");
            value.setAccessible(true);
            value.set(player, new Lyrics(Collections.singletonList(lyrics.lines.get(0)), "", "Unison", "ja"));
            java.lang.reflect.Field track = NowPlayingView.class.getDeclaredField("track");
            track.setAccessible(true);
            track.set(player, new MusicApi.Track("fixture", "Song", "Artist", "", ""));
            java.lang.reflect.Field mode = NowPlayingView.class.getDeclaredField("lyricsMode");
            mode.setAccessible(true);
            mode.setBoolean(player, true);
            java.lang.reflect.Method render = NowPlayingView.class.getDeclaredMethod("render");
            render.setAccessible(true);
            render.invoke(player);
            assertNotNull(AdaptiveUiTest.text(player, "おはよう"));
            TextView roman = (TextView) AdaptiveUiTest.text(player, "ohayou");
            TextView malay = (TextView) AdaptiveUiTest.text(player, "Selamat pagi");
            assertTrue(malay.getTextSize() > roman.getTextSize());
            assertEquals("ms", new AppSettings(activity).text("lyricTarget", "en"));
            player.tick(1500);
            assertEquals(0xfff5eef5, roman.getCurrentTextColor());
            prefs.set("lyricTarget", "en");
            render.invoke(player);
            assertNotNull(AdaptiveUiTest.text(player, "Good morning"));
            assertNull(AdaptiveUiTest.text(player, "Selamat pagi"));
            player.close();
            artwork.close();
        }
    }

    @Test public void providerResultsAlignPerLineWithoutInventingTiming() throws Exception {
        java.util.List<String> originals = Arrays.asList("こんにちは", "안녕하세요", "你好", "Привет");
        JSONArray rows = new JSONArray("[{\"romanization\":\"konnichiwa\"},{\"romanization\":\"annyeonghaseyo\"},"
                + "{\"romanization\":\"nǐ hǎo\"},{\"romanization\":\"privet\",\"translation\":\"Hello\"}]");
        org.json.JSONObject values = LyricExtras.align(originals, rows);
        for (String original : originals) assertFalse(values.getJSONObject(original).getString("romanization").isEmpty());
        assertEquals("Hello", values.getJSONObject("Привет").getString("translation"));
        assertThrows(java.io.IOException.class, () -> LyricExtras.align(originals, new JSONArray()));
        Lyrics.Line repeated = new Lyrics.Line(100, "你好");
        assertEquals(1, LyricExtras.needed(Arrays.asList(repeated, repeated), true, false, "en").size());
    }

    @Test public void oldWebViewAdviceIsSpecific() {
        assertTrue(GoogleSignIn.webViewAdvice("69.0.3497.100").contains("Open separately"));
        assertEquals("", GoogleSignIn.webViewAdvice("130.0.1"));
    }

    @Test @Config(sdk = 31) public void offlineIcuRomanizationWorksForCyrillic() {
        assertEquals("Privet", LyricExtras.localRomanization("Привет", "ru"));
        assertEquals("", LyricExtras.localRomanization("今日は", "ja"));
    }
}
