// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

final class Artwork {
    private static final int DETAIL_SIZE = 640;
    private static final Pattern SIZE = Pattern.compile("=(?:w(\\d{1,5})-h(\\d{1,5})|s(\\d{1,5}))(-[a-z0-9-]+)?$");
    private static final Pattern VIDEO_THUMBNAIL = Pattern.compile("^/vi/([A-Za-z0-9_-]{11})/[^/]+$");
    private final ExecutorService downloads = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(3 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap image) { return image.getByteCount(); }
    };
    private volatile boolean closed;

    void load(ImageView view, String url) {
        bind(view, url, url, 256, null);
    }

    void loadDetail(ImageView view, String url, java.util.function.Consumer<Bitmap> preview) {
        bind(view, url, detailUrl(url), DETAIL_SIZE, preview);
    }

    static String detailUrl(String url) {
        URI uri = artworkUri(url);
        if (uri == null || uri.getRawQuery() != null || uri.getRawFragment() != null) return url;
        if (uri.getHost().equals("i.ytimg.com")) {
            Matcher video = VIDEO_THUMBNAIL.matcher(uri.getRawPath());
            return video.matches() ? "https://i.ytimg.com/vi/" + video.group(1) + "/maxresdefault.jpg" : url;
        }
        Matcher size = SIZE.matcher(uri.getRawPath());
        if (!size.find()) return url;
        int width = Integer.parseInt(size.group(1) == null ? size.group(3) : size.group(1));
        int height = size.group(2) == null ? width : Integer.parseInt(size.group(2));
        int longest = Math.max(width, height);
        if (longest == 0 || longest >= DETAIL_SIZE) return url;
        String dimensions = size.group(3) != null ? "s" + DETAIL_SIZE
                : "w" + Math.max(1, width * DETAIL_SIZE / longest)
                + "-h" + Math.max(1, height * DETAIL_SIZE / longest);
        return url.substring(0, url.length() - size.group().length()) + "="
                + dimensions + (size.group(4) == null ? "" : size.group(4));
    }

    private void bind(ImageView view, String url, String preferred, int limit,
                      java.util.function.Consumer<Bitmap> preview) {
        view.setTag(url);
        WeakReference<ImageView> target = new WeakReference<>(view);
        acquire(preferred, url, limit, () -> target.get() != null, bitmap -> {
            ImageView image = target.get();
            if (image == null || !url.equals(image.getTag())) return;
            image.setImageBitmap(bitmap);
            if (preview != null) preview.accept(bitmap);
        });
    }

    void acquire(String url, java.util.function.Consumer<Bitmap> receive) {
        acquire(url, url, 256, () -> true, receive);
    }

    private static URI artworkUri(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || host == null
                || !(host.equals("i.ytimg.com") || host.endsWith(".googleusercontent.com")
                || host.equals("yt3.ggpht.com"))) return null;
        return uri;
    }

    private void acquire(String url, String original, int limit,
                         java.util.function.BooleanSupplier wanted, java.util.function.Consumer<Bitmap> receive) {
        URI uri = artworkUri(url);
        if (uri == null) return;
        String key = limit + ":" + url;
        Bitmap stored = cache.get(key);
        if (stored != null) {
            receive.accept(stored);
            return;
        }
        downloads.execute(() -> {
            if (closed || !wanted.getAsBoolean()) return;
            Bitmap bitmap = cache.get(key);
            if (bitmap == null) bitmap = download(uri, limit);
            if (bitmap == null && !url.equals(original) && !closed && wanted.getAsBoolean()) {
                bitmap = cache.get("256:" + original);
                if (bitmap == null) bitmap = download(URI.create(original), limit);
            }
            if (bitmap == null || closed) return;
            cache.put(key, bitmap);
            Bitmap ready = bitmap;
            main.post(() -> {
                if (!closed) receive.accept(ready);
            });
        });
    }

    private Bitmap download(URI uri, int limit) {
        HttpsURLConnection connection = null;
        try {
            connection = (HttpsURLConnection) uri.toURL().openConnection();
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(false);
            if (connection.getResponseCode() != 200) return null;
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream image = new ByteArrayOutputStream();
                byte[] bytes = new byte[4096];
                int count;
                while ((count = input.read(bytes)) != -1) {
                    if (image.size() + count > (limit == DETAIL_SIZE ? 1024 : 512) * 1024) return null;
                    image.write(bytes, 0, count);
                }
                byte[] picture = image.toByteArray();
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(picture, 0, picture.length, options);
                options.inJustDecodeBounds = false;
                options.inSampleSize = 1;
                while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > limit) {
                    options.inSampleSize *= 2;
                }
                return BitmapFactory.decodeByteArray(picture, 0, picture.length, options);
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    void clear() { cache.evictAll(); }

    void close() {
        closed = true;
        downloads.shutdownNow();
        main.removeCallbacksAndMessages(null);
        cache.evictAll();
    }
}
