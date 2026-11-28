// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.GCMParameterSpec;

final class LyricCloudKey {
    private static final String ALIAS = "ytm.lyrics.cloud-translation.v1";
    private final SharedPreferences storage;

    LyricCloudKey(Context context) {
        storage = context.getSharedPreferences("lyrics-cloud", Context.MODE_PRIVATE);
    }

    boolean configured() { return storage.contains("key"); }

    void save(String key) throws Exception {
        if (!key.matches("[A-Za-z0-9_-]{20,100}")) throw new IllegalArgumentException();
        KeyStore store = keyStore();
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, store.getKey(ALIAS, null));
        String encrypted = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                + Base64.encodeToString(cipher.doFinal(key.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        storage.edit().putString("key", encrypted).apply();
    }

    String read() throws Exception {
        String saved = storage.getString("key", "");
        if (saved.isEmpty()) return "";
        String[] fields = saved.split(":", 2);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, keyStore().getKey(ALIAS, null),
                new GCMParameterSpec(128, Base64.decode(fields[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(fields[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    void remove() {
        storage.edit().remove("key").apply();
    }

    private static KeyStore keyStore() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        return store;
    }
}
