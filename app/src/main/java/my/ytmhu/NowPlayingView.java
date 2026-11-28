// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Iterator;
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
    private final Button lyricsTab, queueTab, returnToCurrent;
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
    private boolean waitingForDuration;
    private boolean draggingLyrics;
    private long followPausedUntil;
    private int scrollRevision;
    private final Set<LyricRow> movingRows = new LinkedHashSet<>();
    private final Runnable motionFrame = this::updateMotion;
    private boolean motionPending, richLyrics;
    private int scrollMotionCount, lastLyricTime = -1, lastSeekRevision;
    private String readingProvider = "", translationProvider = "";
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
        setPadding(dp(30), dp(18), dp(24), dp(12));
        frost = appearance.glass ? new FrostedBackdrop(appearance.start,
                prefs.flag("flat", false) ? appearance.start : appearance.end, appearance.scrim, prefs.number("brightness", 100)) : null;
        background(appearance.start);

        cover = new ImageView(context);
        cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
        cover.setContentDescription("Album artwork");
        android.graphics.drawable.GradientDrawable mask = new android.graphics.drawable.GradientDrawable();
        mask.setColor(0xff171318);
        mask.setCornerRadius(dp(appearance.coverRadius));
        cover.setBackground(mask);
        cover.setClipToOutline(true);
        cover.setElevation(dp(8));
        title = text("Now playing", appearance.titleSize, FOREGROUND);
        title.setTypeface(appearance.font, Typeface.BOLD);
        title.setMaxLines(2);
        artist = text("", appearance.artistSize, MUTED);
        artist.setMaxLines(2);
        LinearLayout details = new LinearLayout(context) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int width = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
                title.measure(MeasureSpec.makeMeasureSpec(Math.max(0, width), MeasureSpec.AT_MOST), 0);
                artist.measure(MeasureSpec.makeMeasureSpec(Math.max(0, width), MeasureSpec.AT_MOST), 0);
                int available = MeasureSpec.getSize(heightSpec) - title.getMeasuredHeight() - artist.getMeasuredHeight()
                        - getPaddingTop() - getPaddingBottom() - dp(8);
                int edge = Math.max(0, Math.min(width, available));
                cover.getLayoutParams().width = edge;
                cover.getLayoutParams().height = edge;
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        details.setOrientation(VERTICAL);
        details.setClipChildren(false);
        details.setClipToPadding(false);
        details.setPadding(dp(4), dp(8), dp(4), dp(8));
        LayoutParams coverParams = new LayoutParams(0, 0);
        coverParams.gravity = Gravity.CENTER_HORIZONTAL;
        coverParams.bottomMargin = dp(8);
        details.addView(cover, coverParams);
        details.addView(title);
        details.addView(artist);
        if (appearance.centerNowPlaying) {
            title.setGravity(Gravity.CENTER);
            artist.setGravity(Gravity.CENTER);
        }
        FrameLayout left = new FrameLayout(context);
        left.setClipChildren(false);
        left.addView(details, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL));
        addView(left, new LayoutParams(0, -1, 0.80f));

        LinearLayout right = column();
        right.setPadding(dp(20), 0, 0, 0);
        LinearLayout tabs = new LinearLayout(context);
        lyricsTab = button("Lyrics", () -> showLyrics(true));
        lyricsTab.setOnLongClickListener(view -> { copyDiagnostics(); return true; });
        queueTab = button("Up next", () -> showLyrics(false));
        tabs.setPadding(dp(6), 0, 0, dp(6));
        addTab(tabs, lyricsTab, 0, 1);
        addTab(tabs, queueTab, 0, 1);
        Button closeTab = button(Icons.CLOSE, close);
        closeTab.setTypeface(appearance.icons);
        closeTab.setContentDescription("Close Now Playing");
        addTab(tabs, closeTab, dp(Viewport.TOUCH), 0);
        right.addView(tabs);
        scroll = new ScrollView(context) {
            private final int slop = ViewConfiguration.get(context).getScaledTouchSlop();
            private float downY;

            @Override public boolean dispatchTouchEvent(MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downY = event.getY();
                        stopLyricScroll();
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (hasTimedLyrics() && Math.abs(event.getY() - downY) > slop && !draggingLyrics) {
                            draggingLyrics = true;
                            pauseFollow();
                        }
                        break;
                    case MotionEvent.ACTION_CANCEL:
                    case MotionEvent.ACTION_UP:
                        if (draggingLyrics) {
                            draggingLyrics = false;
                            pauseFollow();
                        }
                        break;
                    default: break;
                }
                return super.dispatchTouchEvent(event);
            }

            @Override public boolean onGenericMotionEvent(MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_SCROLL && hasTimedLyrics()) pauseFollow();
                return super.onGenericMotionEvent(event);
            }
        };
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(dp(26));
        rows = column();
        scroll.addView(rows);
        android.widget.FrameLayout panel = new android.widget.FrameLayout(context);
        panel.addView(scroll, new android.widget.FrameLayout.LayoutParams(-1, -1));
        queueRows = new PageRows(context);
        panel.addView(queueRows, new android.widget.FrameLayout.LayoutParams(-1, -1));
        returnToCurrent = button("Return to current lyric", this::resumeFollow);
        returnToCurrent.setVisibility(GONE);
        android.widget.FrameLayout.LayoutParams follow = new android.widget.FrameLayout.LayoutParams(
                -2, dp(Viewport.TOUCH), Gravity.BOTTOM | Gravity.END);
        follow.setMargins(0, 0, dp(8), dp(6));
        returnToCurrent.setPadding(dp(12), 0, dp(12), 0);
        panel.addView(returnToCurrent, follow);
        right.addView(panel, new LayoutParams(-1, 0, 1));
        addView(right, new LayoutParams(0, -1, 1.20f));
    }

    boolean lyricsVisible() { return lyricsMode; }
    boolean hasTimedLyrics() { return lyrics != null && lyrics.timed(); }
    boolean hasActiveWordTiming() {
        return activeLine >= 0 && activeLine < lines.size() && lines.get(activeLine) instanceof KaraokeLineView;
    }

    void setProfile(PerformancePolicy.Profile next) {
        if (profile == next) return;
        profile = next;
        if (quickLyricScroll()) stopLyricScroll();
        if (profile == PerformancePolicy.Profile.ULTRA_LITE) finishMotion();
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
        } else if (waitingForDuration && playback != null && (!playback.buffering() || playback.duration() > 0)) {
            render();
        } else {
            syncPosition();
        }
    }

    void syncPosition() {
        if (playback == null || getVisibility() != VISIBLE) return;
        int seekRevision = playback.lyricSeekRevision();
        boolean seeked = lastSeekRevision != seekRevision;
        lastSeekRevision = seekRevision;
        tick(playback.position(), seeked);
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
        draggingLyrics = false;
        clearFollowPause();
        finishMotion();
        waitingForDuration = false;
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
        lastLyricTime = -1;
        if (track == null) {
            rows.addView(text("Play a song to see its lyrics.", 17, MUTED));
        } else if (lyrics != null) {
            showLyricsResult();
        } else {
            rows.addView(text("Loading lyrics…", 17, MUTED));
            if (lyricsTask == null) {
                waitingForDuration = playback != null && playback.buffering() && playback.duration() <= 0;
                if (!waitingForDuration) loadLyrics();
            }
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
                if (!lyricsMode || getVisibility() != VISIBLE) return;
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
        readingProvider = translationProvider = "";
        boolean roman = prefs.flag("lyricRoman", true);
        boolean translate = prefs.flag("lyricTranslation", true);
        if (!lyrics.timed() && !lyrics.plain.trim().isEmpty()) {
            rows.addView(text("Synced lyrics unavailable — showing plain lyrics.", 13, MUTED));
        }
        if (lyrics.lines.isEmpty() && (lyrics.plain.isEmpty() || (!roman && !translate))) {
            rows.addView(lyricsLine(lyrics.plain.isEmpty() ? "No lyrics found." : lyrics.plain, FOREGROUND));
        } else {
            List<Lyrics.Line> display = lyrics.displayLines();
            int spacing = dp(Math.round(22 * prefs.number("lyricSpacing", 100) / 100f));
            richLyrics = lyrics.timed() && lyrics.lines.stream().anyMatch(line -> !line.words.isEmpty());
            for (Lyrics.Line line : display) {
                LyricRow group = new LyricRow();
                int color = line.millis < 0 ? FOREGROUND : Color.WHITE;
                if (line.millis >= 0) {
                    group.setAlpha(appearance.lyricMotion.lineOpacity(lines.size() + 1, richLyrics));
                    group.setScaleX(appearance.lyricMotion.restScale);
                    group.setScaleY(appearance.lyricMotion.restScale);
                }
                TextView view = line.words.isEmpty() ? lyricsLine(line.text, color) : karaokeLine(line, color);
                group.setPadding(dp(8), spacing / 2, dp(6), spacing - spacing / 2);
                group.addView(view);
                if (line.millis >= 0 && !line.text.isEmpty()) group.setOnClickListener(clicked -> {
                    if (playback != null) {
                        clearFollowPause();
                        playback.seekTo(line.millis + prefs.lyricOffset());
                        syncPosition();
                    }
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
                        SettingsView.lyricTarget(prefs));
                loadExtras(display, roman, translate);
            }
            syncPosition();
        }
        if (!lyrics.source.isEmpty()) {
            rows.addView(text(lyrics.source, 13, MUTED));
            rows.addView(button("Copy lyrics diagnostics", this::copyDiagnostics));
        }
    }

    private TextView addAlternate(LinearLayout group, String key) {
        boolean roman = "lyricRomanSize".equals(key);
        TextView view = text("", appearance.lyricsSize, INACTIVE_LYRIC);
        view.setTextSize(appearance.lyricsSize * prefs.number("lyricsSize", 100) / 100f
                * prefs.number(key, roman ? 75 : 65) / 100f);
        view.setTypeface(Typeface.create(appearance.font, roman ? Typeface.BOLD : Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        view.setPadding(dp(roman ? 9 : 2), dp(roman ? 4 : 2), dp(roman ? 9 : 2), dp(roman ? 4 : 2));
        view.setVisibility(GONE);
        LayoutParams params = new LayoutParams(-2, -2);
        if (centerLyrics()) params.gravity = Gravity.CENTER_HORIZONTAL;
        params.topMargin = dp(roman ? 4 : 3);
        if (roman) {
            GradientDrawable capsule = new GradientDrawable();
            capsule.setColor(0x0fffffff);
            capsule.setStroke(dp(1), 0x23ffffff);
            capsule.setCornerRadius(dp(11));
            view.setBackground(capsule);
        }
        group.addView(view, params);
        return view;
    }

    private TextView alternateStatus() {
        TextView status = text("", 13, MUTED);
        status.setVisibility(GONE);
        rows.addView(status);
        return status;
    }

    private boolean currentExtras(Lyrics original, LyricExtras request) {
        return !closed && getVisibility() == VISIBLE && lyricsMode && original == lyrics && extrasRequest == request;
    }

    void clearRomanizationCache() { romanizer.clear(); Lyrics.trimCache(); }

    private void loadExtras(List<Lyrics.Line> display, boolean roman, boolean translate) {
        cancelExtras();
        Lyrics original = lyrics;
        String target = SettingsView.lyricTarget(prefs);
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
                try (LocalRomanizer.Batch batch = romanizer.new Batch(getContext(), new JapaneseDictionary(getContext()), true)) {
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
        readingProvider = String.join(", ", readings);
        translationProvider = String.join(", ", translations);
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
        boolean retarget = scrollMotionCount > 0 && following() && activeLine >= 0;
        int scrollY = retarget ? displayedScrollY() : scroll.getScrollY();
        View anchor = scrollY > 0 ? firstVisibleGroup(scrollY) : null;
        int oldTop = anchor == null ? 0 : anchor.getTop();
        int revision = scrollRevision;
        for (int i = 0; i < display.size(); i++) {
            Lyrics.Line line = display.get(i);
            if (roman) {
                String value = LyricExtras.clean(line.roman, line.text);
                LocalRomanizer.Reading reading = i < local.size() ? local.get(i) : LocalRomanizer.Reading.NONE;
                if (value.isEmpty() && reading.complete) value = reading.text;
                if (value.isEmpty()) value = extra(values, line.text, "romanization");
                if (value.isEmpty()) value = reading.text;
                showAlternate(alternates.get(i).get(0), value, FOREGROUND);
            }
            if (translate) {
                String value = LyricExtras.clean(line.translation(target), line.text);
                if (value.isEmpty()) value = extra(values, line.text, "translation");
                TextView view = alternates.get(i).get(roman ? 1 : 0);
                showAlternate(view, value, FOREGROUND);
                if (!value.isEmpty()) {
                    String provider = extra(values, line.text, "provider");
                    view.setContentDescription(LyricExtras.clean(line.translation(target), line.text).isEmpty() && !provider.isEmpty()
                            ? "Machine translation by " + provider + ". " + value : value);
                }

            }
            boolean hasAlternate = alternates.get(i).stream().anyMatch(view -> view.getVisibility() == VISIBLE);
            lines.get(i).setVisibility(!hasAlternate || prefs.flag("lyricOriginal", true) ? VISIBLE : GONE);
        }
        if (!rows.isLayoutRequested()) return;
        if (retarget) stopLyricScroll();
        if (anchor != null || retarget) afterLayout(() -> {
            if (scrollRevision != revision || anchor != null && anchor.getParent() != rows) return;
            if (anchor != null && (retarget || scroll.getScrollY() == scrollY)) {
                scroll.scrollTo(0, scrollY + anchor.getTop() - oldTop);
            }
            if (retarget && activeLine >= 0) followActive(activeLine, lines.get(activeLine), true);
        });
    }

    private View firstVisibleGroup(int scrollY) {
        for (TextView line : lines) {
            View group = (View) line.getParent();
            if (group.getBottom() > scrollY) return group;
        }
        return null;
    }

    private static String extra(org.json.JSONObject values, String text, String key) {
        return LyricExtras.value(values, text, key);
    }

    private static void showAlternate(TextView view, String text, boolean active) {
        showAlternate(view, text, active ? FOREGROUND : INACTIVE_LYRIC);
    }

    private static void showAlternate(TextView view, String text, int color) {
        if (!text.contentEquals(view.getText())) view.setText(text);
        if (view.getCurrentTextColor() != color) view.setTextColor(color);
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
        tick(position, false);
    }

    private void tick(int position, boolean seeked) {
        if (!lyricsMode || !hasTimedLyrics() || lines.isEmpty()) return;
        int time = position - prefs.lyricOffset();
        int index = lyrics.activeAt(time);
        int previousTime = lastLyricTime;
        lastLyricTime = time;
        boolean jump = seeked || time < previousTime - 120 || time > previousTime + 1250;
        boolean expired = !draggingLyrics && followPausedUntil > 0 && SystemClock.uptimeMillis() >= followPausedUntil;
        if (activeLine == index && !jump) {
            updateWord(time);
            if (expired) resumeFollow();
            return;
        }
        if (expired) clearFollowPause();
        int previous = activeLine;
        boolean animate = !jump && previousTime >= 0 && playback != null && playback.playing() && motionEnabled();
        if (jump) finishMotion();
        if (previous != index && previous >= 0 && lines.get(previous) instanceof KaraokeLineView) {
            KaraokeLineView word = (KaraokeLineView) lines.get(previous);
            if (animate) ((LyricRow) word.getParent()).fadeWord(word, SystemClock.uptimeMillis());
            else word.position(-1);
        }
        activeLine = index;
        if (jump || Math.abs(previous - index) > 2) {
            for (int i = 0; i < lines.size(); i++) updateLyricState(i, false);
        } else {
            int radius = appearance.lyricMotion.dimRadius(richLyrics);
            int start = Math.max(0, Math.min(previous, index) - radius);
            int end = Math.min(lines.size() - 1, Math.max(previous, index) + radius);
            for (int i = start; i <= end; i++) updateLyricState(i, animate);
        }
        updateWord(time);
        if (index >= 0) {
            followActive(index, lines.get(index), animate);
        } else if (following() && time < lyrics.lines.get(0).millis) {
            ++scrollRevision;
            stopLyricScroll();
            scroll.scrollTo(0, 0);
        }
    }

    private void updateWord(int time) {
        if (hasActiveWordTiming()) {
            KaraokeLineView word = (KaraokeLineView) lines.get(activeLine);
            ((LyricRow) word.getParent()).clearWordFade();
            word.setEffects(appearance.lyricMotion, profile);
            word.position(time - prefs.number("lyricsWordTrim", 0));
        }
    }

    private void updateLyricState(int index, boolean animate) {
        TextView line = lines.get(index);
        LyricRow group = (LyricRow) line.getParent();
        boolean active = index == activeLine;
        int color = active ? activeLyricColour() : Color.WHITE;
        if (line.getCurrentTextColor() != color) line.setTextColor(color);
        LyricMotion style = appearance.lyricMotion;
        float alpha = style.lineOpacity(index - activeLine, richLyrics);
        float scale = active ? style.activeScale : style.restScale;
        Lyrics.Line lyric = lyrics.lines.get(index);
        int end = lyric.end >= 0 ? lyric.end : index + 1 < lines.size() ? lyrics.lines.get(index + 1).millis : lyric.millis;
        LyricMotion.Timing opacity = active ? style.active : index < activeLine ? style.past : style.regular;
        group.emphasize(scale, alpha, opacity, end - lyric.millis, active, animate);
    }

    private int activeLyricColour() {
        if (!"accent".equals(prefs.text("lyricColour", "white"))) return Color.WHITE;
        return Color.rgb((Color.red(appearance.accent) + 510) / 3,
                (Color.green(appearance.accent) + 510) / 3, (Color.blue(appearance.accent) + 510) / 3);
    }

    private boolean following() { return !draggingLyrics && followPausedUntil == 0; }

    private void pauseFollow() {
        stopLyricScroll();
        followPausedUntil = SystemClock.uptimeMillis() + 8000;
        ++scrollRevision;
        returnToCurrent.setVisibility(VISIBLE);
    }

    private void clearFollowPause() {
        stopLyricScroll();
        followPausedUntil = 0;
        ++scrollRevision;
        returnToCurrent.setVisibility(GONE);
    }

    private void resumeFollow() {
        draggingLyrics = false;
        clearFollowPause();
        if (activeLine >= 0) followActive(activeLine, lines.get(activeLine), false);
        else scroll.scrollTo(0, 0);
    }

    private void followActive(int index, TextView line, boolean animate) {
        if (!following()) return;
        int revision = ++scrollRevision;
        afterLayout(() -> {
            if (!following() || revision != scrollRevision || !lyricsMode || getVisibility() != VISIBLE
                    || activeLine != index || !(line.getParent() instanceof View)) return;
            View group = (View) line.getParent();
            if (group.getParent() != rows) return;
            int height = scroll.getHeight();
            int top = group.getTop() + group.getHeight() / 2 - Math.round(height * appearance.lyricAnchor);
            top = Math.min(group.getTop(), Math.max(top, group.getBottom() - height));
            top = Math.max(0, Math.min(top, rows.getHeight() - height));
            int from = scroll.getScrollY();
            int delta = top - from;
            if (!animate || quickLyricScroll() || Math.abs(delta) > height) {
                stopLyricScroll();
                scroll.scrollTo(0, top);
            } else if (Math.abs(delta) > 1) {
                int start = firstGroupIndex(Math.min(from, top) - height / 2);
                int end = firstGroupIndex(Math.max(from, top) + height * 2);
                scroll.scrollTo(0, top);
                long now = SystemClock.uptimeMillis();
                for (int i = start; i <= end && i < lines.size(); i++) {
                    LyricRow row = (LyricRow) lines.get(i).getParent();
                    row.move(delta, i - index, now);
                }
            }
        });
    }

    private int firstGroupIndex(int y) {
        int low = 0, high = lines.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            View group = (View) lines.get(middle).getParent();
            if (group.getBottom() <= y) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    private boolean motionEnabled() {
        return profile != PerformancePolicy.Profile.ULTRA_LITE && android.animation.ValueAnimator.areAnimatorsEnabled();
    }

    private boolean quickLyricScroll() {
        return !motionEnabled() || prefs.flag("quickLyrics", false) || profile == PerformancePolicy.Profile.LITE;
    }

    private int displayedScrollY() {
        int top = scroll.getScrollY();
        if (scrollMotionCount == 0 || lines.isEmpty()) return top;
        int center = Math.min(lines.size() - 1, firstGroupIndex(top + scroll.getHeight() / 2));
        View row = (View) lines.get(center).getParent();
        return Math.max(0, top - Math.round(row.getTranslationY()));
    }

    private void stopLyricScroll() {
        if (scrollMotionCount == 0) return;
        int top = displayedScrollY();
        for (LyricRow group : movingRows) group.clearMove();
        scroll.scrollTo(0, top);
    }

    private void scheduleMotion(LyricRow group) {
        movingRows.add(group);
        if (motionPending) return;
        motionPending = true;
        postNextMotionFrame();
    }

    private void postNextMotionFrame() {
        // Align to a display frame while allowing roughly one, two or four 60 Hz
        // intervals. Animation values use uptime, independent of skipped frames.
        int delay = profile == PerformancePolicy.Profile.HIGH_QUALITY ? 8
                : profile == PerformancePolicy.Profile.BALANCED ? 24 : 56;
        postOnAnimationDelayed(motionFrame, delay);
    }

    private void updateMotion() {
        long now = SystemClock.uptimeMillis();
        Iterator<LyricRow> iterator = movingRows.iterator();
        while (iterator.hasNext()) {
            if (!iterator.next().advance(now)) iterator.remove();
        }
        motionPending = !movingRows.isEmpty();
        if (motionPending) postNextMotionFrame();
    }

    private void finishMotion() {
        for (LyricRow row : movingRows) row.advance(Long.MAX_VALUE);
        movingRows.clear();
        removeCallbacks(motionFrame);
        motionPending = false;
    }

    private final class LyricRow extends LinearLayout {
        private LyricMotion.Transition scale, opacity, shift, wordFade;
        private KaraokeLineView fadingWord;

        LyricRow() {
            super(NowPlayingView.this.getContext());
            setOrientation(VERTICAL);
        }

        @Override public boolean hasOverlappingRendering() { return false; }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            setPivotX(centerLyrics() ? w / 2f : getLayoutDirection() == LAYOUT_DIRECTION_RTL ? w : 0f);
            setPivotY(h / 2f);
        }

        void emphasize(float targetScale, float targetAlpha, LyricMotion.Timing timing, int duration,
                       boolean active, boolean animate) {
            if (!animate) {
                scale = opacity = null;
                setScaleX(targetScale);
                setScaleY(targetScale);
                setAlpha(targetAlpha);
                return;
            }
            LyricMotion style = appearance.lyricMotion;
            long now = SystemClock.uptimeMillis();
            if (scale == null || !scale.targets(targetScale)) {
                scale = getScaleX() == targetScale ? null
                        : new LyricMotion.Transition(getScaleX(), targetScale, now, style.scaleMs,
                                active ? style.enter : style.exit);
            }
            if (opacity == null || !opacity.targets(targetAlpha)) {
                opacity = getAlpha() == targetAlpha ? null
                        : new LyricMotion.Transition(getAlpha(), targetAlpha, now + timing.delay,
                                timing.duration(duration), style.opacity);
            }
            if (scale != null || opacity != null) scheduleMotion(this);
        }

        void move(float delta, int relative, long now) {
            if (shift == null) scrollMotionCount++;
            float from = getTranslationY() + delta;
            setTranslationY(from);
            LyricMotion style = appearance.lyricMotion;
            shift = new LyricMotion.Transition(from, 0f, now, style.scrollDuration(relative, delta), style.scroll);
            scheduleMotion(this);
        }

        void clearMove() {
            if (shift == null) return;
            shift = null;
            scrollMotionCount--;
            setTranslationY(0f);
        }

        void fadeWord(KaraokeLineView word, long now) {
            if (word.activeSpan() < 0) return;
            clearWordFade();
            fadingWord = word;
            LyricMotion style = appearance.lyricMotion;
            wordFade = new LyricMotion.Transition(1f, 0f, now + style.wordFadeDelay,
                    style.wordFadeMs, LyricMotion.EASE);
            scheduleMotion(this);
        }

        void clearWordFade() {
            if (fadingWord == null) return;
            fadingWord.position(-1);
            fadingWord.setExitOpacity(1f);
            fadingWord = null;
            wordFade = null;
        }

        boolean advance(long now) {
            if (scale != null) {
                float value = scale.value(now);
                setScaleX(value);
                setScaleY(value);
                if (scale.finished(now)) scale = null;
            }
            if (opacity != null) {
                setAlpha(Math.max(0f, Math.min(1f, opacity.value(now))));
                if (opacity.finished(now)) opacity = null;
            }
            if (shift != null) {
                if (shift.finished(now)) clearMove();
                else setTranslationY(shift.value(now));
            }
            if (wordFade != null) {
                if (wordFade.finished(now)) clearWordFade();
                else fadingWord.setExitOpacity(wordFade.value(now));
            }
            return scale != null || opacity != null || shift != null || wordFade != null;
        }
    }

    private void afterLayout(Runnable action) {
        if (!rows.isLayoutRequested() || !scroll.isAttachedToWindow()) {
            scroll.post(action);
            return;
        }
        ViewTreeObserver observer = scroll.getViewTreeObserver();
        observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                if (observer.isAlive()) observer.removeOnPreDrawListener(this);
                else scroll.getViewTreeObserver().removeOnPreDrawListener(this);
                action.run();
                return true;
            }
        });
    }

    String diagnostics() {
        String provider = lyrics == null || lyrics.source.isEmpty() ? "none" : lyrics.source;
        boolean timed = hasTimedLyrics();
        int groups = lyrics == null || (!timed && lyrics.plain.trim().isEmpty()) ? 0 : lyrics.displayLines().size();
        int position = playback == null ? 0 : playback.position();
        int timestamps = timed ? lyrics.lines.size() : 0;
        String lineTime = timed && activeLine >= 0 && activeLine < timestamps ? lyrics.lines.get(activeLine).millis + " ms" : "none";
        int wordSpans = timed ? lyrics.lines.stream().mapToInt(line -> line.words.size()).sum() : 0;
        KaraokeLineView karaoke = hasActiveWordTiming() ? (KaraokeLineView) lines.get(activeLine) : null;
        return "YTM " + BuildVersion.VERSION + " lyrics\nProvider: " + provider
                + "\nTiming: " + (groups == 0 ? "unavailable" : timed ? "timed" : "plain")
                + "\nActive group timing: " + (karaoke != null ? "word/syllable spans" : activeLine >= 0 ? "line" : "none")
                + "\nValid word/syllable spans: " + wordSpans
                + "\nKaraoke renderer: " + (karaoke != null
                        ? "progressive sweep (" + (profile.ordinal() <= PerformancePolicy.Profile.LITE.ordinal()
                                ? "playback clock" : "frame-limited while playing") + ")"
                        : activeLine < 0 ? "no active timed group" : "active group has line timings only")
                + "\nActive word/syllable (zero-based): " + (karaoke == null || karaoke.activeSpan() < 0
                        ? "none" : karaoke.activeSpan() + " (" + karaoke.fillPercent() + "%)")
                + "\nAlternate alignment: " + (timed ? "line" : "none")
                + "\nLyric motion: " + (!timed || !motionEnabled() ? "static emphasis"
                        : quickLyricScroll() ? "scale and opacity, immediate follow" : "scale, opacity and staggered follow")
                + "\nProvider attempts: " + (lyrics == null ? "no loaded result" : lyrics.attempts)
                + "\nLyrics cache: " + (lyrics != null && lyrics.cached ? "hit" : "not used")
                + "\nGroups: " + groups + "\nValid timestamps: " + timestamps
                + "\nPlayback position: " + position + " ms\nApplied offset: " + (timed ? prefs.lyricOffset() : 0) + " ms"
                + "\nWord trim: " + prefs.number("lyricsWordTrim", 0) + " ms"
                + "\nActive index (zero-based): " + activeLine + "\nActive timestamp: " + lineTime
                + "\nRomanization: " + diagnosticProvider("lyricRoman", readingProvider)
                + "\nTranslation: " + diagnosticProvider("lyricTranslation", translationProvider)
                + "\nAutomatic follow: " + (!timed ? "unavailable" : following() ? "on" : "paused by manual scroll")
                + "\nEffective profile: " + profile;
    }

    private String diagnosticProvider(String key, String provider) {
        return !prefs.flag(key, true) ? "off" : provider.isEmpty() ? "pending or unavailable" : provider;
    }

    private void copyDiagnostics() {
        ClipboardManager clipboard = getContext().getSystemService(ClipboardManager.class);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("Lyrics diagnostics", diagnostics()));
        android.widget.Toast.makeText(getContext(), "Lyrics diagnostics copied.", android.widget.Toast.LENGTH_SHORT).show();
    }

    void hideLyrics() {
        cancelExtras();
        draggingLyrics = false;
        clearFollowPause();
        finishMotion();
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
        readingProvider = translationProvider = "";
        waitingForDuration = false;
    }

    void close() {
        closed = true;
        stopLyricScroll();
        finishMotion();
        cancelExtras();
        network.shutdownNow();
    }

    private TextView karaokeLine(Lyrics.Line line, int color) {
        TextView view = new KaraokeLineView(getContext(), line.words, appearance.accent);
        view.setText(line.text);
        view.setTextColor(color);
        styleOriginal(view);
        return view;
    }

    private TextView lyricsLine(String line, int color) {
        TextView view = text(line, appearance.lyricsSize, color);
        styleOriginal(view);
        return view;
    }

    private void styleOriginal(TextView view) {
        view.setTextSize(appearance.lyricsSize * prefs.number("lyricsSize", 100) / 100f);
        view.setTypeface(appearance.font, "regular".equals(prefs.text("lyricsWeight", "theme"))
                ? Typeface.NORMAL : Typeface.BOLD);
        view.setIncludeFontPadding(false);
        view.setPadding(0, dp(2), 0, dp(2));
        view.setLineSpacing(dp(2), 1f);
        if (centerLyrics()) view.setGravity(Gravity.CENTER_HORIZONTAL);
    }

    private boolean centerLyrics() {
        return "classic".equals(prefs.text("lyricsStyle", "theme"));
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
