// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class NowPlayingView extends LinearLayout {
    interface SongMenu {
        void show(View anchor, MusicApi.Track track, int index);
    }

    private static final int FOREGROUND = 0xfff5eef5;
    private static final int MUTED = 0xffc6b8c6;
    private final Artwork artwork;
    private final SongMenu menu;
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final TextView title, artist;
    private final Button lyricsTab, queueTab;
    private final ImageView cover;
    private final ScrollView scroll;
    private final LinearLayout rows;
    private final List<TextView> lines = new ArrayList<>();
    private PlaybackService playback;
    private MusicApi.Track track;
    private boolean lyricsMode;
    private Lyrics lyrics;
    private Future<?> lyricsTask;
    private int activeLine = -1;
    private boolean closed;
    private List<MusicApi.Track> lastQueue = Collections.emptyList();
    private int lastIndex = -2;

    NowPlayingView(Context context, Artwork artwork, SongMenu menu, Runnable close) {
        super(context);
        this.artwork = artwork;
        this.menu = menu;
        setOrientation(HORIZONTAL);
        setPadding(dp(20), dp(14), dp(18), dp(8));
        background(0xff352637);

        LinearLayout left = column();
        cover = new ImageView(context);
        cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
        cover.setContentDescription("Album artwork");
        left.addView(cover, new LayoutParams(-1, 0, 1));
        title = text("Now playing", 24, FOREGROUND);
        title.setTypeface(null, Typeface.BOLD);
        title.setMaxLines(2);
        left.addView(title);
        artist = text("", 16, MUTED);
        left.addView(artist);
        addView(left, new LayoutParams(0, -1, 0.83f));

        LinearLayout right = column();
        right.setPadding(dp(20), 0, 0, 0);
        LinearLayout tabs = new LinearLayout(context);
        lyricsTab = button("Lyrics", () -> showLyrics(true));
        queueTab = button("Up next", () -> showLyrics(false));
        tabs.addView(lyricsTab, new LayoutParams(0, dp(46), 1));
        tabs.addView(queueTab, new LayoutParams(0, dp(46), 1));
        tabs.addView(button("Close", close), new LayoutParams(dp(84), dp(46)));
        right.addView(tabs);
        scroll = new ScrollView(context);
        rows = column();
        scroll.addView(rows);
        right.addView(scroll, new LayoutParams(-1, 0, 1));
        addView(right, new LayoutParams(0, -1, 1.17f));
    }

    void show(PlaybackService playback, boolean lyricsVisible) {
        lyricsMode = lyricsVisible;
        lastIndex = -2;
        update(playback);
        render();
    }

    void update(PlaybackService playback) {
        this.playback = playback;
        MusicApi.Track current = playback == null ? null : playback.currentTrack();
        if (current != track) {
            if (lyricsTask != null) lyricsTask.cancel(true);
            lyricsTask = null;
            track = current;
            lyrics = null;
            lastIndex = -2;
            cover.setTag(null);
            cover.setImageDrawable(null);
            background(0xff352637);
            title.setText(track == null ? "Now playing" : track.title);
            artist.setText(track == null ? "" : track.artist);
            if (track != null) {
                artwork.load(cover, track.artwork, bitmap -> {
                    if (track == current && !closed) {
                        int tone = bitmap.getPixel(bitmap.getWidth() / 2, bitmap.getHeight() / 2);
                        background(Color.rgb(32 + Color.red(tone) / 3, 18 + Color.green(tone) / 3,
                                24 + Color.blue(tone) / 3));
                    }
                });
            }
            render();
        } else if (!lyricsMode) {
            renderQueue();
        }
    }

    private void showLyrics(boolean visible) {
        if (lyricsMode == visible) return;
        lyricsMode = visible;
        lastIndex = -2;
        render();
    }

    private void render() {
        lyricsTab.setTextColor(lyricsMode ? FOREGROUND : MUTED);
        queueTab.setTextColor(lyricsMode ? MUTED : FOREGROUND);
        if (!lyricsMode) {
            renderQueue();
            return;
        }
        rows.removeAllViews();
        scroll.scrollTo(0, 0);
        lines.clear();
        activeLine = -1;
        if (track == null) {
            rows.addView(text("Play a song to see its lyrics.", 17, MUTED));
        } else if (lyrics != null) {
            showLyricsResult();
        } else {
            rows.addView(text("Loading lyrics…", 17, MUTED));
            if (lyricsTask == null) loadLyrics();
        }
    }

    private void loadLyrics() {
        MusicApi.Track current = track;
        int duration = playback == null ? 0 : playback.duration();
        lyricsTask = network.submit(() -> {
            Lyrics result = null;
            try {
                result = Lyrics.load(current, duration);
            } catch (Exception unavailable) {
                // Keep an explicit retry for connection and provider errors.
            }
            Lyrics received = result;
            post(() -> {
                if (track != current || closed) return;
                lyricsTask = null;
                lyrics = received;
                if (!lyricsMode) return;
                rows.removeAllViews();
                if (lyrics == null) {
                    rows.addView(text("Lyrics could not load.", 17, MUTED));
                    rows.addView(button("Retry", this::render));
                } else {
                    showLyricsResult();
                }
            });
        });
    }

    private void showLyricsResult() {
        if (lyrics.lines.isEmpty()) {
            rows.addView(text(lyrics.plain.isEmpty() ? "No lyrics found." : lyrics.plain, 22, FOREGROUND));
        } else {
            for (Lyrics.Line line : lyrics.lines) {
                TextView view = text(line.text, 24, MUTED);
                if (!line.text.isEmpty()) {
                    view.setOnClickListener(clicked -> {
                        if (playback != null) playback.seekTo(line.millis);
                    });
                }
                lines.add(view);
                rows.addView(view);
            }
            tick(playback == null ? 0 : playback.position());
        }
        rows.addView(text(lyrics.source, 13, MUTED));
    }

    void tick(int position) {
        if (!lyricsMode || lyrics == null || lines.isEmpty()) return;
        int index = lyrics.activeAt(position);
        if (activeLine == index) return;
        if (activeLine >= 0) {
            TextView last = lines.get(activeLine);
            last.setTextColor(MUTED);
            last.setTypeface(null, Typeface.NORMAL);
        }
        activeLine = index;
        if (index >= 0) {
            TextView line = lines.get(index);
            line.setTextColor(Color.WHITE);
            line.setTypeface(null, Typeface.BOLD);
            scroll.post(() -> {
                if (activeLine == index && lyricsMode && line.getParent() == rows) {
                    scroll.smoothScrollTo(0, Math.max(0, line.getTop() - scroll.getHeight() / 3));
                }
            });
        } else {
            scroll.smoothScrollTo(0, 0);
        }
    }

    private void renderQueue() {
        List<MusicApi.Track> queue = playback == null ? Collections.emptyList() : playback.queueSnapshot();
        int current = playback == null ? -1 : playback.queueIndex();
        if (lastIndex == current && lastQueue.equals(queue)) return;
        lastIndex = current;
        lastQueue = queue;
        rows.removeAllViews();
        scroll.scrollTo(0, 0);
        if (queue.isEmpty()) {
            rows.addView(text("The queue is empty.", 17, MUTED));
            return;
        }
        for (int i = Math.max(0, current); i < queue.size(); i++) {
            int position = i;
            MusicApi.Track song = queue.get(i);
            LinearLayout row = new LinearLayout(getContext());
            row.setGravity(Gravity.CENTER_VERTICAL);
            ImageView thumbnail = new ImageView(getContext());
            thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            artwork.load(thumbnail, song.artwork);
            row.addView(thumbnail, new LayoutParams(dp(38), dp(38)));
            LinearLayout names = column();
            names.addView(text(song.title, 16, i == current ? 0xffffd2db : FOREGROUND));
            if (!song.artist.isEmpty()) names.addView(text(song.artist, 13, MUTED));
            names.setOnClickListener(view -> {
                if (playback != null) playback.playAt(position);
            });
            row.addView(names, new LayoutParams(0, -2, 1));
            Button options = new Button(getContext());
            options.setText("⋮");
            options.setContentDescription("Actions for " + song.title);
            options.setOnClickListener(view -> menu.show(view, song, position));
            row.addView(options, new LayoutParams(dp(48), dp(48)));
            rows.addView(row, new LayoutParams(-1, -2));
        }
    }

    void close() {
        closed = true;
        network.shutdownNow();
    }

    private void background(int tone) {
        setBackground(new GradientDrawable(GradientDrawable.Orientation.TR_BL, new int[]{tone, 0xff18101e}));
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(getContext());
        view.setOrientation(VERTICAL);
        return view;
    }

    private Button button(String label, Runnable action) {
        Button button = new Button(getContext());
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private TextView text(String label, int size, int color) {
        TextView text = new TextView(getContext());
        text.setText(label);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setPadding(dp(8), dp(4), dp(6), dp(4));
        return text;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
