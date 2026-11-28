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

    @Test
    @Config(sdk = 31, qualifiers = "w1024dp-h600dp-land-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void timedSweepActuallyPaintsAcrossWrappedTextAndRewinds() {
        try (org.robolectric.android.controller.ActivityController<Activity> controller =
                     Robolectric.buildActivity(Activity.class).setup()) {
            String text = "The source span wraps across rows";
            KaraokeLineView line = new KaraokeLineView(controller.get(),
                    Collections.singletonList(new Lyrics.Word(1000, 2000, 0, text.length())), Color.YELLOW);
            line.setText(text);
            line.setTextColor(Color.WHITE);
            line.setTextSize(22);
            controller.get().setContentView(line);
            AdaptiveUiTest.layout(line, 134, 200);
            assertTrue(line.getLayout().getLineCount() > 1);
            int start = highlightedPixels(line, 1000);
            int middle = highlightedPixels(line, 1500);
            int full = highlightedPixels(line, 2000);
            assertEquals(0, start);
            assertTrue(middle > 0);
            assertTrue(full > middle);
            assertEquals(start, highlightedPixels(line, 800));
            line.setEffects(new LyricMotion(null), PerformancePolicy.Profile.BALANCED);
            int feathered = highlightedPixels(line, 1500);
            assertTrue(feathered > 0 && feathered < middle);
            line.setExitOpacity(0.1f);
            assertTrue(highlightedPixels(line, 1500) < feathered);
        }
    }

    @Test
    @Config(sdk = 31)
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void sourceTimedWaveDrawsDifferentFramesWithoutMovingThePlaybackBoundary() throws Exception {
        try (org.robolectric.android.controller.ActivityController<Activity> controller =
                     Robolectric.buildActivity(Activity.class).setup()) {
            KaraokeLineView line = new KaraokeLineView(controller.get(),
                    Collections.singletonList(new Lyrics.Word(1000, 2400, 0, 4)), Color.YELLOW);
            line.setText("WAVE");
            line.setTextColor(Color.WHITE);
            line.setTextSize(34);
            line.setPadding(8, 20, 0, 4);
            controller.get().setContentView(line);
            AdaptiveUiTest.layout(line, 250, 110);
            LyricMotion style = new LyricMotion(new JSONObject("{\"wobble\":false,\"letter_wave\":{\"crest\":0.2}}"));
            line.setEffects(style, PerformancePolicy.Profile.HIGH_QUALITY);
            line.position(1750);
            Bitmap wave = Bitmap.createBitmap(250, 110, Bitmap.Config.ARGB_8888);
            Bitmap normal = Bitmap.createBitmap(250, 110, Bitmap.Config.ARGB_8888);
            try {
                line.draw(new Canvas(wave));
                assertNotNull(org.robolectric.util.ReflectionHelpers.getField(line, "clusters"));
                int percent = line.fillPercent();
                line.setEffects(style, PerformancePolicy.Profile.BALANCED);
                line.draw(new Canvas(normal));
                assertFalse(wave.sameAs(normal));
                assertEquals(percent, line.fillPercent());
                line.position(999);
                assertEquals(-1, line.activeSpan());
            } finally {
                wave.recycle();
                normal.recycle();
            }
        }
    }

    @Test public void twoNamedTtmlVoicesKeepIndependentTimingAndVisualState() throws Exception {
        Lyrics pair = TtmlLyrics.parse("<tt xmlns='http://www.w3.org/ns/ttml' xmlns:ttm='http://www.w3.org/ns/ttml#metadata'>"
                + "<body><div><p ttm:agent='v1' begin='1s' end='3s'><span begin='1s' end='3s'>Lead</span></p>"
                + "<p ttm:agent='v2' begin='1.5s' end='2.5s'><span begin='1.5s' end='2.5s'>Reply</span></p></div></body></tt>");
        assertEquals(1, pair.activeAt(2000));
        assertEquals(0, pair.companionAt(1, 2000));
        assertEquals(0, pair.activeAt(2600));
        assertEquals(-1, pair.activeAt(3000));
        try (org.robolectric.android.controller.ActivityController<Activity> controller =
                     Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Artwork art = new Artwork();
            AppSettings prefs = new AppSettings(activity);
            prefs.set("lyricRoman", false);
            prefs.set("lyricTranslation", false);
            NowPlayingView view = new NowPlayingView(activity, prefs, new Appearance(activity, prefs), art,
                    (anchor, track, index) -> {}, () -> {});
            try {
                activity.setContentView(view);
                org.robolectric.util.ReflectionHelpers.setField(view, "track", new MusicApi.Track("fixture", "Song", "Artist", "", ""));
                org.robolectric.util.ReflectionHelpers.setField(view, "lyrics", pair);
                org.robolectric.util.ReflectionHelpers.setField(view, "lyricsMode", true);
                org.robolectric.util.ReflectionHelpers.callInstanceMethod(view, "render");
                view.tick(2000);
                KaraokeLineView lead = (KaraokeLineView) AdaptiveUiTest.text(view, "Lead");
                KaraokeLineView reply = (KaraokeLineView) AdaptiveUiTest.text(view, "Reply");
                assertEquals(50, lead.fillPercent());
                assertEquals(50, reply.fillPercent());
                assertEquals(1f, ((View) lead.getParent()).getScaleX(), 0.01f);
                assertEquals(1f, ((View) reply.getParent()).getScaleX(), 0.01f);
                view.tick(2600);
                assertEquals(80, lead.fillPercent());
                assertEquals(-1, reply.activeSpan());
            } finally {
                view.close();
                art.close();
            }
        }
    }

    private static int highlightedPixels(KaraokeLineView line, int time) {
        line.position(time);
        Bitmap bitmap = Bitmap.createBitmap(line.getWidth(), line.getHeight(), Bitmap.Config.ARGB_8888);
        try {
            line.draw(new Canvas(bitmap));
            int count = 0;
            for (int y = 0; y < bitmap.getHeight(); y++) {
                for (int x = 0; x < bitmap.getWidth(); x++) {
                    int color = bitmap.getPixel(x, y);
                    if (Color.alpha(color) > 127 && Color.red(color) > 220 && Color.green(color) > 220
                            && Color.blue(color) < 40) count++;
                }
            }
            return count;
        } finally {
            bitmap.recycle();
        }
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
                assertNotNull(roman.getBackground());
                assertNull(english.getBackground());
                if (capture) assertTrue(source.getTypeface().isBold());
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
