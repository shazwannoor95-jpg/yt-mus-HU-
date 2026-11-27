// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class ShelfPager extends LinearLayout {
    interface Cell {
        View create(int index, int widthPx);
    }

    private final Viewport viewport;
    private final int count, preferred, maximum, gap, slop;
    private final Cell cell;
    private final LinearLayout cells;
    private final Button previous, next;
    private int first, columns = 1, measuredWidth;
    private final int[] savedStart;
    private float downX, downY;
    private boolean horizontalDrag;

    ShelfPager(Context context, Viewport viewport, String title, int count, int preferredDp,
               int colour, Typeface font, int[] savedStart, Cell cell) {
        super(context);
        this.viewport = viewport;
        this.count = count;
        this.savedStart = savedStart;
        first = savedStart[0];
        preferred = dp(preferredDp);
        maximum = dp(preferredDp > 200 ? 340 : Math.max(180, preferredDp));
        gap = dp(viewport.gap);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        this.cell = cell;
        setOrientation(VERTICAL);
        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = new TextView(context);
        heading.setText(title);
        heading.setTextColor(colour);
        heading.setTypeface(font, Typeface.BOLD);
        heading.setTextSize(viewport.sectionSp);
        heading.setPadding(dp(2), dp(4), dp(4), dp(4));
        header.addView(heading, new LayoutParams(0, -2, 1));
        previous = arrow("‹", "Previous " + title, () -> step(-1), colour);
        next = arrow("›", "Next " + title, () -> step(1), colour);
        header.addView(previous, new LayoutParams(dp(Viewport.TOUCH), dp(Viewport.TOUCH)));
        header.addView(next, new LayoutParams(dp(Viewport.TOUCH), dp(Viewport.TOUCH)));
        addView(header);
        cells = new LinearLayout(context);
        addView(cells, new LayoutParams(-1, -2));
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        if (width != measuredWidth && width > 0) {
            measuredWidth = width;
            render(width);
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    private void step(int delta) {
        int requested = first + delta * columns;
        if (requested >= count || requested < 0) return;
        first = requested;
        savedStart[0] = first;
        render(getWidth());
    }

    private void render(int width) {
        if (width <= 0) return;
        columns = viewport.shelfColumns(width, gap, preferred, 8);
        first = Math.min(first, Math.max(0, count - 1)) / columns * columns;
        int cellWidth = Math.min(maximum, Viewport.shelfCell(width, gap, columns));
        cells.removeAllViews();
        for (int i = first; i < Math.min(count, first + columns); i++) {
            LayoutParams params = new LayoutParams(cellWidth, -2);
            if (i != first) params.setMarginStart(gap);
            cells.addView(cell.create(i, cellWidth), params);
        }
        previous.setEnabled(first > 0);
        next.setEnabled(first + columns < count);
        previous.setVisibility(count > columns ? VISIBLE : GONE);
        next.setVisibility(count > columns ? VISIBLE : GONE);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = event.getX();
            downY = event.getY();
            horizontalDrag = false;
        } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE && count > columns
                && downY >= cells.getTop()) {
            float dx = Math.abs(event.getX() - downX);
            if (dx > slop && dx > Math.abs(event.getY() - downY) * 1.5f) {
                horizontalDrag = true;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
        }
        return super.onInterceptTouchEvent(event);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!horizontalDrag) return super.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            float dx = event.getX() - downX;
            if (Math.abs(dx) >= dp(28)) step(dx < 0 ? 1 : -1);
            horizontalDrag = false;
        } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            horizontalDrag = false;
        }
        return true;
    }

    private Button arrow(String text, String description, Runnable action, int colour) {
        Button button = new Button(getContext());
        button.setText(text);
        button.setTextColor(colour);
        button.setTextSize(22);
        button.setPadding(0, 0, 0, 0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setBackgroundResource(android.R.drawable.list_selector_background);
        button.setContentDescription(description);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
