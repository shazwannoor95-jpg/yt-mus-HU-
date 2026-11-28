// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

final class DemoPlaybackPolicy {
    static final String PACKAGE = "my.ytmhu.videofixture";

    static boolean permitted(Context context) {
        return permitted(context.getPackageName(), Build.HARDWARE, Build.PRODUCT,
                context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE));
    }

    static boolean permitted(String packageName, String hardware, String product, boolean automotive) {
        return PACKAGE.equals(packageName) && nonAutomotiveSdk(hardware, product, automotive);
    }

    static boolean localPlaybackPermitted(Context context) {
        return localPlaybackPermitted(context.getPackageName(), Build.HARDWARE, Build.PRODUCT,
                context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE));
    }

    static boolean localPlaybackPermitted(String packageName, String hardware, String product, boolean automotive) {
        return "my.ytmhu".equals(packageName) && nonAutomotiveSdk(hardware, product, automotive);
    }

    private static boolean nonAutomotiveSdk(String hardware, String product, boolean automotive) {
        return !automotive && product.startsWith("sdk") && ("ranchu".equals(hardware) || "goldfish".equals(hardware));
    }
}
