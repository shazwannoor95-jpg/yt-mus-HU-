// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ListView;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/** Keeps only the visible feed sections and rows attached. Child controls own focus and clicks. */
final class PageRows extends ListView {
    private List<Supplier<View>> rows = Collections.emptyList();
    private final BaseAdapter adapter = new BaseAdapter() {
        @Override public int getCount() { return rows.size(); }
        @Override public Supplier<View> getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View view, ViewGroup parent) {
            FrameLayout row = view == null ? new FrameLayout(parent.getContext()) : (FrameLayout) view;
            Supplier<View> content = getItem(position);
            if (row.getTag() != content) {
                row.removeAllViews();
                row.addView(content.get(), new FrameLayout.LayoutParams(-1, -2));
                row.setTag(content);
            }
            return row;
        }
    };

    PageRows(Context context) {
        super(context);
        setDivider(null);
        setItemsCanFocus(true);
        setSelector(android.R.color.transparent);
        setClipToPadding(false);
        setAdapter(adapter);
    }

    void show(List<Supplier<View>> content, int row, int offset) {
        rows = content;
        adapter.notifyDataSetChanged();
        setSelectionFromTop(Math.max(0, Math.min(content.size() - 1, row)), offset);
    }

    int rowOffset() {
        View first = getChildAt(0);
        return first == null ? 0 : first.getTop() - getPaddingTop();
    }
}
