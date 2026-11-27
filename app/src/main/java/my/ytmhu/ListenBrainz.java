// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.HttpsURLConnection;

final class ListenBrainz {
    private static final String ROOT = "https://api.listenbrainz.org/1/";
    private static final String KEY = "ytm.listenbrainz";
    private static final String SESSION = "listenbrainz";

    private ListenBrainz() {}

    static String account(AppSettings prefs) {
        String saved = prefs.text(SESSION, "");
        int separator = saved.indexOf('\n');
        return separator < 0 ? "" : saved.substring(0, separator);
    }

    static String session(AppSettings prefs) { return prefs.text(SESSION, ""); }

    static void disconnect(AppSettings prefs) {
        prefs.set(SESSION, "");
    }

    static String connect(AppSettings prefs, String token, java.util.function.BooleanSupplier active) throws Exception {
        if (!token.matches("[A-Za-z0-9_-]{16,128}")) throw new IOException("Enter a valid ListenBrainz user token.");
        JSONObject response = request("validate-token", token, null);
        String name = response.optString("user_name");
        if (!response.optBoolean("valid") || name.isEmpty() || name.contains("\n")) {
            throw new IOException("ListenBrainz did not accept this token.");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        KeyStore store = keyStore();
        if (!store.containsAlias(KEY)) {
            KeyGenerator key = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            key.init(new KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            key.generateKey();
            store.load(null);
        }
        cipher.init(Cipher.ENCRYPT_MODE, store.getKey(KEY, null));
        String encrypted = name + "\n" + Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "\n"
                + Base64.encodeToString(cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        if (!active.getAsBoolean()) throw new IOException("Connection cancelled.");
        prefs.set(SESSION, encrypted);
        return name;
    }

    static void submit(AppSettings prefs, String session, MusicApi.Track song, int duration, long started,
                       boolean now) throws Exception {
        if (session.isEmpty() || !session.equals(session(prefs))) return;
        String[] parts = session.split("\n", 3);
        if (parts.length != 3) return;
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, keyStore().getKey(KEY, null),
                new GCMParameterSpec(128, Base64.decode(parts[1], Base64.DEFAULT)));
        String token = new String(cipher.doFinal(Base64.decode(parts[2], Base64.DEFAULT)), StandardCharsets.UTF_8);
        JSONObject additional = new JSONObject().put("duration_ms", duration)
                .put("origin_url", MusicApi.MUSIC + "/watch?v=" + song.id).put("submission_client", "YTM Head Unit");
        JSONObject entry = new JSONObject().put("track_metadata", new JSONObject().put("artist_name", song.artist)
                .put("track_name", song.title).put("additional_info", additional));
        if (!now) entry.put("listened_at", started);
        JSONObject body = new JSONObject().put("listen_type", now ? "playing_now" : "single")
                .put("payload", new JSONArray().put(entry));
        if (session.equals(session(prefs))) request("submit-listens", token, body);
    }

    private static KeyStore keyStore() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        return store;
    }

    private static JSONObject request(String endpoint, String token, JSONObject body) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) URI.create(ROOT + endpoint).toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(6500);
        connection.setReadTimeout(6500);
        connection.setRequestProperty("Authorization", "Token " + token);
        try {
            if (body != null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(data.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(data);
                }
            }
            return new JSONObject(MusicApi.read(connection));
        } finally {
            connection.disconnect();
        }
    }
}
