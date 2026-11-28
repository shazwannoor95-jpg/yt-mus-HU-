// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.net.ssl.HttpsURLConnection;

final class LyricExtras {
    static final int CLOUD_BATCH = 100;
    private static final String UNISON = "https://unison.betterlyrics.org/translate";
    private static final String CLOUD = "https://translation.googleapis.com/language/translate/v2";
    private static final String CACHE_VERSION = "lyrics-extras-2";
    private static final int BYTE_LIMIT = 48 * 1024;
    private static final long TTL = 86_400_000;
    private final File folder;
    private volatile HttpsURLConnection connection;
    private volatile boolean cancelled;

    LyricExtras(Context context) { folder = new File(context.getCacheDir(), "lyric-extras"); }

    void cancel() {
        cancelled = true;
        HttpsURLConnection active = connection;
        if (active != null) active.disconnect();
    }

    static boolean nonLatin(String text) {
        return text.codePoints().anyMatch(c -> Character.isLetter(c)
                && Character.UnicodeScript.of(c) != Character.UnicodeScript.LATIN);
    }

    static List<String> missingTranslations(List<Lyrics.Line> lines, JSONObject translated, String target) {
        List<String> candidates = needed(lines, java.util.Collections.emptyList(), false, true, target);
        candidates.removeIf(text -> !value(translated, text, "translation").isEmpty());
        return candidates;
    }

    static String value(JSONObject values, String text, String field) {
        JSONObject entry = values.optJSONObject(text.trim());
        return entry == null ? "" : entry.optString(field);
    }

    static List<String> needed(List<Lyrics.Line> lines, List<LocalRomanizer.Reading> local,
                               boolean roman, boolean translate, String target) {
        Set<String> unique = new LinkedHashSet<>();
        int bytes = 0;
        for (int index = 0; index < lines.size(); index++) {
            Lyrics.Line line = lines.get(index);
            String text = line.text.trim();
            if (text.isEmpty() || text.equals("♪") || unique.contains(text)) continue;
            boolean missingRoman = roman && clean(line.roman, text).isEmpty() && nonLatin(text)
                    && (index >= local.size() || !local.get(index).complete);
            if (!missingRoman && !(translate && clean(line.translation(target), text).isEmpty())) continue;
            bytes += text.getBytes(StandardCharsets.UTF_8).length;
            if (unique.size() >= 240 || bytes > BYTE_LIMIT / 2) break;
            unique.add(text);
        }
        return new ArrayList<>(unique);
    }

    JSONObject load(List<String> lines, String target, String sourceLanguage) throws Exception {
        JSONObject query = new JSONObject().put("to", target).put("lines", new JSONArray(lines));
        if (sourceLanguage.matches("[a-zA-Z]{2,3}(?:-[a-zA-Z0-9]{2,8})?")) query.put("from", sourceLanguage);
        return load(lines, UNISON, query, "");
    }

    JSONObject cloud(List<String> lines, String target, String sourceLanguage, String key) throws Exception {
        JSONObject query = new JSONObject().put("q", new JSONArray(lines))
                .put("target", target).put("format", "text");
        if (sourceLanguage.matches("[a-z]{2,3}")) query.put("source", sourceLanguage);
        return load(lines, CLOUD, query, key);
    }

