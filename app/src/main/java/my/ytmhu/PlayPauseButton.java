// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.widget.Button;

final class PlayPauseButton extends Button {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path triangle = new Path();
    private boolean playing;

    PlayPauseButton(Context context) {
        super(context);
        triangle.moveTo(-6, -9);
        triangle.lineTo(9, 0);
        triangle.lineTo(-6, 9);
        triangle.close();
        showPlaying(false);
    }

    void showPlaying(boolean playing) {
        this.playing = playing;
        setContentDescription(playing ? "Pause" : "Play");
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        paint.setColor(getCurrentTextColor());
        if (!isEnabled()) paint.setAlpha(110);
        int save = canvas.save();
        canvas.translate(getWidth() * 0.5f, getHeight() * 0.5f);
        canvas.scale(density, density);
        if (playing) {
            canvas.drawRoundRect(-7, -9, -2, 9, 1.2f, 1.2f, paint);
            canvas.drawRoundRect(2, -9, 7, 9, 1.2f, 1.2f, paint);
        } else {
            canvas.drawPath(triangle, paint);
        }
        canvas.restoreToCount(save);
    }
}
