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

import javax.net.ssl.HttpsURLConnection;

final class Artwork {
    private final ExecutorService downloads = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(3 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap image) { return image.getByteCount(); }
    };
    private volatile boolean closed;

    void load(ImageView view, String url) {
        load(view, url, null);
    }

    void load(ImageView view, String url, java.util.function.Consumer<Bitmap> preview) {
        view.setTag(url);
        WeakReference<ImageView> target = new WeakReference<>(view);
        acquire(url, () -> target.get() != null, bitmap -> {
            ImageView image = target.get();
            if (image == null || !url.equals(image.getTag())) return;
            image.setImageBitmap(bitmap);
            if (preview != null) preview.accept(bitmap);
        });
    }

    void acquire(String url, java.util.function.Consumer<Bitmap> receive) {
        acquire(url, () -> true, receive);
    }

    private void acquire(String url, java.util.function.BooleanSupplier wanted,
                         java.util.function.Consumer<Bitmap> receive) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException invalid) {
            return;
        }
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || host == null
                || !(host.equals("i.ytimg.com") || host.endsWith(".googleusercontent.com")
                || host.equals("yt3.ggpht.com"))) return;
        Bitmap stored = cache.get(url);
        if (stored != null) {
            receive.accept(stored);
            return;
        }
        downloads.execute(() -> {
            if (closed || !wanted.getAsBoolean()) return;
            Bitmap bitmap = cache.get(url);
            if (bitmap == null) bitmap = download(uri);
            if (bitmap == null || closed) return;
            cache.put(url, bitmap);
            Bitmap ready = bitmap;
            main.post(() -> {
                if (!closed) receive.accept(ready);
            });
        });
    }

    private Bitmap download(URI uri) {
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
                    if (image.size() + count > 512 * 1024) return null;
                    image.write(bytes, 0, count);
                }
                byte[] picture = image.toByteArray();
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(picture, 0, picture.length, options);
                options.inJustDecodeBounds = false;
                options.inSampleSize = 1;
                while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 256) {
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
