// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DemoMedia {
    static final String ARTIST = "Bundled synthetic test media";
    final List<MusicApi.Track> queue = new ArrayList<>();
    final Map<String, File> audio = new LinkedHashMap<>();
    final Map<String, Recording> recordings = new LinkedHashMap<>();

    static DemoMedia load(Context context) throws IOException {
        DemoMedia result = new DemoMedia();
        result.add(context, "grid", "demo-grid01", "Studio Grid");
        result.add(context, "hue", "demo-hue002", "Colour Shift");
        return result;
    }

    private void add(Context context, String base, String id, String title) throws IOException {
        File file = new File(context.getCacheDir(), "demo-0433-" + base + ".m4a");
        File temp = File.createTempFile("demo-", ".m4a", context.getCacheDir());
        try {
            try (InputStream input = context.getAssets().open("demo/" + base + ".m4a");
                 FileOutputStream output = new FileOutputStream(temp)) {
                byte[] chunk = new byte[8192];
                for (int read; (read = input.read(chunk)) != -1;) output.write(chunk, 0, read);
            }
            if (!temp.renameTo(file)) throw new IOException("Could not prepare demo audio");
        } finally {
            temp.delete();
        }
        audio.put(id, file);
        queue.add(new MusicApi.Track(id, title, ARTIST, "", ""));
        List<MediaFormat> formats = new ArrayList<>();
        for (int height : new int[]{180, 144}) {
            try (AssetFileDescriptor descriptor = context.getAssets().openFd(video(base, height))) {
                MediaExtractor decoder = new MediaExtractor();
                try {
                    decoder.setDataSource(descriptor.getFileDescriptor(), descriptor.getStartOffset(), descriptor.getLength());
                    for (int i = 0; i < decoder.getTrackCount(); i++) {
                        MediaFormat format = decoder.getTrackFormat(i);
                        if ("video/avc".equals(format.getString(MediaFormat.KEY_MIME))) formats.add(format);
                    }
                } finally {
                    decoder.release();
                }
            }
        }
        Bitmap cover;
        try (InputStream input = context.getAssets().open("demo/" + base + ".jpg")) {
            cover = BitmapFactory.decodeStream(input);
        }
        recordings.put(id, new Recording(base, cover, VideoRenditions.rank(formats,
                PerformancePolicy.Profile.ULTRA_LITE, 180, 0, VideoRenditions::platformDecoder)));
    }

    private static String video(String base, int height) { return "demo/" + base + "-" + height + ".mp4"; }

    static final class Recording {
        final String base;
        final Bitmap cover;
        final List<MediaFormat> renditions;

        Recording(String base, Bitmap cover, List<MediaFormat> renditions) {
            this.base = base;
            this.cover = cover;
            this.renditions = renditions;
        }

        AssetFileDescriptor open(Context context, MediaFormat rendition) throws IOException {
            return context.getAssets().openFd(video(base, rendition.getInteger(MediaFormat.KEY_HEIGHT)));
        }
    }
}
