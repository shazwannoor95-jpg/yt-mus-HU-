// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.net.ssl.HttpsURLConnection;

/** Stages a known progressive rendition from an already authorized source. */
final class VerifiedVideoDownload {
    static final long MAX_BYTES = 16L * 1024 * 1024;

    static File fetch(File cache, URI uri, String originHost, long bytes, String sha256) throws IOException {
        if (!accepts(uri, originHost) || bytes <= 0 || bytes > MAX_BYTES || !sha256.matches("[a-f0-9]{64}")) {
            throw new IOException("Invalid verified rendition descriptor");
        }
        File target = new File(cache, "video-" + sha256 + ".mp4");
        long deadline = SystemClock.elapsedRealtime() + 20_000;
        if (target.isFile() && target.length() == bytes) {
            try (InputStream input = new FileInputStream(target)) {
                verify(input, null, bytes, sha256, deadline);
                return target;
            } catch (IOException invalid) {
                checkActive(deadline);
                // A corrupt cached copy can be replaced only by another verified download.
            }
        }

        HttpsURLConnection connection = (HttpsURLConnection) uri.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(5_000);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept-Encoding", "identity");
        File partial = null;
        try {
            if (connection.getResponseCode() != 200 || connection.getContentLengthLong() != bytes
                    || connection.getContentEncoding() != null && !"identity".equalsIgnoreCase(connection.getContentEncoding())) {
                throw new IOException("Unexpected rendition response");
            }
            partial = File.createTempFile("video-", ".part", cache);
            try (FileOutputStream output = new FileOutputStream(partial); InputStream input = connection.getInputStream()) {
                verify(input, output, bytes, sha256, deadline);
            }
            checkActive(deadline);
            if (!partial.renameTo(target)) throw new IOException("Could not stage verified video");
            return target;
        } finally {
            connection.disconnect();
            if (partial != null) partial.delete();
        }
    }

    static boolean accepts(URI uri, String originHost) {
        return "https".equals(uri.getScheme()) && originHost.equals(uri.getHost())
                && uri.getUserInfo() == null && uri.getFragment() == null
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && uri.getRawPath() != null && uri.getRawPath().startsWith("/");
    }

    static void verify(InputStream input, FileOutputStream output, long bytes, String sha256, long deadline) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IOException("SHA-256 unavailable", unavailable);
        }
        DigestInputStream checked = new DigestInputStream(input, digest);
        byte[] buffer = new byte[8192];
        long read = 0;
        while (read < bytes) {
            checkActive(deadline);
            int count = checked.read(buffer, 0, (int) Math.min(buffer.length, bytes - read));
            if (count == -1) throw new IOException("Incomplete rendition");
            if (output != null) output.write(buffer, 0, count);
            read += count;
        }
        checkActive(deadline);
        if (input.read() != -1 || !MessageDigest.isEqual(digest.digest(), fromHex(sha256))) {
            throw new IOException("Unexpected rendition bytes");
        }
    }

    private static byte[] fromHex(String hex) {
        byte[] expected = new byte[32];
        for (int i = 0; i < expected.length; i++) expected[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        return expected;
    }

    private static void checkActive(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted() || SystemClock.elapsedRealtime() >= deadline) {
            throw new IOException("Rendition preparation expired");
        }
    }
}
