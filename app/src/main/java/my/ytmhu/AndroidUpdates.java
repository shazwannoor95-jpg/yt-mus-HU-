// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;

import javax.net.ssl.HttpsURLConnection;

final class AndroidUpdates {
    private static final String DOWNLOADS =
            "https://raw.githubusercontent.com/shazwannoor95-jpg/yt-mus-HU-/main/downloads/";

    private AndroidUpdates() {}

    static String[] check(int installedVersion) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) URI.create(DOWNLOADS + "latest-android.json").toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(6000);
        connection.setReadTimeout(6000);
        try {
            return available(new JSONObject(MusicApi.read(connection)), installedVersion);
        } finally {
            connection.disconnect();
        }
    }

    static String[] available(JSONObject metadata, int installedVersion) throws IOException {
        String version = metadata.optString("version");
        int code = metadata.optInt("versionCode");
        if (code <= 0 || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) throw new IOException("Invalid Android version manifest.");
        if (code <= installedVersion) return null;
        return new String[]{version, DOWNLOADS + "ytm-head-unit-" + version + "-debug.apk"};
    }
}
