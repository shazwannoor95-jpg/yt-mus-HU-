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

    static List<String> needed(List<Lyrics.Line> lines, List<LocalRomanizer.Reading> local,
                               boolean roman, boolean translate, String target) {
        Set<String> unique = new LinkedHashSet<>();
        int bytes = 0;
        for (int index = 0; index < lines.size(); index++) {
            Lyrics.Line line = lines.get(index);
            String text = line.text.trim();
            if (text.isEmpty() || text.equals("♪") || unique.contains(text)) continue;
            boolean missingRoman = roman && line.roman.isEmpty() && nonLatin(text)
                    && (index >= local.size() || !local.get(index).complete);
            if (!missingRoman && !(translate && line.translation(target).isEmpty())) continue;
            bytes += text.getBytes(StandardCharsets.UTF_8).length;
            if (unique.size() >= 240 || bytes > BYTE_LIMIT / 2) break;
            unique.add(text);
        }
        return new ArrayList<>(unique);
    }

    JSONObject load(List<String> lines, String target, String sourceLanguage) throws Exception {
        if (lines.isEmpty() || cancelled) return new JSONObject();
        JSONObject query = new JSONObject().put("to", target).put("lines", new JSONArray(lines));
        if (sourceLanguage.matches("[a-zA-Z]{2,3}(?:-[a-zA-Z0-9]{2,8})?")) query.put("from", sourceLanguage);
        byte[] body = query.toString().getBytes(StandardCharsets.UTF_8);
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(body);
        String name = android.util.Base64.encodeToString(hash, android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP);
        File path = new File(folder, name);
        AtomicFile file = new AtomicFile(path);
        if (path.length() > 0 && path.length() <= BYTE_LIMIT && System.currentTimeMillis() - path.lastModified() < TTL) {
            try { return new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8)); }
            catch (Exception invalid) { file.delete(); }
        }
        HttpsURLConnection web = (HttpsURLConnection) new URL("https://unison.betterlyrics.org/translate").openConnection();
        connection = web;
        try {
            if (cancelled) return new JSONObject();
            web.setInstanceFollowRedirects(false);
            web.setConnectTimeout(4500);
            web.setReadTimeout(5500);
            web.setRequestMethod("POST");
            web.setDoOutput(true);
            web.setRequestProperty("Content-Type", "application/json");
            web.setFixedLengthStreamingMode(body.length);
            try (java.io.OutputStream stream = web.getOutputStream()) { stream.write(body); }
            if (web.getResponseCode() != 200) throw new IOException("Unison unavailable");
            byte[] response;
            try (InputStream in = web.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (cancelled || out.size() + n > BYTE_LIMIT) throw new IOException("Translation interrupted or too large");
                    out.write(buffer, 0, n);
                }
                response = out.toByteArray();
            }
            JSONObject result = align(lines, new JSONObject(new String(response, StandardCharsets.UTF_8)).optJSONArray("lines"));
            if (!cancelled) save(file, result.toString().getBytes(StandardCharsets.UTF_8));
            return result;
        } finally {
            connection = null;
            web.disconnect();
        }
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
                if (!translation.isEmpty() && row.optBoolean("needsTranslation", true)) item.put("translation", translation);
                if (!roman.isEmpty() && nonLatin(original)) item.put("romanization", roman);
                if (item.length() > 0) values.put(original, item);
            }
        }
        return values;
    }

    private static String clean(Object value, String original) {
        if (!(value instanceof String)) return "";
        String text = ((String) value).trim();
        return text.equalsIgnoreCase(original) || text.length() > 2000 ? "" : text;
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
