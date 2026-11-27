// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.view.ViewGroup.LayoutParams;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class MainActivity extends Activity {
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final MusicApi api = new MusicApi();
    private List<MusicApi.Track> tracks = Collections.emptyList();
    private PlaybackService playback;
    private boolean bound;
    private Future<?> searchTask;
    private int generation;
    private EditText query;
    private LinearLayout results;
    private TextView searchStatus, nowPlaying;
    private Button previous, play, next;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            playback = ((PlaybackService.LocalBinder) binder).service();
            playback.changed = MainActivity.this::renderPlayer;
            renderPlayer();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            playback = null;
            renderPlayer();
        }
    };

    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(24), dp(12), dp(24), dp(12));
        setContentView(layout);

        TextView title = label("YTM Head Unit", 24);
        layout.addView(title);

        LinearLayout searchBar = new LinearLayout(this);
        query = new EditText(this);
        query.setSingleLine(true);
        query.setHint("Tajuk lagu atau artis");
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            search();
            return true;
        });
        searchBar.addView(query, new LinearLayout.LayoutParams(0, dp(56), 1));
        searchBar.addView(button("Cari", this::search), new LinearLayout.LayoutParams(dp(120), dp(56)));
        layout.addView(searchBar);
        searchStatus = label("Cari lagu untuk mula.", 16);
        layout.addView(searchStatus);

        ScrollView scroll = new ScrollView(this);
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(results);
        layout.addView(scroll, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));

        nowPlaying = label("", 18);
        nowPlaying.setSingleLine(true);
        nowPlaying.setEllipsize(TextUtils.TruncateAt.END);
        layout.addView(nowPlaying);

        LinearLayout controls = new LinearLayout(this);
        previous = button("Sebelum", () -> { if (playback != null) playback.skip(-1); });
        play = button("Main", this::playOrOpen);
        next = button("Seterus", () -> { if (playback != null) playback.skip(1); });
        for (Button control : new Button[]{previous, play, next}) {
            controls.addView(control, new LinearLayout.LayoutParams(0, dp(56), 1));
        }
        layout.addView(controls);
        layout.setFocusableInTouchMode(true);
        layout.requestFocus();
        renderPlayer();
    }

    @Override public void onStart() {
        super.onStart();
        bound = bindService(new Intent(this, PlaybackService.class), connection, Context.BIND_AUTO_CREATE);
    }

    @Override public void onStop() {
        if (playback != null) playback.changed = null;
        if (bound) unbindService(connection);
        playback = null;
        bound = false;
        super.onStop();
    }

    @Override public void onDestroy() {
        ++generation;
        network.shutdownNow();
        super.onDestroy();
    }

    private void search() {
        String text = query.getText().toString().trim();
        if (text.isEmpty()) {
            query.setError("Masukkan tajuk lagu atau artis.");
            return;
        }
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(query.getWindowToken(), 0);
        final int current = ++generation;
        if (searchTask != null) searchTask.cancel(true);
        searchStatus.setText("Mencari…");
        results.removeAllViews();
        searchTask = network.submit(() -> {
            try {
                List<MusicApi.Track> found = api.search(text);
                runOnUiThread(() -> {
                    if (generation == current) showResults(found);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (generation == current) {
                        String detail = error.getMessage();
                        searchStatus.setText("Carian gagal" + (detail == null ? "." : " · " + detail));
                    }
                });
            }
        });
    }

    private void showResults(List<MusicApi.Track> found) {
        tracks = found;
        searchStatus.setText(found.isEmpty() ? "Tiada lagu dijumpai." : found.size() + " lagu");
        for (int index = 0; index < found.size(); index++) {
            final int selected = index;
            MusicApi.Track track = found.get(index);
            String name = track.artist.isEmpty() ? track.title : track.title + " · " + track.artist;
            Button result = button(name, () -> {
                if (playback != null) playback.play(tracks, selected);
            });
            result.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);
            result.setMaxLines(2);
            result.setEllipsize(TextUtils.TruncateAt.END);
            results.addView(result, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(56)));
        }
    }

    private void renderPlayer() {
        nowPlaying.setText(playback == null ? "Pilih lagu untuk main." : playback.status());
        previous.setEnabled(playback != null && playback.canSkip(-1));
        play.setEnabled(playback != null && playback.canPlay());
        next.setEnabled(playback != null && playback.canSkip(1));
        String officialId = playback == null ? null : playback.officialTrackId();
        play.setText(officialId != null ? "Buka YouTube Music" :
                playback != null && playback.playing() ? "Jeda" : "Main");
    }

    private void playOrOpen() {
        if (playback == null) return;
        String id = playback.officialTrackId();
        if (id == null) {
            playback.toggle();
            return;
        }
        Uri link = Uri.parse("https://music.youtube.com/watch").buildUpon()
                .appendQueryParameter("v", id).build();
        Intent intent = new Intent(Intent.ACTION_VIEW, link);
        intent.setPackage("com.google.android.apps.youtube.music");
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException missingApp) {
            intent.setPackage(null);
            try {
                startActivity(intent);
            } catch (ActivityNotFoundException missingBrowser) {
                Toast.makeText(this, "YouTube Music atau pelayar diperlukan.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private Button button(String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(17);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private TextView label(String text, int sp) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setPadding(dp(4), dp(4), dp(4), dp(4));
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
