// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.MediaRouteButton;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.media.AudioManager;
import android.media.MediaRouter;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
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
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class MainActivity extends Activity {
    private static final int SAVE_AUDIO = 21;
    private static final int INK = 0xfff4eef4;
    private static final int MUTED = 0xffc6b8cc;
    private static final int PANEL = 0xff211721;
    private static final int CONTROL = 0xff392c39;
    private static final int ACCENT = 0xfff83b61;

    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MusicApi api = new MusicApi();
    private final Artwork artwork = new Artwork();
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
    private String page = "Home";
    private Browse.Search searchType = Browse.Search.Songs;
    private String collectionId = "";
    private String mood = "";
    private Dialog signInDialog;
    private EditText query;
    private ScrollView scroll;
    private LinearLayout results, sidebarPlaylists;
    private TextView nowPlaying, playerStatus, playbackTime;
    private Button home, explore, library, settings, accountButton, sideAccount;
    private Button previous, play, next, shuffle, repeat;
    private SeekBar seek;
    private boolean seeking;

    private final Runnable clock = new Runnable() {
        @Override public void run() {
            renderProgress();
            if (expanded.getVisibility() == View.VISIBLE) {
                expanded.tick(playback == null ? 0 : playback.position());
            }
            main.postDelayed(this, 250);
        }
    };

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
        LinearLayout root = column();
        root.setBackgroundColor(0xff120c16);
        root.setFocusableInTouchMode(true);
        setContentView(root);

        FrameLayout stage = new FrameLayout(this);
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout body = new LinearLayout(this);
        browser = body;
        stage.addView(body, new FrameLayout.LayoutParams(-1, -1));
        expanded = new NowPlayingView(this, artwork, this::showSongMenu, this::closePlayer);
        expanded.setVisibility(View.GONE);
        stage.addView(expanded, new FrameLayout.LayoutParams(-1, -1));
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        LinearLayout side = column();
        side.setPadding(dp(10), dp(12), dp(10), dp(8));
        side.setBackgroundColor(0xff1b111d);
        body.addView(side, new LinearLayout.LayoutParams(dp(166), -1));

        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.setPadding(dp(8), 0, 0, 0);
        ImageView icon = new ImageView(this);
        icon.setImageResource(getApplicationInfo().icon);
        brand.addView(icon, new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView wordmark = label("Music", 23, INK);
        wordmark.setTypeface(null, Typeface.BOLD);
        brand.addView(wordmark);
        side.addView(brand, new LinearLayout.LayoutParams(-1, dp(52)));
        home = navigation(side, "Home", () -> navigate("Home"));
        explore = navigation(side, "Explore", () -> navigate("Explore"));
        library = navigation(side, "Library", () -> navigate("Library"));
        sideAccount = navigation(side, "Sign in", this::openAccount);
        ScrollView saved = new ScrollView(this);
        sidebarPlaylists = column();
        saved.addView(sidebarPlaylists);
        side.addView(saved, new LinearLayout.LayoutParams(-1, 0, 1));
        settings = navigation(side, "Settings", () -> navigate("Account"));

        LinearLayout mainPane = column();
        mainPane.setPadding(dp(18), dp(8), dp(16), dp(4));
        mainPane.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xff281520, 0xff21101b, 0xff100d17}));
        body.addView(mainPane, new LinearLayout.LayoutParams(0, -1, 1));

        LinearLayout searchBar = new LinearLayout(this);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        query = new EditText(this);
        query.setSingleLine();
        query.setTextSize(17);
        query.setHint("Search songs, albums, artists");
        query.setPadding(dp(16), 0, dp(12), 0);
        query.setBackground(shape(CONTROL, 22));
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        query.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            search();
            return true;
        });
        searchBar.addView(query, new LinearLayout.LayoutParams(0, dp(44), 1));
        searchBar.addView(button("Search", this::search), spacedWidth(86, 44));
        accountButton = button("Sign in", this::openAccount);
        searchBar.addView(accountButton, spacedWidth(106, 44));
        mainPane.addView(searchBar, new LinearLayout.LayoutParams(-1, dp(54)));

        scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        results = column();
        results.setPadding(0, dp(8), 0, dp(12));
        scroll.addView(results);
        mainPane.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        root.addView(playerBar(), new LinearLayout.LayoutParams(-1, dp(74)));
        root.requestFocus();
        renderPlayer();
        if (savedState != null) {
            page = savedState.getString("page", "Home");
            query.setText(savedState.getString("query", ""));
            collectionId = savedState.getString("collectionId", "");
            mood = savedState.getString("mood", "");
            searchType = Browse.Search.valueOf(savedState.getString("searchType", "Songs"));
            Bundle song = savedState.getBundle("download");
            if (song != null) pendingDownload = new MusicApi.Track(song.getString("id"), song.getString("title"),
                    song.getString("artist"), "", "");
        }
        if (MusicApi.hasSession(GoogleSignIn.cookies())) verifyAccount();
        else loadPage();
    }

    private LinearLayout playerBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(4), dp(12), dp(4));
        bar.setBackgroundColor(0xff110d15);
        LinearLayout information = column();
        nowPlaying = label("Now playing", 16, INK);
        playerStatus = label("", 13, MUTED);
        playerStatus.setMaxLines(2);
        nowPlaying.setOnClickListener(view -> openPlayer(false));
        information.addView(nowPlaying);
        information.addView(playerStatus);
        bar.addView(information, new LinearLayout.LayoutParams(0, -2, 1.25f));

        shuffle = transport(bar, "⤨", "Shuffle", () -> { if (playback != null) playback.toggleShuffle(); });
        previous = transport(bar, "⏮", "Previous", () -> { if (playback != null) playback.skip(-1); });
        play = transport(bar, "▶", "Play", () -> { if (playback != null) playback.toggle(); });
        next = transport(bar, "⏭", "Next", () -> { if (playback != null) playback.skip(1); });
        repeat = transport(bar, "⟳", "Repeat", () -> { if (playback != null) playback.toggleRepeat(); });
        seek = new SeekBar(this);
        seek.setContentDescription("Track progress");
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {}
            @Override public void onStartTrackingTouch(SeekBar bar) { seeking = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                seeking = false;
                if (playback != null) playback.seekTo(bar.getProgress());
            }
        });
        bar.addView(seek, new LinearLayout.LayoutParams(0, dp(48), 1f));
        playbackTime = label("", 13, MUTED);
        playbackTime.setGravity(Gravity.CENTER);
        bar.addView(playbackTime, new LinearLayout.LayoutParams(dp(86), -2));
        transport(bar, "◉", "Volume and audio output", this::showAudio);
        transport(bar, "♫", "Lyrics", () -> openPlayer(true));
        transport(bar, "☰", "Up next", () -> openPlayer(false));
        return bar;
    }

    @Override public void onStart() {
        super.onStart();
        bound = bindService(new Intent(this, PlaybackService.class), connection, Context.BIND_AUTO_CREATE);
        main.post(clock);
    }

    @Override public void onStop() {
        main.removeCallbacks(clock);
        if (playback != null) playback.changed = null;
        if (bound) unbindService(connection);
        playback = null;
        bound = false;
        super.onStop();
    }

    @Override public void onDestroy() {
        ++generation;
        if (signInDialog != null && signInDialog.isShowing()) signInDialog.dismiss();
        network.shutdownNow();
        downloads.shutdownNow();
        expanded.close();
        artwork.close();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("page", page);
        state.putString("query", query.getText().toString());
        state.putString("collectionId", collectionId);
        state.putString("mood", mood);
        state.putString("searchType", searchType.name());
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
        page = target;
        mood = "";
        loadPage();
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (expanded.getVisibility() == View.VISIBLE) closePlayer();
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
            navigate("Account");
            return;
        }
        if (signInDialog != null && signInDialog.isShowing()) return;
        try {
            signInDialog = GoogleSignIn.show(this, this::verifyAccount);
        } catch (RuntimeException unavailable) {
            accountError = "Android System WebView could not be opened.";
            navigate("Account");
        }
    }

    private void verifyAccount() {
        if (refreshingAccount) return;
        refreshingAccount = true;
        accountError = "";
        String cookies = GoogleSignIn.cookies();
        network.execute(() -> {
            String name = "";
            String error = "";
            try {
                api.setSession(cookies);
                name = api.accountName();
            } catch (Exception failure) {
                error = "YouTube could not verify your sign-in. Try signing in again.";
            }
            String verified = name;
            String detail = error;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                refreshingAccount = false;
                account = verified;
                accountError = detail;
                if (account.isEmpty()) navigate("Account");
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
                    sidebarPlaylists.removeAllViews();
                    sidebarPlaylists.addView(label("PLAYLISTS", 12, MUTED));
                    for (Browse.Shelf shelf : saved) {
                        for (Browse.Card card : shelf.cards) {
                            if (!card.browseId.isEmpty()) navigation(sidebarPlaylists, card.title, () -> openCollection(card));
                        }
                    }
                });
            } catch (Exception ignored) {
                // Library's main page still offers a user-initiated retry.
            }
        });
    }

    private void signOut() {
        refreshingAccount = true;
        GoogleSignIn.signOut(() -> {
            if (isDestroyed()) return;
            refreshingAccount = false;
            account = "";
            accountError = "";
            sidebarPlaylists.removeAllViews();
            if (playback != null) playback.stopForSignOut();
            navigate("Home");
        });
        loadPage();
    }

    private void loadPage() {
        final int current = ++generation;
        if (pageTask != null) pageTask.cancel(true);
        showNavigation();
        results.removeAllViews();
        scroll.scrollTo(0, 0);
        if ("Account".equals(page)) {
            showAccount();
            return;
        }
        if ("Library".equals(page) && account.isEmpty()) {
            heading("Library");
            results.addView(label("Sign in to see your playlists.", 16, MUTED));
            results.addView(button("Sign in with Google", this::openAccount), new LinearLayout.LayoutParams(dp(215), dp(48)));
            return;
        }
        String active = page;
        Browse.Search type = searchType;
        String input = query.getText().toString().trim();
        String selectedMood = mood;
        String selectedCollection = collectionId;
        String cookies = account.isEmpty() ? "" : GoogleSignIn.cookies();
        results.addView(label("Loading…", 16, MUTED));
        pageTask = network.submit(() -> {
            try {
                api.setSession(cookies);
                if ("Search".equals(active)) {
                    if (type == Browse.Search.Songs) {
                        List<MusicApi.Track> tracks = api.search(input);
                        runOnUiThread(() -> { if (generation == current) showTracks(tracks); });
                    } else {
                        List<Browse.Shelf> shelves = Browse.shelves(api.search(input, type.params));
                        runOnUiThread(() -> {
                            if (generation != current) return;
                            showBrowse(shelves, new ArrayList<>());
                        });
                    }
                    return;
                }
                JSONObject request = new JSONObject();
                switch (active) {
                    case "Library": request.put("continuation", Browse.SAVED_PLAYLISTS); break;
                    case "Explore": request.put("browseId", "FEmusic_explore"); break;
                    case "Collection": request.put("browseId", selectedCollection); break;
                    default:
                        request.put("browseId", "FEmusic_home");
                        if (!selectedMood.isEmpty()) request.put("params", selectedMood);
                }
                JSONObject data = api.browse(request);
                List<Browse.Shelf> shelves = Browse.shelves(data);
                List<Browse.Mood> moods = "Home".equals(active) ? Browse.moods(data) : new ArrayList<>();
                runOnUiThread(() -> { if (generation == current) showBrowse(shelves, moods); });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (generation != current) return;
                    results.removeAllViews();
                    results.addView(label("Could not load " + active.toLowerCase(java.util.Locale.ROOT) + ".", 16, MUTED));
                    results.addView(button("Retry", this::loadPage), new LinearLayout.LayoutParams(dp(96), dp(44)));
                });
            }
        });
    }

    private void showNavigation() {
        home.setSelected("Home".equals(page));
        explore.setSelected("Explore".equals(page));
        library.setSelected("Library".equals(page));
        settings.setSelected("Account".equals(page));
        String name = account.isEmpty() ? "Sign in" : account;
        sideAccount.setText(name);
        accountButton.setText(name);
    }

    private void showAccount() {
        heading("Settings");
        heading("Account");
        LinearLayout panel = column();
        panel.setPadding(dp(14), dp(8), dp(14), dp(8));
        panel.setBackground(shape(PANEL, 13));
        results.addView(panel);
        if (refreshingAccount) {
            panel.addView(label("Checking account…", 16, MUTED));
        } else if (!account.isEmpty()) {
            panel.addView(label(account, 19, INK));
            panel.addView(label("Signed in with Google", 15, MUTED));
            panel.addView(button("Sign out", this::signOut), new LinearLayout.LayoutParams(dp(135), dp(46)));
        } else {
            String text = accountError.isEmpty() ? "Sign in to see your home feed and playlists." : accountError;
            panel.addView(label(text, 15, MUTED));
            panel.addView(button("Sign in with Google", this::openAccount), new LinearLayout.LayoutParams(dp(215), dp(46)));
        }
    }

    private void showTracks(List<MusicApi.Track> tracks) {
        results.removeAllViews();
        showSearchFilters();
        if (tracks.isEmpty()) results.addView(label("No songs found.", 16, MUTED));
        for (int i = 0; i < tracks.size(); i++) {
            final int index = i;
            MusicApi.Track track = tracks.get(i);
            Button row = navigation(results, track.title + (track.artist.isEmpty() ? "" : " · " + track.artist),
                    () -> playTracks(tracks, index));
            row.setMaxLines(2);
            row.setOnLongClickListener(view -> {
                showSongMenu(view, track, -1);
                return true;
            });
        }
    }

    private void showSearchFilters() {
        LinearLayout filters = carousel();
        for (Browse.Search type : Browse.Search.values()) {
            Button chip = button(type.name(), () -> {
                searchType = type;
                loadPage();
            });
            if (type == searchType) chip.setTextColor(ACCENT);
            filters.addView(chip, spacedWidth(-2, 42));
        }
    }

    private void showBrowse(List<Browse.Shelf> shelves, List<Browse.Mood> moods) {
        results.removeAllViews();
        if ("Search".equals(page)) showSearchFilters();
        if (!moods.isEmpty()) {
            LinearLayout chips = carousel();
            for (Browse.Mood value : moods) {
                Button chip = button(value.title, () -> {
                    mood = value.params;
                    loadPage();
                });
                chips.addView(chip, spacedWidth(-2, 42));
            }
        }
        if ("Library".equals(page)) heading("Playlists");
        if (shelves.isEmpty()) results.addView(label("No music to show.", 16, MUTED));
        for (Browse.Shelf shelf : shelves) addShelf(shelf);
    }

    private void addShelf(Browse.Shelf shelf) {
        if (!shelf.title.isEmpty()) heading(shelf.title);
        List<MusicApi.Track> tracks = new ArrayList<>();
        for (Browse.Card card : shelf.cards) if (card.track != null) tracks.add(card.track);
        if (tracks.size() == shelf.cards.size() && !"Library".equals(page)) {
            addSongShelf(shelf.cards, tracks);
            return;
        }
        LinearLayout row = carousel();
        for (int index = 0; index < Math.min(shelf.cards.size(), 24); index++) {
            Browse.Card card = shelf.cards.get(index);
            LinearLayout tile = column();
            tile.setPadding(dp(4), dp(4), dp(4), dp(8));
            tile.setBackground(shape(PANEL, 8));
            tile.setContentDescription(card.title);
            tile.setFocusable(true);
            tile.setOnClickListener(view -> {
                if (card.track != null) playTracks(tracks, tracks.indexOf(card.track));
                else openCollection(card);
            });
            tile.addView(cover(card.image), new LinearLayout.LayoutParams(-1, dp(124)));
            TextView title = label(card.title, 15, INK);
            title.setMaxLines(2);
            tile.addView(title);
            if (!card.subtitle.isEmpty()) tile.addView(label(card.subtitle, 13, MUTED));
            row.addView(tile, spacedWidth(138, -2));
        }
    }

    private void addSongShelf(List<Browse.Card> cards, List<MusicApi.Track> tracks) {
        if ("Collection".equals(page)) {
            for (int i = 0; i < cards.size(); i++) results.addView(songRow(cards.get(i), tracks, i));
            return;
        }
        LinearLayout lane = carousel();
        for (int i = 0; i < Math.min(cards.size(), 24); i += 3) {
            LinearLayout group = column();
            for (int j = i; j < Math.min(cards.size(), i + 3); j++) group.addView(songRow(cards.get(j), tracks, j));
            lane.addView(group, spacedWidth(242, -2));
        }
    }

    private View songRow(Browse.Card card, List<MusicApi.Track> tracks, int index) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(3), dp(4), dp(3));
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
        text.addView(label(card.title, 15, INK));
        text.addView(label(card.subtitle, 13, MUTED));
        row.addView(text, new LinearLayout.LayoutParams(0, dp(50), 1));
        return row;
    }

    private void openCollection(Browse.Card card) {
        collectionId = card.browseId;
        navigate("Collection");
    }

    private void playTracks(List<MusicApi.Track> tracks, int index) {
        if (playback != null) playback.play(tracks, index);
    }

    private ImageView cover(String url) {
        ImageView view = new ImageView(this);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setBackground(shape(CONTROL, 6));
        view.setClipToOutline(true);
        artwork.load(view, url);
        return view;
    }

    private LinearLayout carousel() {
        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        horizontal.addView(row);
        results.addView(horizontal);
        return row;
    }

    private void openPlayer(boolean lyrics) {
        browser.setVisibility(View.GONE);
        expanded.setVisibility(View.VISIBLE);
        expanded.show(playback, lyrics);
    }

    private void closePlayer() {
        expanded.setVisibility(View.GONE);
        browser.setVisibility(View.VISIBLE);
    }

    private void showAudio() {
        AudioManager audio = getSystemService(AudioManager.class);
        LinearLayout body = column();
        body.setPadding(dp(16), dp(8), dp(16), dp(8));
        SeekBar volume = new SeekBar(this);
        volume.setContentDescription("Media volume");
        volume.setMax(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        volume.setProgress(audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                if (user && !audio.isVolumeFixed()) audio.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        volume.setEnabled(!audio.isVolumeFixed());
        body.addView(volume, new LinearLayout.LayoutParams(-1, dp(48)));
        MediaRouter router = getSystemService(MediaRouter.class);
        MediaRouter.RouteInfo route = router.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO);
        body.addView(label(route == null ? "Android default output" : route.getName().toString(), 16, MUTED));
        MediaRouteButton routes = new MediaRouteButton(this);
        routes.setRouteTypes(MediaRouter.ROUTE_TYPE_LIVE_AUDIO);
        routes.setContentDescription("Choose Android audio output");
        LinearLayout selector = new LinearLayout(this);
        selector.setGravity(Gravity.CENTER_VERTICAL);
        selector.addView(routes, new LinearLayout.LayoutParams(dp(54), dp(54)));
        selector.addView(label("Android audio routes", 16, INK));
        body.addView(selector);
        new AlertDialog.Builder(this).setTitle("Volume and audio output").setView(body)
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
                runOnUiThread(() -> { if (!isDestroyed()) toast(feedback); });
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
                runOnUiThread(() -> { if (!isDestroyed()) toast("Saved to " + playlist.title); });
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
        playerStatus.setText(playback == null ? "" : playback.status());
        previous.setEnabled(playback != null && playback.canSkip(-1));
        next.setEnabled(playback != null && playback.canSkip(1));
        play.setEnabled(playback != null && playback.canPlay());
        boolean playing = playback != null && playback.playing();
        play.setText(playing ? "Ⅱ" : "▶");
        play.setContentDescription(playing ? "Pause" : "Play");
        shuffle.setTextColor(playback != null && playback.isShuffle() ? ACCENT : INK);
        repeat.setTextColor(playback != null && playback.isRepeat() ? ACCENT : INK);
        if (playback != null && playback.requiresSignIn() && account.isEmpty()) {
            playerStatus.setOnClickListener(view -> openAccount());
        } else {
            playerStatus.setOnClickListener(null);
        }
        if (expanded.getVisibility() == View.VISIBLE) expanded.update(playback);
        renderProgress();
    }

    private void renderProgress() {
        if (seeking || seek == null) return;
        int duration = playback == null ? 0 : playback.duration();
        seek.setEnabled(duration > 0);
        seek.setMax(Math.max(1, duration));
        int position = playback == null ? 0 : playback.position();
        seek.setProgress(position);
        playbackTime.setText(duration > 0 ? time(position) + " / " + time(duration) : "");
    }

    private static String time(int millis) {
        int seconds = millis / 1000;
        return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private Button navigation(LinearLayout parent, String title, Runnable action) {
        Button button = button(title, action);
        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        android.graphics.drawable.StateListDrawable background = new android.graphics.drawable.StateListDrawable();
        background.addState(new int[]{android.R.attr.state_selected}, shape(CONTROL, 9));
        background.addState(new int[]{android.R.attr.state_pressed}, shape(CONTROL, 9));
        background.addState(new int[]{}, shape(Color.TRANSPARENT, 9));
        button.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(46));
        params.bottomMargin = dp(4);
        parent.addView(button, params);
        return button;
    }

    private Button transport(LinearLayout row, String title, String description, Runnable action) {
        Button button = button(title, action);
        button.setPadding(0, 0, 0, 0);
        button.setTextSize(22);
        button.setContentDescription(description);
        row.addView(button, spacedWidth(48, 48));
        return button;
    }

    private Button button(String title, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        button.setTextColor(INK);
        button.setTextSize(15);
        button.setSingleLine();
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x40ffffff), shape(CONTROL, 12), null));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private void heading(String title) {
        TextView text = label(title, 22, INK);
        text.setTypeface(null, Typeface.BOLD);
        text.setPadding(dp(4), dp(9), 0, dp(9));
        results.addView(text);
    }

    private TextView label(String text, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
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
