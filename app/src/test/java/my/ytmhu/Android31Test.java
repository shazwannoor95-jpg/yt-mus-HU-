// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Dialog;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public final class Android31Test {
    @Test public void routesRegionalLoginAndBlocksExternalHandoffs() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Dialog dialog = GoogleSignIn.show(activity, () -> {});
            View decor = dialog.getWindow().getDecorView();
            WebView web = findWebView(decor);

            assertEquals(31, Build.VERSION.SDK_INT);
            assertFalse(web.getWebViewClient().shouldOverrideUrlLoading(web,
                    request("https://accounts.google.com.my/v3/signin/challenge", true)));
            assertTrue(web.getWebViewClient().shouldOverrideUrlLoading(web,
                    request("https://verification.example.com.my/?otp=654321", true)));
            assertTrue(web.getWebViewClient().shouldOverrideUrlLoading(web, request(
                    "intent://example.com/#Intent;scheme=https;package=com.example.app;end", true)));
            assertEquals(View.GONE, text(decor, "Continue").getVisibility());
            assertNull(shadowOf(activity).getNextStartedActivity());

            assertTrue(web.getWebViewClient().shouldOverrideUrlLoading(web, request(
                    "intent://example.com/#Intent;scheme=https;"
                            + "S.browser_fallback_url=https%3A%2F%2Faccounts.google.com.my%2Fsignin;end", true)));
            assertEquals(View.VISIBLE, text(decor, "Continue").getVisibility());
            text(decor, "Continue").performClick();
            assertEquals("https://accounts.google.com.my/signin", shadowOf(web).getLastLoadedUrl());
            text(decor, "Choose account").performClick();
            assertTrue(shadowOf(web).getLastLoadedUrl().startsWith("https://accounts.google.com/AccountChooser?"));
            dialog.dismiss();
        }
    }

    @Test public void cancelAndFailedPageDoNotCompleteSignIn() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            AtomicInteger verified = new AtomicInteger();
            Dialog dialog = GoogleSignIn.show(controller.get(), verified::incrementAndGet);
            WebView web = findWebView(dialog.getWindow().getDecorView());
            assertFalse(MusicApi.hasSession(GoogleSignIn.cookies()));
            CookieManager.getInstance().setCookie(MusicApi.MUSIC + "/", "SAPISID=test_session; Secure");
            web.getWebViewClient().shouldOverrideUrlLoading(web, request("https://example.com.my/", true));
            web.getWebViewClient().onPageFinished(web, MusicApi.MUSIC + "/");
            assertTrue(dialog.isShowing());
            dialog.cancel();
            shadowOf(Looper.getMainLooper()).idle();
            assertFalse(dialog.isShowing());
            assertEquals(0, verified.get());
        }
    }

    @Test public void largeArtworkUsesSupportedCdnSizes() {
        assertEquals("https://lh3.googleusercontent.com/a=w640-h640-l90-rj",
                Artwork.detailUrl("https://lh3.googleusercontent.com/a=w120-h120-l90-rj"));
        assertEquals("https://lh3.googleusercontent.com/a=w640-h320",
                Artwork.detailUrl("https://lh3.googleusercontent.com/a=w200-h100"));
        assertEquals("https://i.ytimg.com/vi/abcdefghijk/maxresdefault.jpg",
                Artwork.detailUrl("https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg"));
        assertEquals("https://images.example/a=s120", Artwork.detailUrl("https://images.example/a=s120"));
    }

    @Test public void appWebViewKeepsItsSessionAcrossDialogsAndClearsOnSignOut() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            AtomicInteger verified = new AtomicInteger();
            Dialog first = GoogleSignIn.show(controller.get(), verified::incrementAndGet);
            WebView web = findWebView(first.getWindow().getDecorView());
            CookieManager.getInstance().setCookie(MusicApi.MUSIC + "/", "SAPISID=test_session; Secure");
            web.getWebViewClient().onPageFinished(web, "https://music.youtube.com.evil.example/");
            assertTrue(first.isShowing());
            web.getWebViewClient().onPageFinished(web, MusicApi.MUSIC + "/");
            assertFalse(first.isShowing());
            assertEquals(1, verified.get());

            Dialog reopened = GoogleSignIn.show(controller.get(), verified::incrementAndGet);
            assertTrue(MusicApi.hasSession(GoogleSignIn.cookies()));
            reopened.cancel();
            GoogleSignIn.signOut(() -> {});
            shadowOf(Looper.getMainLooper()).idle();
            assertFalse(MusicApi.hasSession(GoogleSignIn.cookies()));
            assertEquals(1, verified.get());
        }
    }

    @Test public void copyDiagnosticOnlyUsesHostAndSchemeFromTheUrl() {
        String diagnostic = GoogleSignIn.diagnostic("12", 31, "130.0.1", "available",
                "unavailable", Uri.parse("https://fixture.example.com.my/secretPath?code=654321#secretToken"),
                "blocked", "unrecognized secure site");
        assertTrue(diagnostic.contains("Android: 12 (API 31)"));
        assertTrue(diagnostic.contains("Android System WebView: 130.0.1"));
        assertTrue(diagnostic.contains("Browser: available"));
        assertTrue(diagnostic.contains("Google Play Services: unavailable"));
        assertTrue(diagnostic.contains("Mechanism: YouTube Music session in app WebView"));
        assertTrue(diagnostic.contains("Hostname: fixture.example.com.my"));
        assertTrue(diagnostic.contains("Redirect scheme: https"));
        assertFalse(diagnostic.contains("654321"));
        assertFalse(diagnostic.contains("secretPath"));
        assertFalse(diagnostic.contains("secretToken"));
    }

    @Test public void queueAndNowPlayingControlsStillWorkOnApi31() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            ServiceController<PlaybackService> playback = Robolectric.buildService(PlaybackService.class).create();
            Activity activity = controller.get();
            AppSettings prefs = new AppSettings(activity);
            Artwork artwork = new Artwork();
            NowPlayingView player = new NowPlayingView(activity, prefs, new Appearance(activity, prefs),
                    artwork, (anchor, track, index) -> {}, () -> {});
            try {
                PlaybackService service = playback.get();
                service.enqueue(new MusicApi.Track("fixture-one", "First song", "Artist", "", ""), false);
                service.enqueue(new MusicApi.Track("fixture-two", "Second song", "Artist", "", ""), false);
                service.moveAt(1, -1);
                assertTrue(service.canPlay());
                assertEquals("Second song", service.queueSnapshot().get(0).title);

                player.show(service, false);
                Button lyrics = (Button) text(player, "Lyrics");
                Button queue = (Button) text(player, "Up next");
                assertTrue(queue.isSelected());
                assertEquals("Second song", text(player, "Second song").getText().toString());
                lyrics.performClick();
                assertTrue(lyrics.isSelected());
                queue.performClick();
                assertTrue(queue.isSelected());
            } finally {
                player.close();
                artwork.close();
                playback.destroy();
            }
        }
    }

    @Test @Config(qualifiers = "w640dp-h360dp-land")
    public void compactLandscapeKeepsMiniPlayerAndVolumeReachable() {
        Bundle state = new Bundle();
        state.putString("page", "Account");
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).create(state)) {
            MainActivity activity = controller.get();
            View root = activity.findViewById(android.R.id.content);
            View mini = described(root, "Mini player");
            View cover = described(root, "Open Now Playing");
            View seek = described(root, "Mini player track progress");
            assertNull(described(root, "Audio output"));
            assertTrue(described(root, "Media volume").isEnabled());
            assertEquals(View.GONE, ((View) seek.getParent()).getVisibility());
            mini.performClick();
            root.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 640, 360);
            View panel = (View) seek.getParent();
            assertEquals(View.VISIBLE, panel.getVisibility());
            assertTrue(panel.getWidth() > 0 && panel.getWidth() < root.getWidth());
            assertTrue(cover.getMeasuredWidth() > 0);
            assertEquals(cover.getMeasuredWidth(), cover.getMeasuredHeight());
            activity.onBackPressed();
            assertEquals(View.GONE, panel.getVisibility());
        }
    }

    private static WebResourceRequest request(String url, boolean mainFrame) {
        return new WebResourceRequest() {
            @Override public Uri getUrl() { return Uri.parse(url); }
            @Override public boolean isForMainFrame() { return mainFrame; }
            @Override public boolean isRedirect() { return true; }
            @Override public boolean hasGesture() { return false; }
            @Override public String getMethod() { return "GET"; }
            @Override public Map<String, String> getRequestHeaders() { return Collections.emptyMap(); }
        };
    }

    private static WebView findWebView(View root) {
        if (root instanceof WebView) return (WebView) root;
        if (root instanceof ViewGroup) {
            ViewGroup views = (ViewGroup) root;
            for (int i = 0; i < views.getChildCount(); i++) {
                WebView found = findWebView(views.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static TextView text(View root, String label) {
        if (root instanceof TextView && label.contentEquals(((TextView) root).getText())) return (TextView) root;
        if (root instanceof ViewGroup) {
            ViewGroup views = (ViewGroup) root;
            for (int i = 0; i < views.getChildCount(); i++) {
                TextView found = text(views.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View described(View root, String label) {
        if (label.equals(root.getContentDescription())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup views = (ViewGroup) root;
            for (int i = 0; i < views.getChildCount(); i++) {
                View found = described(views.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }
}
