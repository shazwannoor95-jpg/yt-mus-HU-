// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;

/** Enters the visible fixture only on a non-automotive Android SDK emulator. */
public final class VideoFixtureActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!permitted(this)) finish();
    }

    public static boolean permitted(Context context) {
        return context.getPackageName().endsWith(".videofixture")
                && ("ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE))
                && Build.PRODUCT.startsWith("sdk")
                && !context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);
    }

    @Override public void setContentView(View view) {
        if (permitted(this)) super.setContentView(view);
    }
}
