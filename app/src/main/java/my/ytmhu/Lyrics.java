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

    static final class Word {
        final int start, end, from, to;

        Word(int start, int end, int from, int to) {
            this.start = start;
            this.end = end;
            this.from = from;
            this.to = to;
        }
    }

    static final class Line {
        final int millis, end;
        final List<Word> words;
        final String text, roman;
        final java.util.Map<String, String> translations;

        Line(int millis, String text) { this(millis, text, null, null); }

        Line(int millis, String text, String roman, java.util.Map<String, String> translations) {
            this(millis, -1, text, roman, translations, Collections.emptyList());
        }

        Line(int millis, int end, String text, String roman, java.util.Map<String, String> translations, List<Word> words) {
            this.millis = millis;
            this.end = end;
            this.words = Collections.unmodifiableList(words);
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

    interface Source {
        Lyrics fetch(String provider) throws Exception;
    }

    private static final android.util.LruCache<String, Lyrics> TIMED_CACHE = new android.util.LruCache<>(4);

    static String preferredProvider(AppSettings prefs) { return prefs.text("lyricsFirst", "auto"); }

    static Lyrics load(MusicApi.Track track, int duration, AppSettings prefs) throws Exception {
        String first = preferredProvider(prefs);
        boolean allowUnison = prefs.flag("unisonLyrics", true);
        boolean allowLrclib = prefs.flag("lrclib", true);
        String key = track.id + "\n" + track.title + "\n" + track.artist + "\n" + duration
                + "\n" + first + "\n" + allowUnison + "\n" + allowLrclib;
        Lyrics cached = TIMED_CACHE.get(key);
        if (cached != null) return cached;
        String[] order = ("auto".equals(first) || "unison".equals(first)) ? new String[]{"unison", "lrclib", "youtube"}
                : "youtube".equals(first) ? new String[]{"youtube", "lrclib", "unison"}
                : new String[]{"lrclib", "youtube", "unison"};
        Lyrics selected = select(order, duration, provider -> {
            switch (provider) {
                case "unison": return allowUnison ? unison(track, duration) : null;
                case "youtube": return prefs.flag("ytLyrics", true)
                        ? new Lyrics(Collections.emptyList(), new MusicApi(prefs).plainLyrics(track.id), "YouTube Music") : null;
                default: return allowLrclib ? lrclib(track, duration) : null;
            }
        });
        if (selected.timed() && selected.lines.size() <= 240
                && selected.lines.stream().mapToInt(line -> line.text.length() + line.roman.length()
                        + line.translations.values().stream().mapToInt(String::length).sum() + line.words.size() * 16).sum() <= 20_000) {
            TIMED_CACHE.put(key, selected);
        }
        return selected;
    }

    static void trimCache() { TIMED_CACHE.evictAll(); }

    // Provider priority orders timed candidates and, separately, plain fallbacks.
    // YouTube Music only supplies plain text, so it cannot win before timed checks finish.
    static Lyrics select(String[] order, int duration, Source source) throws Exception {
        Lyrics[] fallback = new Lyrics[order.length];
        boolean failed = false;
        for (int i = 0; i < order.length; i++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if ("youtube".equals(order[i])) continue;
            try {
                Lyrics result = source.fetch(order[i]);
                if (result != null && result.timedFor(duration)) return result;
                if (result != null && !result.plain.trim().isEmpty()) {
                    fallback[i] = new Lyrics(Collections.emptyList(), result.plain, result.source, result.language);
                }
            } catch (InterruptedException cancelled) {
                throw cancelled;
            } catch (Exception unavailable) {
                failed = true;
            }
        }
        for (int i = 0; i < order.length; i++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (fallback[i] != null) return fallback[i];
            if (!"youtube".equals(order[i])) continue;
            try {
                Lyrics result = source.fetch(order[i]);
                if (result != null && !result.plain.trim().isEmpty()) return result;
            } catch (InterruptedException cancelled) {
                throw cancelled;
            } catch (Exception unavailable) {
                failed = true;
            }
        }
        if (failed) throw new IOException("A lyrics source could not be reached.");
        return new Lyrics(Collections.emptyList(), "", "");
    }

    boolean timed() { return !lines.isEmpty(); }

    private boolean timedFor(int duration) {
        if (!timed() || !lines.stream().anyMatch(line -> !line.text.trim().isEmpty())) return false;
        return duration <= 0 || lines.get(lines.size() - 1).millis <= (long) duration + 1000;
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
            JSONObject data = new JSONObject(readProvider(connection)).optJSONObject("data");
            if (data == null) return null;
            String recordingId = data.optString("videoId", "");
            if (!recordingId.isEmpty() && !track.id.equals(recordingId)) return null;
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
        Lyrics exact = null;
        if (duration > 0) {
            try {
                String response = lrclibRequest("get?" + query + "&duration=" + Math.round(duration / 1000.0));
                if (response != null) {
                    JSONObject entry = new JSONObject(response);
                    exact = lrclibResult(bestMatch(new JSONArray().put(entry), track.title, track.artist, duration), duration);
                    if (exact != null && exact.timedFor(duration)) return exact;
                }
            } catch (IOException | org.json.JSONException unavailable) {
                // Search is still useful when the exact endpoint misses or rejects the metadata.
            }
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        Lyrics search;
        try {
            String response = lrclibRequest("search?" + query);
            search = response == null ? null : lrclibResult(
                    bestMatch(new JSONArray(response), track.title, track.artist, duration), duration);
        } catch (IOException | org.json.JSONException unavailable) {
            if (exact != null) return exact;
            throw unavailable;
        }
        if (search != null && search.timedFor(duration)) return search;
        String foldedTitle = normalized(track.title);
        String foldedArtist = normalized(track.artist);
        if (!foldedTitle.equalsIgnoreCase(track.title.trim()) || !foldedArtist.equalsIgnoreCase(track.artist.trim())) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            try {
                String foldedQuery = "track_name=" + URLEncoder.encode(foldedTitle, "UTF-8")
                        + "&artist_name=" + URLEncoder.encode(foldedArtist, "UTF-8");
                String alternative = lrclibRequest("search?" + foldedQuery);
                if (alternative != null) {
                    Lyrics match = lrclibResult(bestMatch(new JSONArray(alternative), track.title, track.artist, duration), duration);
                    if (match != null && (match.timedFor(duration) || (search == null && exact == null))) return match;
                }
            } catch (IOException | org.json.JSONException unavailable) {
                // Keep the first valid plain result for the final fallback.
            }
        }
        return search != null ? search : exact;
    }

    private static Lyrics lrclibResult(JSONObject match, int duration) {
        if (match == null) return null;
        List<Line> lines = duration > 0 && durationMillis(match) > 0
                ? parse(match.optString("syncedLyrics", "")) : Collections.emptyList();
        String plain = match.isNull("plainLyrics") ? "" : match.optString("plainLyrics");
        return new Lyrics(lines, plain, "LRCLIB");
    }

    private static String lrclibRequest(String path) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) URI.create("https://lrclib.net/api/" + path)
                .toURL().openConnection();
        try {
            connection.setConnectTimeout(3500);
            connection.setReadTimeout(4500);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "YTM Head Unit/" + BuildVersion.VERSION);
            if (connection.getResponseCode() == 404) return null;
            return readProvider(connection);
        } finally {
            connection.disconnect();
        }
    }

    private static String readProvider(HttpsURLConnection connection) throws IOException {
        try (java.io.InputStream in = connection.getInputStream();
             java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) != -1; ) {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
                if (bytes.size() + n > 1_048_576) throw new IOException("Lyrics response too large");
                bytes.write(buffer, 0, n);
            }
            return bytes.toString("UTF-8");
        }
    }

    static JSONObject bestMatch(JSONArray options, String title, String artist, int duration) {
        JSONObject best = null;
        long bestScore = Long.MIN_VALUE;
        for (int i = 0; i < options.length(); i++) {
            JSONObject entry = options.optJSONObject(i);
            if (entry == null || entry.optBoolean("instrumental")) continue;
            if (!same(entry.optString("trackName"), title) || !same(entry.optString("artistName"), artist)) continue;
            int ms = durationMillis(entry);
            int difference = duration > 0 && ms > 0 ? Math.abs(duration - ms) : 4001;
            if (duration > 0 && ms > 0 && difference > 4000) continue;
            String plain = entry.isNull("plainLyrics") ? "" : entry.optString("plainLyrics");
            List<Line> lines = difference <= 4000 ? parse(entry.optString("syncedLyrics", "")) : Collections.emptyList();
            boolean timed = new Lyrics(lines, "", "LRCLIB").timedFor(duration);
            if (!timed && plain.trim().isEmpty()) continue;
            long score = (timed ? 8002 : 0) - difference;
            if (score > bestScore) {
                best = entry;
                bestScore = score;
            }
        }
        return best;
    }

    private static int durationMillis(JSONObject entry) {
        double millis = entry.optDouble("duration", 0) * 1000;
        return millis > 0 && millis <= Integer.MAX_VALUE ? (int) Math.round(millis) : 0;
    }

    private static boolean same(String a, String b) {
        return normalized(a).equalsIgnoreCase(normalized(b));
    }

    private static String normalized(String value) {
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
                .replace('’', '\'').replace('–', '-').replace('—', '-')
                .replaceAll("[\\s\\p{Zs}]+", " ").trim();
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
        if (low == 0) return -1;
        Line previous = lines.get(low - 1);
        return previous.end >= 0 && millis >= previous.end ? -1 : low - 1;
    }
}
