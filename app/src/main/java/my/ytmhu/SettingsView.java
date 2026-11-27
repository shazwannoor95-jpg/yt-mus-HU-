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
    static final String[] TABS = {"Account", "Playback", "Lyrics", "Appearance", "Connections", "General", "Keyboard shortcuts", "About"};

    interface Host {
        String accountLabel();
        String accountDescription();
        boolean checkingAccount();
        boolean signedIn();
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
    private final List<Entry> entries = new ArrayList<>();
    private final ScrollView scroller;
    private String tab, filter = "";
    private Button equalizerPreset;

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
                 int ink, int muted, int surface, int accent, Appearance appearance) {
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
        setOrientation(HORIZONTAL);
        LinearLayout left = column();
        left.setPadding(0, 0, dp(12), 0);
        left.addView(label("Settings", 25, ink, true));
        EditText search = new EditText(context);
        search.setSingleLine();
        search.setTextSize(15);
        search.setTypeface(font);
        search.setHint("Search settings");
        search.setTextColor(ink);
        search.setHintTextColor(muted);
        search.setPadding(dp(11), 0, dp(7), 0);
        search.setBackground(card(9));
        left.addView(search, new LayoutParams(-1, dp(43)));
        navigation = column();
        left.addView(navigation);
        ScrollView navScroll = new ScrollView(context);
        navScroll.addView(left);
        addView(navScroll, new LayoutParams(dp(158), -1));

        contents = column();
        contents.setPadding(0, 0, dp(4), dp(8));
        scroller = new ScrollView(context);
        scroller.addView(contents);
        addView(scroller, new LayoutParams(0, -1, 1));
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

        toggle("Playback", "Playback", "Autoplay", "Play similar songs when the queue ends.", "autoplay", false);
        toggle("Playback", "Playback", "Hide explicit songs", "Hide songs marked E in supported lists and future queues.", "hideExplicit", false);
        toggle("Playback", "Playback", "Use YouTube’s Restricted Mode", "Ask YouTube to filter mature content from future browse requests.", "restricted", false);
        toggle("Playback", "Sound", "Logarithmic volume", "Use a logarithmic range on the media volume slider.", "logVolume", false);
        toggle("Playback", "Sound", "Fade when pausing", "Fade out over 200 ms when you pause from the app.", "fadePause", false);
        choice("Playback", "Sound", "Volume normalization", "Attenuate loud tracks when YouTube provides loudness metadata.", "normalization", "off",
                new String[]{"Off", "Normal", "Quiet"}, new String[]{"off", "normal", "quiet"});
        range("Playback", "Sound", "Balance", "Adjust the left and right channels.", "balance", 0, -100, 100, 5,
                value -> value == 0 ? "Centre" : value < 0 ? "L " + -value : "R " + value);
        action("Playback", "Sound", "Output device", "Android routes exposed by this head unit.", "Choose", "audio");
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

        if (!appearance.themes.isEmpty()) {
            int count = appearance.themes.size();
            String[] names = new String[count];
            String[] ids = new String[count];
            for (int i = 0; i < count; i++) {
                names[i] = appearance.themes.get(i).optString("name");
                ids[i] = appearance.themes.get(i).optString("id");
            }
            choice("Appearance", "Colours", "Theme", "Upstream colour palettes adapted for the Android player.",
                    "theme", "ytm-rs", names, ids);
        }
        choice("Appearance", "Colours", "Accent colour", null, "accent", "theme",
                new String[]{"Theme default", "Red", "Amber", "Blue", "Green"}, new String[]{"theme", "red", "amber", "blue", "green"});
        toggle("Appearance", "Colours", "Accent coloured controls", "Colour active player and filter controls with the accent.", "accentControls", true);
        toggle("Appearance", "Background", "Flat background", "Use a solid background in the browser and Now Playing.", "flat", false);
        toggle("Appearance", "Background", "Cover colours", "Tint Now Playing using the current album art.", "coverColors", true);
        range("Appearance", "Background", "Background brightness", null, "brightness", 100, 35, 130, 5, value -> value + "%");
        choice("Appearance", "Appearance", "Font", null, "font", "system",
                new String[]{"System", "Inter", "DM Sans", "Figtree"}, new String[]{"system", "Inter", "DMSans", "Figtree"});
        range("Appearance", "Appearance", "Zoom", "Scale labels and other app text.", "zoom", 100, 85, 125, 5, value -> value + "%");
        toggle("Appearance", "Appearance", "Always show seek knob", "Keep the position marker visible even when the player is idle.", "seekKnob", true);

        action("Connections", "ListenBrainz", "ListenBrainz", host.connectionLabel(), "Manage", "listenbrainz");
        action("Connections", "Android", "Media controls", "Manage Android notifications for playback controls.", "Open", "notifications");
        action("Connections", "Android", "Audio output", "View audio routes reported by Android.", "Choose", "audio");

        choice("General", "Song info", "Song info language", "Requested language for YouTube Music data.", "songLanguage", "en",
                new String[]{"English", "Bahasa Melayu", "Indonesia", "日本語", "한국어", "Français", "Español", "Deutsch"},
                new String[]{"en", "ms", "id", "ja", "ko", "fr", "es", "de"});
        choice("General", "Song info", "Song info country", "Requested country for charts and recommendations.", "songCountry", "MY",
                new String[]{"Malaysia", "Singapore", "Indonesia", "United States", "United Kingdom", "Japan", "South Korea", "India"},
                new String[]{"MY", "SG", "ID", "US", "GB", "JP", "KR", "IN"});
        choice("General", "Window", "Open on", "Page to open when the app starts.", "openOn", "Home",
                new String[]{"Home", "Explore", "Library"}, new String[]{"Home", "Explore", "Library"});
        toggle("General", "Window", "Collapse sidebar", "Use a narrow icon-only sidebar.", "collapseSidebar", false);
        action("General", "Network", "Test connection", "Check the connection to YouTube Music from this device.", "Test", "network");
        action("General", "Storage", "Cache", "Clear decoded album artwork from the app’s memory.", "Clear", "cache");

        action("Keyboard shortcuts", "Playback", "Space", "Play or pause outside a text field.", null, "");
        action("Keyboard shortcuts", "Playback", "Ctrl + Left / Right", "Previous or next track.", null, "");
        action("Keyboard shortcuts", "Navigation", "Ctrl + F", "Focus music search.", null, "");
        action("Keyboard shortcuts", "Navigation", "Esc", "Close Now Playing or return to Home.", null, "");

        action("About", "Updates", "Check now", "Check GitHub for a newer Android APK. Updates are installed through Android.", "Check", "updates");
        action("About", "Updates", "What’s new in " + BuildVersion.VERSION,
                "Searchable settings, Android sound controls, lyric timing and source preferences, colour palettes, ListenBrainz and keyboard shortcuts.", null, "");
        action("About", "About", "YTM Head Unit " + BuildVersion.VERSION, "Android port of parts of ytm-rs. Android API 27 and newer.", null, "");
        action("About", "About", "Lyrics", "LRCLIB and YouTube Music.", null, "");
        action("About", "About", "Fonts and icons", "Inter, DM Sans and Figtree (SIL OFL). App icon from ytm-rs.", null, "");
        action("About", "About", "Source code", "GPL-3.0-only. Includes upstream attributions.", "Open GitHub", "source");
        action("About", "About", "Upstream ytm-rs", "Desktop project, releases and credits.", "Open GitHub", "upstream");
    }

    private void showNav() {
        navigation.removeAllViews();
        for (String name : TABS) {
            Button button = button(name);
            button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            button.setBackground(name.equals(tab) ? card(7) : null);
            button.setOnClickListener(view -> {
                tab = name;
                host.tabChanged(tab);
                showNav();
                render();
            });
            navigation.addView(button, new LayoutParams(-1, dp(42)));
        }
    }

    private void render() {
        contents.removeAllViews();
        scroller.scrollTo(0, 0);
        String last = "";
        for (Entry entry : entries) {
            String fullText = (entry.tab + " " + entry.section + " " + entry.title + " " + entry.detail).toLowerCase(Locale.ROOT);
            if (filter.isEmpty() ? !tab.equals(entry.tab) : !fullText.contains(filter)) continue;
            String group = filter.isEmpty() || entry.tab.equals(entry.section)
                    ? entry.section : entry.tab + " · " + entry.section;
            if (!group.equals(last)) contents.addView(label(group, 18, ink, true));
            entry.build.run();
            last = group;
        }
        if (last.isEmpty()) contents.addView(label("No settings match.", 16, muted, false));
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
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(9), dp(4), dp(8), dp(4));
        row.setBackground(card(8));
        LinearLayout text = column();
        text.addView(label(title, 15, ink, true));
        if (detail != null) text.addView(label(detail, 13, muted, false));
        row.addView(text, new LayoutParams(0, -2, 1));
        if (control != null) {
            int width = control instanceof Switch ? -2 : dp(132);
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
        label.setPadding(dp(4), dp(6), dp(4), dp(5));
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
        button.setPadding(dp(8), 0, dp(8), 0);
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
