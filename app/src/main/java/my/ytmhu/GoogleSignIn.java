// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.app.Dialog;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
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
    private static final String YOUTUBE_RETURN = "?service=youtube&continue=https%3A%2F%2Fmusic.youtube.com%2F";
    private static final String GOOGLE_LOGIN = "https://accounts.google.com/ServiceLogin" + YOUTUBE_RETURN;
    private static final String ACCOUNT_CHOOSER = "https://accounts.google.com/AccountChooser" + YOUTUBE_RETURN;
    // Read only YouTube Music's request context, with no native JavaScript bridge.
    private static final String MUSIC_CONTEXT = "(function(){function read(k){"
            + "var v=window.ytcfg&&typeof window.ytcfg.get==='function'?window.ytcfg.get(k):undefined;"
            + "if(v==null&&window.yt&&window.yt.config_)v=window.yt.config_[k];"
            + "return typeof v==='number'||typeof v==='string'||typeof v==='boolean'?String(v):'';}"
            + "return {index:read('SESSION_INDEX'),sync:read('DATASYNC_ID')||read('datasyncId'),"
            + "visitor:read('VISITOR_DATA'),version:read('INNERTUBE_CLIENT_VERSION'),loggedIn:read('LOGGED_IN')};})()";

    private static boolean signingOut;
    private static String selectionCookies = "";
    private static WebSession selection = WebSession.EMPTY;

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
        selectionCookies = "";
        selection = WebSession.EMPTY;
        CookieManager jar = CookieManager.getInstance();
        jar.removeAllCookies(removed -> {
            synchronized (GoogleSignIn.class) {
                jar.flush();
                signingOut = false;
            }
            done.run();
        });
    }

    static synchronized WebSession selection(String cookies) {
        return !signingOut && selectionCookies.equals(cookies) ? selection : WebSession.EMPTY;
    }

    private static synchronized void rememberSelection(String cookies, WebSession context) {
        if (signingOut) return;
        selectionCookies = cookies;
        selection = context;
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
        String refreshed = cookies();
        if (selectionCookies.equals(previous)) selectionCookies = refreshed;
        return refreshed;
    }

    private static final Pattern HTTPS_ORIGIN = Pattern.compile(
            "\\Ahttps://([a-z0-9.-]+)(?::443)?(?=[/?#]|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNSAFE = Pattern.compile("[\\p{Cntrl}\\\\]");
    private static final String DNS_LABEL = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?";
    private static final Pattern DNS_NAME = Pattern.compile(DNS_LABEL + "(?:\\." + DNS_LABEL + ")*");
    private static final Pattern SCHEME = Pattern.compile("[a-z][a-z0-9+.-]{0,31}");
    private static final Pattern VERSION = Pattern.compile("[0-9][a-zA-Z0-9._+-]{0,63}");

    static boolean trustedLoginPage(String location) {
        // Validate the HTTPS origin independently of the query. Google's
        // WebView URLs sometimes contain literal characters in query values.
        if (location == null || UNSAFE.matcher(location).find()) return false;
        java.util.regex.Matcher origin = HTTPS_ORIGIN.matcher(location);
        if (!origin.find()) return false;
        String host = origin.group(1).toLowerCase(Locale.ROOT);
        switch (host) {
            case "accounts.google.com": case "myaccount.google.com": case "gds.google.com": case "accounts.youtube.com":
            case "consent.google.com": case "consent.youtube.com": case "signin.google.com":
            // Explicit login origins on Google domains listed in Chromium's google_tld_list.h.
            case "accounts.google.com.my": case "myaccount.google.com.my": case "consent.google.com.my":
            case "accounts.google.co.uk":
            case "music.youtube.com": case "www.youtube.com": case "youtube.com":
                return true;
            case "g.co": case "www.g.co":
                return authPath(location.substring(origin.end()), "/verifyaccount", "/signin", "/recover",
                        "/accountrecovery", "/2sv", "/passkeys", "/sc");
            case "www.google.com": case "google.com": case "www.google.com.my": case "google.com.my":
                return authPath(location.substring(origin.end()), "/accounts", "/signin", "/recaptcha", "/sorry");
            case "support.google.com": case "support.google.com.my":
                return authPath(location.substring(origin.end()), "/accounts");
            default:
                return false;
        }
    }

    private static boolean authPath(String remainder, String... accepted) {
        int stop = remainder.length();
        for (char delimiter : new char[]{'?', '#'}) {
            int index = remainder.indexOf(delimiter);
            if (index >= 0) stop = Math.min(stop, index);
        }
        String path = remainder.substring(0, stop);
        if (path.indexOf('%') >= 0 || path.indexOf(';') >= 0 || path.contains("..")) return false;
        for (String prefix : accepted) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) return true;
        }
        return false;
    }

    static String diagnosticHost(String host) {
        if (host == null) return "unavailable";
        String name = host.toLowerCase(Locale.ROOT);
        String dnsName = name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
        return name.length() <= 254 && DNS_NAME.matcher(dnsName).matches() ? name : "unavailable";
    }

    static String diagnosticScheme(String scheme) {
        if (scheme == null) return "unavailable";
        String name = scheme.toLowerCase(Locale.ROOT);
        return SCHEME.matcher(name).matches() ? name : "unavailable";
    }

    static boolean isMusicReturn(String location) {
        return trustedLoginPage(location) && "music.youtube.com".equalsIgnoreCase(Uri.parse(location).getHost());
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

    private static Intent browserIntent() {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(MusicApi.MUSIC + "/"))
                .addCategory(Intent.CATEGORY_BROWSABLE);
    }

    static void openSeparately(Activity activity) {
        new AlertDialog.Builder(activity)
                .setMessage("Open YouTube Music separately. Its browser session stays separate from this app.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open", (prompt, which) -> {
                    try {
                        activity.startActivity(browserIntent());
                    } catch (ActivityNotFoundException | SecurityException unavailable) {
                        new AlertDialog.Builder(activity).setMessage("No available browser or YouTube Music app.")
                                .setPositiveButton("Close", null).show();
                    }
                }).show();
    }

    private static String version(String value) {
        return value != null && VERSION.matcher(value).matches() ? value : "unavailable";
    }

    private static String webViewVersion() {
        try {
            PackageInfo provider = WebView.getCurrentWebViewPackage();
            return provider == null ? "unavailable" : version(provider.versionName);
        } catch (RuntimeException unavailable) {
            return "unavailable";
        }
    }

    static String webViewAdvice(String version) {
        String major = version.split("\\.", 2)[0];
        if (!major.matches("[0-9]{1,3}") || Integer.parseInt(major) >= 100) return "";
        return "Android System WebView " + version
                + " is old. Update the system WebView provider if your firmware offers an update, or use Open separately.";
    }

    static String webViewAdvice() { return webViewAdvice(webViewVersion()); }

    private static String playServices(Activity activity) {
        try {
            return activity.getPackageManager().getApplicationInfo("com.google.android.gms", 0).enabled
                    ? "installed" : "disabled";
        } catch (PackageManager.NameNotFoundException missing) {
            return "unavailable";
        }
    }

    private static String browserAvailability(Activity activity) {
        Intent probe = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/"))
                .addCategory(Intent.CATEGORY_BROWSABLE);
        return probe.resolveActivity(activity.getPackageManager()) == null ? "unavailable" : "available";
    }

    static String diagnostic(String android, int api, String webView, String browser,
                             String services, Uri navigation, String result, String detail) {
        return "YTM " + BuildVersion.VERSION
                + "\nAndroid: " + version(android) + " (API " + api + ")"
                + "\nAndroid System WebView: " + version(webView)
                + "\nBrowser: " + browser
                + "\nGoogle Play Services: " + services
                + "\nMechanism: YouTube Music session in app WebView"
                + "\nHostname: " + diagnosticHost(navigation.getHost())
                + "\nRedirect scheme: " + diagnosticScheme(navigation.getScheme())
                + "\nResult: " + result + "\nDetail: " + detail;
    }

    static void copyDiagnostic(Activity activity, Uri navigation, String result, String detail) {
        ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
        clipboard.setPrimaryClip(ClipData.newPlainText("YTM login diagnostic", diagnostic(
                Build.VERSION.RELEASE, Build.VERSION.SDK_INT, webViewVersion(), browserAvailability(activity),
                playServices(activity), navigation, result, detail)));
    }

    @SuppressWarnings("deprecation")
    static Dialog show(Activity activity, Runnable verify) {
        Dialog dialog = new Dialog(activity);
        dialog.setTitle("Sign in with Google");
        WebView web = new WebView(activity);
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        LinearLayout actions = new LinearLayout(activity);
        Button close = new Button(activity);
        close.setText("Close");
        close.setOnClickListener(view -> dialog.dismiss());
        actions.addView(close, new LinearLayout.LayoutParams(0, -2, 1));
        Button account = new Button(activity);
        account.setText("Choose account");
        account.setOnClickListener(view -> web.loadUrl(ACCOUNT_CHOOSER));
        actions.addView(account, new LinearLayout.LayoutParams(0, -2, 1));
        Button browser = new Button(activity);
        browser.setText("Open separately");
        browser.setOnClickListener(view -> openSeparately(activity));
        actions.addView(browser, new LinearLayout.LayoutParams(0, -2, 1));
        panel.addView(actions);
        TextView status = new TextView(activity);
        status.setPadding(12, 4, 12, 8);
        status.setVisibility(View.GONE);
        panel.addView(status);
        LinearLayout recovery = new LinearLayout(activity);
        recovery.setVisibility(View.GONE);
        panel.addView(recovery);

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
        Button restart = new Button(activity);
        restart.setText("Restart");
        restart.setOnClickListener(view -> web.loadUrl(GOOGLE_LOGIN));
        recovery.addView(restart, new LinearLayout.LayoutParams(0, -2, 1));
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(false);
        CookieManager jar = CookieManager.getInstance();
        jar.setAcceptCookie(true);
        // Google and YouTube are separate sites in this dedicated sign-in WebView.
        jar.setAcceptThirdPartyCookies(web, true);
        Handler main = new Handler(Looper.getMainLooper());
        web.setWebViewClient(new WebViewClient() {
            private boolean interrupted;
            private String mainPage = GOOGLE_LOGIN;
            private int pageRevision;
            private String pageLoginState = "";

            private void clearMessage() {
                interrupted = false;
                status.setVisibility(View.GONE);
                recovery.setVisibility(View.GONE);
                continuation.setOnClickListener(null);
                copy.setOnClickListener(null);
            }

            private void showMessage(String message, Uri navigation, String result, String detail, String fallback) {
                interrupted = true;
                String advice = webViewAdvice();
                status.setText(advice.isEmpty() ? message : message + "\n" + advice);
                status.setVisibility(View.VISIBLE);
                recovery.setVisibility(View.VISIBLE);
                continuation.setVisibility(fallback == null ? View.GONE : View.VISIBLE);
                continuation.setOnClickListener(fallback == null ? null : button -> {
                    clearMessage();
                    web.loadUrl(fallback);
                });
                copy.setOnClickListener(button -> copyDiagnostic(activity, navigation, result, detail));
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                Uri url = request.getUrl();
                String location = url.toString();
                if (isBlankNavigation(location)) return false;
                if (trustedLoginPage(location)) {
                    mainPage = location;
                    return false;
                }
                reject(view, url);
                return true;
            }

            private void reject(WebView view, Uri url) {
                String scheme = url.getScheme();
                String fallback = "intent".equalsIgnoreCase(scheme) ? intentFallback(url.toString()) : null;
                if (fallback != null && fallback.equals(view.getUrl())) fallback = null;
                showMessage(fallback == null
                                ? "Google verification could not continue here. Go back and choose Try another way on Google’s page."
                                : "Google supplied a browser step. Tap Continue to open it here.",
                        url, fallback == null ? "blocked; no in-app continuation" : "trusted HTTPS fallback available",
                        challengeType(scheme), fallback);
            }

            @Override public void onPageStarted(WebView view, String location, Bitmap icon) {
                ++pageRevision;
                if (location == null || isBlankNavigation(location)) return;
                if (trustedLoginPage(location)) {
                    mainPage = location;
                    clearMessage();
                } else {
                    view.stopLoading();
                    reject(view, Uri.parse(location));
                }
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (!request.isForMainFrame()) return;
                showMessage("Google sign-in page could not load. Go back or restart sign-in.",
                        request.getUrl(), "load failed", "WebView error " + error.getErrorCode(), null);
            }

            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (!request.isForMainFrame()) return;
                showMessage("Google sign-in did not complete. Restart sign-in or try another verification method.",
                        request.getUrl(), "HTTP error", "HTTP status " + response.getStatusCode(), null);
            }

            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                String location = error.getUrl();
                if (location == null || !(location.equals(mainPage) || location.equals(view.getUrl()))) return;
                showMessage("A secure connection could not be established. Restart sign-in to retry.",
                        Uri.parse(location), "TLS connection cancelled",
                        "Certificate error " + error.getPrimaryError(), null);
            }

            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                Uri page = Uri.parse(mainPage);
                String reason = detail.didCrash() ? "WebView renderer crashed" : "WebView renderer stopped";
                dialog.dismiss();
                if (!activity.isFinishing() && !activity.isDestroyed()) {
                    new AlertDialog.Builder(activity).setMessage("Android System WebView closed. Open Sign in to try again.")
                            .setNeutralButton("Copy diagnostic", (prompt, which) ->
                                    copyDiagnostic(activity, page, "sign-in interrupted", reason))
                            .setPositiveButton("Close", null).show();
                }
                return true;
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (interrupted || !dialog.isShowing() || !isMusicReturn(url) || !url.equals(view.getUrl())) return;
                int revision = ++pageRevision;
                pageLoginState = "";
                checkSession(view, url, revision, 0);
            }

            private boolean current(WebView view, String url, int revision) {
                return !interrupted && revision == pageRevision && dialog.isShowing() && url.equals(view.getUrl());
            }

            private void checkSession(WebView view, String url, int revision, int attempt) {
                if (!current(view, url, revision)) return;
                // The return page can finish before its sign-in configuration or cookies arrive.
                boolean[] resolved = {false};
                Runnable noResult = () -> {
                    resolved[0] = true;
                    checkResult(view, url, revision, attempt, null);
                };
                main.postDelayed(noResult, 1250);
                view.evaluateJavascript(MUSIC_CONTEXT, result -> {
                    if (resolved[0]) return;
                    resolved[0] = true;
                    main.removeCallbacks(noResult);
                    checkResult(view, url, revision, attempt, result);
                });
            }

            private void checkResult(WebView view, String url, int revision, int attempt, String result) {
                if (!current(view, url, revision)) return;
                String session = cookies();
                String state = WebSession.loginState(result);
                if (!state.isEmpty()) pageLoginState = state;
                if ("true".equals(state) && MusicApi.hasSession(session)) {
                    finishSession(url, result, session);
                } else if (attempt < 5) {
                    main.postDelayed(() -> checkSession(view, url, revision, attempt + 1), 500);
                } else {
                    boolean canVerify = pageLoginState.isEmpty() && MusicApi.hasSession(session);
                    showMessage(canVerify
                                    ? "This WebView did not report a sign-in state. Tap Continue to check with YouTube Music."
                                    : "YouTube Music did not establish a signed-in session. Choose an account or restart sign-in.",
                            Uri.parse(url), "WebView session not confirmed",
                            canVerify ? "WEBVIEW_STATE_MISSING" : "false".equals(pageLoginState) ? "WEBVIEW_SIGNED_OUT" : "WEBVIEW_SESSION_MISSING",
                            null);
                    if (canVerify) {
                        continuation.setVisibility(View.VISIBLE);
                        continuation.setOnClickListener(button -> {
                            String latest = cookies();
                            if (revision == pageRevision && dialog.isShowing() && url.equals(view.getUrl())
                                    && MusicApi.hasSession(latest)) finishSession(url, result, latest);
                        });
                    }
                }
            }

            private void finishSession(String url, String context, String session) {
                rememberSelection(session, WebSession.fromWebView(context, Uri.parse(url).getQueryParameter("authuser")));
                jar.flush();
                dialog.dismiss();
                verify.run();
            }
        });
        panel.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        dialog.setContentView(panel);
        dialog.setOnDismissListener(ignored -> {
            main.removeCallbacksAndMessages(null);
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
