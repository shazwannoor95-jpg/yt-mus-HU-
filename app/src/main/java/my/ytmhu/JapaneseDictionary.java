// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.app.ActivityManager;
import android.content.Context;
import android.util.AtomicFile;

import com.atilika.kuromoji.TokenBase;
import com.atilika.kuromoji.TokenizerBase;
import com.atilika.kuromoji.dict.Dictionary;
import com.atilika.kuromoji.viterbi.ViterbiNode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URL;
import java.security.MessageDigest;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.net.ssl.HttpsURLConnection;

final class JapaneseDictionary {
    static final String DOWNLOAD_SIZE = "12.8 MiB";
    static final int ARCHIVE_BYTES = 13_343_016;
    private static final String URL =
            "https://repo.maven.apache.org/maven2/com/atilika/kuromoji/kuromoji-ipadic/0.9.0/kuromoji-ipadic-0.9.0.jar";
    private static final String SHA256 = "24909fd751c0b439f7af5131b080eb65bc85062f0c4977ca4a71c76abe74e0b6";
    private static final Object INSTALL_LOCK = new Object();
    private static final long HEAP_HEADROOM = 48L * 1024 * 1024;

    final File archive;

    JapaneseDictionary(Context context) {
        archive = new File(context.getNoBackupFilesDir(), "kuromoji-ipadic-0.9.0.jar");
    }

    boolean installed() { return archive.length() == ARCHIVE_BYTES; }

    static boolean memoryAvailable(Context context) {
        Runtime runtime = Runtime.getRuntime();
        if (runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory()) < HEAP_HEADROOM) return false;
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        if (manager == null) return false;
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(info);
        return !info.lowMemory && info.availMem - info.threshold >= HEAP_HEADROOM;
    }

    void install() throws Exception {
        synchronized (INSTALL_LOCK) {
            if (!installed()) download();
        }
    }

    private void download() throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
        if (archive.getParentFile().getUsableSpace() < ARCHIVE_BYTES + 1024 * 1024) {
            throw new IOException("Free at least 14 MiB to install the Japanese dictionary.");
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        AtomicFile file = new AtomicFile(archive);
        HttpsURLConnection connection = (HttpsURLConnection) new URL(URL).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        FileOutputStream out = null;
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Japanese dictionary server unavailable.");
            int total = 0;
            out = file.startWrite();
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[16 * 1024];
                for (int n; (n = in.read(buffer)) != -1; ) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                    total += n;
                    if (total > ARCHIVE_BYTES) throw new IOException("Unexpected Japanese dictionary size.");
                    digest.update(buffer, 0, n);
                    out.write(buffer, 0, n);
                }
            }
            if (total != ARCHIVE_BYTES || !MessageDigest.isEqual(digest.digest(), decodeHash())) {
                throw new IOException("Japanese dictionary verification failed.");
            }
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            file.finishWrite(out);
            out = null;
        } finally {
            if (out != null) file.failWrite(out);
            connection.disconnect();
        }
    }

    private static byte[] decodeHash() {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(SHA256.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    static final class JapaneseToken extends TokenBase {
        JapaneseToken(int id, String text, ViterbiNode.Type type, int position, Dictionary data) {
            super(id, text, type, position, data);
        }

        String partOfSpeech() { return getFeature(4); }
        String category() { return getFeature(5); }
        String subcategory() { return getFeature(6); }
        String reading() { return getFeature(11); }
        String pronunciation() { return getFeature(12); }
    }

    static final class Tokenizer extends TokenizerBase {
        Tokenizer(File archive) throws IOException {
            // Read only dictionary data. The downloaded JAR is never placed on a classloader.
            try (ZipFile zip = new ZipFile(archive)) {
                load(name -> {
                    ZipEntry entry = zip.getEntry("com/atilika/kuromoji/ipadic/" + name);
                    if (entry == null) throw new IOException("Missing Japanese dictionary data.");
                    return zip.getInputStream(entry);
                });
            }
        }

        Tokenizer(Context context) {
            load(name -> context.getAssets().open("ipadic/" + name));
        }

        private void load(com.atilika.kuromoji.util.ResourceResolver source) {
            configure(new TokenizerBase.Builder() {
                {
                    totalFeatures = 9;
                    readingFeature = 7;
                    partOfSpeechFeature = 0;
                    tokenFactory = JapaneseToken::new;
                    resolver = name -> {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                        return source.resolve(name);
                    };
                }

                @Override public <T extends TokenizerBase> T build() {
                    throw new UnsupportedOperationException();
                }
            });
        }

        @Override public List<JapaneseToken> tokenize(String text) { return createTokenList(text); }
    }
}