    private JSONObject load(List<String> lines, String endpoint, JSONObject query, String key) throws Exception {
        if (lines.isEmpty() || cancelled) return new JSONObject();
        boolean cloud = CLOUD.equals(endpoint);
        byte[] body = query.toString().getBytes(StandardCharsets.UTF_8);
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                (CACHE_VERSION + "\n" + endpoint + "\n" + query).getBytes(StandardCharsets.UTF_8));
        String name = android.util.Base64.encodeToString(hash, android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP);
        File path = new File(folder, name);
        AtomicFile file = new AtomicFile(path);
        if (path.length() > 0 && path.length() <= BYTE_LIMIT && System.currentTimeMillis() - path.lastModified() < TTL) {
            try { return new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8)); }
            catch (Exception invalid) { file.delete(); }
        }
        HttpsURLConnection web = (HttpsURLConnection) new URL(endpoint).openConnection();
        connection = web;
        try {
            if (cancelled) return new JSONObject();
            web.setInstanceFollowRedirects(false);
            web.setConnectTimeout(4500);
            web.setReadTimeout(5500);
            web.setRequestMethod("POST");
            web.setDoOutput(true);
            web.setRequestProperty("Content-Type", "application/json");
            if (cloud) web.setRequestProperty("X-Goog-Api-Key", key);
            web.setFixedLengthStreamingMode(body.length);
            try (java.io.OutputStream stream = web.getOutputStream()) { stream.write(body); }
            int status = web.getResponseCode();
            if (status != 200) throw new Rejected(cloud ? "Google Cloud Translation" : "Unison", status);
            byte[] response;
            try (InputStream in = web.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (cancelled || out.size() + n > BYTE_LIMIT) throw new IOException("Invalid response size");
                    out.write(buffer, 0, n);
                }
                response = out.toByteArray();
            }
            JSONObject payload = new JSONObject(new String(response, StandardCharsets.UTF_8));
            JSONObject result = cloud ? alignCloud(lines, payload) : align(lines, payload.optJSONArray("lines"));
            if (!cancelled && result.length() > 0) save(file, result.toString().getBytes(StandardCharsets.UTF_8));
            return result;
        } finally {
            connection = null;
            web.disconnect();
        }
    }

    static String failure(Exception cause) {
        if (cause instanceof Rejected) return cause.getMessage();
        if (cause instanceof UnknownHostException || cause instanceof SocketTimeoutException) {
            return "Network unavailable or timed out. Check your connection and reopen Lyrics.";
        }
        return "The translation provider is unavailable or returned an invalid response. Reopen Lyrics to retry.";
    }

    private static final class Rejected extends IOException {
        Rejected(String provider, int status) {
            super(provider + " rejected the request (HTTP " + status + ").");
        }
    }

    static JSONObject alignCloud(List<String> lines, JSONObject response) throws Exception {
        JSONArray translations = response.getJSONObject("data").getJSONArray("translations");
        if (translations.length() != lines.size()) throw new IOException("Translation line count mismatch");
        JSONObject values = new JSONObject();
        for (int i = 0; i < lines.size(); i++) {
            String text = lines.get(i);
            String translated = clean(translations.getJSONObject(i).opt("translatedText"), text);
            if (!translated.isEmpty()) values.put(text, new JSONObject()
                    .put("translation", translated).put("provider", "Google Cloud Translation"));
        }
        return values;
    }

    static JSONObject align(List<String> lines, JSONArray rows) throws Exception {
        if (rows == null || lines.size() != rows.length()) throw new IOException("Unison line count mismatch");
        JSONObject values = new JSONObject();
        for (int i = 0; i < lines.size(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null) {
                String original = lines.get(i);
                JSONObject item = new JSONObject();
                String translation = clean(row.opt("translation"), original);
                String roman = clean(row.opt("romanization"), original);
                if (!translation.isEmpty() && row.optBoolean("needsTranslation", true)) {
                    item.put("translation", translation).put("provider", "Unison");
                }
                if (!roman.isEmpty() && nonLatin(original) && !nonLatin(roman)) item.put("romanization", roman);
                if (item.length() > 0) values.put(original, item);
            }
        }
        return values;
    }

    static String clean(Object value, String original) {
        if (!(value instanceof String)) return "";
        String text = ((String) value).trim();
        return text.equalsIgnoreCase(original.trim()) || text.length() > 2000 ? "" : text;
    }

    private void save(AtomicFile file, byte[] bytes) {
        if (bytes.length > BYTE_LIMIT || (!folder.isDirectory() && !folder.mkdirs())) return;
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(bytes);
            file.finishWrite(out);
        } catch (IOException failure) {
            if (out != null) file.failWrite(out);
        }
        File[] files = folder.listFiles(item -> item.getName().matches("[A-Za-z0-9_-]{43}="));
        if (files == null) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        for (int i = 0; i < files.length; i++) {
            if (i >= 8 || System.currentTimeMillis() - files[i].lastModified() > TTL) new AtomicFile(files[i]).delete();
        }
    }

}
