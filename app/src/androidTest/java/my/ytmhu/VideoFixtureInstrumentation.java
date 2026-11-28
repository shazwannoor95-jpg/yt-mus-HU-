// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.AssetFileDescriptor;
import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.net.Uri;
import android.os.Looper;
import android.widget.FrameLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises off-screen and visible playback using only synthetic media in an isolated AVD install. */
public final class VideoFixtureInstrumentation extends Instrumentation {
    private static final String VIDEO = "native-video-fixture.mp4";
    private static final String VIDEO_SMALL = "native-video-fixture-144.mp4";
    private static final String AUDIO = "native-audio-fixture.m4a";

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            exercise();
            result.putString("stream", "\nPASS: off-screen and visible AVC surfaces, fit/fill, fullscreen, seek, pause and audio recovery\n");
            finish(Activity.RESULT_OK, result);
        } catch (Exception | AssertionError failed) {
            result.putString("stream", "\nFAIL: " + failed + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void exercise() throws Exception {
        Context target = getTargetContext();
        require(target.getPackageName().endsWith(".videofixture"), "Use the isolated fixture APK target");
        Class<?> host = Class.forName("my.ytmhu.VideoFixtureActivity", true, target.getClassLoader());
        require(Boolean.TRUE.equals(host.getMethod("permitted", Context.class).invoke(null, target)),
                "Visible fixture is restricted to a non-automotive Android SDK emulator");
        MediaFormat format = videoFormat(VIDEO);
        MediaFormat small = videoFormat(VIDEO_SMALL);
        require(format != null && "video/avc".equals(format.getString(MediaFormat.KEY_MIME)), "Fixture AVC track");
        int width = format.getInteger(MediaFormat.KEY_WIDTH);
        int height = format.getInteger(MediaFormat.KEY_HEIGHT);
        require(width == 320 && height == 180, "Fixture dimensions");
        require(!VideoRenditions.rank(Collections.singletonList(format), PerformancePolicy.Profile.ULTRA_LITE,
                height, 0, VideoRenditions::platformDecoder).isEmpty(), "Platform advertises a compatible local decoder");

        require(small != null && small.getInteger(MediaFormat.KEY_HEIGHT) == 144, "Alternate fixture rendition");
        List<MediaFormat> renditions = VideoRenditions.rank(Arrays.asList(format, small), PerformancePolicy.Profile.ULTRA_LITE,
                height, 0, VideoRenditions::platformDecoder);
        File localAudio = File.createTempFile("ytm-video-fixture-", ".m4a", target.getCacheDir());
        CountDownLatch connected = new CountDownLatch(1);
        AtomicReference<PlaybackService> bound = new AtomicReference<>();
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                bound.set(((PlaybackService.LocalBinder) binder).service());
                connected.countDown();
            }

            @Override public void onServiceDisconnected(ComponentName name) { bound.set(null); }
        };
        try {
            try (InputStream clip = getContext().getAssets().open(AUDIO);
                 FileOutputStream out = new FileOutputStream(localAudio)) {
                byte[] buffer = new byte[4096];
                for (int n; (n = clip.read(buffer)) >= 0;) out.write(buffer, 0, n);
            }
            Intent intent = new Intent(target, PlaybackService.class);
            boolean binding = onMain(() -> target.bindService(intent, connection, Context.BIND_AUTO_CREATE));
            require(binding, "Bind existing PlaybackService");
            try {
                require(connected.await(5, TimeUnit.SECONDS), "PlaybackService bind timed out");
                runServiceFixture(bound.get(), localAudio, width, height);
                runVisibleFixture(bound.get(), renditions, format, host);
            } finally {
                onMain(() -> {
                    target.unbindService(connection);
                    return null;
                });
            }
        } finally {
            localAudio.delete();
        }
    }

    private void runServiceFixture(PlaybackService service, File audioFile, int width, int height) throws Exception {
        Field activePlayer = PlaybackService.class.getDeclaredField("player");
        activePlayer.setAccessible(true);
        Field currentIndex = PlaybackService.class.getDeclaredField("index");
        currentIndex.setAccessible(true);
        Field session = PlaybackService.class.getDeclaredField("session");
        session.setAccessible(true);
        Method prepare = PlaybackService.class.getDeclaredMethod("prepare", List.class, int.class, int.class);
        prepare.setAccessible(true);

        onMain(() -> {
            // This service instance belongs only to the separate fixture package.
            field("queue").set(service, new java.util.ArrayList<MusicApi.Track>());
            field("originalQueue").set(service, new java.util.ArrayList<MusicApi.Track>());
            currentIndex.setInt(service, -1);
            service.enqueue(new MusicApi.Track("fixture-recording", "Fixture", "Local", "", ""), false);
            currentIndex.setInt(service, 0);
            prepare.invoke(service, Collections.singletonList(new MusicApi.AudioSource(
                    Uri.fromFile(audioFile).toString(), "", audioFile.length(), 0)), 0, 0);
            return null;
        });

        await(service::playing, "Local audio running");
        MediaPlayer song = onMain(() -> (MediaPlayer) activePlayer.get(service));
        Object originalSession = onMain(() -> session.get(service));
        onMain(() -> {
            song.setLooping(true);
            return null;
        });

        CountDownLatch frames = new CountDownLatch(2);
        try (ImageReader output = ImageReader.newInstance(width, height, ImageFormat.PRIVATE, 3);
             AssetFileDescriptor video = getContext().getAssets().openFd(VIDEO)) {
            output.setOnImageAvailableListener(reader -> {
                try (Image frame = reader.acquireLatestImage()) {
                    if (frame != null && frame.getTimestamp() > 0) frames.countDown();
                }
            }, new Handler(Looper.getMainLooper()));

            require(!onMain(() -> service.offerOffscreenVideo("wrong-recording", video, output)),
                    "Reject different recording");
            seek(service, 600);
            require(onMain(() -> service.offerOffscreenVideo("fixture-recording", video, output) && song.isPlaying()),
                    "Candidate preparation preserves audio");
            await(service::videoActive, "Video switched through PlaybackService");
            MediaPlayer candidate = onMain(() -> (MediaPlayer) activePlayer.get(service));
            require(onMain(() -> candidate != song && candidate.isPlaying() && !song.isPlaying()),
                    "Only candidate is playing");
            require(frames.await(5, TimeUnit.SECONDS), "Decoder produced video frames");
            onMain(() -> {
                service.toggle();
                return null;
            });
            await(() -> !service.playing() && !candidate.isPlaying(), "Video paused");
            seek(service, 2000);
            require(Math.abs(onMain(service::position) - 2000) < 500, "Video seek reached its timestamp");
            onMain(() -> {
                service.toggle();
                return null;
            });
            await(candidate::isPlaying, "Video resumed");
            onMain(() -> {
                service.endVideo();
                return null;
            });
            await(() -> !service.videoActive() && song.isPlaying(), "Original audio recovered");
            require(onMain(() -> activePlayer.get(service) == song && session.get(service) == originalSession
                    && "fixture-recording".equals(service.currentTrack().id)), "One session, queue and recording");
            require(onMain(service::position) >= 1500, "Recovery preserved playback position");
        } finally {
            onMain(() -> {
                service.endVideo();
                return null;
            });
        }
    }

    private void runVisibleFixture(PlaybackService service, List<MediaFormat> renditions, MediaFormat large, Class<?> host)
            throws Exception {
        Field active = field("player"), session = field("session");
        MediaPlayer original = onMain(() -> (MediaPlayer) active.get(service));
        Object token = onMain(() -> session.get(service));
        Activity screen = startActivitySync(new Intent(getTargetContext(), host).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            VisibleVideoPanel panel = onMain(() -> {
                VisibleVideoPanel view = new VisibleVideoPanel(screen, service, "fixture-recording", renditions,
                        format -> getContext().getAssets().openFd(format == large ? VIDEO : VIDEO_SMALL));
                screen.setContentView(view);
                return view;
            });
            seek(service, 450);
            await(() -> panel.video.isEnabled() && panel.song.isSelected(), "Song default and visible surface ready");
            onMain(panel.video::performClick);
            await(panel::hasVideoFrame, "TextureView acquired a native decoder frame");
            require(onMain(() -> service.videoActive() && !original.isPlaying()), "One audible player after visible handoff");
            onMain(panel.play::performClick);
            await(() -> !service.playing(), "Visible transport pauses the service");
            require(onMain(panel.fit::performClick), "Toggle Fill");
            require(onMain(panel.full::performClick), "Enter fullscreen");
            require(onMain(() -> "Exit fullscreen".contentEquals(panel.full.getText())), "Fullscreen label reflects state");
            require(onMain(panel.full::performClick), "Exit fullscreen");
            seek(service, 950);
            onMain(panel.play::performClick);
            await(service::playing, "Visible transport resumes the service");
            onMain(() -> { screen.setContentView(new FrameLayout(screen)); return null; });
            await(() -> !service.videoActive() && original.isPlaying(), "View detach releases video and resumes audio");
            require(onMain(() -> active.get(service) == original && session.get(service) == token), "Original player and MediaSession preserved");
        } finally {
            onMain(() -> { screen.finish(); service.endVideo(); return null; });
        }
    }

    private static Field field(String name) throws Exception {
        Field field = PlaybackService.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private void seek(PlaybackService service, int position) throws Exception {
        Field pending = PlaybackService.class.getDeclaredField("pendingSeek");
        pending.setAccessible(true);
        onMain(() -> {
            service.seekTo(position);
            return null;
        });
        await(() -> pending.getInt(service) < 0, "Seek completed");
    }

    private void await(Callable<Boolean> condition, String reason) throws Exception {
        long time = SystemClock.uptimeMillis() + 5_000;
        while (SystemClock.uptimeMillis() < time) {
            if (onMain(condition)) return;
            SystemClock.sleep(25);
        }
        throw new AssertionError(reason);
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
