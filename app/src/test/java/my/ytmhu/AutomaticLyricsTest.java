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
                assertEquals("en", prefs.lyricTarget());
                prefs.set("lyricTarget", "auto");
                assertEquals("ms", new AppSettings(activity).lyricTarget());
                prefs.set("lyricTarget", "id");
                assertEquals("id", new AppSettings(activity).lyricTarget());
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
            view.position(2500);
            assertEquals(1, view.activeSpan());
            assertEquals(50, view.fillPercent());
            view.position(1250);
            assertEquals(0, view.activeSpan());
            assertEquals(25, view.fillPercent());
            view.position(999);
            assertEquals(-1, view.activeSpan());
            assertEquals(0, view.fillPercent());
        }
    }

    @Test public void enhancedLrcKeepsOnlyExplicitInOrderWordIntervals() {
        java.util.List<Lyrics.Line> lines = Lyrics.parse("[offset:+100]\n"
                + "[00:01.0]<00:01.00>今<00:01.70> 日<00:02.10> text\n"
                + "[00:03.00]<00:03.70>bad<00:03.50> order\n"
                + "[00:04.00]<00:04.00>too long<00:06.00>\n"
                + "[00:05.00][00:07.00]<00:05.00>repeat<00:05.50>");
        assertEquals(5, lines.size());
        Lyrics.Line first = lines.get(0);
        assertEquals("今 日 text", first.text);
        assertEquals(900, first.millis);
        assertEquals(2, first.words.size());
        Lyrics.Word second = first.words.get(1);
        assertEquals(1600, second.start);
        assertEquals(2000, second.end);
        assertEquals("日", first.text.substring(second.from, second.to));
        assertEquals("bad order", lines.get(1).text);
        for (int i = 1; i < lines.size(); i++) assertTrue(lines.get(i).words.isEmpty());
    }

    @Test public void typographyCanMatchButLiveVersionCannot() throws Exception {
        JSONArray rows = new JSONArray("[{\"trackName\":\"Ｓｏｎｇ — Demo\",\"artistName\":\"Ａｒｔｉｓｔ\","
                + "\"duration\":240,\"syncedLyrics\":\"[00:01]line\"}]");
        assertNotNull(Lyrics.bestMatch(rows, "Song - Demo", "Artist", 240000));
        assertNull(Lyrics.bestMatch(rows, "Song - Demo (Live)", "Artist", 240000));
    }
}
