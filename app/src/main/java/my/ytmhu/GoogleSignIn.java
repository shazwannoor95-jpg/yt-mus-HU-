// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
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

import java.net.URI;
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

    static boolean trustedLoginPage(String location) {
        URI url;
        try {
            url = URI.create(location);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        if (!"https".equals(url.getScheme()) || (url.getPort() != -1 && url.getPort() != 443)
                || url.getRawUserInfo() != null) return false;
        String host = url.getHost();
        return "accounts.google.com".equals(host) || "myaccount.google.com".equals(host)
                || "accounts.youtube.com".equals(host) || "music.youtube.com".equals(host)
                || "www.youtube.com".equals(host) || "youtube.com".equals(host);
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

        WebView web = new WebView(activity);
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
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                if (trustedLoginPage(request.getUrl().toString())) return false;
                status.setText("This sign-in step could not open in Android WebView.");
                status.setVisibility(View.VISIBLE);
                return true;
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (!request.isForMainFrame()) return;
                status.setText("Google sign-in page could not load. Close and try again.");
                status.setVisibility(View.VISIBLE);
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
        window.setLayout(activity.getResources().getDisplayMetrics().widthPixels * 55 / 100, -1);
        web.loadUrl(GOOGLE_LOGIN);
        return dialog;
    }
}
