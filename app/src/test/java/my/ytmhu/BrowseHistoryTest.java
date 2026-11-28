// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import static org.junit.Assert.*;

import android.os.Bundle;
import android.view.View;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 27, qualifiers = "w1024dp-h600dp-land-mdpi")
public final class BrowseHistoryTest {
    @Test public void restoresRouteScrollAndClearsForwardAfterNewDestination() {
        BrowseHistory history = new BrowseHistory();
        history.visit(route("Home", "", "", ""));
        BrowseHistory.Route search = BrowseHistory.Route.publicRoute("Search", "", "", "album", "", Browse.Search.Albums);
        history.visit(search);
        history.capture(9, -30);
        history.visit(route("Collection", "MPREb1234", "", ""));
        assertSame(search, history.move(-1));
        assertEquals(9, history.current().row);
        assertEquals(-30, history.current().offset);
        assertEquals(Browse.Search.Albums, history.current().scope);
        assertTrue(history.canMove(1));
        history.visit(BrowseHistory.Route.publicRoute("Search", "", "", "album", "", Browse.Search.Albums));
        assertTrue(history.canMove(1));
        history.visit(route("Explore", "", "", ""));
        assertFalse(history.canMove(1));
        assertSame(search, history.move(-1));
    }

    @Test public void limitsPublicHistoryAndClearsAtPrivateRoutes() {
        BrowseHistory history = new BrowseHistory();
        for (int i = 0; i < 40; i++) history.visit(route("Search", "", "query " + i, ""));
        for (int i = 0; i < 15; i++) assertNotNull(history.move(-1));
        assertEquals("query 24", history.current().query);
        assertFalse(history.canMove(-1));
        history.visit(route("Collection", "VLPL_private", "", ""));
        assertNull(history.current());
        assertFalse(history.canMove(1));
        assertNull(route("Library", "", "", ""));
    }

    @Test @SuppressWarnings("unchecked")
    public void wideToolbarBackAndForwardRestoreCachedPublicRoutes() throws Exception {
        Bundle state = new Bundle();
        state.putString("page", "Account");
        try (org.robolectric.android.controller.ActivityController<MainActivity> controller =
                     Robolectric.buildActivity(MainActivity.class).create(state)) {
            MainActivity activity = controller.get();
            Class<?> data = Class.forName("my.ytmhu.MainActivity$BrowsePage");
            Constructor<?> constructor = data.getDeclaredConstructor(List.class, List.class, List.class);
            constructor.setAccessible(true);
            Map<String, Object> pages = (Map<String, Object>) field("pages").get(activity);
            pages.put("Home\nfalse\nfalse\n", constructor.newInstance(Collections.emptyList(), Collections.emptyList(), null));
            pages.put("Explore\nfalse\nfalse", constructor.newInstance(Collections.emptyList(), Collections.emptyList(), null));
            Method navigate = MainActivity.class.getDeclaredMethod("navigate", String.class);
            navigate.setAccessible(true);
            navigate.invoke(activity, "Home");
            View back = (View) field("historyBack").get(activity);
            View forward = (View) field("historyForward").get(activity);
            assertFalse(back.isEnabled());
            navigate.invoke(activity, "Explore");
            assertTrue(back.isEnabled());
            assertFalse(forward.isEnabled());
            assertTrue(back.performClick());
            assertEquals("Home", field("page").get(activity));
            assertTrue(forward.isEnabled());
            assertTrue(forward.performClick());
            assertEquals("Explore", field("page").get(activity));
        }
    }

    private static BrowseHistory.Route route(String page, String id, String query, String mood) {
        return BrowseHistory.Route.publicRoute(page, id, "Fixture", query, mood, Browse.Search.All);
    }

    private static Field field(String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
