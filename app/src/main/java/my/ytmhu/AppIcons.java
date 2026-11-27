// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

final class AppIcons {
    static final String[] IDS = {"classic", "official", "smilering", "gear", "wave", "vinyl", "lyrics", "duet", "kawarp", "fluent"};
    static final String[] LABELS = {"Classic", "YT Music", "Smile ring", "Rust gear", "Waveform", "Record", "Lit line", "Duet", "Warp", "Fluent"};

    private AppIcons() {}

    static int index(String id) {
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return i;
        return 0;
    }

    static int drawable(Context context, String id) {
        String safe = IDS[index(id)];
        return context.getResources().getIdentifier("classic".equals(safe) ? "music_icon" : "music_" + safe,
                "drawable", context.getPackageName());
    }

    static void apply(Context context, AppSettings prefs, String requested) {
        String after = IDS[index(requested)];
        String before = IDS[index(prefs.text("appIcon", "classic"))];
        if (after.equals(before)) return;
        PackageManager manager = context.getPackageManager();
        set(manager, context, after, PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        try {
            set(manager, context, before, PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
        } catch (RuntimeException error) {
            set(manager, context, after, PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
            throw error;
        }
        prefs.set("appIcon", after);
    }

    private static void set(PackageManager manager, Context context, String id, int state) {
        ComponentName alias = new ComponentName(context, context.getPackageName() + ".Launcher_" + id);
        manager.setComponentEnabledSetting(alias, state, PackageManager.DONT_KILL_APP);
    }
}
