// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.ActivityManager;
import android.graphics.Color;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.util.Locale;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 27)
public final class AutomaticLyricsTest {
    @Test public void dictionaryIsBundledAndWorksWithoutAnInstalledCopy() throws Exception {
        try (org.robolectric.android.controller.ActivityController<Activity> controller =
                     Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
            memory.availMem = 320L << 20;
            memory.threshold = 64L << 20;
            Shadows.shadowOf(activity.getSystemService(ActivityManager.class)).setMemoryInfo(memory);
            JapaneseDictionary dictionary = new JapaneseDictionary(activity);
            assertFalse(dictionary.installed());
            try (LocalRomanizer.Batch batch = new LocalRomanizer().new Batch(activity, dictionary, true)) {
                assertEquals("hitori ni natte", batch.read("一人になって", "ja").text);
            }
            assertFalse(dictionary.installed());
            AppSettings prefs = new AppSettings(activity);
            assertEquals("auto", Lyrics.preferredProvider(prefs));
            Locale saved = Locale.getDefault();
            try {
                Locale.setDefault(Locale.forLanguageTag("ms-MY"));
                assertEquals("ms", SettingsView.lyricTarget(prefs));
                prefs.set("lyricTarget", "id");
                assertEquals("id", SettingsView.lyricTarget(prefs));
            } finally {
                Locale.setDefault(saved);
            }
        }
    }

    @Test public void providerWordsKeepTheirOwnIntervalsAndOffsets() throws Exception {
        Lyrics lyrics = TtmlLyrics.parse("<tt xmlns='http://www.w3.org/ns/ttml'><body>"
                + "<p begin='1.0' end='4.0'> <span begin='1.0' end='2.0'>one</span> "
                + "<span begin='2s' end='3s'>two</span> <span begin='3s'>plain</span></p>"
                + "</body></tt>");
        Lyrics.Line line = lyrics.lines.get(0);
        assertEquals("one two plain", line.text);
        assertEquals(4000, line.end);
        assertEquals(0, lyrics.activeAt(3999));
        assertEquals(-1, lyrics.activeAt(4000));
        assertEquals(2, line.words.size());
        Lyrics.Word two = line.words.get(1);
        assertEquals("two", line.text.substring(two.from, two.to));
        assertEquals(2000, two.start);
        assertEquals(3000, two.end);
        try (org.robolectric.android.controller.ActivityController<Activity> controller =
                     Robolectric.buildActivity(Activity.class).setup()) {
            KaraokeLineView view = new KaraokeLineView(controller.get(), line.words, Color.YELLOW);
            view.setText(line.text);
            view.position(2500, true);
            java.lang.reflect.Field fraction = KaraokeLineView.class.getDeclaredField("fraction");
            fraction.setAccessible(true);
            assertEquals(0.5f, fraction.getFloat(view), 0.001f);
            view.position(1500, false);
            assertEquals(1f, fraction.getFloat(view), 0.001f);
        }
    }

    @Test public void typographyCanMatchButLiveVersionCannot() throws Exception {
        JSONArray rows = new JSONArray("[{\"trackName\":\"Ｓｏｎｇ — Demo\",\"artistName\":\"Ａｒｔｉｓｔ\","
                + "\"duration\":240,\"syncedLyrics\":\"[00:01]line\"}]");
        assertNotNull(Lyrics.bestMatch(rows, "Song - Demo", "Artist", 240000));
        assertNull(Lyrics.bestMatch(rows, "Song - Demo (Live)", "Artist", 240000));
    }
}
