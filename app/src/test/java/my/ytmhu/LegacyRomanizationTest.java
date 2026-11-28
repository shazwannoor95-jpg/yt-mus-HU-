// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.ActivityManager;
import android.os.Looper;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 27)
public final class LegacyRomanizationTest {
    @Test public void localScriptsPreserveUnknownHanAndMixedLyrics() throws Exception {
        try (org.robolectric.android.controller.ActivityController<Activity> controller =
                     Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            JapaneseDictionary dictionary = new JapaneseDictionary(activity);
            LocalRomanizer engine = new LocalRomanizer();
            try (LocalRomanizer.Batch batch = engine.new Batch(activity, dictionary)) {
                assertEquals("sayonara", batch.read("さよなら", "").text);
                assertEquals("matcha kōhī", batch.read("まっちゃ コーヒー", "").text);
                assertEquals("annyeonghaseyo", batch.read("안녕하세요", "").text);
                assertEquals("ni hao", batch.read("你好", "zh").text);
                assertEquals("zhong guo", batch.read("中国", "zh").text);
                assertEquals("Privet", batch.read("Привет", "").text);
                assertEquals("Hello Privet annyeong", batch.read("Hello Привет 안녕", "").text);
                assertEquals("sayonarano前ni", batch.read("さよならの前に", "zh").text);
                assertFalse(batch.read("さよならの前に", "ja").complete);
                assertEquals("", batch.read("你好", "").text);
                assertEquals("", batch.read("前", "ja").text);
                assertEquals("", batch.read("行", "zh").text);
                LocalRomanizer.Reading first = batch.read("Привет", "");
                assertSame(first, batch.read("Привет", ""));
                engine.clear();
                assertNotSame(first, batch.read("Привет", ""));
            }
        }
    }

    @Test public void installedDictionaryRendersYouTubeMusicLyricsOfflineAndDropsOldTrack() throws Exception {
        try (org.robolectric.android.controller.ActivityController<Activity> controller =
                     Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            JapaneseDictionary dictionary = installFixture(activity);
            memory(activity, false);
            try (LocalRomanizer.Batch batch = new LocalRomanizer().new Batch(activity, dictionary)) {
                assertEquals("sayonara no mae ni", batch.read("さよならの前に", "ja").text);
            }
            AppSettings prefs = new AppSettings(activity);
            prefs.set("lyricRoman", true);
            prefs.set("lyricHanLanguage", "zh");
            assertTrue(new AppSettings(activity).flag("lyricRoman", false));
            assertEquals("zh", new AppSettings(activity).text("lyricHanLanguage", "auto"));
            Artwork artwork = new Artwork();
            NowPlayingView player = new NowPlayingView(activity, prefs, new Appearance(activity, prefs),
                    artwork, (v,t,i) -> {}, () -> {});
            activity.setContentView(player);
            try {
                Method render = NowPlayingView.class.getDeclaredMethod("render");
                render.setAccessible(true);
                field("lyricsMode").setBoolean(player, true);
                String japanese = "さよならの前に";
                field("track").set(player, new MusicApi.Track("jp", japanese, "AAA", "", ""));
                Lyrics lines = new Lyrics(Collections.emptyList(), japanese, "YouTube Music", "");
                field("lyrics").set(player, lines);
                assertEquals(-1, lines.displayLines().get(0).millis);
                render.invoke(player);
                awaitBackground(player);
                assertNotNull(AdaptiveUiTest.text(player, japanese));
                TextView reading = (TextView) AdaptiveUiTest.text(player, "sayonara no mae ni");
                assertNotNull(reading);
                assertTrue(reading.isShown());
                assertNull(AdaptiveUiTest.text(player, "For kanji readings, install the Japanese dictionary in Settings › Lyrics (12.8 MiB)."));

                // Queue work for the former track before replacing the panel's current result.
                render.invoke(player);
                field("track").set(player, new MusicApi.Track("ru", "Привет", "Test", "", ""));
                field("lyrics").set(player, new Lyrics(Collections.emptyList(), "Привет", "YouTube Music", ""));
                render.invoke(player);
                awaitBackground(player);
                assertNotNull(AdaptiveUiTest.text(player, "Privet"));
                assertNull(AdaptiveUiTest.text(player, "sayonara no mae ni"));
            } finally {
                player.close();
                artwork.close();
                Files.deleteIfExists(dictionary.archive.toPath());
            }
        }
    }

