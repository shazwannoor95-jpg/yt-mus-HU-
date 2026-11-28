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

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;

final class NowPlayingView extends LinearLayout {
    interface SongMenu {
        void show(View anchor, MusicApi.Track track, int index);
    }

    private static final int FOREGROUND = 0xfff5eef5;
    private static final int INACTIVE_LYRIC = 0xffb3aeb9;
    private static final int MUTED = 0xffc6b8c6;
    private final Artwork artwork;
    private final LocalRomanizer romanizer = new LocalRomanizer();
    private final AppSettings prefs;
    private final Appearance appearance;
    private final SongMenu menu;
    private final ThreadPoolExecutor network = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(2));
    private final TextView title, artist;
    private final Button lyricsTab, queueTab;
    private final ImageView cover;
    private final FrostedBackdrop frost;
    private final ScrollView scroll;
    private final PageRows queueRows;
    private final LinearLayout rows;
    private final List<TextView> lines = new ArrayList<>();
    private PlaybackService playback;
    private MusicApi.Track track;
    private boolean lyricsMode;
    private Lyrics lyrics;
    private Future<?> lyricsTask, extrasTask;
    private LyricExtras extrasRequest;
    private final List<List<TextView>> alternates = new ArrayList<>();
    private TextView romanStatus, translationStatus;
    private int lyricsGeneration;
    private int activeLine = -1;
    private boolean closed;
    private List<MusicApi.Track> lastQueue = Collections.emptyList();
    private int lastIndex = -2;
    private PerformancePolicy.Profile profile = PerformancePolicy.Profile.BALANCED;

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
        Button closeTab = button(Icons.CLOSE, close);
        closeTab.setTypeface(appearance.icons);
        closeTab.setContentDescription("Close Now Playing");
        addTab(tabs, closeTab, dp(Viewport.TOUCH), 0);
        right.addView(tabs);
        scroll = new ScrollView(context);
        rows = column();
        scroll.addView(rows);
        android.widget.FrameLayout panel = new android.widget.FrameLayout(context);
        panel.addView(scroll, new android.widget.FrameLayout.LayoutParams(-1, -1));
        queueRows = new PageRows(context);
        panel.addView(queueRows, new android.widget.FrameLayout.LayoutParams(-1, -1));
        right.addView(panel, new LayoutParams(-1, 0, 1));
        addView(right, new LayoutParams(0, -1, 1.17f));
    }

    boolean lyricsVisible() { return lyricsMode; }
    boolean hasTimedLyrics() { return lyrics != null && !lyrics.lines.isEmpty(); }

    void setProfile(PerformancePolicy.Profile next) {
        if (profile == next) return;
        profile = next;
        if (frost != null) frost.setProfile(next);
        if (track != null && isShown()) bindCover(track);
    }

    void show(PlaybackService playback, boolean lyricsVisible) {
        lyricsMode = lyricsVisible;
        lastIndex = -2;
        boolean sameTrack = track == (playback == null ? null : playback.currentTrack());
        update(playback);
        if (sameTrack) render();
    }

    void update(PlaybackService playback) {
        this.playback = playback;
        MusicApi.Track current = playback == null ? null : playback.currentTrack();
        if (current != track) {
            invalidateLyrics();
            track = current;
            lastIndex = -2;
            artwork.unbind(cover);
            cover.setImageDrawable(null);
            if (frost != null) frost.setAlbum(null);
            background(appearance.start);
            title.setText(track == null ? "Now playing" : track.title);
            artist.setText(track == null ? "" : track.artist);
            if (track != null) bindCover(track);
            render();
        } else if (!lyricsMode) {
            renderQueue();
        }
    }

    private void bindCover(MusicApi.Track current) {
        artwork.loadDetail(cover, current.artwork, bitmap -> {
            if (track != current || closed || !appearance.albumTint || !prefs.flag("coverColors", true)
                    || prefs.flag("flat", false)) return;
            if (frost != null) {
                frost.setAlbum(bitmap);
            } else {
                int tone = bitmap.getPixel(bitmap.getWidth() / 2, bitmap.getHeight() / 2);
                background(Color.rgb(32 + Color.red(tone) / 3, 18 + Color.green(tone) / 3, 24 + Color.blue(tone) / 3));
            }
        });
    }

    private void showLyrics(boolean visible) {
        if (lyricsMode == visible) return;
        lyricsMode = visible;
        lastIndex = -2;
        render();
    }

    private void render() {
        cancelExtras();
        lyricsTab.setSelected(lyricsMode);
        queueTab.setSelected(!lyricsMode);
        scroll.setVisibility(lyricsMode ? VISIBLE : GONE);
        queueRows.setVisibility(lyricsMode ? GONE : VISIBLE);
        if (!lyricsMode) {
            renderQueue();
            return;
        }
        rows.removeAllViews();
        scroll.scrollTo(0, 0);
        lines.clear();
        alternates.clear();
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
        boolean roman = prefs.flag("lyricRoman", false);
        boolean translate = prefs.flag("lyricTranslation", false);
        if (lyrics.lines.isEmpty() && (lyrics.plain.isEmpty() || (!roman && !translate))) {
            rows.addView(lyricsLine(lyrics.plain.isEmpty() ? "No lyrics found." : lyrics.plain, FOREGROUND));
        } else {
            List<Lyrics.Line> display = lyrics.displayLines();
            for (Lyrics.Line line : display) {
                LinearLayout group = column();
                TextView view = lyricsLine(line.text, line.millis < 0 ? FOREGROUND : INACTIVE_LYRIC);
                int padding = dp(Math.round(8 * prefs.number("lyricSpacing", 100) / 100f));
                group.setPadding(0, padding / 2, 0, padding - padding / 2);
                group.addView(view);
                if (line.millis >= 0 && !line.text.isEmpty()) group.setOnClickListener(clicked -> {
                    if (playback != null) playback.seekTo(line.millis + prefs.lyricOffset());
                });
                lines.add(view);
                List<TextView> extras = new ArrayList<>();
                if (roman) extras.add(addAlternate(group, "lyricRomanSize"));
                if (translate) extras.add(addAlternate(group, "lyricTranslationSize"));
                alternates.add(extras);
                rows.addView(group);
            }
            if (roman || translate) {
                romanStatus = roman ? alternateStatus() : null;
                translationStatus = translate ? alternateStatus() : null;
                showExtras(display, new org.json.JSONObject(), Collections.emptyList(), roman, translate,
                        prefs.text("lyricTarget", "en"));
                loadExtras(display, roman, translate);
            }
            tick(playback == null ? 0 : playback.position());
        }
        if (!lyrics.source.isEmpty()) rows.addView(text(lyrics.source, 13, MUTED));
    }

    private TextView addAlternate(LinearLayout group, String key) {
        TextView view = lyricsLine("", INACTIVE_LYRIC);
        view.setTextSize(appearance.lyricsSize * prefs.number("lyricsSize", 100) / 100f
                * prefs.number(key, "lyricTranslationSize".equals(key) ? 65 : 75) / 100f);
        view.setVisibility(GONE);
        group.addView(view);
        return view;
    }

    private TextView alternateStatus() {
        TextView status = text("", 13, MUTED);
        status.setVisibility(GONE);
        rows.addView(status);
        return status;
    }

    private boolean currentExtras(Lyrics original, LyricExtras request) {
        return !closed && lyricsMode && original == lyrics && extrasRequest == request;
    }

    void clearRomanizationCache() { romanizer.clear(); }

    private void loadExtras(List<Lyrics.Line> display, boolean roman, boolean translate) {
        cancelExtras();
        Lyrics original = lyrics;
        String target = prefs.text("lyricTarget", "en");
        String hanPreference = prefs.text("lyricHanLanguage", "auto");
        String title = track == null ? "" : track.title;
        LyricExtras request = new LyricExtras(getContext());
        extrasRequest = request;
        extrasTask = network.submit(() -> {
            String inferred = LocalRomanizer.language(original.language, title, display);
            String language = !"auto".equals(hanPreference) && inferred.isEmpty() ? hanPreference : inferred;
            List<LocalRomanizer.Reading> local = new ArrayList<>();
            LocalRomanizer.DictionaryState dictionaryState = LocalRomanizer.DictionaryState.MISSING;
            if (roman) {
                try (LocalRomanizer.Batch batch = romanizer.new Batch(getContext(), new JapaneseDictionary(getContext()))) {
                    for (Lyrics.Line line : display) {
                        if (Thread.currentThread().isInterrupted()) return;
                        local.add(LyricExtras.clean(line.roman, line.text).isEmpty() ? batch.read(line.text, language) : LocalRomanizer.Reading.ORIGINAL);
                    }
                    dictionaryState = batch.dictionaryState();
                }
            }
            boolean fetchTranslation = translate && !sameLanguage(inferred, target);
            List<String> requested = LyricExtras.needed(display, local, roman, fetchTranslation, target);
            LocalRomanizer.DictionaryState status = dictionaryState;
            if (!requested.isEmpty() && roman) {
                post(() -> {
                    if (currentExtras(original, request)) {
                        showExtras(display, new org.json.JSONObject(), local, true, translate, target);
                    }
                });
            }
            String failure = "";
            JSONObject values = new JSONObject();
            if (!requested.isEmpty() && !Thread.currentThread().isInterrupted()) {
                try { values = request.load(requested, target, inferred); }
                catch (Exception unavailable) { failure = LyricExtras.failure(unavailable); }
            }
            if (fetchTranslation && !Thread.currentThread().isInterrupted()) {
                List<String> missing = LyricExtras.missingTranslations(display, values, target);
                if (!missing.isEmpty()) {
                    try {
                        String key = new LyricCloudKey(getContext()).read();
                        if (!key.isEmpty()) {
                            for (int i = 0; i < missing.size() && !Thread.currentThread().isInterrupted(); i += LyricExtras.CLOUD_BATCH) {
                                List<String> batch = missing.subList(i, Math.min(i + LyricExtras.CLOUD_BATCH, missing.size()));
                                JSONObject result = request.cloud(batch, target, inferred, key);
                                for (String text : batch) {
                                    JSONObject received = result.optJSONObject(text);
                                    if (received == null) continue;
                                    JSONObject existing = values.optJSONObject(text);
                                    if (existing != null && existing.has("romanization")) {
                                        received.put("romanization", existing.optString("romanization"));
                                    }
                                    values.put(text, received);
                                }
                            }
                            failure = "";
                        }
                    } catch (Exception unavailable) {
                        failure = LyricExtras.failure(unavailable);
                    }
                }
            }
            if (Thread.currentThread().isInterrupted()) return;
            JSONObject received = values;
            String providerFailure = failure;
            post(() -> {
                if (!currentExtras(original, request)) return;
                showExtras(display, received, local, roman, translate, target);
                showDiagnostics(display, received, local, roman, translate, target,
                        language, status, fetchTranslation, providerFailure);
            });
        });
    }

    private void showDiagnostics(List<Lyrics.Line> display, JSONObject values, List<LocalRomanizer.Reading> local,
                                 boolean roman, boolean translate, String target, String language,
                                 LocalRomanizer.DictionaryState dictionaryState, boolean fetchTranslation, String failure) {
        Set<String> readings = new LinkedHashSet<>();
        Set<String> translations = new LinkedHashSet<>();
        boolean missingRoman = false, japaneseHan = false, missingTranslation = false;
        for (int i = 0; i < display.size(); i++) {
            Lyrics.Line line = display.get(i);
            if (line.text.trim().isEmpty() || "♪".equals(line.text.trim())) continue;
            if (roman && LyricExtras.nonLatin(line.text)) {
                if (!LyricExtras.clean(line.roman, line.text).isEmpty()) readings.add("source-provided");
                else if (local.get(i).complete) {
                    readings.add(LocalRomanizer.japaneseHan(line.text, language) ? "local dictionary" : "local conversion");
                } else if (!extra(values, line.text, "romanization").isEmpty()) readings.add("Unison");
                else {
                    if (!local.get(i).text.isEmpty()) readings.add("partial local conversion");
                    missingRoman = true;
                    japaneseHan |= LocalRomanizer.japaneseHan(line.text, language);
                }
            }
            if (!translate) continue;
            if (!LyricExtras.clean(line.translation(target), line.text).isEmpty()) translations.add("source-provided");
            else if (!extra(values, line.text, "translation").isEmpty()) {
                translations.add("machine translation by " + extra(values, line.text, "provider"));
            } else if (fetchTranslation) missingTranslation = true;
        }
        if (roman) {
            String state = readings.isEmpty() ? "" : "Romanization: " + String.join(", ", readings) + ".";
            if (missingRoman) {
                String advice = "Some readings are unsupported or could not be converted.";
                if (japaneseHan && dictionaryState == LocalRomanizer.DictionaryState.MISSING) {
                    advice = "For kanji readings, install the Japanese dictionary in Settings › Lyrics (" + JapaneseDictionary.DOWNLOAD_SIZE + ").";
                } else if (japaneseHan && dictionaryState == LocalRomanizer.DictionaryState.MEMORY) {
                    advice = "Japanese dictionary paused to save memory. Reopen Lyrics when more memory is available.";
                } else if (japaneseHan && dictionaryState == LocalRomanizer.DictionaryState.ERROR) {
                    advice = "Japanese dictionary could not open. Reinstall it in Settings › Lyrics.";
                } else if (language.isEmpty() && LocalRomanizer.hasHan(display)) {
                    advice = "For Han-only lyrics with unknown language, choose Japanese or Chinese in Settings › Lyrics.";
                }
                state = state.isEmpty() ? "Romanization: " + advice : state + " " + advice;
            }
            showAlternate(romanStatus, state, false);
        }
        if (translate) {
            String state = translations.isEmpty() ? "" : "Translation: " + String.join(", ", translations) + ".";
            if (missingTranslation) {
                String advice = failure.isEmpty() ? "Some lines have no translation in the selected language." : failure;
                state = state.isEmpty() ? "Translation: " + advice : state + " " + advice;
            }
            showAlternate(translationStatus, state, false);
        }
    }

    private static boolean sameLanguage(String first, String second) {
        return first.split("[-_]", 2)[0].equalsIgnoreCase(second.split("[-_]", 2)[0]);
    }

    private void showExtras(List<Lyrics.Line> display, org.json.JSONObject values,
                            List<LocalRomanizer.Reading> local, boolean roman, boolean translate, String target) {
        for (int i = 0; i < display.size(); i++) {
            Lyrics.Line line = display.get(i);
            if (roman) {
                String value = LyricExtras.clean(line.roman, line.text);
                LocalRomanizer.Reading reading = i < local.size() ? local.get(i) : LocalRomanizer.Reading.NONE;
                if (value.isEmpty() && reading.complete) value = reading.text;
                if (value.isEmpty()) value = extra(values, line.text, "romanization");
                if (value.isEmpty()) value = reading.text;
                showAlternate(alternates.get(i).get(0), value, i == activeLine);
            }
            if (translate) {
                String value = LyricExtras.clean(line.translation(target), line.text);
                if (value.isEmpty()) value = extra(values, line.text, "translation");
                TextView view = alternates.get(i).get(roman ? 1 : 0);
                showAlternate(view, value, i == activeLine);
                if (!value.isEmpty()) {
                    String provider = extra(values, line.text, "provider");
                    view.setContentDescription(LyricExtras.clean(line.translation(target), line.text).isEmpty() && !provider.isEmpty()
                            ? "Machine translation by " + provider + ". " + value : value);
                }

            }
            boolean hasAlternate = alternates.get(i).stream().anyMatch(view -> view.getVisibility() == VISIBLE);
            lines.get(i).setVisibility(!hasAlternate || prefs.flag("lyricOriginal", true) ? VISIBLE : GONE);
        }
        if (activeLine >= 0) followActive(activeLine, lines.get(activeLine));
    }

    private static String extra(org.json.JSONObject values, String text, String key) {
        return LyricExtras.value(values, text, key);
    }

    private static void showAlternate(TextView view, String text, boolean active) {
        view.setText(text);
        view.setTextColor(active ? FOREGROUND : INACTIVE_LYRIC);
        view.setVisibility(text.isEmpty() ? GONE : VISIBLE);
    }

    private void cancelExtras() {
        if (extrasRequest != null) extrasRequest.cancel();
        if (extrasTask != null) extrasTask.cancel(true);
        extrasRequest = null;
        extrasTask = null;
        network.purge();
    }

    void refreshLyricAppearance() {
        if (lyricsMode && isShown()) render();
    }

    void tick(int position) {
        if (!lyricsMode || lyrics == null || lines.isEmpty()) return;
        int index = lyrics.activeAt(position - prefs.lyricOffset());
        if (activeLine == index) return;
        if (activeLine >= 0) {
            TextView last = lines.get(activeLine);
            if (activeLine < alternates.size()) for (TextView extra : alternates.get(activeLine)) extra.setTextColor(INACTIVE_LYRIC);
            last.setTextColor(INACTIVE_LYRIC);
            last.setTypeface(last.getTypeface(), "bold".equals(prefs.text("lyricsWeight", "theme")) ? Typeface.BOLD : Typeface.NORMAL);
        }
        activeLine = index;
        if (index >= 0) {
            TextView line = lines.get(index);
            if (index < alternates.size()) for (TextView extra : alternates.get(index)) extra.setTextColor(FOREGROUND);
            line.setTextColor(activeLyricColour());
            line.setTypeface(line.getTypeface(), "regular".equals(prefs.text("lyricsWeight", "theme")) ? Typeface.NORMAL : Typeface.BOLD);
            followActive(index, line);
        } else {
            scroll.smoothScrollTo(0, 0);
        }
    }

    private int activeLyricColour() {
        if (!"accent".equals(prefs.text("lyricColour", "white"))) return Color.WHITE;
        return Color.rgb((Color.red(appearance.accent) + 510) / 3,
                (Color.green(appearance.accent) + 510) / 3, (Color.blue(appearance.accent) + 510) / 3);
    }

    private void followActive(int index, TextView line) {
        scroll.post(() -> {
            if (activeLine != index || !lyricsMode || !(line.getParent() instanceof View)) return;
            View group = (View) line.getParent();
            if (group.getParent() != rows) return;
            int top = Math.max(0, group.getTop() - scroll.getHeight() / 3);
            if (prefs.flag("quickLyrics", false) || profile.ordinal() <= PerformancePolicy.Profile.LITE.ordinal()) scroll.scrollTo(0, top);
            else scroll.smoothScrollTo(0, top);
        });
    }

    private void renderQueue() {
        List<MusicApi.Track> queue = playback == null ? Collections.emptyList() : playback.queueSnapshot();
        int current = playback == null ? -1 : playback.queueIndex();
        if (lastIndex == current && lastQueue.equals(queue)) return;
        int visible = current == lastIndex ? queueRows.getFirstVisiblePosition() : 0;
        int offset = current == lastIndex ? queueRows.rowOffset() : 0;
        lastIndex = current;
        lastQueue = queue;
        List<java.util.function.Supplier<View>> items = new ArrayList<>();
        if (queue.isEmpty()) items.add(() -> text("The queue is empty.", 17, MUTED));
        for (int i = Math.max(0, current); i < queue.size(); i++) {
            int position = i;
            MusicApi.Track song = queue.get(i);
            items.add(() -> queueRow(song, position, position == current));
        }
        queueRows.show(items, visible, offset);
    }

    private View queueRow(MusicApi.Track song, int position, boolean current) {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView thumbnail = new ImageView(getContext());
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artwork.load(thumbnail, song.artwork);
        row.addView(thumbnail, new LayoutParams(dp(38), dp(38)));
        LinearLayout names = column();
        TextView name = text(song.title, 16, current ? 0xffffd2db : FOREGROUND);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        names.addView(name);
        if (!song.artist.isEmpty()) names.addView(text(song.artist, 13, MUTED));
        names.setMinimumHeight(dp(Viewport.TOUCH));
        names.setGravity(Gravity.CENTER_VERTICAL);
        names.setFocusable(true);
        names.setOnClickListener(view -> {
            if (playback != null) playback.playAt(position);
        });
        row.addView(names, new LayoutParams(0, -2, 1));
        Button options = new Button(getContext());
        options.setText(Icons.MORE);
        options.setTypeface(appearance.icons);
        options.setContentDescription("Actions for " + song.title);
        options.setOnClickListener(view -> menu.show(view, song, position));
        row.addView(options, new LayoutParams(dp(Viewport.TOUCH), dp(Viewport.TOUCH)));
        return row;
    }

    void invalidateLyrics() {
        cancelExtras();
        if (lyricsTask != null) lyricsTask.cancel(true);
        ++lyricsGeneration;
        lyricsTask = null;
        network.purge();
        lyrics = null;
    }

    void close() {
        closed = true;
        cancelExtras();
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
        LayoutParams params = new LayoutParams(width, dp(Viewport.TOUCH), weight);
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
