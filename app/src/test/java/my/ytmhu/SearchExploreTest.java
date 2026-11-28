// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.widget.AutoCompleteTextView;
import android.widget.FrameLayout;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 27)
public final class SearchExploreTest {
    @Test public void keepsTermsAndIdentifiedProviderHitsWithoutAccountHistory() throws Exception {
        JSONObject artist = new JSONObject().put("navigationEndpoint", new JSONObject().put("browseEndpoint",
                new JSONObject().put("browseId", "UCabcdefghijklmnopqrstuv"))).put("flexColumns", new JSONArray()
                .put(new JSONObject().put("musicResponsiveListItemFlexColumnRenderer", new JSONObject().put("text", label("Artist")))));
        JSONObject page = suggestions(new JSONArray().put(suggestion("active term")).put(suggestion("active term"))
                .put(new JSONObject().put("historySuggestionRenderer", new JSONObject().put("suggestion", label("private history"))))
                .put(new JSONObject().put("musicResponsiveListItemRenderer", artist)));
        List<Browse.Suggestion> items = Browse.searchSuggestions(page, false);
        assertEquals(2, items.size());
        assertEquals("active term", items.get(0).title);
        assertNull(items.get(0).card);
        assertEquals("UCabcdefghijklmnopqrstuv", items.get(1).card.browseId);
    }

    @Test public void typedSuggestionsCoalesceAndNeverShowAnOlderResult() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            AppSettings prefs = new AppSettings(activity);
            AutoCompleteTextView input = new AutoCompleteTextView(activity);
            FrameLayout root = new FrameLayout(activity);
            root.addView(input, new FrameLayout.LayoutParams(300, 48));
            activity.setContentView(root);
            FrameLayout.LayoutParams anchored = (FrameLayout.LayoutParams) input.getLayoutParams();
            anchored.topMargin = 30;
            input.setLayoutParams(anchored);
            assertTrue(input.requestFocus());
            CountDownLatch ready = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            try (SearchSuggestions control = new SearchSuggestions(input, prefs, new Appearance(activity, prefs), inputTerm -> {
                if (calls.incrementAndGet() == 1) {
                    ready.countDown();
                    release.await(2, TimeUnit.SECONDS);
                }
                return suggestions(new JSONArray().put(suggestion(inputTerm + " result")));
            }, selected -> {})) {
                input.setText("original");
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
                assertTrue(ready.await(2, TimeUnit.SECONDS));
                input.setText("current");
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
                assertEquals(1, calls.get());
                assertNull(input.getAdapter());
                release.countDown();
                ExecutorService worker = ReflectionHelpers.getField(control, "worker");
                worker.submit(() -> {}).get(2, TimeUnit.SECONDS);
                shadowOf(Looper.getMainLooper()).idle();
                worker.submit(() -> {}).get(2, TimeUnit.SECONDS);
                shadowOf(Looper.getMainLooper()).idle();
                assertEquals(2, calls.get());
                assertEquals("current result", input.getAdapter().getItem(0).toString());
                control.clear();
                assertFalse(input.isPopupShowing());
            } finally {
                release.countDown();
            }
        }
    }

    @Test public void onlyRealMoodEndpointsBecomeRoutesAndBackReturnsToTheCategories() throws Exception {
        JSONArray items = new JSONArray().put(category("Indie", "FEmusic_moods_and_genres_category", "good%3D"))
                .put(category("Other", "FEmusic_liked_videos", "other"))
                .put(category("Invalid", "FEmusic_moods_and_genres_category", "https://wrong.example"));
        JSONObject grid = new JSONObject().put("header", new JSONObject().put("gridHeaderRenderer",
                new JSONObject().put("title", label("Genres")))).put("items", items);
        JSONObject page = new JSONObject().put("sectionListRenderer", new JSONObject().put("contents",
                new JSONArray().put(new JSONObject().put("gridRenderer", grid))));
        List<Browse.MoodGroup> grouped = Browse.genreGroups(page);
        assertEquals(1, grouped.size());
        assertEquals(1, grouped.get(0).choices.size());
        Browse.Mood entry = grouped.get(0).choices.get(0);
        assertEquals("Indie", entry.title);
        BrowseHistory history = new BrowseHistory();
        history.visit(BrowseHistory.Route.publicRoute("Genres", "", "", "", "", Browse.Search.All));
        history.visit(BrowseHistory.Route.publicRoute("Genre", "", "", "", entry.params, Browse.Search.All));
        assertEquals("Genres", history.move(-1).page);
        assertEquals("good%3D", history.move(1).mood);
        assertTrue(Browse.genreGroups(new JSONObject()).isEmpty());
    }

    private static JSONObject suggestions(JSONArray items) throws Exception {
        return new JSONObject().put("contents", new JSONArray().put(new JSONObject().put("searchSuggestionsSectionRenderer",
                new JSONObject().put("contents", items))));
    }

    private static JSONObject suggestion(String text) throws Exception {
        return new JSONObject().put("searchSuggestionRenderer", new JSONObject().put("suggestion", label(text)));
    }

    private static JSONObject category(String title, String endpoint, String params) throws Exception {
        return new JSONObject().put("musicNavigationButtonRenderer", new JSONObject().put("buttonText", label(title))
                .put("clickCommand", new JSONObject().put("browseEndpoint", new JSONObject().put("browseId", endpoint).put("params", params))));
    }

    private static JSONObject label(String text) throws Exception {
        return new JSONObject().put("runs", new JSONArray().put(new JSONObject().put("text", text)));
    }
}
