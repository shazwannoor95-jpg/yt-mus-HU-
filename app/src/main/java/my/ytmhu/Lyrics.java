// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import org.json.JSONArray;
import org.json.JSONObject;

import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Lyrics {
    private static final Pattern TIMESTAMP = Pattern.compile("\\[(\\d{1,3}):([0-5]\\d)(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern OFFSET = Pattern.compile("(?im)^\\[offset:([+-]?\\d{1,7})\\]");

    static final class Line {
        final int millis;
        final String text, roman;
        final java.util.Map<String, String> translations;

        Line(int millis, String text) { this(millis, text, null, null); }

        Line(int millis, String text, String roman, java.util.Map<String, String> translations) {
            this.millis = millis;
            this.text = text;
            this.roman = roman == null ? "" : roman;
            this.translations = translations == null ? Collections.emptyMap() : translations;
        }

        String translation(String target) {
            for (java.util.Map.Entry<String, String> entry : translations.entrySet()) {
                if (target.equalsIgnoreCase(entry.getKey())) return entry.getValue();
            }
            for (java.util.Map.Entry<String, String> entry : translations.entrySet()) {
                if (target.equalsIgnoreCase(entry.getKey().split("-", 2)[0])) return entry.getValue();
            }
            return "";
        }
    }

    final List<Line> lines;
    final String plain;
    final String source, language;

    private Lyrics(List<Line> lines, String plain, String source) { this(lines, plain, source, ""); }

    Lyrics(List<Line> lines, String plain, String source, String language) {
        this.language = language;
        this.lines = lines;
        this.plain = plain;
        this.source = source;
    }

    static Lyrics load(MusicApi.Track track, int duration, AppSettings prefs) throws Exception {
        boolean lrclib = prefs.flag("lrclib", true);
        boolean youtube = prefs.flag("ytLyrics", true);
        boolean youtubeFirst = "youtube".equals(prefs.text("lyricsFirst", "lrclib"));
        boolean unison = prefs.flag("unisonLyrics", false);
        if (!lrclib && !youtube && !unison) return new Lyrics(Collections.emptyList(), "", "");
        boolean failed = false;
        String first = prefs.text("lyricsFirst", "lrclib");
        String[] order = "unison".equals(first) ? new String[]{"unison", "lrclib", "youtube"}
                : youtubeFirst ? new String[]{"youtube", "lrclib", "unison"} : new String[]{"lrclib", "youtube", "unison"};
        for (String provider : order) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if ("youtube".equals(provider) ? !youtube : "unison".equals(provider) ? !unison : !lrclib) continue;
            try {
                Lyrics result;
                switch (provider) {
                    case "youtube": result = new Lyrics(Collections.emptyList(), new MusicApi(prefs).plainLyrics(track.id), "YouTube Music"); break;
                    case "unison": result = unison(track, duration); break;
                    default: result = lrclib(track, duration);
                }
                if (result != null && (!result.lines.isEmpty() || !result.plain.isEmpty())) return result;
            } catch (Exception unavailable) {
                failed = true;
            }
        }
        if (failed) throw new IOException("A lyrics source could not be reached.");
        return new Lyrics(Collections.emptyList(), "", "");
    }

    private static Lyrics unison(MusicApi.Track track, int duration) throws Exception {
        if (!track.id.matches("[A-Za-z0-9_-]{11}")) return null;
        String url = "https://unison.betterlyrics.org/lyrics?v=" + track.id
                + "&song=" + URLEncoder.encode(track.title, "UTF-8") + "&artist=" + URLEncoder.encode(track.artist, "UTF-8")
                + "&duration=" + Math.max(0, duration / 1000);
        HttpsURLConnection connection = (HttpsURLConnection) URI.create(url).toURL().openConnection();
        try {
            connection.setConnectTimeout(4500);
            connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(false);
            if (connection.getResponseCode() == 404) return null;
            JSONObject data = new JSONObject(MusicApi.read(connection)).optJSONObject("data");
            if (data == null) return null;
            String text = data.optString("lyrics");
            if (text.length() > 512_000) return null;
            switch (data.optString("format")) {
                case "ttml": return TtmlLyrics.parse(text);
                case "lrc": return new Lyrics(parse(text), "", "Unison", data.optString("language"));
                case "plain": return new Lyrics(Collections.emptyList(), text, "Unison", data.optString("language"));
                default: return null;
            }
        } finally {
            connection.disconnect();
        }
    }

    List<Line> displayLines() {
        if (!lines.isEmpty()) return lines;
        List<Line> display = new ArrayList<>();
        for (String line : plain.split("\\r?\\n")) display.add(new Line(-1, line));
        return display;
    }

    private static Lyrics lrclib(MusicApi.Track track, int duration) throws Exception {
        String query = "track_name=" + URLEncoder.encode(track.title, "UTF-8")
                + "&artist_name=" + URLEncoder.encode(track.artist, "UTF-8");
        HttpsURLConnection connection = (HttpsURLConnection) URI.create("https://lrclib.net/api/search?" + query)
                .toURL().openConnection();
        try {
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(6500);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "YTM Head Unit/" + BuildVersion.VERSION);
            JSONArray results = new JSONArray(MusicApi.read(connection));
            JSONObject match = bestMatch(results, track.title, track.artist, duration);
            if (match != null) {
                List<Line> lines = parse(match.optString("syncedLyrics", ""));
                String plain = match.isNull("plainLyrics") ? "" : match.optString("plainLyrics");
                if (!lines.isEmpty()) return new Lyrics(lines, plain, "LRCLIB");
                if (!plain.isEmpty()) return new Lyrics(lines, plain, "LRCLIB");
            }
        } finally {
            connection.disconnect();
        }
        return null;
    }

    static JSONObject bestMatch(JSONArray options, String title, String artist, int duration) {
        JSONObject best = null;
        long bestScore = Long.MIN_VALUE;
        for (int i = 0; i < options.length(); i++) {
            JSONObject entry = options.optJSONObject(i);
            if (entry == null || entry.optBoolean("instrumental")) continue;
            if (!same(entry.optString("trackName"), title) || !same(entry.optString("artistName"), artist)) continue;
            int ms = (int) (entry.optDouble("duration", 0) * 1000);
            int difference = duration > 0 && ms > 0 ? Math.abs(duration - ms) : 0;
            if (difference > 15000) continue;
            String synced = entry.optString("syncedLyrics", "");
            long score = (synced.isEmpty() || "null".equals(synced) ? 0 : 20000) - difference;
            if (score > bestScore) {
                best = entry;
                bestScore = score;
            }
        }
        return best;
    }

    private static boolean same(String a, String b) {
        return a.trim().equalsIgnoreCase(b.trim());
    }

    static List<Line> parse(String lrc) {
        List<Line> lines = new ArrayList<>();
        Matcher offset = OFFSET.matcher(lrc);
        int shift = offset.find() ? Integer.parseInt(offset.group(1)) : 0;
        for (String raw : lrc.split("\\r?\\n")) {
            Matcher marker = TIMESTAMP.matcher(raw);
            int until = 0;
            List<Integer> times = new ArrayList<>();
            while (marker.find() && raw.substring(until, marker.start()).trim().isEmpty()) {
                String fraction = marker.group(3);
                int ms = fraction == null ? 0 : Integer.parseInt((fraction + "00").substring(0, 3));
                times.add(Math.max(0, Integer.parseInt(marker.group(1)) * 60000
                        + Integer.parseInt(marker.group(2)) * 1000 + ms - shift));
                until = marker.end();
            }
            String text = raw.substring(until).trim();
            for (int time : times) lines.add(new Line(time, text));
        }
        lines.sort(Comparator.comparingInt(line -> line.millis));
        return lines;
    }

    int activeAt(int millis) {
        return activeAt(lines, millis);
    }

    static int activeAt(List<Line> lines, int millis) {
        int low = 0, high = lines.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (lines.get(middle).millis <= millis) low = middle + 1;
            else high = middle;
        }
        return low - 1;
    }
}
