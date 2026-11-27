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
        final String text;

        Line(int millis, String text) {
            this.millis = millis;
            this.text = text;
        }
    }

    final List<Line> lines;
    final String plain;
    final String source;

    private Lyrics(List<Line> lines, String plain, String source) {
        this.lines = lines;
        this.plain = plain;
        this.source = source;
    }

    static Lyrics load(MusicApi.Track track, int duration) throws Exception {
        Lyrics plainFallback = null;
        Exception providerFailure = null;
        try {
            String query = "track_name=" + URLEncoder.encode(track.title, "UTF-8")
                    + "&artist_name=" + URLEncoder.encode(track.artist, "UTF-8");
            HttpsURLConnection connection = (HttpsURLConnection) URI.create("https://lrclib.net/api/search?" + query)
                    .toURL().openConnection();
            try {
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(6500);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", "YTM Head Unit/0.3.0");
                JSONArray results = new JSONArray(MusicApi.read(connection));
                JSONObject match = bestMatch(results, track.title, track.artist, duration);
                if (match != null) {
                    List<Line> lines = parse(match.optString("syncedLyrics", ""));
                    String plain = match.isNull("plainLyrics") ? "" : match.optString("plainLyrics");
                    if (!lines.isEmpty()) return new Lyrics(lines, plain, "LRCLIB");
                    if (!plain.isEmpty()) plainFallback = new Lyrics(lines, plain, "LRCLIB");
                }
            } finally {
                connection.disconnect();
            }
        } catch (Exception unavailable) {
            providerFailure = unavailable;
        }
        if (plainFallback != null) return plainFallback;
        String plain;
        try {
            plain = new MusicApi().plainLyrics(track.id);
        } catch (Exception unavailable) {
            if (providerFailure != null) throw new IOException("Lyrics sources could not be reached.");
            throw unavailable;
        }
        return new Lyrics(Collections.emptyList(), plain, "YouTube Music");
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
