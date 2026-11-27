// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.LinearLayout;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public final class AdaptiveUiTest {
    @Test public void landscapeMatrixKeepsSeekAndPrimaryControlsInBounds() {
        int[][] windows = {{800,480,160},{1024,600,160},{1280,720,160},{1280,800,160},
                {1920,720,240},{1920,1080,240},{2000,1200,320},{2560,1440,320},{800,480,320},{640,360,160}};
        for (int[] physical : windows) {
            for (String sidebar : new String[]{"rail", "expanded"}) {
                float d = physical[2] / 160f;
                int w = (int) (physical[0] / d), h = (int) (physical[1] / d);
                RuntimeEnvironment.setQualifiers("w" + w + "dp-h" + h + "dp-land-" + physical[2] + "dpi");
                AppSettings prefs = new AppSettings(RuntimeEnvironment.getApplication());
                prefs.set("sidebar", sidebar);
                prefs.set("zoom", physical[0] == 640 ? 150 : 100);
                Bundle state = new Bundle();
                state.putString("page", "Account");
                try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).create(state)) {
                    MainActivity activity = controller.get();
                    View root = activity.findViewById(android.R.id.content);
                    layout(root, physical[0], physical[1]);
                    for (String label : new String[]{"Home","Liked Music","Previous","Play","Next","Track progress","Playback options"}) {
                        View control = described(root, label);
                        assertNotNull(label, control);
                        assertTrue(label + " has a short hit area at " + w, control.getHeight() >= Math.round(48 * d));
                        Rect bounds = new Rect(0, 0, control.getWidth(), control.getHeight());
                        ((ViewGroup) root).offsetDescendantRectToMyCoords(control, bounds);
                        // Navigation items in their scroll container can start below its viewport.
                        if (!"Liked Music".equals(label)) assertTrue(label + " outside window " + bounds, bounds.left >= 0
                                && bounds.right <= root.getWidth() && bounds.bottom <= root.getHeight());
                    }
                    if (w >= 700) assertTrue(described(root, "Track progress").getWidth() >= Math.round(100 * d));
                }
            }
        }
    }

    @Test @Config(sdk = {27, 31, 35}, qualifiers = "w1024dp-h600dp-land-mdpi")
    public void profileSwitchKeepsTheActivityAndQueueAndPersistsIndependently() throws Exception {
        Bundle state = new Bundle();
        state.putString("page", "Account");
        state.putString("settingsTab", "Performance");
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).create(state)) {
            org.robolectric.android.controller.ServiceController<PlaybackService> service =
                    Robolectric.buildService(PlaybackService.class).create();
            try {
                MainActivity activity = controller.get();
                View root = activity.findViewById(android.R.id.content);
                service.get().enqueue(new MusicApi.Track("fixture-001", "One song", "Artist", "", ""), false);
                Field playback = MainActivity.class.getDeclaredField("playback");
                playback.setAccessible(true);
                playback.set(activity, service.get());
                AppSettings prefs = new AppSettings(activity);
                assertEquals("AUTO", prefs.text("performance", "AUTO"));
                prefs.set("layoutDensity", "large");
                prefs.set("performance", "ULTRA_LITE");
                Method setting = MainActivity.class.getDeclaredMethod("settingsChanged", String.class);
                setting.setAccessible(true);
                setting.invoke(activity, "performance");
                assertSame(root, activity.findViewById(android.R.id.content));
                assertSame(service.get(), playback.get(activity));
                assertEquals("One song", service.get().queueSnapshot().get(0).title);
                assertEquals("ULTRA_LITE", new AppSettings(activity).text("performance", "AUTO"));
                assertEquals("large", new AppSettings(activity).text("layoutDensity", "auto"));
                assertNotNull(text(root, "Ultra Lite"));
            } finally {
                service.destroy();
            }
        }
    }

    @Test @Config(qualifiers = "w1024dp-h600dp-land-mdpi")
    public void longCollectionsOnlyBindVisibleRows() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            AtomicInteger virtual = new AtomicInteger();
            List<Supplier<View>> items = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                int position = i;
                items.add(() -> {
                    virtual.incrementAndGet();
                    TextView row = new TextView(controller.get());
                    row.setMinHeight(48);
                    row.setText("Track " + position);
                    return row;
                });
            }
            LinearLayout eager = new LinearLayout(controller.get());
            eager.setOrientation(LinearLayout.VERTICAL);
            for (Supplier<View> item : items) eager.addView(item.get());
            int eagerCount = virtual.getAndSet(0);
            PageRows rows = new PageRows(controller.get());
            controller.get().setContentView(rows);
            rows.show(items, 0, 0);
            layout(rows, 900, 448);
            int mounted = virtual.get();
            assertEquals(1000, eagerCount);
            assertTrue(mounted >= 2 && mounted < 30);
            rows.setSelectionFromTop(500, 0);
            layout(rows, 900, 448);
            assertEquals(500, rows.getFirstVisiblePosition());
            assertNotNull(text(rows, "Track 500"));
            assertTrue(virtual.get() < 60);
            System.out.println("Logical browse workload, 1,000 rows: eager=" + eagerCount
                    + " boundInitially=" + mounted + " boundAfterJump=" + virtual.get());
        }
    }

    @Test public void artworkSizingHonorsTargetAndKeepsAspectRatio() {
        assertEquals(96, Artwork.requestSize(82, 256));
        assertEquals(128, Artwork.requestSize(400, 128));
        assertEquals("https://lh3.googleusercontent.com/test=w160-h80",
                Artwork.resizedUrl("https://lh3.googleusercontent.com/test=w800-h400", 160, false));
        assertEquals("https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg",
                Artwork.resizedUrl("https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg", 384, true));
    }

    static void layout(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    static View described(View view, String value) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (value.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) {
            for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++) {
                View child = described(((ViewGroup) view).getChildAt(index), value);
                if (child != null) return child;
            }
        }
        return null;
    }

    static View text(View view, String value) {
        if (view instanceof TextView && ((TextView) view).getText().toString().startsWith(value)) return view;
        if (view instanceof ViewGroup) {
            for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++) {
                View child = text(((ViewGroup) view).getChildAt(index), value);
                if (child != null) return child;
            }
        }
        return null;
    }
}
