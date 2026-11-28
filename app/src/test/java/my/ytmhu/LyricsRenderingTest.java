// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import android.widget.TextView;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 27, qualifiers = "w1024dp-h600dp-land-mdpi")
public final class LyricsRenderingTest {
    @Test public void translatedLinesMapToOriginalsAndRetainTiming() throws Exception {
        exerciseRendering(false);
    }

    @Test
    @Config(sdk = 31, qualifiers = "w1024dp-h600dp-land-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void landscapeLyricsPreview() throws Exception {
        exerciseRendering(true);
    }

    @Test public void missingOrInvalidProviderResultsKeepSourceTextAndDiagnosticsSeparate() throws Exception {
        List<String> lines = Collections.singletonList("学校");
        assertThrows(java.io.IOException.class, () -> LyricExtras.alignCloud(lines,
                new JSONObject("{\"data\":{\"translations\":[]}}")));
        assertEquals("", LyricExtras.clean(" 学校 ", "学校"));
        assertEquals("Network unavailable or timed out. Check your connection and reopen Lyrics.",
                LyricExtras.failure(new java.net.UnknownHostException("unavailable")));
        assertFalse(LyricExtras.failure(new IllegalArgumentException("secret fixture")).contains("secret"));
        assertFalse(LyricExtras.align(lines, new org.json.JSONArray("[{\"romanization\":\"gaッ kou\"}]")).has("学校"));
    }

    private static void exerciseRendering(boolean capture) throws Exception {
        try (org.robolectric.android.controller.ActivityController<Activity> controller =
                     Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            AppSettings prefs = new AppSettings(activity);
            prefs.set("lyricRoman", true);
            prefs.set("lyricTranslation", true);
            prefs.set("lyricTarget", "en");
            String first = "おはよう";
            String second = "こんにちは";
            List<Lyrics.Line> display = Arrays.asList(
                    new Lyrics.Line(1500, first, "ohayou", Collections.singletonMap("en", "Good morning")),
                    new Lyrics.Line(3700, second, "konnichiwa", Collections.singletonMap("en", "Hello")));
            Lyrics original = new Lyrics(display, "", "Fixture", "ja");
            assertEquals(0, original.activeAt(1500));
            assertEquals(1, original.activeAt(3700));
            Artwork art = new Artwork();
            NowPlayingView player = new NowPlayingView(activity, prefs, new Appearance(activity, prefs), art,
                    (v, track, index) -> {}, () -> {});
            try {
                activity.setContentView(player);
                set(player, "lyrics", original);
                set(player, "track", new MusicApi.Track("fixture", "Japanese lyric sample", "Fixture", "", ""));
                set(player, "lyricsMode", true);
                Method render = NowPlayingView.class.getDeclaredMethod("render");
                render.setAccessible(true);
                render.invoke(player);
                player.tick(1500);
                TextView source = (TextView) AdaptiveUiTest.text(player, first);
                TextView roman = (TextView) AdaptiveUiTest.text(player, "ohayou");
                TextView english = (TextView) AdaptiveUiTest.text(player, "Good morning");
                assertTrue(source.getTextSize() > roman.getTextSize());
                assertTrue(roman.getTextSize() > english.getTextSize());
                assertEquals(Color.WHITE, source.getCurrentTextColor());
                assertNull(((View) source.getParent()).getBackground());

                JSONObject malay = LyricExtras.alignCloud(Arrays.asList(first, second),
                        new JSONObject("{\"data\":{\"translations\":[{\"translatedText\":\"Selamat pagi\"},"
                                + "{\"translatedText\":\"Apa khabar\"}]}}"));
                assertEquals("Selamat pagi", LyricExtras.value(malay, first, "translation"));
                assertEquals("Apa khabar", LyricExtras.value(malay, second, "translation"));
                assertEquals("Google Cloud Translation", LyricExtras.value(malay, first, "provider"));
                prefs.set("lyricTarget", "ms");
                Method show = NowPlayingView.class.getDeclaredMethod("showExtras", List.class,
                        JSONObject.class, List.class, boolean.class, boolean.class, String.class);
                show.setAccessible(true);
                show.invoke(player, display, malay, Collections.emptyList(), true, true, "ms");
                assertNull(AdaptiveUiTest.text(player, "Good morning"));
                assertNotNull(AdaptiveUiTest.text(player, "Selamat pagi"));
                assertNotNull(AdaptiveUiTest.text(player, "Apa khabar"));
                assertTrue(AdaptiveUiTest.text(player, "Selamat pagi").getContentDescription()
                        .toString().startsWith("Machine translation by Google Cloud Translation."));
                assertTrue(source.isShown());
                prefs.set("lyricOriginal", false);
                show.invoke(player, display, malay, Collections.emptyList(), true, true, "ms");
                assertEquals(View.GONE, source.getVisibility());
                prefs.set("lyricOriginal", true);
                show.invoke(player, display, malay, Collections.emptyList(), true, true, "ms");

                String path = System.getProperty("ytm.lyrics.preview", "");
                if (capture && !path.isEmpty()) {
                    AdaptiveUiTest.layout(player, 1024, 600);
                    Bitmap image = Bitmap.createBitmap(1024, 600, Bitmap.Config.ARGB_8888);
                    player.draw(new Canvas(image));
                    try (FileOutputStream output = new FileOutputStream(path)) {
                        assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
                    } finally {
                        image.recycle();
                    }
                }
            } finally {
                player.close();
                art.close();
            }
        }
    }

    private static void set(NowPlayingView player, String name, Object value) throws Exception {
        Field field = NowPlayingView.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(player, value);
    }
}
