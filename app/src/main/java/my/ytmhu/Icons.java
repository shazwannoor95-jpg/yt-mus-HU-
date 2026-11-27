// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.TextPaint;
import android.text.style.MetricAffectingSpan;

final class Icons {
    static final String HOME = "\ue0f5", EXPLORE = "\ue09b", LIBRARY = "\ue100", SEARCH = "\ue151",
            SETTINGS = "\ue154", USER = "\ue461", PLAY = "\ue13c", PAUSE = "\ue12e", PREVIOUS = "\ue15f",
            NEXT = "\ue160", SHUFFLE = "\ue15e", REPEAT = "\ue146", VOLUME = "\ue1ab", LYRICS = "\ue349",
            QUEUE = "\ue2e0", MORE = "\ue0b7", HEART = "\ue0f2", THUMBS_DOWN = "\ue189", MINI = "\ue3af",
            EXPAND = "\ue070", COLLAPSE = "\ue06d", CLOSE = "\ue1b2";

    private Icons() {}

    static CharSequence withLabel(String icon, String text, Typeface icons) {
        if (text.isEmpty()) return icon;
        SpannableString result = new SpannableString(icon + "  " + text);
        result.setSpan(new MetricAffectingSpan() {
            @Override public void updateDrawState(TextPaint paint) { paint.setTypeface(icons); }
            @Override public void updateMeasureState(TextPaint paint) { paint.setTypeface(icons); }
        }, 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return result;
    }
}
