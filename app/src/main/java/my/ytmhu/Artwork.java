// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

final class Artwork {
    private static final long DISK_TTL = TimeUnit.DAYS.toMillis(7);
    private static final int MAX_BYTES = 1024 * 1024;
    private static final int[] SIZES = {64, 96, 128, 160, 192, 256, 320, 384, 512, 576, 640, 768};
    private static final Pattern SIZE = Pattern.compile("=(?:w(\\d{1,5})-h(\\d{1,5})|s(\\d{1,5}))(-[a-z0-9-]+)?$");
    private static final Pattern VIDEO = Pattern.compile("^/vi/([A-Za-z0-9_-]{11})/[^/]+$");
    private final ThreadPoolExecutor downloads = new ThreadPoolExecutor(
            2, 2, 15, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32));
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(4 * MAX_BYTES) {
        @Override protected int sizeOf(String key, Bitmap image) { return image.getByteCount(); }
    };
    private final Map<String, Request> pending = new HashMap<>(); // Main thread only
    private static final Object DISK_LOCK = new Object();
    private final File directory;
    private volatile PerformancePolicy.Profile profile = PerformancePolicy.Profile.BALANCED;
    private volatile int diskBudget;
    private volatile int diskRevision;
    private boolean clearDiskRequested;
    private final Runnable diskMaintenance = this::maintainDisk;
    private boolean closed;

    Artwork() { this(null); }

    Artwork(Context context) {
        directory = context == null ? null : new File(context.getCacheDir(), "artwork-v1");
        downloads.allowCoreThreadTimeOut(true);
    }

    void configure(PerformancePolicy.Profile profile, int memoryBytes, int diskBytes) {
        this.profile = profile;
        int previousBudget = diskBudget;
        diskBudget = diskBytes;
        cache.resize(memoryBytes);
        int workers = profile.workers;
        if (workers > downloads.getMaximumPoolSize()) downloads.setMaximumPoolSize(workers);
        downloads.setCorePoolSize(workers);
        if (workers < downloads.getMaximumPoolSize()) downloads.setMaximumPoolSize(workers);
        if (directory != null && diskBytes < previousBudget) maintainDisk();
    }

    void load(ImageView view, String url) { bind(view, url, false, null); }

    void loadDetail(ImageView view, String url, Consumer<Bitmap> preview) {
        bind(view, url, true, preview);
    }

    void unbind(ImageView view) {
        if (view.getTag() instanceof Binding) ((Binding) view.getTag()).remove();
        view.setTag(null);
    }

    private void bind(ImageView view, String url, boolean detail, Consumer<Bitmap> preview) {
        unbind(view);
        if (artworkUri(url) == null) return;
        Binding binding = new Binding(view, url, detail, preview);
        view.setTag(binding);
        view.addOnAttachStateChangeListener(binding);
        view.addOnLayoutChangeListener(binding);
        binding.update();
    }

    private final class Binding implements View.OnAttachStateChangeListener, View.OnLayoutChangeListener {
        final ImageView view;
        final String url;
        final boolean detail;
        final Consumer<Bitmap> preview;
        Runnable cancel;
        int size;

        Binding(ImageView view, String url, boolean detail, Consumer<Bitmap> preview) {
            this.view = view;
            this.url = url;
            this.detail = detail;
            this.preview = preview;
        }

        void update() {
            if (!view.isAttachedToWindow() || view.getWidth() <= 0 || view.getHeight() <= 0) return;
            int dimension = detail ? Math.min(view.getWidth(), view.getHeight()) : Math.max(view.getWidth(), view.getHeight());
            int next = requestSize(dimension, detail ? profile.detailPx : profile.thumbnailPx);
            if (size == next) return;
            stop();
            size = next;
            cancel = acquire(url, next, detail, bitmap -> {
                if (view.getTag() != this || !view.isAttachedToWindow()) return;
                view.setImageBitmap(bitmap);
                if (preview != null) preview.accept(bitmap);
            });
        }

        void stop() {
            if (cancel != null) cancel.run();
            cancel = null;
            size = 0;
        }

        void remove() {
            stop();
            view.removeOnAttachStateChangeListener(this);
            view.removeOnLayoutChangeListener(this);
        }

        @Override public void onViewAttachedToWindow(View view) { update(); }
        @Override public void onViewDetachedFromWindow(View view) { stop(); }
        @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) { update(); }
    }

    static int requestSize(int dimension, int ceiling) {
        for (int size : SIZES) if (size >= dimension) return Math.min(size, ceiling);
        return ceiling;
    }

    Runnable acquire(String url, Consumer<Bitmap> receive) { return acquire(url, 96, false, receive); }

    private static URI artworkUri(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (!"https".equals(uri.getScheme()) || host == null || uri.getUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) return null;
            return host.equals("i.ytimg.com") || host.endsWith(".googleusercontent.com") || host.equals("yt3.ggpht.com")
                    ? uri : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    static String detailUrl(String url) { return resizedUrl(url, 640, true); }

    static String resizedUrl(String url, int target, boolean detail) {
        URI uri = artworkUri(url);
        if (uri == null || uri.getRawQuery() != null || uri.getRawFragment() != null) return url;
        if ("i.ytimg.com".equals(uri.getHost())) {
            Matcher video = VIDEO.matcher(uri.getRawPath());
            return detail && target >= 512 && video.matches()
                    ? "https://i.ytimg.com/vi/" + video.group(1) + "/maxresdefault.jpg" : url;
        }
        Matcher size = SIZE.matcher(uri.getRawPath());
        if (!size.find()) return url;
        int width = Integer.parseInt(size.group(1) == null ? size.group(3) : size.group(1));
        int height = size.group(2) == null ? width : Integer.parseInt(size.group(2));
        int longest = Math.max(width, height);
        if (longest == 0 || longest == target) return url;
        String dimensions = size.group(3) != null ? "s" + target
                : "w" + Math.max(1, width * target / longest) + "-h" + Math.max(1, height * target / longest);
        return url.substring(0, url.length() - size.group().length()) + "="
                + dimensions + (size.group(4) == null ? "" : size.group(4));
    }

    private static final Runnable NO_CANCEL = () -> {};
    private static final class Request {
        final List<Consumer<Bitmap>> listeners = new ArrayList<>();
        Future<?> future;
        volatile boolean cancelled;
    }

    private Runnable acquire(String original, int limit, boolean detail, Consumer<Bitmap> receive) {
        String url = resizedUrl(original, limit, detail);
        URI uri = artworkUri(url);
        if (closed || uri == null) return NO_CANCEL;
        String key = limit + ":" + url;
        Bitmap stored = cache.get(key);
        if (stored != null) {
            receive.accept(stored);
            return NO_CANCEL;
        }
        Request job = pending.get(key);
        if (job == null) {
            Request work = new Request();
            pending.put(key, work);
            job = work;
            int revision = diskRevision;
            try {
                work.future = downloads.submit(() -> {
                    Bitmap ready = read(uri, key, limit, revision, work);
                    if (ready == null && !original.equals(url) && !work.cancelled) {
                        ready = read(artworkUri(original), limit + ":" + original, limit, revision, work);
                    }
                    Bitmap result = ready;
                    if (!work.cancelled) main.post(() -> complete(key, work, result, revision));
                });
            } catch (RejectedExecutionException full) {
                pending.remove(key);
                return NO_CANCEL;
            }
        }
        job.listeners.add(receive);
        Request work = job;
        return () -> {
            if (pending.get(key) != work) return;
            work.listeners.remove(receive);
            if (work.listeners.isEmpty()) {
                pending.remove(key);
                cancel(work);
            }
        };
    }

    private void cancel(Request job) {
        job.cancelled = true;
        job.future.cancel(false);
        if (job.future instanceof Runnable) downloads.remove((Runnable) job.future);
    }

    private void complete(String key, Request job, Bitmap result, int revision) {
        if (pending.get(key) != job) return;
        pending.remove(key);
        if (closed || result == null) return;
        if (revision == diskRevision) cache.put(key, result);
        for (Consumer<Bitmap> callback : job.listeners) callback.accept(result);
    }

    private Bitmap read(URI uri, String key, int limit, int revision, Request job) {
        if (uri == null || job.cancelled) return null;
        try {
            File file = diskFile(key);
            if (file != null) {
                synchronized (DISK_LOCK) {
                    if (diskBudget > 0 && revision == diskRevision && file.isFile() && file.length() <= MAX_BYTES
                            && System.currentTimeMillis() - file.lastModified() < DISK_TTL) {
                        Bitmap cached = decode(file, null, limit);
                        if (cached != null) {
                            file.setLastModified(System.currentTimeMillis());
                            return cached;
                        }
                        file.delete();
                    }
                }
            }
            byte[] bytes = transfer(uri, job);
            if (bytes == null || job.cancelled) return null;
            Bitmap image = decode(null, bytes, limit);
            if (file != null && image != null && !job.cancelled) {
                synchronized (DISK_LOCK) {
                    if (!job.cancelled && diskBudget > 0 && revision == diskRevision && directory.isDirectory()) {
                        try (FileOutputStream output = new FileOutputStream(file)) {
                            output.write(bytes);
                        } catch (java.io.IOException full) {
                            file.delete();
                        }
                        pruneDisk(false);
                    }
                }
            }
            return image;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static byte[] transfer(URI uri, Request job) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) uri.toURL().openConnection();
        try {
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(false);
            if (connection.getResponseCode() != 200) return null;
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream image = new ByteArrayOutputStream()) {
                byte[] bytes = new byte[8192];
                int count;
                while ((count = input.read(bytes)) != -1) {
                    if (job.cancelled || image.size() + count > MAX_BYTES) return null;
                    image.write(bytes, 0, count);
                }
                return image.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    private static Bitmap decode(File file, byte[] bytes, int limit) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        decodeBitmap(file, bytes, options);
        int longest = Math.max(options.outWidth, options.outHeight);
        if (longest <= 0 || longest > 8192) return null;
        options.inJustDecodeBounds = false;
        options.inSampleSize = 1;
        while (longest / (options.inSampleSize * 2) >= limit) options.inSampleSize *= 2;
        Bitmap bitmap = decodeBitmap(file, bytes, options);
        if (bitmap == null) return null;
        int size = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (size <= limit) return bitmap;
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.max(1, bitmap.getWidth() * limit / size),
                Math.max(1, bitmap.getHeight() * limit / size), true);
        if (scaled != bitmap) bitmap.recycle();
        return scaled;
    }

    private static Bitmap decodeBitmap(File file, byte[] bytes, BitmapFactory.Options options) {
        return file == null ? BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options)
                : BitmapFactory.decodeFile(file.getPath(), options);
    }

    private File diskFile(String key) throws Exception {
        if (directory == null || diskBudget == 0) return null;
        synchronized (DISK_LOCK) {
            if (!directory.isDirectory() && !directory.mkdirs()) return null;
        }
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        return new File(directory, android.util.Base64.encodeToString(hash, android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP));
    }

    private void pruneDisk(boolean clear) {
        File[] files = directory.listFiles(File::isFile);
        if (files == null) return;
        long now = System.currentTimeMillis();
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        long bytes = 0;
        int kept = 0;
        for (File file : files) {
            long length = file.length();
            if (clear || kept >= 384 || bytes + length > diskBudget || now - file.lastModified() >= DISK_TTL) file.delete();
            else {
                bytes += length;
                kept++;
            }
        }
    }

    private void maintainDisk() {
        if (directory == null || closed) return;
        main.removeCallbacks(diskMaintenance);
        boolean clear = clearDiskRequested;
        try {
            downloads.execute(() -> { synchronized (DISK_LOCK) { pruneDisk(clear); } });
            clearDiskRequested = false;
        } catch (RejectedExecutionException full) {
            main.postDelayed(diskMaintenance, 300);
        }
    }

    void trim() { cache.evictAll(); }

    void clear() {
        cache.evictAll();
        diskRevision++;
        clearDiskRequested = true;
        maintainDisk();
    }

    void close() {
        closed = true;
        for (Request job : pending.values()) cancel(job);
        pending.clear();
        downloads.shutdownNow();
        main.removeCallbacksAndMessages(null);
        cache.evictAll();
    }
}
