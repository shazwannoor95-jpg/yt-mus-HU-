// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Debounced public suggestions. Only one request can be in flight. */
final class SearchSuggestions implements AutoCloseable {
    interface Fetcher { JSONObject fetch(String input) throws Exception; }

    private final AutoCompleteTextView input;
    private final AppSettings prefs;
    private final Fetcher fetcher;
    private final Appearance appearance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LinkedHashMap<String, Cached> cache = new LinkedHashMap<>(8, 0.75f, true);
    private final Runnable scheduled = this::fetch;
    private int revision;
    private boolean loading, pending, closed;

    private static final class Cached {
        final List<Browse.Suggestion> choices;
        final long expires = SystemClock.uptimeMillis() + 60_000;

        Cached(List<Browse.Suggestion> choices) { this.choices = choices; }
    }

    SearchSuggestions(AutoCompleteTextView input, AppSettings prefs, Appearance appearance, Fetcher fetcher,
                      Consumer<Browse.Suggestion> selected) {
        this.input = input;
        this.prefs = prefs;
        this.appearance = appearance;
        this.fetcher = fetcher;
        input.setThreshold(3);
        input.setOnItemClickListener((parent, view, position, id) -> {
            Object item = parent.getItemAtPosition(position);
            clear();
            if (item instanceof Browse.Suggestion) selected.accept((Browse.Suggestion) item);
        });
        input.setOnFocusChangeListener((view, focused) -> schedule());
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable text) {
                if (!input.isPerformingCompletion()) schedule();
            }
        });
    }

    private void schedule() {
        clear();
        if (eligible()) main.postDelayed(scheduled, 360);
    }

    private boolean eligible() {
        String value = term();
        return !closed && input.hasFocus() && input.isShown() && value.length() >= 3 && value.length() <= 120;
    }

    private String term() { return input.getText().toString().trim(); }

    private void fetch() {
        if (!eligible()) return;
        String value = term();
        boolean hideExplicit = prefs.flag("hideExplicit", false);
        String key = value + '\n' + hideExplicit;
        Cached previous = cache.get(key);
        if (previous != null && previous.expires > SystemClock.uptimeMillis()) {
            show(previous.choices);
            return;
        }
        if (loading) {
            pending = true;
            return;
        }
        loading = true;
        int ticket = revision;
        worker.execute(() -> {
            List<Browse.Suggestion> choices = null;
            try {
                choices = Browse.searchSuggestions(fetcher.fetch(value), hideExplicit);
            } catch (Exception unavailable) {
                // The normal search path stays available when suggestions are unavailable.
            }
            boolean received = choices != null;
            List<Browse.Suggestion> result = received ? choices : Collections.emptyList();
            main.post(() -> {
                loading = false;
                if (closed) return;
                if (received) {
                    cache.put(key, new Cached(result));
                    if (cache.size() > 8) cache.remove(cache.keySet().iterator().next());
                }
                if (ticket == revision && eligible() && value.equals(term()) && hideExplicit == prefs.flag("hideExplicit", false)) show(result);
                if (pending) {
                    pending = false;
                    fetch();
                }
            });
        });
    }

    private void show(List<Browse.Suggestion> choices) {
        if (choices.isEmpty()) return;
        input.setAdapter(new ArrayAdapter<Browse.Suggestion>(input.getContext(), android.R.layout.simple_dropdown_item_1line, choices) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                TextView row = (TextView) super.getView(position, recycled, parent);
                Browse.Suggestion choice = getItem(position);
                String subtitle = choice.card == null ? "" : choice.card.subtitle;
                row.setText(subtitle.isEmpty() ? choice.title : choice.title + " · " + subtitle);
                row.setSingleLine();
                row.setEllipsize(TextUtils.TruncateAt.END);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setTypeface(appearance.font);
                row.setTextColor(0xfff4eef4);
                row.setTextSize(14);
                row.setMinimumHeight(Math.round(Viewport.TOUCH * input.getResources().getDisplayMetrics().density));
                return row;
            }
        });
        input.showDropDown();
    }

    void clear() {
        ++revision;
        pending = false;
        main.removeCallbacks(scheduled);
        input.dismissDropDown();
    }

    @Override public void close() {
        closed = true;
        clear();
        cache.clear();
        worker.shutdownNow();
    }
}
