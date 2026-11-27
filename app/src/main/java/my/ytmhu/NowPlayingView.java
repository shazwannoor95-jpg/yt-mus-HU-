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
    private final AppSettings prefs;
    private final Appearance appearance;
    private final SongMenu menu;
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final TextView title, artist;
    private final Button lyricsTab, queueTab;
    private final ImageView cover;
    private final FrostedBackdrop frost;
    private final ScrollView scroll;
    private final LinearLayout rows;
    private final List<TextView> lines = new ArrayList<>();
    private PlaybackService playback;
    private MusicApi.Track track;
    private boolean lyricsMode;
    private Lyrics lyrics;
    private Future<?> lyricsTask;
    private int lyricsGeneration;
    private int activeLine = -1;
    private boolean closed;
    private List<MusicApi.Track> lastQueue = Collections.emptyList();
    private int lastIndex = -2;

    NowPlayingView(Context context, AppSettings prefs, Appearance appearance, Artwork artwork, SongMenu menu, Runnable close) {
        super(context);
        this.prefs = prefs;
        this.appearance = appearance;
        this.artwork = artwork;
        this.menu = menu;
        setOrientation(HORIZONTAL);
        setPadding(dp(20), dp(14), dp(18), dp(8));
        frost = appearance.glass ? new FrostedBackdrop(appearance.start,
                prefs.flag("flat", false) ? appearance.start : appearance.end, appearance.scrim, prefs.number("brightness", 100)) : null;
        background(appearance.start);

        LinearLayout left = column();
        cover = new ImageView(context);
        cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
        cover.setContentDescription("Album artwork");
        android.graphics.drawable.GradientDrawable mask = new android.graphics.drawable.GradientDrawable();
        mask.setColor(android.graphics.Color.TRANSPARENT);
        mask.setCornerRadius(dp(appearance.coverRadius));
        cover.setBackground(mask);
        cover.setClipToOutline(true);
        left.addView(cover, new LayoutParams(-1, 0, 1));
        title = text("Now playing", appearance.titleSize, FOREGROUND);
        title.setTypeface(appearance.font, Typeface.BOLD);
        title.setMaxLines(2);
        left.addView(title);
        artist = text("", appearance.artistSize, MUTED);
        left.addView(artist);
        if (appearance.centerNowPlaying) {
            title.setGravity(Gravity.CENTER);
            artist.setGravity(Gravity.CENTER);
        }
        addView(left, new LayoutParams(0, -1, 0.83f));

        LinearLayout right = column();
        right.setPadding(dp(20), 0, 0, 0);
        LinearLayout tabs = new LinearLayout(context);
        lyricsTab = button("Lyrics", () -> showLyrics(true));
        queueTab = button("Up next", () -> showLyrics(false));
        tabs.setPadding(dp(6), 0, 0, dp(6));
        addTab(tabs, lyricsTab, 0, 1);
        addTab(tabs, queueTab, 0, 1);
        addTab(tabs, button("Close", close), dp(72), 0);
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
            invalidateLyrics();
            track = current;
            lastIndex = -2;
            cover.setTag(null);
            cover.setImageDrawable(null);
            if (frost != null) frost.setAlbum(null);
            background(appearance.start);
            title.setText(track == null ? "Now playing" : track.title);
            artist.setText(track == null ? "" : track.artist);
            if (track != null) {
                artwork.loadDetail(cover, track.artwork, bitmap -> {
                    if (track == current && !closed && appearance.albumTint && prefs.flag("coverColors", true)
                            && !prefs.flag("flat", false)) {
                        if (frost != null) {
                            frost.setAlbum(bitmap);
                        } else {
                            int tone = bitmap.getPixel(bitmap.getWidth() / 2, bitmap.getHeight() / 2);
                            background(Color.rgb(32 + Color.red(tone) / 3, 18 + Color.green(tone) / 3,
                                    24 + Color.blue(tone) / 3));
                        }
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
        lyricsTab.setSelected(lyricsMode);
        queueTab.setSelected(!lyricsMode);
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
        int loading = ++lyricsGeneration;
        lyricsTask = network.submit(() -> {
            Lyrics result = null;
            try {
                result = Lyrics.load(current, duration, prefs);
            } catch (Exception unavailable) {
                // Keep an explicit retry for connection and provider errors.
            }
            Lyrics received = result;
            post(() -> {
                if (track != current || loading != lyricsGeneration || closed) return;
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
            rows.addView(lyricsLine(lyrics.plain.isEmpty() ? "No lyrics found." : lyrics.plain, FOREGROUND));
        } else {
            for (Lyrics.Line line : lyrics.lines) {
                TextView view = lyricsLine(line.text, MUTED);
                if (!line.text.isEmpty()) {
                    view.setOnClickListener(clicked -> {
                        if (playback != null) playback.seekTo(line.millis + prefs.lyricOffset());
                    });
                }
                lines.add(view);
                rows.addView(view);
            }
            tick(playback == null ? 0 : playback.position());
        }
        if (!lyrics.source.isEmpty()) rows.addView(text(lyrics.source, 13, MUTED));
    }

    void tick(int position) {
        if (!lyricsMode || lyrics == null || lines.isEmpty()) return;
        int index = lyrics.activeAt(position - prefs.lyricOffset());
        if (activeLine == index) return;
        if (activeLine >= 0) {
            TextView last = lines.get(activeLine);
            last.setTextColor(MUTED);
            last.setTypeface(last.getTypeface(), "bold".equals(prefs.text("lyricsWeight", "theme")) ? Typeface.BOLD : Typeface.NORMAL);
        }
        activeLine = index;
        if (index >= 0) {
            TextView line = lines.get(index);
            line.setTextColor(Color.WHITE);
            line.setTypeface(line.getTypeface(), "regular".equals(prefs.text("lyricsWeight", "theme")) ? Typeface.NORMAL : Typeface.BOLD);
            scroll.post(() -> {
                if (activeLine == index && lyricsMode && line.getParent() == rows) {
                    int top = Math.max(0, line.getTop() - scroll.getHeight() / 3);
                    if (prefs.flag("quickLyrics", false)) scroll.scrollTo(0, top);
                    else scroll.smoothScrollTo(0, top);
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
            options.setText(Icons.MORE);
            options.setTypeface(appearance.icons);
            options.setContentDescription("Actions for " + song.title);
            options.setOnClickListener(view -> menu.show(view, song, position));
            row.addView(options, new LayoutParams(dp(48), dp(48)));
            rows.addView(row, new LayoutParams(-1, -2));
        }
    }

    void invalidateLyrics() {
        if (lyricsTask != null) lyricsTask.cancel(true);
        ++lyricsGeneration;
        lyricsTask = null;
        lyrics = null;
    }

    void close() {
        closed = true;
        network.shutdownNow();
    }

    private TextView lyricsLine(String line, int color) {
        TextView view = text(line, appearance.lyricsSize * prefs.number("lyricsSize", 100) / 100, color);
        if ("classic".equals(prefs.text("lyricsStyle", "theme"))) view.setGravity(Gravity.CENTER);
        if ("bold".equals(prefs.text("lyricsWeight", "theme"))) view.setTypeface(view.getTypeface(), Typeface.BOLD);
        return view;
    }

    private void background(int tone) {
        if (frost != null) setBackground(frost);
        else setBackground(Appearance.background(prefs, GradientDrawable.Orientation.TR_BL, tone, appearance.solid ? tone : appearance.end));
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
        button.setTypeface(appearance.font);
        button.setTextColor(new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_selected}, new int[0]},
                new int[]{FOREGROUND, MUTED}));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(3), 0, dp(3), 0);
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{android.R.attr.state_selected}, tabShape(appearance.glass ? 0x36ffffff : appearance.control));
        states.addState(new int[0], tabShape(appearance.glass ? 0x12ffffff : appearance.surface));
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x48ffffff), states, null));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private GradientDrawable tabShape(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(9));
        return shape;
    }

    private void addTab(LinearLayout row, Button button, int width, float weight) {
        LayoutParams params = new LayoutParams(width, dp(40), weight);
        params.rightMargin = dp(5);
        row.addView(button, params);
    }

    private TextView text(String label, int size, int color) {
        TextView text = new TextView(getContext());
        text.setText(label);
        text.setTextSize(size);
        text.setTypeface(appearance.font);
        text.setTextColor(color);
        text.setPadding(dp(8), dp(4), dp(6), dp(4));
        return text;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