    @Test public void japaneseMorphologyJoinsConjugationsAndPronouncesParticles() throws Exception {
        try (org.robolectric.android.controller.ActivityController<Activity> controller =
                     Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            JapaneseDictionary dictionary = installFixture(activity);
            memory(activity, false);
            try (LocalRomanizer.Batch batch = new LocalRomanizer().new Batch(activity, dictionary)) {
                assertEquals("hitori ni natte", batch.read("一人になって", "ja").text);
                assertEquals("koko ni atte", batch.read("ここにあって", "ja").text);
                assertEquals("omotteru", batch.read("思ってる", "ja").text);
                assertEquals("nigirishimeteta", batch.read("握りしめてた", "ja").text);
                assertEquals("gakkō", batch.read("学校", "ja").text);
                assertEquals("kyō wa", batch.read("今日は", "ja").text);
                assertEquals("gakkō e iku", batch.read("学校へ行く", "ja").text);
                assertEquals("kimi o omou", batch.read("君を想う", "ja").text);
                assertEquals("Hello, koko ni atte 2026!", batch.read("Hello, ここにあって 2026!", "ja").text);
                assertEquals("ja", LocalRomanizer.language("", "Shamrock", java.util.Arrays.asList(
                        new Lyrics.Line(0, "学校"), new Lyrics.Line(1000, "ここにあって"))));
                assertEquals("gakkō", batch.read("学校", "ja").text);
            } finally {
                Files.deleteIfExists(dictionary.archive.toPath());
            }
        }
    }

    @Test public void pressureSkipsTheDictionaryWhileKanaRemainsAvailable() throws Exception {
        try (org.robolectric.android.controller.ActivityController<Activity> controller =
                     Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            JapaneseDictionary dictionary = installFixture(activity);
            memory(activity, true);
            LocalRomanizer engine = new LocalRomanizer();
            try (LocalRomanizer.Batch batch = engine.new Batch(activity, dictionary)) {
                assertEquals("sayonarano前ni", batch.read("さよならの前に", "ja").text);
                assertEquals(LocalRomanizer.DictionaryState.MEMORY, batch.dictionaryState());
                assertEquals("annyeong", batch.read("안녕", "").text);
            } finally {
                Files.deleteIfExists(dictionary.archive.toPath());
            }
        }
    }

    private static JapaneseDictionary installFixture(Activity activity) throws Exception {
        File fixture = new File(System.getProperty("ytm.test.japanese.dictionary"));
        JapaneseDictionary dictionary = new JapaneseDictionary(activity);
        Files.copy(fixture.toPath(), dictionary.archive.toPath(), StandardCopyOption.REPLACE_EXISTING);
        assertTrue(dictionary.installed());
        return dictionary;
    }

    private static void memory(Activity activity, boolean low) {
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        info.lowMemory = low;
        info.availMem = 320L * 1024 * 1024;
        info.threshold = 64L * 1024 * 1024;
        shadowOf(activity.getSystemService(ActivityManager.class)).setMemoryInfo(info);
    }

    private static Field field(String name) throws Exception {
        Field field = NowPlayingView.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void awaitBackground(NowPlayingView player) throws Exception {
        ExecutorService network = (ExecutorService) field("network").get(player);
        network.submit(() -> {}).get(15, TimeUnit.SECONDS);
        shadowOf(Looper.getMainLooper()).idle();
    }
}
