// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;

import javax.net.ssl.HttpsURLConnection;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;

final class MusicDownload {
    private static final long MAX_BYTES = 100L * 1024 * 1024;

    private MusicDownload() {}

    static String fileName(String title) {
        String cleaned = title.replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|]", "_").trim();
        if (cleaned.isEmpty()) cleaned = "song";
        if (cleaned.length() > 100) cleaned = cleaned.substring(0, 100);
        return cleaned + ".m4a";
    }

    static void save(Context context, MusicApi.Track track, String cookies, Uri destination) throws Exception {
        File temporary = null;
        boolean saved = false;
        try {
            MusicApi api = new MusicApi();
            api.setSession(cookies);
            temporary = File.createTempFile("audio-", ".m4a", context.getCacheDir());
            boolean complete = false;
            for (MusicApi.AudioSource source : api.audio(track, true)) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Download cancelled.");
                if (source.bytes > MAX_BYTES) continue;
                try {
                    fetch(source, temporary);
                    complete = true;
                    break;
                } catch (IOException unavailable) {
                    // YouTube can reject one rendition while another remains available.
                }
            }
            if (!complete) throw new IOException("A complete AAC file could not be downloaded.");
            try (InputStream input = new FileInputStream(temporary);
                 OutputStream output = context.getContentResolver().openOutputStream(destination, "wt")) {
                if (output == null) throw new IOException("The selected location could not be opened.");
                copy(input, output, temporary.length());
            }
            saved = true;
        } finally {
            if (temporary != null) temporary.delete();
            if (!saved) {
                // ACTION_CREATE_DOCUMENT gave this operation its own document.
                try {
                    DocumentsContract.deleteDocument(context.getContentResolver(), destination);
                } catch (Exception ignored) {
                    // Some document providers do not allow deletion.
                }
            }
        }
    }

    private static void fetch(MusicApi.AudioSource source, File target) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) URI.create(source.url).toURL().openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(12000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", source.agent);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Audio download was refused.");
            try (InputStream input = connection.getInputStream(); OutputStream output = new FileOutputStream(target)) {
                copy(input, output, source.bytes);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void copy(InputStream input, OutputStream output, long bytes) throws IOException {
        byte[] buffer = new byte[32768];
        long written = 0;
        for (int count; (count = input.read(buffer)) != -1;) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Download cancelled.");
            written += count;
            if (written > bytes) throw new IOException("Audio size did not match the response.");
            output.write(buffer, 0, count);
        }
        if (written != bytes) throw new IOException("Audio file was incomplete.");
    }
}
