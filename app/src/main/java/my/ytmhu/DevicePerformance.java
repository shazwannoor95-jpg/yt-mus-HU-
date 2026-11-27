// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentCallbacks2;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.FrameMetrics;
import android.view.Window;
import android.webkit.WebView;

final class DevicePerformance {
    private static final int MIB = 1024 * 1024;
    private final Activity activity;
    private final ActivityManager manager;
    private final PerformancePolicy.Capabilities capabilities;
    private final PerformancePolicy policy;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable changed;
    private final Window.OnFrameMetricsAvailableListener listener = this::frame;
    private boolean started;
    private long sampleStarted;
    private int sampled, slow;

    DevicePerformance(Activity activity, String preference, Runnable changed) {
        this.activity = activity;
        this.changed = changed;
        manager = activity.getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo info = memory();
        android.content.pm.ConfigurationInfo graphics = manager.getDeviceConfigurationInfo();
        capabilities = new PerformancePolicy.Capabilities(mib(info.totalMem), manager.getMemoryClass(),
                graphics == null ? 0 : graphics.reqGlEsVersion, manager.isLowRamDevice());
        policy = new PerformancePolicy(capabilities, preference, mib(info.availMem), mib(info.threshold),
                info.lowMemory, SystemClock.uptimeMillis());
    }

    PerformancePolicy.Profile profile() { return policy.effective(); }
    String summary() { return policy.summary(); }
    int cacheBytes() { return policy.cacheMiB() * MIB; }

    int diskBytes() {
        long usable = activity.getCacheDir().getUsableSpace();
        return usable < 128L * MIB ? 0 : Math.min(profile().diskMiB * MIB, (int) Math.min(Integer.MAX_VALUE, usable / 16));
    }

    String capabilitiesText(Viewport viewport) {
        android.content.pm.PackageInfo webView = WebView.getCurrentWebViewPackage();
        return "Android API " + Build.VERSION.SDK_INT + " · " + String.join(", ", Build.SUPPORTED_ABIS)
                + "\nRAM " + capabilities.totalMiB + " MB · App heap " + capabilities.heapMiB
                + " MB · GLES " + (capabilities.gles >> 16)
                + "\nWindow " + viewport.width + " × " + viewport.height + " dp · " + viewport.densityDpi + " dpi"
                + "\nArtwork cache " + policy.cacheMiB() + " MB memory · " + diskBytes() / MIB + " MB disk"
                + "\nWebView " + (webView == null ? "unavailable" : webView.versionName);
    }

    void choose(String preference) {
        policy.choose(preference, SystemClock.uptimeMillis());
        changed.run();
    }

    void start() {
        if (started) return;
        started = true;
        sampleStarted = SystemClock.uptimeMillis();
        sampled = slow = 0;
        ActivityManager.MemoryInfo info = memory();
        PerformancePolicy.Profile before = profile();
        if (info.lowMemory) policy.memoryPressure(true, sampleStarted);
        else policy.recover(mib(info.availMem), mib(info.threshold), false, sampleStarted);
        if (profile() != before) changed.run();
        activity.getWindow().addOnFrameMetricsAvailableListener(listener, main);
    }

    void stop() {
        if (!started) return;
        started = false;
        activity.getWindow().removeOnFrameMetricsAvailableListener(listener);
    }

    void trim(int level) {
        boolean critical = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
                || level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE;
        boolean runningLow = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
                || level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE;
        if (critical || runningLow) {
            policy.memoryPressure(critical, SystemClock.uptimeMillis());
            changed.run();
        }
    }

    private void frame(Window window, FrameMetrics frame, int dropped) {
        if (!started || !activity.hasWindowFocus() || frame.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1) return;
        if (frame.getMetric(FrameMetrics.TOTAL_DURATION) <= 0) return;
        sampled++;
        // A 50 ms frame misses several refresh intervals at 60 Hz. Isolated stalls are ignored.
        if (frame.getMetric(FrameMetrics.TOTAL_DURATION) >= 50_000_000) slow++;
        long now = SystemClock.uptimeMillis();
        if (now - sampleStarted < 3000) return;
        PerformancePolicy.Profile before = profile();
        if (sampled >= 12) {
            ActivityManager.MemoryInfo info = policy.recoveryDue(now) ? memory() : null;
            policy.frames(sampled, slow, info == null ? 0 : mib(info.availMem),
                    info == null ? 0 : mib(info.threshold), info != null && info.lowMemory, now);
        }
        sampleStarted = now;
        sampled = slow = 0;
        if (before != profile()) changed.run();
    }

    private ActivityManager.MemoryInfo memory() {
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(info);
        return info;
    }

    private static int mib(long bytes) { return (int) Math.min(Integer.MAX_VALUE, bytes / MIB); }
}
