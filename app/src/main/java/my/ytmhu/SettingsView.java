// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;

final class SettingsView extends LinearLayout {
    static final String[] TABS = {"Account", "Playback", "Lyrics", "Appearance", "Connections", "Performance", "General", "Keyboard shortcuts", "About"};

    interface Host {
        String accountLabel();
        String accountDescription();
        boolean checkingAccount();
        boolean signedIn();
        boolean accountVerificationFailed();
        boolean canRetryAccount();
        String performanceStatus();
        String deviceCapabilities();
        String connectionLabel();
        void run(String action);
        void tabChanged(String selected);
        void changed(String key);
    }

    private final AppSettings prefs;
    private final Host host;
    private final int ink, muted, surface, accent;
    private final Typeface font;
    private final Appearance appearance;
    private final LinearLayout navigation, contents;
    private final Viewport viewport;
    private final EditText search;
    private Button compactTab;
    private TextView effectiveProfile, deviceDetails;
    private final List<Entry> entries = new ArrayList<>();
    private final ScrollView scroller;
    private String tab, filter = "";
    private Button equalizerPreset;
    private View themeAnchor, iconAnchor;

    private static final class Entry {
        final String tab, section, title, detail;
        final Runnable build;

        Entry(String tab, String section, String title, String detail, Runnable build) {
            this.tab = tab;
            this.section = section;
            this.title = title;
            this.detail = detail;
            this.build = build;
        }
    }

