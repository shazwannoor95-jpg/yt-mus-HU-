// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.provider.Settings;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.text.InputType;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class MainActivity extends Activity {
    private static final int SAVE_AUDIO = 21;
    private static final int INK = 0xfff4eef4;
    private static final int MUTED = 0xffc6b8cc;
    private AppSettings prefs;
    private Appearance appearance;
    private Viewport viewport;
    private DevicePerformance performance;
    private String restoredPlayer = "";
    private Runnable cancelPlayerArtwork = () -> {};
    private long lastDisplayedSecond = -1;
    private int lastDisplayedDuration = -1;
    private FrostedBackdrop backdrop;
    private LinearLayout musicSearchBar;
    private ImageView playerArtwork;
    private SeekBar inlineVolume;
    private LinearLayout miniPlayer;
    private FrostedBackdrop miniBackdrop;
    private ImageView miniArtwork;
    private TextView miniTitle, miniArtist;
    private Button miniPrevious, miniNext, miniLike, miniDislike, expandedButton;
    private SeekBar miniSeek;
    private boolean miniSeeking;
    private long volumeRefreshed;
    private int settingsScroll;
    private String currentArtwork = "";

    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private MusicApi api;
    private FrameLayout paneContent;
    private SettingsView settingsView;
    private String settingsTab = "Account";
    private Artwork artwork;
    private PlaybackService playback;
    private MusicApi.Track pendingDownload;
    private final ExecutorService downloads = Executors.newSingleThreadExecutor();
    private LinearLayout browser;
    private NowPlayingView expanded;
    private boolean bound;
    private boolean refreshingAccount;
    private Future<?> pageTask;
    private int generation;
    private String account = "";
    private String accountError = "";
    private String accountErrorCode = "";
    private boolean retryAccount;
    private String page = "Home";
    private Browse.Search searchType = Browse.Search.Songs;
    private String collectionId = "";
    private String collectionTitle = "";
    private String mood = "";
    private Dialog signInDialog;
    private EditText query;
    private PageRows scroll;
    private final List<Supplier<View>> results = new ArrayList<>();
    private LinearLayout sidebarPlaylists;
    private List<Browse.Card> sidebarItems = Collections.emptyList();
    private int browseRow, browseOffset;
    private String browseKey = "";
    private final Map<String, BrowsePage> pages = new LinkedHashMap<>(6, 0.75f, true);

    private static final class BrowsePage {
        final List<Browse.Shelf> shelves;
        final List<Browse.Mood> moods;
        final List<MusicApi.Track> tracks;
        final long loaded = android.os.SystemClock.uptimeMillis();
        int row, offset;
        final List<int[]> shelfStarts = new ArrayList<>();

        BrowsePage(List<Browse.Shelf> shelves, List<Browse.Mood> moods, List<MusicApi.Track> tracks) {
            this.shelves = shelves;
            this.moods = moods;
            this.tracks = tracks;
        }

        int[] shelfStart(int index) {
            while (shelfStarts.size() <= index) shelfStarts.add(new int[1]);
            return shelfStarts.get(index);
        }
    }
    private TextView nowPlaying, playerStatus, playbackTime;
    private Button home, explore, library, liked, settings, accountButton, sideAccount;
    private Button previous, next, shuffle, repeat;
    private PlayPauseButton play, miniPlay;
    private SeekBar seek;
    private boolean seeking;

    private final Runnable clock = new Runnable() {
        @Override public void run() {
            renderProgress();
            if (inlineVolume != null && android.os.SystemClock.uptimeMillis() - volumeRefreshed >= 1000) {
                syncVolume();
            }
            syncLyricFrames();
            boolean lyricsVisible = expanded.getVisibility() == View.VISIBLE && expanded.lyricsVisible();
            if (lyricsVisible && !lyricFramesRunning) expanded.tick(playback == null ? 0 : playback.position());
            main.postDelayed(this, lyricsVisible ? 250 : performance.profile().clockMs);
        }
    };

    private boolean lyricFramesRunning;
    private long lastLyricFrame;
    private final android.view.Choreographer.FrameCallback lyricFrame = this::updateLyricFrame;

    private void syncLyricFrames() {
        boolean active = bound && playback != null && playback.playing()
                && expanded.getVisibility() == View.VISIBLE && expanded.lyricsVisible() && expanded.hasTimedLyrics()
                && prefs.flag("lyricRefresh", false) && performance.profile().ordinal() >= PerformancePolicy.Profile.BALANCED.ordinal();
        if (active == lyricFramesRunning) return;
        lyricFramesRunning = active;
        android.view.Choreographer choreographer = android.view.Choreographer.getInstance();
        if (active) {
            lastLyricFrame = 0;
            choreographer.postFrameCallback(lyricFrame);
        } else choreographer.removeFrameCallback(lyricFrame);
    }

    private void updateLyricFrame(long nanos) {
        if (!lyricFramesRunning) return;
        long interval = performance.profile() == PerformancePolicy.Profile.BALANCED ? 32_000_000 : 16_000_000;
        if (nanos - lastLyricFrame >= interval) {
            lastLyricFrame = nanos;
            expanded.tick(playback.position());
        }
        android.view.Choreographer.getInstance().postFrameCallback(lyricFrame);
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            playback = ((PlaybackService.LocalBinder) binder).service();
            playback.changed = MainActivity.this::renderPlayer;
            renderPlayer();
            String restore = restoredPlayer;
            restoredPlayer = "";
            if ("mini".equals(restore)) toggleMiniPlayer();
            else if (!restore.isEmpty()) openPlayer("lyrics".equals(restore));
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            playback = null;
            renderPlayer();
        }
    };

    @Override protected void attachBaseContext(Context base) {
        AppSettings saved = new AppSettings(base);
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.fontScale *= saved.number("zoom", 100) / 100f;
        super.attachBaseContext(base.createConfigurationContext(config));
    }

    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        prefs = new AppSettings(this);
        appearance = new Appearance(this, prefs);
        viewport = Viewport.current(this, prefs);
        performance = new DevicePerformance(this, prefs.text("performance", "AUTO"), this::applyPerformance);
        artwork = new Artwork(this);
        api = new MusicApi(prefs);
        page = prefs.text("openOn", "Home");
        if (savedState != null) {
            settingsTab = savedState.getString("settingsTab", "Account");
            settingsScroll = savedState.getInt("settingsScroll");
            browseRow = savedState.getInt("browseRow");
            browseOffset = savedState.getInt("browseOffset");
            restoredPlayer = savedState.getString("playerPanel", "");
        }
        LinearLayout root = column();
        if (appearance.glass) {
            backdrop = new FrostedBackdrop(appearance.window,
                    prefs.flag("flat", false) ? appearance.window : appearance.end,
                    appearance.scrim, prefs.number("brightness", 100));
            root.setBackground(backdrop);
        } else {
            root.setBackgroundColor(appearance.window);
        }
        root.setFocusableInTouchMode(true);
        setContentView(root);

        FrameLayout stage = new FrameLayout(this);
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout body = new LinearLayout(this);
        browser = body;
        stage.addView(body, new FrameLayout.LayoutParams(-1, -1));
        expanded = new NowPlayingView(this, prefs, appearance, artwork, this::showSongMenu, this::closePlayer);
        expanded.setVisibility(View.GONE);
        stage.addView(expanded, new FrameLayout.LayoutParams(-1, -1));
        addMiniPlayer(stage);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        LinearLayout side = column();
        side.setPadding(dp(4), dp(3), dp(4), dp(3));
        side.setBackgroundColor(appearance.panel(appearance.sidebar, 132));
        boolean collapsed = viewport.iconRail();
        body.addView(side, new LinearLayout.LayoutParams(dp(viewport.railWidth), -1));
        ScrollView navigationScroll = new ScrollView(this);
        navigationScroll.setVerticalScrollBarEnabled(false);
        LinearLayout navigationList = column();
        navigationScroll.addView(navigationList);
        side.addView(navigationScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        Button sidebarToggle = transport(brand, "☰", collapsed ? "Expand navigation" : "Collapse navigation", () -> {
            if (viewport.drawerSidebar) showNavigationDrawer();
            else {
                prefs.set("sidebar", viewport.iconRail() ? "expanded" : "rail");
                recreate();
            }
        });
        sidebarToggle.setTypeface(appearance.font);
        if (!collapsed) {
            ImageView icon = new ImageView(this);
            icon.setImageResource(AppIcons.drawable(this, prefs.text("appIcon", "classic")));
            brand.addView(icon, new LinearLayout.LayoutParams(dp(26), dp(26)));
            TextView wordmark = label("Music", 19, INK);
            wordmark.setTypeface(appearance.font, Typeface.BOLD);
            brand.addView(wordmark, new LinearLayout.LayoutParams(0, -2, 1));
        } else {
            brand.setGravity(Gravity.CENTER);
        }
        navigationList.addView(brand, new LinearLayout.LayoutParams(-1, dp(Viewport.TOUCH)));
        home = navigation(navigationList, "Home", () -> navigate("Home"));
        explore = navigation(navigationList, "Explore", () -> navigate("Explore"));
        library = navigation(navigationList, "Library", () -> navigate("Library"));
        sideAccount = navigation(navigationList, "Sign in", this::openAccount);
        liked = navigation(navigationList, "Liked Music", this::openLiked);
        setNavIcon(liked, Icons.HEART, "Liked Music", collapsed);
        sidebarPlaylists = column();
        navigationList.addView(sidebarPlaylists);
        settings = navigation(side, "Settings", () -> navigate("Account"));
        if (collapsed) {
            for (Button control : new Button[]{home, explore, library, sideAccount, liked, settings}) {
                control.setGravity(Gravity.CENTER);
                control.setPadding(0, 0, 0, 0);
            }
        }

        LinearLayout mainPane = column();
        mainPane.setPadding(dp(viewport.gutter), dp(3), dp(viewport.gutter), 0);
        if (!appearance.glass) mainPane.setBackground(Appearance.background(prefs, GradientDrawable.Orientation.TL_BR,
                appearance.start, appearance.solid ? appearance.start : appearance.end));
        body.addView(mainPane, new LinearLayout.LayoutParams(0, -1, 1));

        LinearLayout searchBar = new LinearLayout(this);
        musicSearchBar = searchBar;
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        query = new EditText(this);
        query.setSingleLine();
        query.setTextSize(viewport.songSp);
        query.setTypeface(appearance.font);
        query.setHint("Search songs, albums, artists");
        query.setPadding(dp(16), 0, dp(12), 0);
        query.setBackground(shape(appearance.panel(appearance.control, 146), appearance.searchRadius));
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        query.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            search();
            return true;
        });
        searchBar.addView(query, new LinearLayout.LayoutParams(0, dp(Viewport.TOUCH), 1));
        boolean smallSearch = viewport.width < 780;
        Button submit = button(smallSearch ? Icons.SEARCH : "Search", this::search);
        if (smallSearch) submit.setTypeface(appearance.icons);
        submit.setContentDescription("Search");
        searchBar.addView(submit, spacedWidth(smallSearch ? 48 : 78, Viewport.TOUCH));
        accountButton = button("Sign in", this::openAccount);
        searchBar.addView(accountButton, spacedWidth(smallSearch ? 48 : 96, Viewport.TOUCH));
        mainPane.addView(searchBar, new LinearLayout.LayoutParams(-1, dp(viewport.shortScreen ? 50 : 54)));

        paneContent = new FrameLayout(this);
        mainPane.addView(paneContent, new LinearLayout.LayoutParams(-1, 0, 1));
        scroll = new PageRows(this);
        scroll.setPadding(0, dp(3), 0, dp(viewport.gutter));
        paneContent.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        root.addView(playerBar(), new LinearLayout.LayoutParams(-1, dp(viewport.playerHeight)));
        applyPerformance();
        root.requestFocus();
        renderPlayer();
        if (savedState != null) {
            page = savedState.getString("page", "Home");
            query.setText(savedState.getString("query", ""));
            collectionId = savedState.getString("collectionId", "");
            collectionTitle = savedState.getString("collectionTitle", "");
            mood = savedState.getString("mood", "");
            searchType = Browse.Search.valueOf(savedState.getString("searchType", "Songs"));
            Bundle song = savedState.getBundle("download");
            if (song != null) pendingDownload = new MusicApi.Track(song.getString("id"), song.getString("title"),
                    song.getString("artist"), "", "");
        }
        if (MusicApi.hasSession(GoogleSignIn.cookies())) verifyAccount();
        else {
            if (savedState == null && "Library".equals(page)) page = "Home";
            loadPage();
        }
    }

    private LinearLayout playerBar() {
        LinearLayout player = column();
        player.setBackgroundColor(appearance.panel(appearance.window, appearance.barAlpha));
        android.view.View progressRule = new View(this);
        progressRule.setBackgroundColor(0x27ffffff);
        player.addView(progressRule, new LinearLayout.LayoutParams(-1, dp(1)));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), 0, dp(6), 0);
        boolean separateMetadata = viewport.width < 430;
        LinearLayout metadata = new LinearLayout(this);
        metadata.setGravity(Gravity.CENTER_VERTICAL);
        metadata.setPadding(dp(3), 0, dp(4), 0);
        if (separateMetadata) player.addView(metadata, new LinearLayout.LayoutParams(-1, dp(Viewport.TOUCH)));
        else bar.addView(metadata, new LinearLayout.LayoutParams(0, -1, 1));
        player.addView(bar, new LinearLayout.LayoutParams(-1, 0, 1));

        playerArtwork = cover("");
        playerArtwork.setVisibility(View.GONE);
        metadata.addView(playerArtwork, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout information = column();
        information.setGravity(Gravity.CENTER_VERTICAL);
        nowPlaying = label("Now playing", viewport.songSp, INK);
        playerStatus = label("", viewport.metadataSp, MUTED);
        nowPlaying.setPadding(dp(5), 0, dp(4), 0);
        playerStatus.setPadding(dp(5), 0, dp(4), 0);
        nowPlaying.setIncludeFontPadding(false);
        playerStatus.setIncludeFontPadding(false);
        metadata.setOnClickListener(view -> {
            if (playback != null && playback.requiresSignIn() && account.isEmpty()) openAccount();
            else toggleExpanded();
        });
        metadata.setOnLongClickListener(view -> {
            showCurrentSongMenu(view);
            return playback != null && playback.currentTrack() != null;
        });
        information.addView(nowPlaying);
        information.addView(playerStatus);
        metadata.addView(information, new LinearLayout.LayoutParams(0, -2, 1));

        if (!viewport.compactPlayer) shuffle = transport(bar, Icons.SHUFFLE, "Shuffle", this::toggleShuffle);
        previous = transport(bar, Icons.PREVIOUS, "Previous", () -> { if (playback != null) playback.skip(-1); });
        play = playButton(bar, 52);
        if (!appearance.barePlay) {
            play.setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x40666666),
                    new android.graphics.drawable.InsetDrawable(shape(INK, 28), dp(3)), null));
            play.setTextColor(0xff141119);
        }
        next = transport(bar, Icons.NEXT, "Next", () -> { if (playback != null) playback.skip(1); });
        if (!viewport.compactPlayer) repeat = transport(bar, Icons.REPEAT, "Repeat", this::toggleRepeat);

        LinearLayout utility = viewport.twoLinePlayer ? new LinearLayout(this) : bar;
        if (viewport.twoLinePlayer) {
            utility.setGravity(Gravity.CENTER_VERTICAL);
            utility.setPadding(dp(7), 0, dp(7), 0);
            player.addView(utility, new LinearLayout.LayoutParams(-1, dp(Viewport.TOUCH)));
        }
        seek = new SeekBar(this);
        seek.setContentDescription("Track progress");
        seek.setProgressTintList(android.content.res.ColorStateList.valueOf(appearance.progress(accent())));
        seek.setThumbTintList(android.content.res.ColorStateList.valueOf(appearance.progress(accent())));
        seek.setPadding(dp(8), 0, dp(8), 0);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                if (user && !seeking && playback != null) playback.seekTo(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { seeking = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                seeking = false;
                if (playback != null) playback.seekTo(bar.getProgress());
            }
        });
        utility.addView(seek, new LinearLayout.LayoutParams(0, dp(Viewport.TOUCH), viewport.twoLinePlayer ? 1 : 1.25f));
        playbackTime = label("0:00 / –:––", viewport.metadataSp, MUTED);
        playbackTime.setFontFeatureSettings("tnum");
        playbackTime.setGravity(Gravity.CENTER);
        playbackTime.setPadding(0, 0, 0, 0);
        playbackTime.setOnClickListener(view -> showSeek());
        int timeWidth = Math.min(156, Math.round(Math.max(1, viewport.fontScale) * 92));
        utility.addView(playbackTime, new LinearLayout.LayoutParams(dp(timeWidth), dp(Viewport.TOUCH)));

        if (!viewport.twoLinePlayer && viewport.width >= 810) {
            transport(bar, Icons.VOLUME, "Media volume", this::showVolume);
            transport(bar, Icons.LYRICS, "Lyrics", () -> openPlayer(true));
            transport(bar, Icons.QUEUE, "Up next", () -> openPlayer(false));
        }
        if ((!viewport.twoLinePlayer && viewport.width >= 960) || (viewport.twoLinePlayer && viewport.width >= 600)) {
            transport(utility, Icons.MINI, "Mini player", this::toggleMiniPlayer);
        }
        if (viewport.width >= 1360 && !viewport.twoLinePlayer && !viewport.compactPlayer) {
            inlineVolume = volumeSlider();
            inlineVolume.setPadding(dp(6), 0, dp(6), 0);
            bar.addView(inlineVolume, new LinearLayout.LayoutParams(dp(80), dp(Viewport.TOUCH)));
        }
        if (viewport.width >= 270) expandedButton = transport(bar, Icons.EXPAND, "Expand Now Playing", this::toggleExpanded);
        Button more = transport(bar, Icons.MORE, "Playback options", null);
        more.setOnClickListener(this::showPlaybackOptions);
        if (separateMetadata) bar.setGravity(Gravity.CENTER);
        return player;
    }

    private void showPlaybackOptions(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        addAction(popup, playback != null && playback.isShuffle() ? "Turn shuffle off" : "Turn shuffle on", this::toggleShuffle);
        addAction(popup, playback != null && playback.isRepeat() ? "Turn repeat off" : "Turn repeat on", this::toggleRepeat);
        addAction(popup, "Seek to position", this::showSeek);
        addAction(popup, "Media volume", this::showVolume);
        addAction(popup, "Lyrics", () -> openPlayer(true));
        addAction(popup, "Up next", () -> openPlayer(false));
        addAction(popup, "Mini player", this::toggleMiniPlayer);
        addAction(popup, "Now Playing", this::toggleExpanded);
        addAction(popup, "Song options", () -> showCurrentSongMenu(anchor));
        popup.show();
    }

    private void addMiniPlayer(FrameLayout stage) {
        miniPlayer = column();
        if (appearance.glass) {
            miniBackdrop = new FrostedBackdrop(appearance.window, appearance.surface, appearance.scrim,
                    prefs.number("brightness", 100));
            miniPlayer.setBackground(miniBackdrop);
        } else {
            miniPlayer.setBackground(shape(appearance.surface, 8));
        }
        miniPlayer.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(8));
            }
        });
        miniPlayer.setClipToOutline(true);
        miniPlayer.setElevation(dp(8));

        int width = Math.min(400, Math.max(1, viewport.width - 20));
        int coverSize = width >= 388 ? 78 : 56;
        boolean inline = width - coverSize - 30 >= 6 * Viewport.TOUCH;
        LinearLayout body = new LinearLayout(this);
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.setPadding(dp(10), dp(6), dp(10), 0);
        miniArtwork = cover("");
        miniArtwork.setContentDescription("Open Now Playing");
        miniArtwork.setOnClickListener(view -> openPlayer(false));
        body.addView(miniArtwork, new LinearLayout.LayoutParams(dp(coverSize), dp(coverSize)));

        LinearLayout details = column();
        details.setPadding(dp(10), 0, 0, 0);
        miniTitle = label("Now playing", viewport.songSp, INK);
        miniTitle.setTypeface(appearance.font, Typeface.BOLD);
        miniArtist = label("", viewport.metadataSp, MUTED);
        miniTitle.setPadding(0, 0, 0, 0);
        miniArtist.setPadding(0, 0, 0, 0);
        details.addView(miniTitle, new LinearLayout.LayoutParams(-1, -2));
        details.addView(miniArtist, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        boolean full = inline || width >= 6 * Viewport.TOUCH;
        if (full) {
            miniLike = transport(controls, Icons.HEART, "Like song", () -> rateCurrent("like", "Liked"));
            miniDislike = transport(controls, Icons.THUMBS_DOWN, "Dislike song", () -> rateCurrent("dislike", "Disliked"));
        }
        miniPrevious = transport(controls, Icons.PREVIOUS, "Previous", () -> { if (playback != null) playback.skip(-1); });
        miniPlay = playButton(controls, Viewport.TOUCH);
        miniNext = transport(controls, Icons.NEXT, "Next", () -> { if (playback != null) playback.skip(1); });
        if (full) transport(controls, Icons.VOLUME, "Media volume", this::showVolume);
        else {
            Button more = transport(controls, Icons.MORE, "Mini player options", null);
            more.setOnClickListener(anchor -> {
                PopupMenu popup = new PopupMenu(this, anchor);
                addAction(popup, "Like song", () -> rateCurrent("like", "Liked"));
                addAction(popup, "Dislike song", () -> rateCurrent("dislike", "Disliked"));
                addAction(popup, "Media volume", this::showVolume);
                popup.show();
            });
        }
        if (!appearance.barePlay) {
            miniPlay.setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x40666666),
                    new android.graphics.drawable.InsetDrawable(shape(INK, 24), dp(5)), null));
            miniPlay.setTextColor(0xff141119);
        }
        if (inline) details.addView(controls, new LinearLayout.LayoutParams(-1, dp(Viewport.TOUCH)));
        body.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
        miniPlayer.addView(body);
        if (!inline) miniPlayer.addView(controls, new LinearLayout.LayoutParams(-1, dp(Viewport.TOUCH)));

        miniSeek = new SeekBar(this);
        miniSeek.setPadding(0, 0, 0, 0);
        miniSeek.setThumb(null);
        miniSeek.setContentDescription("Mini player track progress");
        android.graphics.drawable.ClipDrawable filled = new android.graphics.drawable.ClipDrawable(
                shape(appearance.progress(accent()), 0), Gravity.LEFT, android.graphics.drawable.ClipDrawable.HORIZONTAL);
        android.graphics.drawable.LayerDrawable line = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{shape(0x44ffffff, 0), filled});
        line.setId(1, android.R.id.progress);
        for (int i = 0; i < line.getNumberOfLayers(); i++) {
            line.setLayerHeight(i, dp(2));
            line.setLayerGravity(i, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL);
        }
        miniSeek.setProgressDrawable(line);
        miniSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) {
                if (user && !miniSeeking && playback != null) playback.seekTo(value);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { miniSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                miniSeeking = false;
                if (playback != null) playback.seekTo(bar.getProgress());
            }
        });
        miniPlayer.addView(miniSeek, new LinearLayout.LayoutParams(-1, dp(Viewport.TOUCH)));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(width), -2, Gravity.BOTTOM | Gravity.END);
        params.setMargins(dp(10), dp(10), dp(10), dp(8));
        stage.addView(miniPlayer, params);
        miniPlayer.setVisibility(View.GONE);
    }

    private void toggleMiniPlayer() {
        if (miniPlayer.getVisibility() == View.VISIBLE) miniPlayer.setVisibility(View.GONE);
        else {
            closePlayer();
            miniPlayer.setVisibility(View.VISIBLE);
            renderPlayer();
        }
    }

    private void toggleExpanded() {
        if (expanded.getVisibility() == View.VISIBLE) closePlayer();
        else openPlayer(false);
    }

    private void toggleShuffle() {
        if (playback != null) playback.toggleShuffle();
    }

    private void toggleRepeat() {
        if (playback != null) playback.toggleRepeat();
    }

    private void showCurrentSongMenu(View anchor) {
        MusicApi.Track song = playback == null ? null : playback.currentTrack();
        if (song != null) showSongMenu(anchor, song, -1);
    }

    private void rateCurrent(String action, String feedback) {
        MusicApi.Track song = playback == null ? null : playback.currentTrack();
        if (song != null) react(song, action, feedback);
    }

    private void showSeek() {
        if (playback == null || playback.duration() <= 0) return;
        MusicApi.Track selected = playback.currentTrack();
        LinearLayout body = column();
        SeekBar slider = new SeekBar(this);
        slider.setMax(playback.duration());
        slider.setProgress(playback.position());
        slider.setContentDescription("Track progress");
        body.setPadding(dp(12), dp(8), dp(12), dp(8));
        body.addView(slider, new LinearLayout.LayoutParams(-1, dp(48)));
        new AlertDialog.Builder(this).setTitle("Seek to position").setView(body).setNegativeButton("Cancel", null)
                .setPositiveButton("Seek", (dialog, which) -> {
                    if (playback != null && playback.currentTrack() == selected) playback.seekTo(slider.getProgress());
                }).show();
    }

    @Override public void onStart() {
        super.onStart();
        bound = bindService(new Intent(this, PlaybackService.class), connection, Context.BIND_AUTO_CREATE);
        main.post(clock);
        performance.start();
    }

    @Override public void onStop() {
        main.removeCallbacks(clock);
        performance.stop();
        if (playback != null) playback.changed = null;
        if (bound) unbindService(connection);
        playback = null;
        bound = false;
        syncLyricFrames();
        super.onStop();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (performance == null) return;
        performance.trim(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN || level == TRIM_MEMORY_RUNNING_CRITICAL) {
            artwork.trim();
            pages.keySet().removeIf(key -> !key.equals(browseKey));
        }
    }

    @Override public void onLowMemory() {
        super.onLowMemory();
        if (performance == null) return;
        performance.trim(TRIM_MEMORY_COMPLETE);
        artwork.trim();
        pages.keySet().removeIf(key -> !key.equals(browseKey));
    }

    private void applyPerformance() {
        PerformancePolicy.Profile profile = performance.profile();
        artwork.configure(profile, performance.cacheBytes(), performance.diskBytes());
        if (backdrop != null) backdrop.setProfile(profile);
        if (miniBackdrop != null) miniBackdrop.setProfile(profile);
        expanded.setProfile(profile);
        if (settingsView != null) settingsView.updatePerformance();
    }

    @Override public void onDestroy() {
        ++generation;
        if (signInDialog != null && signInDialog.isShowing()) signInDialog.dismiss();
        network.shutdownNow();
        downloads.shutdownNow();
        expanded.close();
        cancelPlayerArtwork.run();
        artwork.close();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("page", page);
        state.putString("query", query.getText().toString());
        state.putString("collectionId", collectionId);
        state.putString("collectionTitle", collectionTitle);
        state.putString("mood", mood);
        state.putString("searchType", searchType.name());
        state.putString("settingsTab", settingsTab);
        state.putInt("settingsScroll", settingsView == null ? 0 : settingsView.scrollPosition());
        state.putInt("browseRow", scroll.getFirstVisiblePosition());
        state.putInt("browseOffset", scroll.rowOffset());
        if (miniPlayer.getVisibility() == View.VISIBLE) state.putString("playerPanel", "mini");
        else if (expanded.getVisibility() == View.VISIBLE) {
            state.putString("playerPanel", expanded.lyricsVisible() ? "lyrics" : "queue");
        }
        if (pendingDownload != null) {
            Bundle song = new Bundle();
            song.putString("id", pendingDownload.id);
            song.putString("title", pendingDownload.title);
            song.putString("artist", pendingDownload.artist);
            state.putBundle("download", song);
        }
        super.onSaveInstanceState(state);
    }

    private void navigate(String target) {
        if ("Account".equals(target) && query.hasFocus()) {
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(query.getWindowToken(), 0);
            query.clearFocus();
        }
        page = target;
        mood = "";
        loadPage();
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (miniPlayer.getVisibility() == View.VISIBLE) miniPlayer.setVisibility(View.GONE);
        else if (expanded.getVisibility() == View.VISIBLE) closePlayer();
        else if (!"Home".equals(page)) navigate("Home");
        else super.onBackPressed();
    }

    private void search() {
        if (query.getText().toString().trim().isEmpty()) {
            query.setError("Enter a song or artist.");
            return;
        }
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(query.getWindowToken(), 0);
        searchType = Browse.Search.Songs;
        navigate("Search");
    }

    private void openAccount() {
        if (!account.isEmpty() || refreshingAccount) {
            settingsTab = "Account";
            navigate("Account");
            return;
        }
        if (signInDialog != null && signInDialog.isShowing()) return;
        try {
            signInDialog = GoogleSignIn.show(this, this::verifyAccount);
        } catch (RuntimeException unavailable) {
            accountError = "Android System WebView could not be opened.";
            accountErrorCode = "WEBVIEW_UNAVAILABLE";
            retryAccount = false;
            navigate("Account");
            GoogleSignIn.openSeparately(this);
        }
    }

    private void verifyAccount() {
        if (refreshingAccount) return;
        refreshingAccount = true;
        accountError = "";
        accountErrorCode = "";
        retryAccount = false;
        if ("Account".equals(page)) loadPage();
        String cookies = GoogleSignIn.cookies();
        network.execute(() -> {
            String name = "";
            MusicApi.AccountFailure error = null;
            try {
                api.setSession(cookies);
                name = api.accountName();
            } catch (MusicApi.AccountFailure failure) {
                error = failure;
                api.setSession("");
            }
            String verified = name;
            MusicApi.AccountFailure detail = error;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                refreshingAccount = false;
                pages.clear();
                browseKey = "";
                account = verified;
                accountError = detail == null ? "" : detail.getMessage();
                accountErrorCode = detail == null ? "" : detail.code;
                retryAccount = detail != null && !detail.signedOut;
                if (account.isEmpty()) {
                    settingsTab = "Account";
                    navigate("Account");
                }
                else {
                    loadPage();
                    loadSidebarPlaylists();
                }
            });
        });
    }

    private void loadSidebarPlaylists() {
        String name = account;
        String cookies = GoogleSignIn.cookies();
        network.execute(() -> {
            try {
                api.setSession(cookies);
                List<Browse.Shelf> saved = Browse.shelves(api.browse(
                        new JSONObject().put("continuation", Browse.SAVED_PLAYLISTS)));
                runOnUiThread(() -> {
                    if (isDestroyed() || refreshingAccount || !name.equals(account)) return;
                    List<Browse.Card> pinned = new ArrayList<>();
                    for (Browse.Shelf shelf : saved) {
                        for (Browse.Card card : shelf.cards) {
                            if (!card.browseId.isEmpty() && !"VLLM".equals(card.browseId)) pinned.add(card);
                        }
                    }
                    sidebarItems = pinned;
                    sidebarPlaylists.removeAllViews();
                    if (!viewport.iconRail() && !pinned.isEmpty()) sidebarPlaylists.addView(label("Playlists", 12, MUTED));
                    for (Browse.Card card : pinned) sidebarPlaylists.addView(playlistNav(card));
                });
            } catch (Exception ignored) {
                // Library's main page still offers a user-initiated retry.
            }
        });
    }

    private View playlistNav(Browse.Card card) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(2), dp(3), dp(2));
        row.setMinimumHeight(dp(44));
        row.setFocusable(true);
        row.setContentDescription(card.title);
        row.setOnClickListener(view -> openCollection(card));
        row.addView(cover(card.image), new LinearLayout.LayoutParams(dp(28), dp(28)));
        LinearLayout text = column();
        text.addView(label(card.title, 13, INK));
        if (!card.subtitle.isEmpty()) text.addView(label(card.subtitle, 11, MUTED));
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private void signOut() {
        refreshingAccount = true;
        GoogleSignIn.signOut(() -> {
            if (isDestroyed()) return;
            refreshingAccount = false;
            account = "";
            accountError = "";
            accountErrorCode = "";
            retryAccount = false;
            sidebarPlaylists.removeAllViews();
            sidebarItems = Collections.emptyList();
            pages.clear();
            browseKey = "";
            if (playback != null) playback.stopForSignOut();
            navigate("Home");
        });
        loadPage();
    }

    private void openLiked() {
        if (!requireAccount()) return;
        collectionId = "VLLM";
        collectionTitle = "Liked Music";
        navigate("Collection");
    }

    private void showNavigationDrawer() {
        List<String> labels = new ArrayList<>(java.util.Arrays.asList(
                "Home", "Explore", "Library", account.isEmpty() ? "Sign in" : account, "Liked Music", "Settings"));
        List<Browse.Card> playlists = sidebarItems;
        for (Browse.Card card : playlists) labels.add(card.title);
        new AlertDialog.Builder(this).setTitle("Navigation").setItems(labels.toArray(new String[0]), (dialog, index) -> {
            switch (index) {
                case 0: case 1: case 2: navigate(labels.get(index)); break;
                case 3: openAccount(); break;
                case 4: openLiked(); break;
                case 5: navigate("Account"); break;
                default: openCollection(playlists.get(index - 6));
            }
        }).show();
    }

    private void loadPage() {
        BrowsePage previous = pages.get(browseKey);
        if (previous != null) {
            previous.row = scroll.getFirstVisiblePosition();
            previous.offset = scroll.rowOffset();
        }
        browseKey = "";
        final int current = ++generation;
        if (pageTask != null) pageTask.cancel(true);
        showNavigation();
        results.clear();
        if (settingsView != null) {
            paneContent.removeView(settingsView);
            settingsView = null;
        }
        boolean settingsPage = "Account".equals(page);
        musicSearchBar.setVisibility(settingsPage ? View.GONE : View.VISIBLE);
        scroll.setVisibility(settingsPage ? View.GONE : View.VISIBLE);
        if (settingsPage) {
            publishRows(0, 0);
            showSettings();
            return;
        }
        if ("Library".equals(page) && account.isEmpty()) {
            heading("Library");
            results.add(() -> label("Sign in to see your playlists.", viewport.songSp, MUTED));
            results.add(() -> button("Sign in with Google", this::openAccount));
            publishRows(0, 0);
            return;
        }
        String active = page;
        Browse.Search type = searchType;
        String input = query.getText().toString().trim();
        String selectedMood = mood;
        String selectedCollection = collectionId;
        String cookies = account.isEmpty() ? "" : GoogleSignIn.cookies();
        boolean hideExplicit = prefs.flag("hideExplicit", false);
        String key = active + "\n" + !cookies.isEmpty() + "\n" + hideExplicit;
        if ("Search".equals(active)) key += "\n" + type + "\n" + input;
        if ("Home".equals(active)) key += "\n" + selectedMood;
        if ("Collection".equals(active)) key += "\n" + selectedCollection;
        final String destination = key;
        BrowsePage cached = pages.get(key);
        if (cached != null && android.os.SystemClock.uptimeMillis() - cached.loaded < 120_000) {
            renderBrowsePage(key, cached);
            return;
        }
        results.add(() -> label("Loading…", viewport.songSp, MUTED));
        publishRows(0, 0);
        pageTask = network.submit(() -> {
            try {
                api.setSession(cookies);
                BrowsePage loaded;
                if ("Search".equals(active) && type == Browse.Search.Songs) {
                    loaded = new BrowsePage(Collections.emptyList(), Collections.emptyList(), api.search(input));
                } else {
                    JSONObject data;
                    if ("Search".equals(active)) data = api.search(input, type.params);
                    else {
                        JSONObject request = new JSONObject();
                        switch (active) {
                            case "Library": request.put("continuation", Browse.SAVED_PLAYLISTS); break;
                            case "Explore": request.put("browseId", "FEmusic_explore"); break;
                            case "Collection": request.put("browseId", selectedCollection); break;
                            default:
                                request.put("browseId", "FEmusic_home");
                                if (!selectedMood.isEmpty()) request.put("params", selectedMood);
                        }
                        data = api.browse(request);
                    }
                    loaded = new BrowsePage(Browse.shelves(data, hideExplicit),
                            "Home".equals(active) ? Browse.moods(data) : Collections.emptyList(), null);
                }
                runOnUiThread(() -> {
                    if (generation != current || isDestroyed()) return;
                    pages.put(destination, loaded);
                    while (pages.size() > (performance.profile().workers == 1 ? 2 : 4)) {
                        pages.remove(pages.keySet().iterator().next());
                    }
                    renderBrowsePage(destination, loaded);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (generation != current || isDestroyed()) return;
                    if (cached != null) {
                        renderBrowsePage(destination, cached);
                        return;
                    }
                    results.clear();
                    results.add(() -> label("Could not load " + active.toLowerCase(java.util.Locale.ROOT) + ".", viewport.songSp, MUTED));
                    results.add(() -> button("Retry", this::loadPage));
                    publishRows(0, 0);
                });
            }
        });
    }

    private void renderBrowsePage(String key, BrowsePage content) {
        browseKey = key;
        results.clear();
        if (content.tracks != null) showTracks(content.tracks);
        else showBrowse(content);
        publishRows(browseRow != 0 || browseOffset != 0 ? browseRow : content.row,
                browseRow != 0 || browseOffset != 0 ? browseOffset : content.offset);
        browseRow = browseOffset = 0;
    }

    private void publishRows(int row, int offset) {
        scroll.show(new ArrayList<>(results), row, offset);
    }

    private void showNavigation() {
        home.setSelected("Home".equals(page));
        explore.setSelected("Explore".equals(page));
        library.setSelected("Library".equals(page));
        liked.setSelected("Collection".equals(page) && "VLLM".equals(collectionId));
        settings.setSelected("Account".equals(page));
        String name = account.isEmpty() ? "Sign in" : account;
        if (viewport.width < 780) setNavIcon(accountButton, Icons.USER, name, true);
        else accountButton.setText(name);
        boolean iconsOnly = viewport.iconRail();
        setNavIcon(home, Icons.HOME, "Home", iconsOnly);
        setNavIcon(explore, Icons.EXPLORE, "Explore", iconsOnly);
        setNavIcon(library, Icons.LIBRARY, "Library", iconsOnly);
        setNavIcon(settings, Icons.SETTINGS, "Settings", iconsOnly);
        setNavIcon(sideAccount, Icons.USER, name, iconsOnly);
    }

    private void setNavIcon(Button button, String icon, String label, boolean iconsOnly) {
        button.setText(iconsOnly ? icon : Icons.withLabel(icon, label, appearance.icons));
        button.setTypeface(iconsOnly ? appearance.icons : appearance.font);
        if (iconsOnly) button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 23);
        else button.setTextSize(viewport.songSp);
        button.setContentDescription(label);
    }

    private void showSettings() {
        settingsView = new SettingsView(this, prefs, new SettingsView.Host() {
            @Override public String accountLabel() { return account.isEmpty() ? "Google account" : account; }
            @Override public String accountDescription() {
                if (refreshingAccount) return "Checking account…";
                if (!account.isEmpty()) return "Signed in with Google.";
                if (accountError.isEmpty()) return "Sign in to see your home feed and playlists.";
                String advice = GoogleSignIn.webViewAdvice();
                return advice.isEmpty() ? accountError : accountError + "\n" + advice;
            }
            @Override public boolean checkingAccount() { return refreshingAccount; }
            @Override public boolean signedIn() { return !account.isEmpty(); }
            @Override public boolean accountVerificationFailed() { return !accountErrorCode.isEmpty(); }
            @Override public boolean canRetryAccount() { return retryAccount; }
            @Override public String performanceStatus() { return performance.summary(); }
            @Override public String deviceCapabilities() { return performance.capabilitiesText(viewport); }
            @Override public String connectionLabel() {
                String user = ListenBrainz.account(prefs);
                return user.isEmpty() ? "Send played tracks to ListenBrainz after connecting." : "Connected as " + user;
            }
            @Override public void run(String action) { settingsAction(action); }
            @Override public void tabChanged(String selected) { settingsTab = selected; }
            @Override public void changed(String key) { settingsChanged(key); }
        }, settingsTab, INK, MUTED, appearance.panel(appearance.surface, 166), accent(), appearance, viewport);
        paneContent.addView(settingsView, new FrameLayout.LayoutParams(-1, -1));
        if (settingsScroll > 0) {
            settingsView.restoreScroll(settingsScroll);
            settingsScroll = 0;
        }
    }

    private void settingsChanged(String key) {
        switch (key) {
            case "theme": case "accent": case "accentControls": case "font": case "zoom":
            case "flat": case "brightness": case "sidebar": case "layoutDensity": case "coverColors": case "glass":
                recreate();
                return;
            case "performance":
                performance.choose(prefs.text("performance", "AUTO"));
                return;
            case "lyricRoman": case "lyricRomanSize": case "lyricTranslation": case "lyricTranslationSize": case "lyricTarget":
                expanded.refreshLyricAppearance();
                break;
            case "lrclib": case "ytLyrics": case "unisonLyrics": case "lyricsFirst":
                expanded.invalidateLyrics();
                break;
            case "balance": case "normalization": case "equalizer": case "speed": case "speedEnabled": case "keepPitch": case "eqPreset":
                if (playback != null) playback.settingsChanged();
                break;
            default:
                if (key.startsWith("eqBand") && playback != null) playback.settingsChanged();
                else renderPlayer();
        }
    }

    private void settingsAction(String action) {
        if (action.startsWith("icon:")) {
            try {
                AppIcons.apply(this, prefs, action.substring(5));
                recreate();
            } catch (RuntimeException error) {
                toast("Could not change the launcher icon.");
            }
            return;
        }
        switch (action) {
            case "account": if (account.isEmpty()) openAccount(); else signOut(); break;
            case "account-retry": verifyAccount(); break;
            case "account-browser": GoogleSignIn.openSeparately(this); break;
            case "account-diagnostic":
                if (!accountErrorCode.isEmpty()) {
                    GoogleSignIn.copyDiagnostic(this, Uri.parse(MusicApi.MUSIC), "account verification failed", accountErrorCode);
                    toast("Login diagnostic copied");
                }
                break;
            case "reset-layout": prefs.resetLayout(); recreate(); break;
            case "cache": artwork.clear(); toast("Artwork cache cleared."); break;
            case "network": testConnection(); break;
            case "notifications":
                startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
                break;
            case "listenbrainz": manageListenBrainz(); break;
            case "updates": checkUpdate(); break;
            case "source": openLink(BuildVersion.REPO); break;
            case "upstream": openLink(BuildVersion.UPSTREAM); break;
        }
    }

    private int accent() { return prefs.flag("accentControls", true) ? appearance.accent : INK; }

    private void openLink(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (android.content.ActivityNotFoundException unavailable) {
            toast("No app is available to open this link.");
        }
    }

    private void testConnection() {
        network.execute(() -> {
            boolean connected;
            try { connected = api.connectionWorks(); }
            catch (Exception unavailable) { connected = false; }
            boolean result = connected;
            runOnUiThread(() -> {
                if (!isDestroyed()) toast(result ? "YouTube Music responded." : "YouTube Music could not be reached.");
            });
        });
    }

    @SuppressWarnings("deprecation")
    private void checkUpdate() {
        network.execute(() -> {
            try {
                String[] update = AndroidUpdates.check(getPackageManager().getPackageInfo(getPackageName(), 0).versionCode);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    if (update == null) toast("You’re on the latest Android build.");
                    else new AlertDialog.Builder(this).setTitle("YTM Head Unit " + update[0])
                            .setMessage("Download the APK and install it through Android.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Download APK", (dialog, which) -> openLink(update[1])).show();
                });
            } catch (Exception unavailable) {
                runOnUiThread(() -> { if (!isDestroyed()) toast("GitHub update check could not complete."); });
            }
        });
    }

    private void manageListenBrainz() {
        String user = ListenBrainz.account(prefs);
        if (!user.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("ListenBrainz").setMessage("Connected as " + user)
                    .setNegativeButton("Cancel", null).setPositiveButton("Disconnect", (dialog, button) -> {
                        ListenBrainz.disconnect(prefs);
                        loadPage();
                    }).show();
            return;
        }
        EditText input = new EditText(this);
        input.setHint("User token");
        input.setSingleLine();
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        AlertDialog connect = new AlertDialog.Builder(this).setTitle("Connect ListenBrainz")
                .setView(input).setNegativeButton("Cancel", null)
                .setNeutralButton("Get token", (dialog, button) -> openLink("https://listenbrainz.org/settings/"))
                .setPositiveButton("Connect", null).create();
        java.util.concurrent.atomic.AtomicBoolean active = new java.util.concurrent.atomic.AtomicBoolean(true);
        connect.setOnDismissListener(dialog -> active.set(false));
        connect.setOnShowListener(dialog -> connect.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            String token = input.getText().toString().trim();
            if (token.isEmpty()) { input.setError("Enter your user token."); return; }
            button.setEnabled(false);
            input.setEnabled(false);
            network.execute(() -> {
                try {
                    String name = ListenBrainz.connect(prefs, token, active::get);
                    runOnUiThread(() -> {
                        if (isDestroyed()) return;
                        connect.dismiss();
                        toast("Connected as " + name);
                        if ("Account".equals(page)) loadPage();
                    });
                } catch (Exception unavailable) {
                    runOnUiThread(() -> {
                        if (!connect.isShowing() || isDestroyed()) return;
                        input.setEnabled(true);
                        button.setEnabled(true);
                        input.setError("Could not validate or store this token.");
                    });
                }
            });
        }));
        connect.show();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            int key = event.getKeyCode();
            if (key == KeyEvent.KEYCODE_ESCAPE) { onBackPressed(); return true; }
            if (key == KeyEvent.KEYCODE_F && event.isCtrlPressed()) {
                closePlayer();
                if (settingsView != null && "Account".equals(page)) settingsView.focusSearch();
                else {
                    query.requestFocus();
                    getSystemService(InputMethodManager.class).showSoftInput(query, InputMethodManager.SHOW_IMPLICIT);
                }
                return true;
            }
            if (playback != null && !(getCurrentFocus() instanceof EditText)) {
                if (key == KeyEvent.KEYCODE_SPACE) { playback.toggle(); return true; }
                if (event.isCtrlPressed() && (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                    playback.skip(key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void showTracks(List<MusicApi.Track> tracks) {
        showSearchFilters();
        if (tracks.isEmpty()) results.add(() -> label("No songs found.", viewport.songSp, MUTED));
        for (int i = 0; i < tracks.size(); i++) {
            int index = i;
            MusicApi.Track track = tracks.get(index);
            Browse.Card card = new Browse.Card(track.title, track.artist, track.artwork, "", track);
            results.add(() -> songRow(card, tracks, index));
        }
    }

    private void showSearchFilters() {
        results.add(() -> chipRow(row -> {
            for (Browse.Search type : Browse.Search.values()) {
                Button chip = button(type.name(), () -> {
                    searchType = type;
                    loadPage();
                });
                if (type == searchType) chip.setTextColor(accent());
                row.addView(chip, spacedWidth(-2, Viewport.TOUCH));
            }
        }));
    }

    private void showBrowse(BrowsePage content) {
        if ("Search".equals(page)) showSearchFilters();
        if (!content.moods.isEmpty()) {
            results.add(() -> chipRow(row -> {
                for (Browse.Mood value : content.moods) {
                    Button chip = button(value.title, () -> {
                        mood = value.params.equals(mood) ? "" : value.params;
                        loadPage();
                    });
                    chip.setSelected(value.params.equals(mood));
                    if (chip.isSelected()) chip.setTextColor(accent());
                    row.addView(chip, spacedWidth(-2, Viewport.TOUCH));
                }
            }));
        }
        if ("Library".equals(page)) heading("Playlists");
        else if ("Explore".equals(page)) heading("Explore");
        else if ("Collection".equals(page) && !collectionTitle.isEmpty()) heading(collectionTitle);
        if (content.shelves.isEmpty()) results.add(() -> label("No music to show.", viewport.songSp, MUTED));
        for (int i = 0; i < content.shelves.size(); i++) addShelf(content.shelves.get(i), content.shelfStart(i));
    }

    private void addShelf(Browse.Shelf shelf, int[] start) {
        List<MusicApi.Track> tracks = new ArrayList<>();
        for (Browse.Card card : shelf.cards) if (card.track != null) tracks.add(card.track);
        String title = shelf.title.isEmpty() ? "Songs" : shelf.title;
        boolean songList = tracks.size() == shelf.cards.size();
        if (songList && "Collection".equals(page)) {
            if (!shelf.title.isEmpty()) heading(shelf.title);
            for (int i = 0; i < tracks.size(); i++) {
                int index = i;
                results.add(() -> songRow(shelf.cards.get(index), tracks, index));
            }
            return;
        }

        if (songList && !"Library".equals(page) && !shelf.preferArtwork) {
            int rows = viewport.shortScreen ? 2 : 3;
            results.add(() -> new ShelfPager(this, viewport, title, (tracks.size() + rows - 1) / rows,
                    225, INK, appearance.font, start, (group, width) -> {
                LinearLayout column = column();
                for (int i = group * rows; i < Math.min(tracks.size(), (group + 1) * rows); i++) {
                    column.addView(songRow(shelf.cards.get(i), tracks, i));
                }
                return column;
            }));
        } else {
            results.add(() -> new ShelfPager(this, viewport, title, shelf.cards.size(),
                    viewport.albumCard, INK, appearance.font, start,
                    (index, width) -> albumTile(shelf.cards.get(index), tracks, width)));
        }
    }

    private View albumTile(Browse.Card card, List<MusicApi.Track> tracks, int width) {
        LinearLayout tile = column();
        int padding = dp(3);
        tile.setPadding(padding, padding, padding, dp(6));
        tile.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x35ffffff), null, shape(Color.WHITE, appearance.cardRadius)));
        tile.setContentDescription(card.title);
        tile.setFocusable(true);
        tile.setOnClickListener(view -> {
            if (card.track != null) playTracks(tracks, tracks.indexOf(card.track));
            else openCollection(card);
        });
        if (card.track != null) tile.setOnLongClickListener(view -> {
            showSongMenu(view, card.track, -1);
            return true;
        });
        tile.addView(cover(card.image), new LinearLayout.LayoutParams(-1, Math.max(1, width - 2 * padding)));
        TextView title = label(card.title, viewport.songSp, INK);
        title.setTypeface(appearance.font, Typeface.BOLD);
        title.setMaxLines(2);
        tile.addView(title);
        if (!card.subtitle.isEmpty()) tile.addView(label(card.subtitle, viewport.metadataSp, MUTED));
        return tile;
    }

    private View songRow(Browse.Card card, List<MusicApi.Track> tracks, int index) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(3), dp(4), dp(3));
        row.setMinimumHeight(dp(Viewport.TOUCH));
        row.setBackgroundResource(android.R.drawable.list_selector_background);
        row.setContentDescription(card.title);
        row.setFocusable(true);
        row.setOnClickListener(view -> playTracks(tracks, index));
        row.setOnLongClickListener(view -> {
            showSongMenu(view, card.track, -1);
            return true;
        });
        row.addView(cover(card.image), new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout text = column();
        text.setPadding(dp(6), 0, 0, 0);
        text.addView(label(card.title, viewport.songSp, INK));
        text.addView(label(card.subtitle, viewport.metadataSp, MUTED));
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private void openCollection(Browse.Card card) {
        collectionId = card.browseId;
        collectionTitle = card.title;
        navigate("Collection");
    }

    private void playTracks(List<MusicApi.Track> tracks, int index) {
        if (playback != null) playback.play(tracks, index);
    }

    private ImageView cover(String url) {
        ImageView view = new ImageView(this);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setBackground(shape(appearance.control, appearance.thumbRadius));
        view.setClipToOutline(true);
        if (!url.isEmpty()) artwork.load(view, url);
        return view;
    }

    private View chipRow(java.util.function.Consumer<LinearLayout> populate) {
        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        horizontal.addView(row);
        populate.accept(row);
        return horizontal;
    }

    private void openPlayer(boolean lyrics) {
        miniPlayer.setVisibility(View.GONE);
        if (expandedButton != null) {
            expandedButton.setText(Icons.COLLAPSE);
            expandedButton.setContentDescription("Collapse Now Playing");
        }
        browser.setVisibility(View.GONE);
        expanded.setVisibility(View.VISIBLE);
        expanded.show(playback, lyrics);
        syncLyricFrames();
    }

    private void closePlayer() {
        if (expandedButton != null) {
            expandedButton.setText(Icons.EXPAND);
            expandedButton.setContentDescription("Expand Now Playing");
        }
        expanded.setVisibility(View.GONE);
        browser.setVisibility(View.VISIBLE);
        syncLyricFrames();
    }

    private SeekBar volumeSlider() {
        AudioManager audio = getSystemService(AudioManager.class);
        SeekBar volume = new SeekBar(this);
        volume.setContentDescription("Media volume");
        volume.setProgressTintList(android.content.res.ColorStateList.valueOf(appearance.progress(accent())));
        volume.setThumbTintList(android.content.res.ColorStateList.valueOf(appearance.progress(accent())));
        refreshVolume(volume, audio);
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                if (!user || audio.isVolumeFixed()) return;
                boolean log = prefs.flag("logVolume", false);
                int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int level = log ? (int) Math.round(max * Math.pow(progress / 100.0, 2)) : progress;
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) { syncVolume(); }
        });
        return volume;
    }

    private void syncVolume() {
        if (inlineVolume != null && !inlineVolume.isPressed()) {
            refreshVolume(inlineVolume, getSystemService(AudioManager.class));
        }
        volumeRefreshed = android.os.SystemClock.uptimeMillis();
    }

    private void refreshVolume(SeekBar volume, AudioManager audio) {
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int current = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        boolean log = prefs.flag("logVolume", false);
        volume.setMax(log ? 100 : max);
        volume.setProgress(log && max > 0 ? (int) Math.round(100 * Math.sqrt((double) current / max)) : current);
        volume.setEnabled(!audio.isVolumeFixed());
    }

    private void showVolume() {
        LinearLayout body = column();
        body.setPadding(dp(16), dp(8), dp(16), dp(8));
        body.addView(volumeSlider(), new LinearLayout.LayoutParams(-1, dp(48)));
        new AlertDialog.Builder(this).setTitle("Media volume").setView(body)
                .setPositiveButton("Done", null).show();
    }

    private void showSongMenu(View anchor, MusicApi.Track track, int queueIndex) {
        PopupMenu popup = new PopupMenu(this, anchor);
        addAction(popup, "Like song", () -> react(track, "like", "Liked"));
        addAction(popup, "Dislike song", () -> react(track, "dislike", "Disliked"));
        addAction(popup, "Clear rating", () -> react(track, "removelike", "Rating cleared"));
        addAction(popup, "Add to playlist", () -> pickPlaylist(track));
        addAction(popup, "Copy link", () -> {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            clipboard.setPrimaryClip(ClipData.newPlainText("YouTube Music", MusicApi.MUSIC + "/watch?v=" + track.id));
            toast("Link copied");
        });
        addAction(popup, "Download", () -> selectDownload(track));
        addAction(popup, "Play next", () -> { if (playback != null) playback.enqueue(track, true); });
        addAction(popup, "Add to queue", () -> { if (playback != null) playback.enqueue(track, false); });
        addAction(popup, "Start radio", () -> startRadio(track));
        if (!track.artistId.isEmpty()) {
            addAction(popup, "Go to artist", () -> {
                closePlayer();
                collectionId = track.artistId;
                collectionTitle = track.artist;
                navigate("Collection");
            });
        }
        if (queueIndex >= 0 && playback != null) {
            if (queueIndex > Math.max(0, playback.queueIndex() + 1)) {
                addAction(popup, "Move up", () -> { if (isQueueEntry(track, queueIndex)) playback.moveAt(queueIndex, -1); });
            }
            if (queueIndex + 1 < playback.queueSnapshot().size() && queueIndex != playback.queueIndex()) {
                addAction(popup, "Move down", () -> { if (isQueueEntry(track, queueIndex)) playback.moveAt(queueIndex, 1); });
            }
            if (queueIndex != playback.queueIndex()) {
                addAction(popup, "Remove from queue", () -> { if (isQueueEntry(track, queueIndex)) playback.removeAt(queueIndex); });
            }
        }
        popup.show();
    }

    private boolean isQueueEntry(MusicApi.Track track, int index) {
        if (playback == null) return false;
        List<MusicApi.Track> current = playback.queueSnapshot();
        return index < current.size() && track == current.get(index);
    }

    private void addAction(PopupMenu popup, String label, Runnable action) {
        popup.getMenu().add(label).setOnMenuItemClickListener(item -> {
            action.run();
            return true;
        });
    }

    private void selectDownload(MusicApi.Track track) {
        if (pendingDownload != null) return;
        Intent picker = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("audio/mp4").putExtra(Intent.EXTRA_TITLE, MusicDownload.fileName(track.title));
        pendingDownload = track;
        try {
            startActivityForResult(picker, SAVE_AUDIO);
        } catch (android.content.ActivityNotFoundException missing) {
            pendingDownload = null;
            toast("No Android file picker is installed.");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != SAVE_AUDIO) return;
        MusicApi.Track track = pendingDownload;
        pendingDownload = null;
        if (result != RESULT_OK || data == null || data.getData() == null || track == null) return;
        Uri destination = data.getData();
        if (!"content".equals(destination.getScheme())) return;
        String cookies = account.isEmpty() ? "" : GoogleSignIn.cookies();
        toast("Downloading " + track.title);
        downloads.execute(() -> {
            String status;
            try {
                MusicDownload.save(getApplicationContext(), track, cookies, destination);
                status = "Download saved.";
            } catch (Exception error) {
                status = error.getMessage() == null ? "Download failed." : error.getMessage();
            }
            String feedback = status;
            runOnUiThread(() -> { if (!isDestroyed()) toast(feedback); });
        });
    }

    private void startRadio(MusicApi.Track track) {
        String cookies = account.isEmpty() ? "" : GoogleSignIn.cookies();
        network.execute(() -> {
            try {
                api.setSession(cookies);
                List<MusicApi.Track> songs = api.radio(track);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    if (songs.size() > 1) playTracks(songs, 0);
                    else toast("No station was returned for this song.");
                });
            } catch (Exception error) {
                runOnUiThread(() -> { if (!isDestroyed()) toast("Radio could not load."); });
            }
        });
    }

    private boolean requireAccount() {
        if (!refreshingAccount && !account.isEmpty()) return true;
        toast("Sign in to use this action.");
        openAccount();
        return false;
    }

    private void react(MusicApi.Track track, String action, String feedback) {
        if (!requireAccount()) return;
        network.execute(() -> {
            try {
                api.setSession(GoogleSignIn.cookies());
                api.react(track, action);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    pages.clear();
                    toast(feedback);
                });
            } catch (Exception error) {
                runOnUiThread(() -> { if (!isDestroyed()) toast("YouTube rejected or could not save the rating."); });
            }
        });
    }

    private void pickPlaylist(MusicApi.Track track) {
        if (!requireAccount()) return;
        String selectedAccount = account;
        network.execute(() -> {
            try {
                api.setSession(GoogleSignIn.cookies());
                List<Browse.Card> playlists = new ArrayList<>();
                for (Browse.Shelf shelf : Browse.shelves(api.browse(new JSONObject().put("continuation", Browse.SAVED_PLAYLISTS)))) {
                    for (Browse.Card card : shelf.cards) {
                        if (card.browseId.startsWith("VLPL")) playlists.add(card);
                    }
                }
                runOnUiThread(() -> {
                    if (isDestroyed() || refreshingAccount || !selectedAccount.equals(account)) return;
                    if (playlists.isEmpty()) {
                        toast("No playlists were found.");
                        return;
                    }
                    String[] titles = new String[playlists.size()];
                    for (int i = 0; i < titles.length; i++) titles[i] = playlists.get(i).title;
                    new AlertDialog.Builder(this).setTitle("Add to playlist").setNegativeButton("Cancel", null)
                            .setItems(titles, (dialog, which) -> saveToPlaylist(track, playlists.get(which))).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> { if (!isDestroyed()) toast("Playlists could not load."); });
            }
        });
    }

    private void saveToPlaylist(MusicApi.Track song, Browse.Card playlist) {
        if (!requireAccount()) return;
        network.execute(() -> {
            try {
                api.setSession(GoogleSignIn.cookies());
                api.addToPlaylist(song, playlist.browseId.substring(2));
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    pages.clear();
                    toast("Saved to " + playlist.title);
                });
            } catch (Exception error) {
                runOnUiThread(() -> { if (!isDestroyed()) toast("YouTube could not edit this playlist."); });
            }
        });
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private void renderPlayer() {
        nowPlaying.setText(playback == null ? "Now playing" : playback.title());
        nowPlaying.setContentDescription(nowPlaying.getText() + ", open Now Playing");
        updatePlayerArtwork();
        playerStatus.setText(playback == null ? "" : playback.status());
        previous.setEnabled(playback != null && playback.canSkip(-1));
        next.setEnabled(playback != null && playback.canSkip(1));
        play.setEnabled(playback != null && playback.canPlay());
        boolean playing = playback != null && playback.playing();
        play.showPlaying(playing);
        miniPlay.showPlaying(playing);
        miniPlay.setEnabled(play.isEnabled());
        MusicApi.Track song = playback == null ? null : playback.currentTrack();
        miniTitle.setText(song == null ? "Now playing" : song.title);
        miniArtist.setText(song == null ? "" : song.artist);
        miniPrevious.setEnabled(previous.isEnabled());
        miniNext.setEnabled(next.isEnabled());
        if (miniLike != null) miniLike.setEnabled(song != null);
        if (miniDislike != null) miniDislike.setEnabled(song != null);
        if (shuffle != null) shuffle.setTextColor(playback != null && playback.isShuffle() ? accent() : INK);
        if (repeat != null) repeat.setTextColor(playback != null && playback.isRepeat() ? accent() : INK);
        if (expanded.getVisibility() == View.VISIBLE) expanded.update(playback);
        syncLyricFrames();
        renderProgress();
    }

    private void updatePlayerArtwork() {
        MusicApi.Track song = playback == null ? null : playback.currentTrack();
        String desired = song == null ? "" : song.artwork;
        if (currentArtwork.equals(desired)) return;
        currentArtwork = desired;
        cancelPlayerArtwork.run();
        if (backdrop != null) backdrop.setAlbum(null);
        if (miniBackdrop != null) miniBackdrop.setAlbum(null);
        miniArtwork.setImageDrawable(null);
        if (playerArtwork != null) {
            playerArtwork.setTag(null);
            playerArtwork.setImageDrawable(null);
            playerArtwork.setVisibility(desired.isEmpty() ? View.GONE : View.VISIBLE);
        }
        if (desired.isEmpty()) return;
        boolean tintArtwork = appearance.albumTint && prefs.flag("coverColors", true) && !prefs.flag("flat", false);
        cancelPlayerArtwork = artwork.acquire(desired, bitmap -> {
            if (isDestroyed() || !desired.equals(currentArtwork)) return;
            if (playerArtwork != null) playerArtwork.setImageBitmap(bitmap);
            miniArtwork.setImageBitmap(bitmap);
            if (tintArtwork && backdrop != null) backdrop.setAlbum(bitmap);
            if (tintArtwork && miniBackdrop != null) miniBackdrop.setAlbum(bitmap);
        });
    }

    private void renderProgress() {
        if (seeking) return;
        int duration = playback == null ? 0 : playback.duration();
        int position = playback == null ? 0 : playback.position();
        if (!miniSeeking && miniPlayer.getVisibility() == View.VISIBLE) {
            miniSeek.setEnabled(duration > 0);
            miniSeek.setMax(Math.max(1, duration));
            miniSeek.setProgress(position);
        }
        if (seek != null) {
            seek.setEnabled(duration > 0);
            if (seek.getThumb() != null) seek.getThumb().setAlpha(prefs.flag("seekKnob", true) || duration > 0 ? 255 : 0);
            seek.setMax(Math.max(1, duration));
            seek.setProgress(position);
        }
        if (playbackTime != null && (lastDisplayedDuration != duration || lastDisplayedSecond != position / 1000)) {
            lastDisplayedDuration = duration;
            lastDisplayedSecond = position / 1000;
            playbackTime.setText(duration > 0 ? time(position) + " / " + time(duration) : "0:00 / –:––");
        }
    }

    private static String time(int millis) {
        int seconds = millis / 1000;
        return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private Button navigation(LinearLayout parent, String title, Runnable action) {
        Button button = button(title, action);
        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        android.graphics.drawable.StateListDrawable background = new android.graphics.drawable.StateListDrawable();
        background.addState(new int[]{android.R.attr.state_selected}, shape(appearance.control, 9));
        background.addState(new int[]{android.R.attr.state_pressed}, shape(appearance.control, 9));
        background.addState(new int[]{android.R.attr.state_focused}, shape(appearance.control, 9));
        background.addState(new int[]{}, shape(Color.TRANSPARENT, 9));
        button.setBackground(background);
        button.setMinHeight(dp(Viewport.TOUCH));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(2);
        parent.addView(button, params);
        return button;
    }

    private PlayPauseButton playButton(LinearLayout row, int target) {
        PlayPauseButton button = new PlayPauseButton(this);
        button.setTextColor(INK);
        button.setOnClickListener(view -> { if (playback != null) playback.toggle(); });
        addTransport(row, button, target);
        return button;
    }

    private Button transport(LinearLayout row, String title, String description, Runnable action) {
        return transport(row, title, description, action, viewport.target);
    }

    private Button transport(LinearLayout row, String title, String description, Runnable action, int target) {
        Button button = button(title, action);
        button.setPadding(0, 0, 0, 0);
        button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 22);
        button.setTypeface(appearance.icons);
        button.setContentDescription(description);
        addTransport(row, button, target);
        return button;
    }

    private void addTransport(LinearLayout row, Button button, int target) {
        button.setGravity(Gravity.CENTER);
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x48ffffff), null, shape(Color.WHITE, 22)));
        row.addView(button, new LinearLayout.LayoutParams(dp(target), dp(target)));
    }

    private Button button(String title, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        button.setTextColor(INK);
        button.setTextSize(15);
        button.setTypeface(appearance.font);
        button.setMaxLines(1);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(Viewport.TOUCH));
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x40ffffff), shape(appearance.control, 12), null));
        if (action != null) button.setOnClickListener(view -> action.run());
        return button;
    }

    private void heading(String title) {
        results.add(() -> {
            TextView text = label(title, viewport.titleSp, INK);
            text.setTypeface(appearance.font, Typeface.BOLD);
            text.setPadding(dp(4), dp(8), 0, dp(5));
            return text;
        });
    }

    private TextView label(String text, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setTypeface(appearance.font);
        view.setTextColor(color);
        view.setMaxLines(1);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setPadding(dp(4), dp(3), dp(4), dp(3));
        return view;
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private LinearLayout.LayoutParams spacedWidth(int width, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width < 0 ? width : dp(width),
                height < 0 ? height : dp(height));
        params.leftMargin = dp(5);
        return params;
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
