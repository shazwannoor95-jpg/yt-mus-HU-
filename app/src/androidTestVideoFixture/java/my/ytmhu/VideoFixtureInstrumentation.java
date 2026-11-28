// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;

import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Optional native smoke for the same Activity opened from the launcher. */
public final class VideoFixtureInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            exercise();
            result.putString("stream", "\nPASS: launcher, native frames, qualities, transport, fullscreen and audio recovery\n");
            finish(Activity.RESULT_OK, result);
        } catch (Exception | AssertionError failure) {
            result.putString("stream", "\nFAIL: " + failure + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void exercise() throws Exception {
        require(DemoPlaybackPolicy.permitted(getTargetContext()), "Use a non-automotive Android SDK emulator");
        Intent launch = getTargetContext().getPackageManager().getLaunchIntentForPackage(DemoPlaybackPolicy.PACKAGE);
        require(launch != null, "Demo launcher entry");
        VideoFixtureActivity screen = (VideoFixtureActivity) startActivitySync(launch);
        try {
            await(() -> screen.panel != null && screen.panel.video.isEnabled(), "Bundled audio and visible surface");
            VisibleVideoPanel panel = onMain(() -> screen.panel);
            require(onMain(panel.song::isSelected), "Song is selected at startup");
            onMain(panel.video::performClick);
            await(panel::hasVideoFrame, "TextureView received a decoded frame");
            onMain(() -> { panel.setQuality(144); return null; });
            await(() -> panel.renderedHeight() == 144, "Manual 144p renders");
            onMain(() -> { panel.setQuality(180); return null; });
            await(() -> panel.renderedHeight() == 180, "Manual 180p renders");
            onMain(panel.play::performClick);
            await(() -> "Play".contentEquals(panel.play.getContentDescription()), "Pause from visible controls");
            onMain(panel.fit::performClick);
            require(onMain(() -> "Fit".contentEquals(panel.fit.getText())), "Fill applied");
            onMain(panel.full::performClick);
            require(onMain(() -> "Exit fullscreen".contentEquals(panel.full.getText())), "Fullscreen opens");
            onMain(panel.full::performClick);
            onMain(() -> { seekToMiddle(panel); return null; });
            await(() -> panel.seek.getProgress() > 8_000 && panel.seek.getProgress() < 16_000, "Touch seek moved into the track");
            onMain(panel.song::performClick);
            await(() -> panel.song.isSelected() && !panel.hasVideoFrame(), "Song stops showing video");
            onMain(panel.play::performClick);
            await(() -> "Pause".contentEquals(panel.play.getContentDescription()), "Original audio resumes");
            onMain(panel.next::performClick);
            await(() -> panel.previous.isEnabled() && panel.next.isEnabled() && panel.video.isEnabled(), "Next plays the second local song");
            onMain(panel.video::performClick);
            await(panel::hasVideoFrame, "Second recording has its own paired video");
            onMain(panel.previous::performClick);
            await(() -> !panel.previous.isEnabled() && panel.next.isEnabled() && panel.hasVideoFrame(), "Previous retains video on the first recording");
            onMain(panel.next::performClick);
            onMain(panel.next::performClick);
            await(() -> panel.previous.isEnabled() && !panel.next.isEnabled() && panel.hasVideoFrame(),
                    "Next plays the licensed Rabbit recording with its own paired video");
            onMain(() -> { panel.setQuality(240); return null; });
            await(() -> panel.renderedHeight() == 240, "Licensed original rendition renders at 240p");
        } finally {
            onMain(() -> { screen.finish(); return null; });
        }
    }

    private void seekToMiddle(VisibleVideoPanel panel) {
        long now = SystemClock.uptimeMillis();
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(now, now, action, panel.seek.getWidth() / 2f, panel.seek.getHeight() / 2f, 0);
            try { panel.seek.dispatchTouchEvent(event); }
            finally { event.recycle(); }
        }
    }

    private void await(Callable<Boolean> condition, String reason) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 7000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (onMain(condition)) return;
            SystemClock.sleep(40);
        }
        throw new AssertionError(reason);
    }

    private <T> T onMain(Callable<T> action) throws Exception {
        FutureTask<T> work = new FutureTask<>(action);
        runOnMainSync(work);
        return work.get(5, TimeUnit.SECONDS);
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