    SettingsView(Context context, AppSettings prefs, Host host, String selected,
                 int ink, int muted, int surface, int accent, Appearance appearance, Viewport viewport) {
        super(context);
        this.prefs = prefs;
        this.host = host;
        this.ink = ink;
        this.muted = muted;
        this.surface = surface;
        this.accent = accent;
        this.appearance = appearance;
        this.font = appearance.font;
        this.tab = selected;
        this.viewport = viewport;
        setOrientation(viewport.splitSettings ? HORIZONTAL : VERTICAL);
        search = new EditText(context);
        search.setSingleLine();
        search.setTextSize(viewport.songSp);
        search.setTypeface(font);
        search.setHint("Search settings");
        search.setTextColor(ink);
        search.setHintTextColor(muted);
        search.setPadding(dp(11), 0, dp(7), 0);
        search.setBackground(card(9));
        navigation = column();

        if (viewport.splitSettings) {
            LinearLayout left = column();
            left.setPadding(0, 0, dp(10), 0);
            left.addView(label("Settings", 23, ink, true));
            left.addView(search, new LayoutParams(-1, dp(Viewport.TOUCH)));
            ScrollView navScroll = new ScrollView(context);
            navScroll.addView(navigation);
            left.addView(navScroll, new LayoutParams(-1, 0, 1));
            addView(left, new LayoutParams(dp(viewport.settingsWidth), -1));
        } else {
            LinearLayout header = new LinearLayout(context);
            header.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = label("Settings", 19, ink, true);
            header.addView(title, new LayoutParams(-2, -2));
            compactTab = button(tab + "  ▾");
            compactTab.setGravity(Gravity.CENTER);
            compactTab.setMinHeight(dp(Viewport.TOUCH));
            compactTab.setContentDescription("Settings section, " + tab);
            compactTab.setOnClickListener(view -> new AlertDialog.Builder(context).setTitle("Settings section")
                    .setSingleChoiceItems(TABS, java.util.Arrays.asList(TABS).indexOf(tab), (dialog, index) -> {
                        dialog.dismiss();
                        select(TABS[index]);
                    }).setNegativeButton("Cancel", null).show());
            header.addView(compactTab, new LayoutParams(0, -2, 1));
            Button find = button(Icons.SEARCH);
            find.setTypeface(appearance.icons);
            find.setContentDescription("Search settings");
            find.setOnClickListener(view -> {
                if (search.getVisibility() == View.VISIBLE) {
                    search.setText("");
                    search.clearFocus();
                    getContext().getSystemService(android.view.inputmethod.InputMethodManager.class)
                            .hideSoftInputFromWindow(search.getWindowToken(), 0);
                    search.setVisibility(View.GONE);
                } else focusSearch();
            });
            header.addView(find, new LayoutParams(dp(Viewport.TOUCH), dp(Viewport.TOUCH)));
            addView(header, new LayoutParams(-1, -2));
            addView(search, new LayoutParams(-1, dp(Viewport.TOUCH)));
            search.setVisibility(View.GONE);
        }

        contents = column();
        contents.setPadding(0, 0, dp(3), dp(12));
        scroller = new ScrollView(context);
        scroller.addView(contents);
        if (viewport.splitSettings) addView(scroller, new LayoutParams(0, -1, 1));
        else addView(scroller, new LayoutParams(-1, 0, 1));
        defineControls();
        showNav();
        render();
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                filter = s.toString().trim().toLowerCase(Locale.ROOT);
                render();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void defineControls() {
        action("Account", "Account", host.accountLabel(), host.accountDescription(),
                host.checkingAccount() ? null : host.signedIn() ? "Sign out" : "Sign in with Google", "account");
        if (host.accountVerificationFailed()) {
            if (host.canRetryAccount()) {
                action("Account", "Account", "Check current session", "", "Retry", "account-retry");
            }
            action("Account", "Account", "Sign-in diagnostics", "", "Copy diagnostic", "account-diagnostic");
        }

        toggle("Playback", "Playback", "Autoplay", "Play similar songs when the queue ends.", "autoplay", false);
        toggle("Playback", "Playback", "Hide explicit songs", "Hide songs marked E in supported lists and future queues.", "hideExplicit", false);
        toggle("Playback", "Playback", "Use YouTube’s Restricted Mode", "Ask YouTube to filter mature content from future browse requests.", "restricted", false);
        toggle("Playback", "Sound", "Logarithmic volume", "Use a logarithmic range on the media volume slider.", "logVolume", false);
        toggle("Playback", "Sound", "Fade when pausing", "Fade out over 200 ms when you pause from the app.", "fadePause", false);
        choice("Playback", "Sound", "Volume normalization", "Attenuate loud tracks when YouTube provides loudness metadata.", "normalization", "off",
                new String[]{"Off", "Normal", "Quiet"}, new String[]{"off", "normal", "quiet"});
        range("Playback", "Sound", "Balance", "Adjust the left and right channels.", "balance", 0, -100, 100, 5,
                value -> value == 0 ? "Centre" : value < 0 ? "L " + -value : "R " + value);
        toggle("Playback", "Equalizer", "Equalizer", "Use Android’s equalizer when the device supports it.", "equalizer", false);
        choice("Playback", "Equalizer", "Preset", "Editing a band switches to Custom.", "eqPreset", "custom",
                new String[]{"Custom", "Flat", "Bass boost", "Vocal", "Treble boost"},
                new String[]{"custom", "flat", "bass", "vocal", "treble"});
        for (int hz : PlaybackTuning.BANDS) {
            String name = hz < 1000 ? hz + " Hz" : hz / 1000 + " kHz";
            range("Playback", "Equalizer", name, null, "eqBand" + hz, 0, -12, 12, 1, value -> value + " dB");
        }
        toggle("Playback", "Playback speed", "Playback speed", "Enable a different speed from MediaPlayer.", "speedEnabled", false);
        range("Playback", "Playback speed", "Speed", null, "speed", 100, 50, 200, 5,
                value -> String.format(Locale.ROOT, "%.2f×", value / 100f));
        toggle("Playback", "Playback speed", "Keep the pitch", "Keep pitch when speed changes.", "keepPitch", true);

        choice("Lyrics", "Lyrics look", "Lyrics style", "Choose how lyric lines are laid out.", "lyricsStyle", "theme",
                new String[]{"Theme default", "Classic"}, new String[]{"theme", "classic"});
        range("Lyrics", "Lyrics look", "Text size", null, "lyricsSize", 100, 75, 160, 5, value -> value + "%");
        choice("Lyrics", "Lyrics look", "Line weight", null, "lyricsWeight", "theme",
                new String[]{"Theme default", "Regular", "Bold"}, new String[]{"theme", "regular", "bold"});
        toggle("Lyrics", "Lyrics look", "Quick line changes", "Jump to the current line instead of scrolling smoothly.", "quickLyrics", false);
        range("Lyrics", "Lyrics timing", "Timing offset", "Positive values show lyrics later.", "lyricsOffset", 0, -5000, 5000, 100, SettingsView::milliseconds);
        range("Lyrics", "Lyrics timing", "Line-synced trim", "Extra offset for line-synced sources.", "lineTrim", 0, -2000, 2000, 100, SettingsView::milliseconds);
        toggle("Lyrics", "Lyrics sources", "LRCLIB", "Line-synced and plain text.", "lrclib", true);
        toggle("Lyrics", "Lyrics sources", "YouTube Music", "Plain text.", "ytLyrics", true);
        choice("Lyrics", "Lyrics sources", "Source priority", null, "lyricsFirst", "lrclib",
                new String[]{"LRCLIB first", "YouTube Music first"}, new String[]{"lrclib", "youtube"});

        choice("Appearance", "Background", "Frosted glass", "Use blurred album artwork behind translucent panels.", "glass",
                "theme", new String[]{"Theme default", "On", "Off"}, new String[]{"theme", "on", "off"});
        choice("Appearance", "Colours", "Accent colour", null, "accent", "theme",
                new String[]{"Theme default", "Red", "Amber", "Blue", "Green"}, new String[]{"theme", "red", "amber", "blue", "green"});
        toggle("Appearance", "Colours", "Accent coloured controls", "Colour active player and filter controls with the accent.", "accentControls", true);
        toggle("Appearance", "Background", "Flat background", "Use a solid background in the browser and Now Playing.", "flat", false);
        toggle("Appearance", "Background", "Cover colours", "Tint Now Playing using the current album art.", "coverColors", true);
        range("Appearance", "Background", "Background brightness", null, "brightness", 100, 35, 130, 5, value -> value + "%");
        choice("Appearance", "Appearance", "Font", null, "font", "system",
                new String[]{"System", "Inter", "DM Sans", "Figtree"}, new String[]{"system", "Inter", "DMSans", "Figtree"});
        choice("Appearance", "Display & Layout", "Layout density", "Changes card sizes and spacing.", "layoutDensity", "auto",
                new String[]{"Auto", "Ultra Compact (70%)", "Compact (85%)", "Standard (100%)", "Large (115%)"},
                new String[]{"auto", "ultra", "compact", "standard", "large"});
        range("Appearance", "Display & Layout", "Text size", "Adjusts app text alongside Android font size.", "zoom",
                100, 85, 150, 5, value -> value + "%");
        choice("Appearance", "Display & Layout", "Sidebar", null, "sidebar", prefs.sidebar(),
                new String[]{"Auto", "Collapsed", "Expanded"}, new String[]{"auto", "rail", "expanded"});
        action("Appearance", "Display & Layout", "Reset display settings", null, "Reset", "reset-layout");
        toggle("Appearance", "Appearance", "Always show seek knob", "Keep the position marker visible even when the player is idle.", "seekKnob", true);

        if (!appearance.themes.isEmpty()) {
            add("Appearance", "Themes", "Theme", "Browse the upstream themes and previews.", this::themeGallery);
        }
        add("Appearance", "App icon", "App icon", "Choose the Android launcher icon.", this::iconGallery);

        action("Connections", "ListenBrainz", "ListenBrainz", host.connectionLabel(), "Manage", "listenbrainz");
        action("Connections", "Android", "Media controls", "Manage Android notifications for playback controls.", "Open", "notifications");

        choice("Performance", "Performance", "Performance mode", "Auto adapts artwork and effects to your device.", "performance",
                "AUTO", new String[]{"Auto", "Ultra Lite", "Lite", "Balanced", "High Quality"},
                new String[]{"AUTO", "ULTRA_LITE", "LITE", "BALANCED", "HIGH_QUALITY"});
        add("Performance", "Performance", "Effective profile", "Current profile and fallback reason", () -> {
            effectiveProfile = label(host.performanceStatus(), viewport.songSp, muted, false);
            row("Effective profile", null, effectiveProfile);
        });
        add("Performance", "Device", "Runtime capabilities", "Android version, artwork budgets and available window", () -> {
            deviceDetails = label(host.deviceCapabilities(), viewport.metadataSp, muted, false);
            contents.addView(deviceDetails);
        });

        choice("General", "Song info", "Song info language", "Requested language for YouTube Music data.", "songLanguage", "en",
                new String[]{"English", "Bahasa Melayu", "Indonesia", "日本語", "한국어", "Français", "Español", "Deutsch"},
                new String[]{"en", "ms", "id", "ja", "ko", "fr", "es", "de"});
        choice("General", "Song info", "Song info country", "Requested country for charts and recommendations.", "songCountry", "MY",
                new String[]{"Malaysia", "Singapore", "Indonesia", "United States", "United Kingdom", "Japan", "South Korea", "India"},
                new String[]{"MY", "SG", "ID", "US", "GB", "JP", "KR", "IN"});
        choice("General", "Window", "Open on", "Page to open when the app starts.", "openOn", "Home",
                new String[]{"Home", "Explore", "Library"}, new String[]{"Home", "Explore", "Library"});
        action("General", "Network", "Test connection", "Check the connection to YouTube Music from this device.", "Test", "network");
        action("General", "Storage", "Cache", "Clear cached artwork from memory and disk.", "Clear", "cache");

        action("Keyboard shortcuts", "Playback", "Space", "Play or pause outside a text field.", null, "");
        action("Keyboard shortcuts", "Playback", "Ctrl + Left / Right", "Previous or next track.", null, "");
        action("Keyboard shortcuts", "Navigation", "Ctrl + F", "Focus music search.", null, "");
        action("Keyboard shortcuts", "Navigation", "Esc", "Close Now Playing or return to Home.", null, "");

        action("About", "Updates", "Check now", "Check GitHub for a newer Android APK. Updates are installed through Android.", "Check", "updates");
        action("About", "Updates", "What’s new in " + BuildVersion.VERSION,
                "Responsive head-unit layout, independent text sizing and adaptive artwork performance.", null, "");
        action("About", "About", "YTM Head Unit " + BuildVersion.VERSION, "Android port of parts of ytm-rs. Android API 27 and newer.", null, "");
        action("About", "About", "Lyrics", "LRCLIB and YouTube Music.", null, "");
        action("About", "About", "Fonts and icons", "Inter, DM Sans and Figtree (SIL OFL). App icon from ytm-rs.", null, "");
        action("About", "About", "Source code", "GPL-3.0-only. Includes upstream attributions.", "Open GitHub", "source");
        action("About", "About", "Upstream ytm-rs", "Desktop project, releases and credits.", "Open GitHub", "upstream");
    }

    void updatePerformance() {
        if (effectiveProfile != null) effectiveProfile.setText(host.performanceStatus());
        if (deviceDetails != null) deviceDetails.setText(host.deviceCapabilities());
    }

    int scrollPosition() { return scroller.getScrollY(); }
    void restoreScroll(int position) { scroller.post(() -> scroller.scrollTo(0, position)); }

    void focusSearch() {
        search.setVisibility(View.VISIBLE);
        search.requestFocus();
        getContext().getSystemService(android.view.inputmethod.InputMethodManager.class)
                .showSoftInput(search, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
    }

    private void select(String name) {
        tab = name;
        host.tabChanged(tab);
        if (!filter.isEmpty()) search.setText("");
        showNav();
        render();
    }

    private void showNav() {
        if (!viewport.splitSettings) {
            compactTab.setText(tab + "  ▾");
            compactTab.setContentDescription("Settings section, " + tab);
            return;
        }
        if (navigation.getChildCount() == 0) {
            for (String name : TABS) {
                Button button = button(name);
                button.setMinHeight(dp(Viewport.TOUCH));
                button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                button.setOnClickListener(view -> select(name));
                navigation.addView(button, new LayoutParams(-1, -2));
            }
        }
        for (int i = 0; i < TABS.length; i++) {
            View item = navigation.getChildAt(i);
            item.setBackground(TABS[i].equals(tab) ? card(7) : null);
        }
    }

    private void render() {
        contents.removeAllViews();
        effectiveProfile = null;
        deviceDetails = null;
        scroller.scrollTo(0, 0);
        themeAnchor = null;
        iconAnchor = null;
        if (filter.isEmpty() && "Appearance".equals(tab)) {
            LinearLayout shortcuts = new LinearLayout(getContext());
            Button themes = button("Themes");
            themes.setOnClickListener(view -> jumpTo(themeAnchor));
            Button icons = button("App icon");
            icons.setOnClickListener(view -> jumpTo(iconAnchor));
            addGalleryTile(shortcuts, themes);
            addGalleryTile(shortcuts, icons);
            contents.addView(shortcuts, new LayoutParams(-1, -2));
        }
        String last = "";
        for (Entry entry : entries) {
            String fullText = (entry.tab + " " + entry.section + " " + entry.title + " " + entry.detail).toLowerCase(Locale.ROOT);
            if (filter.isEmpty() ? !tab.equals(entry.tab) : !fullText.contains(filter)) continue;
            String group = filter.isEmpty() || entry.tab.equals(entry.section)
                    ? entry.section : entry.tab + " · " + entry.section;
            if (!group.equals(last)) {
                View heading = label(group, viewport.sectionSp, ink, true);
                contents.addView(heading);
                if ("Appearance".equals(entry.tab) && "Themes".equals(entry.section)) themeAnchor = heading;
                if ("Appearance".equals(entry.tab) && "App icon".equals(entry.section)) iconAnchor = heading;
            }
            entry.build.run();
            last = group;
        }
        if (last.isEmpty()) contents.addView(label("No settings match.", 16, muted, false));
    }

    private void jumpTo(View section) {
        if (section != null) scroller.smoothScrollTo(0, section.getTop());
    }

    private void toggle(String tab, String group, String title, String detail, String key, boolean fallback) {
        add(tab, group, title, detail, () -> {
            Switch control = new Switch(getContext());
            control.setContentDescription(title);
            control.setChecked(prefs.flag(key, fallback));
            control.setOnCheckedChangeListener((button, checked) -> {
                prefs.set(key, checked);
                host.changed(key);
            });
            row(title, detail, control);
        });
    }

    private void range(String tab, String group, String title, String detail, String key,
                       int fallback, int min, int max, int step, IntFunction<String> display) {
        add(tab, group, title, detail, () -> {
            LinearLayout control = column();
            TextView value = label(display.apply(prefs.number(key, fallback)), 13, ink, false);
            value.setGravity(Gravity.CENTER);
            control.addView(value);
            SeekBar slider = new SeekBar(getContext());
            slider.setContentDescription(title);
            slider.setMax((max - min) / step);
            slider.setProgress((prefs.number(key, fallback) - min) / step);
            slider.setProgressTintList(ColorStateList.valueOf(accent));
            slider.setThumbTintList(ColorStateList.valueOf(accent));
            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar, int position, boolean byUser) {
                    int selected = min + position * step;
                    value.setText(display.apply(selected));
                    if (byUser) {
                        if (key.startsWith("eqBand")) {
                            prefs.set("eqPreset", "custom");
                            if (equalizerPreset != null) equalizerPreset.setText("Custom");
                        }
                        prefs.set(key, selected);
                    }
                    if (byUser && !slider.isPressed()) host.changed(key);
                }
                @Override public void onStartTrackingTouch(SeekBar bar) {}
                @Override public void onStopTrackingTouch(SeekBar bar) { host.changed(key); }
            });
            control.addView(slider, new LayoutParams(-1, dp(36)));
            row(title, detail, control);
        });
    }

    private void choice(String tab, String group, String title, String detail, String key, String fallback,
                        String[] labels, String[] values) {
        add(tab, group, title, detail, () -> {
            int selected = java.util.Arrays.asList(values).indexOf(prefs.text(key, fallback));
            Button button = button(labels[Math.max(0, selected)]);
            if ("eqPreset".equals(key)) equalizerPreset = button;
            button.setOnClickListener(view -> {
                int current = java.util.Arrays.asList(values).indexOf(prefs.text(key, fallback));
                new AlertDialog.Builder(getContext()).setTitle(title).setSingleChoiceItems(labels, current, (dialog, i) -> {
                    dialog.dismiss();
                    prefs.set(key, values[i]);
                    button.setText(labels[i]);
                    host.changed(key);
                }).setNegativeButton("Cancel", null).show();
            });
            row(title, detail, button);
        });
    }

    private void themeGallery() {
        List<org.json.JSONObject> themes = new ArrayList<>(appearance.themes);
        themes.sort(java.util.Comparator.comparingInt(theme -> themeRank(theme.optString("id"))));
        int pane = viewport.width - viewport.railWidth - 2 * viewport.gutter
                - (viewport.splitSettings ? viewport.settingsWidth : 0);
        int columns = pane >= 680 ? 3 : pane >= 350 ? 2 : 1;
        LinearLayout panel = column();
        contents.addView(panel, new LayoutParams(-1, -2));
        int selected = 0;
        for (int i = 0; i < themes.size(); i++) {
            if (appearance.themeId.equals(themes.get(i).optString("id"))) selected = i;
        }
        themePage(panel, themes, pane, columns, selected / (2 * columns));
    }

    private void themePage(LinearLayout panel, List<org.json.JSONObject> themes, int pane, int columns, int page) {
        panel.removeAllViews();
        int pageSize = 2 * columns;
        int start = page * pageSize;
        int end = Math.min(themes.size(), start + pageSize);
        LinearLayout header = new LinearLayout(getContext());
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(label(appearance.themeName, viewport.songSp, ink, true), new LayoutParams(0, -2, 1));
        Button prev = button("‹");
        prev.setContentDescription("Previous themes");
        prev.setEnabled(start > 0);
        prev.setOnClickListener(view -> themePage(panel, themes, pane, columns, page - 1));
        header.addView(prev, new LayoutParams(dp(Viewport.TOUCH), dp(Viewport.TOUCH)));
        Button next = button("›");
        next.setContentDescription("More themes");
        next.setEnabled(end < themes.size());
        next.setOnClickListener(view -> themePage(panel, themes, pane, columns, page + 1));
        header.addView(next, new LayoutParams(dp(Viewport.TOUCH), dp(Viewport.TOUCH)));
        panel.addView(header);
        for (int index = start; index < end; index += columns) {
            LinearLayout row = new LinearLayout(getContext());
            for (int i = index; i < index + columns; i++) {
                View tile = i < end ? themeTile(themes.get(i), pane / columns) : new View(getContext());
                addGalleryTile(row, tile);
            }
            panel.addView(row, new LayoutParams(-1, -2));
        }
    }

    private static int themeRank(String id) {
        if ("ytm-rs".equals(id)) return 0;
        if ("classic".equals(id)) return 1;
        return 2;
    }

    private View themeTile(org.json.JSONObject theme, int widthDp) {
        String id = theme.optString("id");
        String name = theme.optString("name");
        boolean selected = id.equals(appearance.themeId);
        LinearLayout tile = column();
        tile.setPadding(dp(4), dp(4), dp(4), dp(4));
        tile.setBackground(galleryCard(selected));
        android.widget.ImageView preview = new android.widget.ImageView(getContext());
        preview.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        try (java.io.InputStream file = getContext().getAssets().open("theme-covers/" + id + ".jpg")) {
            android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
            options.inSampleSize = 2;
            options.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;
            preview.setImageBitmap(android.graphics.BitmapFactory.decodeStream(file, null, options));
        } catch (java.io.IOException missing) {
            preview.setImageResource(AppIcons.drawable(getContext(), "classic".equals(id) ? "classic" : "official"));
            preview.setPadding(dp(16), dp(16), dp(16), dp(16));
            preview.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{appearance.start, appearance.end}));
        }
        tile.addView(preview, new LayoutParams(-1, dp(Math.max(70, Math.min(172, widthDp * 5 / 9)))));
        tile.addView(label((selected ? "✓  " : "") + name, 14, ink, true));
        org.json.JSONArray authors = theme.optJSONArray("creators");
        if (authors != null && authors.length() > 0) {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < authors.length(); i++) names.add(authors.optString(i));
            tile.addView(label(String.join(", ", names), 11, muted, false));
        }
        tile.setContentDescription(name + (selected ? ", selected" : ""));
        tile.setFocusable(true);
        tile.setOnClickListener(view -> {
            if (selected) return;
            prefs.set("theme", id);
            host.changed("theme");
        });
        return tile;
    }

    private void iconGallery() {
        String current = prefs.text("appIcon", "classic");
        contents.addView(label("Android launchers may take a moment to refresh the icon.", 12, muted, false));
        int pane = viewport.width - viewport.railWidth - 2 * viewport.gutter - (viewport.splitSettings ? viewport.settingsWidth : 0);
        int columns = Math.max(2, Math.min(5, pane / 108));
        for (int index = 0; index < AppIcons.IDS.length; index += columns) {
            LinearLayout row = new LinearLayout(getContext());
            for (int i = index; i < index + columns; i++) {
                if (i >= AppIcons.IDS.length) { addGalleryTile(row, new View(getContext())); continue; }
                String id = AppIcons.IDS[i];
                String name = AppIcons.LABELS[i];
                boolean selected = id.equals(current);
                LinearLayout tile = column();
                tile.setGravity(Gravity.CENTER);
                tile.setPadding(dp(3), dp(8), dp(3), dp(3));
                tile.setBackground(galleryCard(selected));
                android.widget.ImageView icon = new android.widget.ImageView(getContext());
                icon.setImageResource(AppIcons.drawable(getContext(), id));
                tile.addView(icon, new LayoutParams(dp(46), dp(46)));
                tile.addView(label((selected ? "✓ " : "") + name, 12, ink, true));
                tile.setContentDescription(name + (selected ? ", selected" : ""));
                tile.setFocusable(true);
                tile.setOnClickListener(view -> {
                    if (!selected) host.run("icon:" + id);
                });
                addGalleryTile(row, tile);
            }
            contents.addView(row, new LayoutParams(-1, -2));
        }
    }

    private void addGalleryTile(LinearLayout row, View tile) {
        LayoutParams params = new LayoutParams(0, -2, 1);
        params.setMargins(dp(3), dp(3), dp(3), dp(5));
        row.addView(tile, params);
    }

    private GradientDrawable galleryCard(boolean selected) {
        GradientDrawable background = card(10);
        if (selected) background.setStroke(dp(2), accent);
        return background;
    }

    private void action(String tab, String group, String title, String detail, String caption, String id) {
        add(tab, group, title, detail, () -> {
            Button control = caption == null ? null : button(caption);
            if (control != null) control.setOnClickListener(view -> host.run(id));
            row(title, detail, control);
        });
    }

    private void add(String tab, String group, String title, String detail, Runnable build) {
        entries.add(new Entry(tab, group, title, detail, build));
    }

    private void row(String title, String detail, View control) {
        LinearLayout row = new LinearLayout(getContext());
        boolean stacked = viewport.stackedSettingsRows && control != null && !(control instanceof Switch);
        row.setOrientation(stacked ? VERTICAL : HORIZONTAL);
        row.setGravity(stacked ? Gravity.START : Gravity.CENTER_VERTICAL);
        row.setPadding(dp(viewport.scale(11)), dp(viewport.scale(4)), dp(viewport.scale(9)), dp(viewport.scale(4)));
        row.setBackground(card(8));
        LinearLayout text = column();
        text.addView(label(title, viewport.songSp, ink, true));
        if (detail != null && !detail.isEmpty()) text.addView(label(detail, viewport.metadataSp, muted, false));
        if (stacked) row.addView(text, new LayoutParams(-1, -2));
        else row.addView(text, new LayoutParams(0, -2, 1));
        if (control != null) {
            int width = control instanceof Switch ? -2 : stacked ? -1 : dp(156);
            control.setMinimumHeight(dp(Viewport.TOUCH));
            control.setMinimumWidth(dp(Viewport.TOUCH));
            row.addView(control, new LayoutParams(width, -2));
        }
        LayoutParams params = new LayoutParams(-1, -2);
        params.bottomMargin = dp(3);
        contents.addView(row, params);
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(getContext());
        view.setOrientation(VERTICAL);
        return view;
    }

    private TextView label(String text, int size, int colour, boolean bold) {
        TextView label = new TextView(getContext());
        label.setText(text);
        label.setTextColor(colour);
        label.setTextSize(size);
        label.setPadding(dp(4), dp(viewport.scale(4)), dp(4), dp(viewport.scale(4)));
        label.setTypeface(font, bold ? Typeface.BOLD : Typeface.NORMAL);
        return label;
    }

    private Button button(String title) {
        Button button = new Button(getContext());
        button.setAllCaps(false);
        button.setText(title);
        button.setTextColor(ink);
        button.setTextSize(14);
        button.setTypeface(font);
        button.setPadding(dp(8), dp(3), dp(8), dp(3));
        button.setMinHeight(dp(Viewport.TOUCH));
        button.setSingleLine(false);
        button.setMaxLines(3);
        button.setBackground(card(8));
        return button;
    }

    private GradientDrawable card(int radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(surface);
        bg.setCornerRadius(dp(radius));
        return bg;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String milliseconds(int millis) {
        return String.format(Locale.ROOT, "%+.1f s", millis / 1000f);
    }
}
