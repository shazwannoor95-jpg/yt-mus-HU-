// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.graphics.Matrix;

final class VideoFraming {
    static Matrix transform(int width, int height, int sourceWidth, int sourceHeight, boolean fill) {
        Matrix matrix = new Matrix();
        if (width <= 0 || height <= 0 || sourceWidth <= 0 || sourceHeight <= 0) return matrix;
        float x = width / (float) sourceWidth;
        float y = height / (float) sourceHeight;
        float scale = fill ? Math.max(x, y) : Math.min(x, y);
        matrix.setScale(scale / x, scale / y, width * 0.5f, height * 0.5f);
        return matrix;
    }
}
