// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.app.Dialog;
import android.net.Uri;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.TextView;
import android.view.View;

import java.util.Locale;
import java.util.regex.Pattern;
import java.util.List;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;

final class GoogleSignIn {
    private static final String GOOGLE_LOGIN = "https://accounts.google.com/ServiceLogin"
            + "?service=youtube&continue=https%3A%2F%2Fmusic.youtube.com%2F";

    private static boolean signingOut;

    private GoogleSignIn() {}

    static String cookies() {
        try {
            String value = CookieManager.getInstance().getCookie(MusicApi.MUSIC + "/");
            return value == null ? "" : value;
        } catch (RuntimeException unavailable) {
            return "";
        }
    }

    static synchronized void signOut(Runnable done) {
        signingOut = true;
        CookieManager jar = CookieManager.getInstance();
        jar.removeAllCookies(removed -> {
            synchronized (GoogleSignIn.class) {
                jar.flush();
                signingOut = false;
            }
            done.run();
        });
    }

    static synchronized String updateCookies(String previous, HttpsURLConnection connection) {
        CookieManager jar = CookieManager.getInstance();
        if (signingOut || !previous.equals(cookies())) return null;
        boolean changed = false;
        for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
            if (!"Set-Cookie".equalsIgnoreCase(header.getKey())) continue;
            for (String value : header.getValue()) {
                jar.setCookie(connection.getURL().toString(), value);
                changed = true;
            }
        }
        if (!changed) return null;
        jar.flush();
        return cookies();
    }

    private static final Pattern TRUSTED_ORIGIN = Pattern.compile(
            "^https://(?:accounts\\.google\\.com|myaccount\\.google\\.com|accounts\\.youtube\\.com|"
                    + "music\\.youtube\\.com|www\\.youtube\\.com|youtube\\.com)(?::443)?(?=[/?#]|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern UNSAFE = Pattern.compile("[\\p{Cntrl}\\\\]");

    static boolean trustedLoginPage(String location) {
        // Chromium can accept first-party URLs that java.net.URI rejects because
        // of literal characters in a query. Validate the origin on its own.
        return location != null && !UNSAFE.matcher(location).find() && TRUSTED_ORIGIN.matcher(location).find();
    }

    static boolean isBlankNavigation(String location) {
        return "about:blank".equals(location);
    }

    static String challengeType(String scheme) {
        switch (scheme == null ? "" : scheme.toLowerCase(Locale.ROOT)) {
            case "intent": return "Android app handoff";
            case "https": return "unrecognized secure site";
            case "http": return "unencrypted navigation";
            case "about": return "internal browser page";
            case "": return "navigation without a scheme";
            default: return "device verification link";
        }
    }

    static String trustedFallback(String candidate) {
        return trustedLoginPage(candidate) ? candidate : null;
    }

    private static String intentFallback(String location) {
        try {
            // Use only a first-party HTTPS fallback. Do not launch the supplied intent.
            return trustedFallback(Intent.parseUri(location, Intent.URI_INTENT_SCHEME)
                    .getStringExtra("browser_fallback_url"));
        } catch (java.net.URISyntaxException | IllegalArgumentException invalid) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    static Dialog show(Activity activity, Runnable verify) {
        Dialog dialog = new Dialog(activity);
        dialog.setTitle("Sign in with Google");
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        LinearLayout actions = new LinearLayout(activity);
        Button close = new Button(activity);
        close.setText("Close");
        close.setOnClickListener(view -> dialog.dismiss());
        actions.addView(close, new LinearLayout.LayoutParams(0, -2, 1));
        Button browser = new Button(activity);
        browser.setText("Open separately");
        browser.setOnClickListener(view -> new android.app.AlertDialog.Builder(activity)
                .setMessage("Open YouTube Music separately. Sign-in there does not link this app's account.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open", (prompt, which) -> {
                    try {
                        activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(MusicApi.MUSIC + "/")));
                    } catch (ActivityNotFoundException unavailable) {
                        new android.app.AlertDialog.Builder(activity).setMessage("No browser or YouTube Music app is installed.")
                                .setPositiveButton("Close", null).show();
                    }
                }).show());
        actions.addView(browser, new LinearLayout.LayoutParams(0, -2, 1));
        panel.addView(actions);
        TextView status = new TextView(activity);
        status.setPadding(12, 4, 12, 8);
        status.setVisibility(View.GONE);
        panel.addView(status);
        LinearLayout recovery = new LinearLayout(activity);
        recovery.setVisibility(View.GONE);
        panel.addView(recovery);

        WebView web = new WebView(activity);
        Button back = new Button(activity);
        back.setText("Back");
        back.setOnClickListener(view -> {
            recovery.setVisibility(View.GONE);
            status.setVisibility(View.GONE);
            if (web.canGoBack()) web.goBack();
            else web.loadUrl(GOOGLE_LOGIN);
        });
        recovery.addView(back, new LinearLayout.LayoutParams(0, -2, 1));
        Button continuation = new Button(activity);
        continuation.setText("Continue");
        continuation.setVisibility(View.GONE);
        recovery.addView(continuation, new LinearLayout.LayoutParams(0, -2, 1));
        Button copy = new Button(activity);
        copy.setText("Copy diagnostic");
        recovery.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager jar = CookieManager.getInstance();
        jar.setAcceptCookie(true);
        // Google and YouTube are separate sites in this dedicated sign-in WebView.
        jar.setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            private void clearMessage() {
                status.setVisibility(View.GONE);
                recovery.setVisibility(View.GONE);
                continuation.setOnClickListener(null);
            }

            private void showMessage(String message, String code, String fallback) {
                status.setText(message);
                status.setVisibility(View.VISIBLE);
                recovery.setVisibility(View.VISIBLE);
                continuation.setVisibility(fallback == null ? View.GONE : View.VISIBLE);
                continuation.setOnClickListener(button -> {
                    clearMessage();
                    web.loadUrl(fallback);
                });
                copy.setOnClickListener(button -> {
                    ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
                    clipboard.setPrimaryClip(ClipData.newPlainText("YTM login diagnostic",
                            "YTM " + BuildVersion.VERSION + " | Android API " + android.os.Build.VERSION.SDK_INT + " | " + code));
                });
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                Uri url = request.getUrl();
                String location = url.toString();
                if (isBlankNavigation(location)) return false;
                if (trustedLoginPage(location)) {
                    clearMessage();
                    return false;
                }
                String scheme = url.getScheme();
                String kind = challengeType(scheme);
                String fallback = "intent".equalsIgnoreCase(scheme) ? intentFallback(location) : null;
                if (fallback != null && fallback.equals(view.getUrl())) fallback = null;
                showMessage(fallback == null
                                ? "Google verification could not continue here. Go back and choose Try another way on Google’s page."
                                : "Google supplied a browser step. Tap Continue to open it here.",
                        kind + (fallback == null ? " | no in-app continuation" : " | HTTPS continuation"), fallback);
                return true;
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (!request.isForMainFrame()) return;
                showMessage("Google sign-in page could not load. Go back and try again.",
                        "WebView error " + error.getErrorCode(), null);
            }

            @Override public void onPageFinished(WebView view, String url) {
                Uri page = Uri.parse(url);
                if (dialog.isShowing() && "https".equals(page.getScheme()) && "music.youtube.com".equals(page.getHost())
                        && MusicApi.hasSession(cookies())) {
                    jar.flush();
                    dialog.dismiss();
                    verify.run();
                }
            }
        });
        panel.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        dialog.setContentView(panel);
        dialog.setOnDismissListener(ignored -> {
            panel.removeView(web);
            web.destroy();
        });
        dialog.show();
        Window window = dialog.getWindow();
        window.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        window.setDimAmount(0.20f);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        int pixels = activity.getResources().getDisplayMetrics().widthPixels;
        int minimum = (int) (480 * activity.getResources().getDisplayMetrics().density);
        window.setLayout(Math.min(pixels, Math.max(minimum, pixels * 62 / 100)), -1);
        web.loadUrl(GOOGLE_LOGIN);
        return dialog;
    }
}
