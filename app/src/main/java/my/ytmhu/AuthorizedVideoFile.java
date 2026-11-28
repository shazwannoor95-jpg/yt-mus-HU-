// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;

/** A single user-selected muxed file. Both modes play the same audio timeline. */
final class AuthorizedVideoFile implements AutoCloseable {
    private static final long MAX_BYTES = 32L * 1024 * 1024;
    final File file;
    final MusicApi.Track track;
    final int width, height;

    private AuthorizedVideoFile(File file, String label, MediaFormat video) {
        this.file = file;
        track = new MusicApi.Track("local-document", label, "Local video", "", "");
        width = video.getInteger(MediaFormat.KEY_WIDTH);
        height = video.getInteger(MediaFormat.KEY_HEIGHT);
    }

    static AuthorizedVideoFile stage(Context context, Uri uri, PerformancePolicy.Profile profile, int outputShortEdge)
            throws IOException {
        if (!DemoPlaybackPolicy.localPlaybackPermitted(context) || !"content".equals(uri.getScheme())) {
            throw new IOException("Local video requires the Android SDK emulator and file picker.");
        }
        ContentResolver resolver = context.getContentResolver();
        File temp = File.createTempFile("local-video-", ".mp4", context.getCacheDir());
        boolean accepted = false;
        try {
            InputStream opened = resolver.openInputStream(uri);
            if (opened == null) throw new IOException("The selected video could not be opened.");
            try (InputStream input = new BufferedInputStream(opened); OutputStream output = new FileOutputStream(temp)) {
                copyMp4(input, output, MAX_BYTES);
            }
            MediaFormat video = validate(temp, profile, outputShortEdge);
            AuthorizedVideoFile file = new AuthorizedVideoFile(temp, label(resolver, uri), video);
            accepted = true;
            return file;
        } catch (RuntimeException unavailable) {
            throw new IOException("The selected video could not be prepared.", unavailable);
        } finally {
            if (!accepted) temp.delete();
        }
    }

    static void copyMp4(InputStream input, OutputStream output, long limit) throws IOException {
        byte[] header = new byte[8];
        for (int read = 0; read < header.length;) {
            int count = input.read(header, read, header.length - read);
            if (count <= 0) throw new IOException("Choose an MP4 file with audio and video.");
            read += count;
        }
        if (header[4] != 'f' || header[5] != 't' || header[6] != 'y' || header[7] != 'p') {
            throw new IOException("Choose an MP4 file with audio and video.");
        }
        output.write(header);
        long total = header.length;
        byte[] buffer = new byte[16 * 1024];
        for (int count; (count = input.read(buffer)) != -1;) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Local video import cancelled.");
            total += count;
            if (total > limit) throw new IOException("Local video must be 32 MiB or smaller.");
            output.write(buffer, 0, count);
        }
    }

    private static MediaFormat validate(File file, PerformancePolicy.Profile profile, int outputShortEdge) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            if (extractor.getDrmInitData() != null || extractor.getTrackCount() != 2) {
                throw new IOException("Choose a clear MP4 with one AVC video track and one AAC audio track.");
            }
            MediaFormat first = extractor.getTrackFormat(0);
            MediaFormat second = extractor.getTrackFormat(1);
            MediaFormat video = "video/avc".equals(first.getString(MediaFormat.KEY_MIME)) ? first : second;
            MediaFormat audio = video == first ? second : first;
            if (!"video/avc".equals(video.getString(MediaFormat.KEY_MIME))
                    || !"audio/mp4a-latm".equals(audio.getString(MediaFormat.KEY_MIME))
                    || !VideoPairing.equalDuration(durationMs(audio), durationMs(video))) {
                throw new IOException("The file needs AVC video and AAC audio on the same timeline.");
            }
            if (VideoRenditions.rank(Collections.singletonList(video), profile, outputShortEdge, 0,
                    VideoRenditions::platformDecoder).isEmpty()) {
                throw new IOException("This resolution exceeds the current decoder or performance profile.");
            }
            return video;
        } finally {
            extractor.release();
        }
    }

    private static int durationMs(MediaFormat format) {
        if (!format.containsKey(MediaFormat.KEY_DURATION)) return 0;
        long millis = format.getLong(MediaFormat.KEY_DURATION) / 1000;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, millis));
    }

    static String safeLabel(String name) {
        if (name == null) return "Local video";
        String cleaned = name.replaceAll("[\\p{Cc}\\p{Cf}]", "").trim();
        if (cleaned.isEmpty()) return "Local video";
        return cleaned.substring(0, cleaned.offsetByCodePoints(0, Math.min(96, cleaned.codePointCount(0, cleaned.length()))));
    }

    private static String label(ContentResolver resolver, Uri uri) {
        try (Cursor row = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            return row != null && row.moveToFirst() ? safeLabel(row.getString(0)) : "Local video";
        } catch (RuntimeException unavailable) {
            return "Local video";
        }
    }

    @Override public void close() { file.delete(); }
}
