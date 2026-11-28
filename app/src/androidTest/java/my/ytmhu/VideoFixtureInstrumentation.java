// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.res.AssetFileDescriptor;
import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises native video off-screen using only synthetic media in the test APK. */
public final class VideoFixtureInstrumentation extends Instrumentation {
    private static final String VIDEO = "native-video-fixture.mp4";
    private static final String AUDIO = "native-audio-fixture.m4a";

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            exercise();
            result.putString("stream", "\nPASS: native AVC surface, seek, pause, resume and audio recovery\n");
            finish(Activity.RESULT_OK, result);
        } catch (Exception | AssertionError failed) {
            result.putString("stream", "\nFAIL: " + failed + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void exercise() throws Exception {
        MediaFormat format = videoFormat(VIDEO);
        require(format != null && "video/avc".equals(format.getString(MediaFormat.KEY_MIME)), "Fixture AVC track");
        int width = format.getInteger(MediaFormat.KEY_WIDTH);
        int height = format.getInteger(MediaFormat.KEY_HEIGHT);
        require(width == 320 && height == 180, "Fixture dimensions");
        AtomicReference<String> audioError = new AtomicReference<>();
        AtomicReference<String> videoError = new AtomicReference<>();
        CountDownLatch frames = new CountDownLatch(2);
        Handler main = new Handler(Looper.getMainLooper());

        try (ImageReader output = ImageReader.newInstance(width, height, ImageFormat.PRIVATE, 3)) {
            output.setOnImageAvailableListener(reader -> {
                try (Image frame = reader.acquireLatestImage()) {
                    if (frame != null && frame.getTimestamp() > 0) frames.countDown();
                }
            }, main);

            MediaPlayer song = prepare(AUDIO, null, audioError);
            try {
                onMain(() -> {
                    song.setLooping(true);
                    song.start();
                    return null;
                });
                require(videoFormat(AUDIO) == null && onMain(song::isPlaying), "Audio-only candidate leaves song playing");
                MediaPlayer video = prepare(VIDEO, output.getSurface(), videoError);
                try {
                    require(onMain(song::isPlaying), "Candidate preparation leaves song playing");
                    seek(video, 1_000);
                    onMain(() -> {
                        song.pause();
                        video.start();
                        return null;
                    });
                    require(onMain(() -> video.isPlaying() && !song.isPlaying()), "Only video plays during handoff");
                    require(frames.await(5, TimeUnit.SECONDS), "Decoder produced video frames");
                    onMain(() -> {
                        video.pause();
                        return null;
                    });
                    require(!onMain(video::isPlaying), "Video paused");
                    seek(video, 2_000);
                    int position = onMain(video::getCurrentPosition);
                    require(Math.abs(position - 2_000) < 500, "Video seek reached its timestamp");
                    onMain(() -> {
                        video.start();
                        return null;
                    });
                    require(onMain(video::isPlaying), "Video resumed");
                    require(videoError.get() == null, "Video error: " + videoError.get());
                } finally {
                    onMain(() -> {
                        video.release();
                        return null;
                    });
                    onMain(() -> {
                        song.start();
                        return null;
                    });
                }
                require(onMain(song::isPlaying) && audioError.get() == null, "Audio restored after video teardown");
            } finally {
                onMain(() -> {
                    song.release();
                    output.setOnImageAvailableListener(null, null);
                    return null;
                });
            }
        }
    }

    private MediaFormat videoFormat(String asset) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        try (AssetFileDescriptor descriptor = getContext().getAssets().openFd(asset)) {
            extractor.setDataSource(descriptor.getFileDescriptor(), descriptor.getStartOffset(), descriptor.getLength());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat track = extractor.getTrackFormat(i);
                String mime = track.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) return track;
            }
            return null;
        } finally {
            extractor.release();
        }
    }

    private MediaPlayer prepare(String asset, Surface surface, AtomicReference<String> error) throws Exception {
        CountDownLatch prepared = new CountDownLatch(1);
        MediaPlayer player = onMain(MediaPlayer::new);
        boolean ready = false;
        try (AssetFileDescriptor descriptor = getContext().getAssets().openFd(asset)) {
            onMain(() -> {
                player.setOnErrorListener((source, what, extra) -> {
                    error.set(what + "/" + extra);
                    prepared.countDown();
                    return true;
                });
                player.setOnPreparedListener(source -> prepared.countDown());
                if (surface != null) player.setSurface(surface);
                player.setDataSource(descriptor.getFileDescriptor(), descriptor.getStartOffset(), descriptor.getLength());
                player.prepareAsync();
                return null;
            });
            require(prepared.await(5, TimeUnit.SECONDS) && error.get() == null, "Prepare: " + asset + " " + error.get());
            ready = true;
            return player;
        } finally {
            if (!ready) onMain(() -> {
                player.release();
                return null;
            });
        }
    }

    private void seek(MediaPlayer player, int time) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        onMain(() -> {
            player.setOnSeekCompleteListener(source -> finished.countDown());
            player.seekTo(time, MediaPlayer.SEEK_CLOSEST);
            return null;
        });
        require(finished.await(5, TimeUnit.SECONDS), "Seek timed out");
    }

    private <T> T onMain(Callable<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        runOnMainSync(() -> {
            try { result.set(action.call()); }
            catch (Exception failed) { error.set(failed); }
        });
        if (error.get() != null) throw error.get();
        return result.get();
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
