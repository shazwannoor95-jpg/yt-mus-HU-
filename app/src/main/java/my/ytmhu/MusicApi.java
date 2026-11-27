// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

/** Android port of the anonymous search and player paths in ytm-rs. */
final class MusicApi {
    static final String MUSIC = "https://music.youtube.com";
    private static final String VIDEO = "https://www.youtube.com";
    private static final String WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";
    static final String PLAYER_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15";
    private static final String CONSENT = "SOCS=CAISAiAD";
    private String version = "1.20261006.10.00";
    private String visitor = "";
    private long nextRefresh;
    private String cookies = "";
    private int sessionIndex;
    private String pageId = "";

    void setSession(String cookies) {
        if (this.cookies.equals(cookies)) return;
        this.cookies = cookies;
        sessionIndex = 0;
        pageId = "";
        visitor = "";
        nextRefresh = 0;
    }

    static boolean hasSession(String cookies) {
        return !cookie(cookies, "SAPISID").isEmpty()
                || !cookie(cookies, "__Secure-1PAPISID").isEmpty()
                || !cookie(cookies, "__Secure-3PAPISID").isEmpty();
    }

    private static String cookie(String cookies, String name) {
        String prefix = name + "=";
        for (String entry : cookies.split(";")) {
            String value = entry.trim();
            if (value.startsWith(prefix)) return value.substring(prefix.length());
        }
        return "";
    }

    private String authorization() throws Exception {
        long now = System.currentTimeMillis() / 1000;
        String[][] schemes = { {"SAPISIDHASH", "SAPISID"}, {"SAPISID1PHASH", "__Secure-1PAPISID"},
                {"SAPISID3PHASH", "__Secure-3PAPISID"} };
        List<String> parts = new ArrayList<>();
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        for (String[] scheme : schemes) {
            String value = cookie(cookies, scheme[1]);
            if (value.isEmpty()) continue;
            byte[] bytes = sha1.digest((now + " " + value + " " + MUSIC).getBytes(StandardCharsets.UTF_8));
            StringBuilder hash = new StringBuilder();
            for (byte b : bytes) hash.append(String.format(Locale.ROOT, "%02x", b & 255));
            parts.add(scheme[0] + " " + now + "_" + hash);
        }
        return String.join(" ", parts);
    }

    String accountName() throws Exception {
        if (!hasSession(cookies)) throw new IOException("Sign in to see your library.");
        JSONObject account = web("account/account_menu", new JSONObject());
        JSONObject header = find(account, "activeAccountHeaderRenderer", 0);
        if (header == null) throw new IOException("YouTube could not verify this account.");
        String name = displayText(header.optJSONObject("accountName"));
        return name.isEmpty() ? "YouTube account" : name;
    }

    JSONObject browse(JSONObject body) throws Exception {
        return web("browse", body);
    }

    private JSONObject web(String endpoint, JSONObject body) throws Exception {
        refreshVisitor();
        JSONObject client = client("WEB_REMIX", version)
                .put("platform", "DESKTOP").put("originalUrl", MUSIC);
        body.put("context", context(client).getJSONObject("context"));
        return post(MUSIC, endpoint, body, WEB_UA, "67", version);
    }

    static JSONObject find(Object node, String key, int depth) {
        if (depth >= 20) return null;
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONObject value = object.optJSONObject(key);
            if (value != null) return value;
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                JSONObject found = find(object.opt(keys.next()), key, depth + 1);
                if (found != null) return found;
            }
        } else if (node instanceof JSONArray) {
            JSONArray objects = (JSONArray) node;
            for (int i = 0; i < objects.length(); i++) {
                JSONObject found = find(objects.opt(i), key, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    static String displayText(JSONObject object) {
        if (object == null) return "";
        JSONArray runs = object.optJSONArray("runs");
        if (runs == null) return object.optString("simpleText");
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < runs.length(); i++) value.append(item(runs, i).optString("text"));
        return value.toString();
    }

    static final class SignInRequired extends IOException {
        private static final long serialVersionUID = 1L;

        SignInRequired(String reason) {
            super(reason);
        }
    }

    static final class Track {
        final String id, title, artist;

        Track(String id, String title, String artist) {
            this.id = id;
            this.title = title;
            this.artist = artist;
        }
    }

    List<Track> search(String query) throws Exception {
        return parseSearch(search(query, "EgWKAQIIAWoQEAMQBBAJEAoQBRAREBAQFQ%3D%3D"));
    }

    JSONObject search(String query, String filter) throws Exception {
        return web("search", new JSONObject().put("query", query).put("params", filter));
    }

    static final class AudioSource {
        final String url, agent;

        AudioSource(String url, String agent) {
            this.url = url;
            this.agent = agent;
        }
    }

    List<AudioSource> audio(Track track) throws Exception {
        try {
            return anonymousAudio(track);
        } catch (SignInRequired blocked) {
            if (!hasSession(cookies)) throw blocked;
            List<AudioSource> urls;
            try {
                urls = signedAudio(track);
            } catch (IOException | org.json.JSONException unavailable) {
                throw blocked;
            }
            if (urls.isEmpty()) throw blocked;
            return urls;
        }
    }

    private List<AudioSource> signedAudio(Track track) throws Exception {
        String watch = landingPage(MUSIC + "/watch?v=" + track.id);
        int timestamp = signatureTimestamp(watch);
        if (timestamp == 0) {
            Matcher script = Pattern.compile("/s/player/[A-Za-z0-9_/-]+/base\\.js").matcher(watch);
            if (script.find()) timestamp = signatureTimestamp(get(MUSIC + script.group(), WEB_UA, false));
        }
        if (timestamp == 0) return java.util.Collections.emptyList();
        JSONObject playback = new JSONObject().put("html5Preference", "HTML5_PREF_WANTS")
                .put("signatureTimestamp", timestamp);
        JSONObject result = web("player", new JSONObject().put("videoId", track.id)
                .put("playbackContext", new JSONObject().put("contentPlaybackContext", playback)));
        if (!"OK".equals(child(result, "playabilityStatus").optString("status"))) {
            return java.util.Collections.emptyList();
        }
        return audioUrls(child(result, "streamingData"), WEB_UA);
    }

    static int signatureTimestamp(String source) {
        Matcher timestamp = Pattern.compile("\"?signatureTimestamp\"?\\s*:\\s*([0-9]{1,8})").matcher(source);
        return timestamp.find() ? Integer.parseInt(timestamp.group(1)) : 0;
    }

    private List<AudioSource> anonymousAudio(Track track) throws Exception {
        refreshVisitor();
        JSONObject client = client("VISIONOS", "1.02")
                .put("deviceMake", "Apple").put("deviceModel", "RealityDevice17,1")
                .put("userAgent", PLAYER_UA).put("osName", "visionOS")
                .put("osVersion", "26.5.23O471");
        JSONObject result = post(VIDEO, "player", context(client).put("videoId", track.id)
                .put("contentCheckOk", true).put("racyCheckOk", true), PLAYER_UA, "101", "1.02");
        JSONObject status = child(result, "playabilityStatus");
        if (!"OK".equals(status.optString("status"))) {
            String reason = status.optString("reason", "This track is unavailable.");
            if ("LOGIN_REQUIRED".equals(status.optString("status"))) throw new SignInRequired(reason);
            throw new IOException(reason);
        }
        List<AudioSource> urls = audioUrls(child(result, "streamingData"), PLAYER_UA);
        if (urls.isEmpty()) throw new IOException("No AAC audio stream is available.");
        return urls;
    }

    private static List<AudioSource> audioUrls(JSONObject data, String agent) {
        List<AudioSource> urls = new ArrayList<>();
        String master = data.optString("hlsManifestUrl");
        if (isHttps(master)) {
            try {
                String playlist = get(master, agent, false);
                String hls = audioPlaylist(master, playlist);
                if (hls != null) urls.add(new AudioSource(hls, agent));
            } catch (IOException ignored) {
                // A progressive AAC stream can still be available.
            }
        }
        JSONArray formats = array(data, "adaptiveFormats");
        for (int itag : new int[]{140, 139}) {
            for (int index = 0; index < formats.length(); index++) {
                JSONObject format = item(formats, index);
                String url = format.optString("url");
                if (format.optInt("itag") == itag && isHttps(url)) urls.add(new AudioSource(url, agent));
            }
        }
        return urls;
    }

    static String audioPlaylist(String master, String playlist) throws IOException {
        Pattern uri = Pattern.compile("(?:^|,)URI=\"([^\"]+)\"");
        for (String line : playlist.split("\\r?\\n")) {
            String prefix = "#EXT-X-MEDIA:";
            if (!line.startsWith(prefix)) continue;
            String attributes = line.substring(prefix.length());
            if (!attributes.contains("TYPE=AUDIO") || !attributes.contains("GROUP-ID=\"234\"")) continue;
            Matcher match = uri.matcher(attributes);
            if (match.find()) {
                try {
                    String resolved = URI.create(master).resolve(match.group(1)).toString();
                    if (isHttps(resolved)) return resolved;
                } catch (IllegalArgumentException ignored) {
                    // Continue looking for a valid rendition.
                }
            }
        }
        return null;
    }

    static List<Track> parseSearch(JSONObject result) {
        Map<String, Track> tracks = new LinkedHashMap<>();
        JSONObject contents = child(result, "contents");
        JSONArray tabs = array(child(contents, "tabbedSearchResultsRenderer"), "tabs");
        JSONObject tab = child(item(tabs, 0), "tabRenderer");
        JSONObject list = child(tabs.length() == 0 ? contents : child(tab, "content"), "sectionListRenderer");
        JSONArray sections = array(list, "contents");
        for (int section = 0; section < sections.length() && tracks.size() < 40; section++) {
            JSONArray rows = array(child(item(sections, section), "musicShelfRenderer"), "contents");
            for (int index = 0; index < rows.length() && tracks.size() < 40; index++) {
                JSONObject row = child(item(rows, index), "musicResponsiveListItemRenderer");
                Track track = track(row);
                if (track != null) tracks.putIfAbsent(track.id, track);
            }
        }
        return new ArrayList<>(tracks.values());
    }

    static Track track(JSONObject row) {
        JSONArray columns = array(row, "flexColumns");
        JSONObject title = item(column(columns, 0), 0);
        String id = child(row, "playlistItemData").optString("videoId");
        if (id.isEmpty()) id = child(child(title, "navigationEndpoint"), "watchEndpoint").optString("videoId");
        if (id.isEmpty()) id = child(child(row, "navigationEndpoint"), "watchEndpoint").optString("videoId");
        String name = title.optString("text").trim();
        if (!id.matches("[A-Za-z0-9_-]{11}") || name.isEmpty()) return null;
        return new Track(id, name, item(column(columns, 1), 0).optString("text").trim());
    }

    private static JSONArray column(JSONArray columns, int index) {
        return array(child(child(item(columns, index), "musicResponsiveListItemFlexColumnRenderer"), "text"), "runs");
    }

    private JSONObject client(String name, String clientVersion) throws Exception {
        return new JSONObject().put("clientName", name).put("clientVersion", clientVersion)
                .put("hl", "en").put("gl", "MY").put("visitorData", visitor);
    }

    private static JSONObject context(JSONObject client) throws Exception {
        JSONObject context = new JSONObject().put("client", client)
                .put("request", new JSONObject().put("useSsl", true));
        return new JSONObject().put("context", context);
    }

    private void refreshVisitor() {
        long now = System.currentTimeMillis();
        if (now < nextRefresh) return;
        nextRefresh = now + 60_000;
        try {
            String page = landingPage(MUSIC + "/");
            String index = field(page, "SESSION_INDEX");
            if (index.matches("[0-9]{1,6}")) sessionIndex = Integer.parseInt(index);
            String[] account = field(page, "datasyncId").split("\\|\\|", -1);
            if (account.length == 2 && !account[1].isEmpty()) pageId = account[0];
            String freshVersion = field(page, "INNERTUBE_CLIENT_VERSION");
            if (!freshVersion.isEmpty()) version = freshVersion;
            String freshVisitor = field(page, "VISITOR_DATA");
            if (!freshVisitor.isEmpty()) visitor = freshVisitor;
            nextRefresh = now + 24 * 60 * 60_000L;
        } catch (IOException ignored) {
            // Use the last known client information while the landing page is unavailable.
        }
    }

    private static String field(String page, String name) {
        Matcher match = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]+)\"").matcher(page);
        return match.find() ? match.group(1) : "";
    }

    private JSONObject post(String origin, String endpoint, JSONObject body, String agent,
                            String clientName, String clientVersion) throws Exception {
        HttpsURLConnection connection = open(origin + "/youtubei/v1/" + endpoint + "?prettyPrint=false", agent);
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Origin", origin);
            connection.setRequestProperty("Referer", origin + "/");
            connection.setRequestProperty("X-YouTube-Client-Name", clientName);
            connection.setRequestProperty("X-YouTube-Client-Version", clientVersion);
            if (MUSIC.equals(origin)) {
                connection.setRequestProperty("X-Origin", origin);
                connection.setInstanceFollowRedirects(false);
                if (hasSession(cookies)) {
                    connection.setRequestProperty("Cookie", cookies);
                    connection.setRequestProperty("Authorization", authorization());
                    connection.setRequestProperty("X-Goog-AuthUser", Integer.toString(sessionIndex));
                    if (!pageId.isEmpty()) connection.setRequestProperty("X-Goog-PageId", pageId);
                } else {
                    connection.setRequestProperty("Cookie", CONSENT);
                }
            }
            if (!visitor.isEmpty()) connection.setRequestProperty("X-Goog-Visitor-Id", visitor);
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }
            JSONObject result = new JSONObject(read(connection));
            if (MUSIC.equals(origin) && hasSession(cookies)) updateCookies(connection);
            return result;
        } finally {
            connection.disconnect();
        }
    }

    private String landingPage(String url) throws IOException {
        HttpsURLConnection connection = open(url, WEB_UA);
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Cookie", hasSession(cookies) ? cookies : CONSENT);
            String page = read(connection);
            if (hasSession(cookies)) updateCookies(connection);
            return page;
        } finally {
            connection.disconnect();
        }
    }

    private void updateCookies(HttpsURLConnection connection) {
        String updated = GoogleSignIn.updateCookies(cookies, connection);
        if (updated != null) cookies = updated;
    }

    private static String get(String url, String agent, boolean consent) throws IOException {
        HttpsURLConnection connection = open(url, agent);
        try {
            if (consent) connection.setRequestProperty("Cookie", CONSENT);
            return read(connection);
        } finally {
            connection.disconnect();
        }
    }

    private static HttpsURLConnection open(String url, String agent) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("User-Agent", agent);
        return connection;
    }

    private static String read(HttpsURLConnection connection) throws IOException {
        int status = connection.getResponseCode();
        if (status != 200) throw new IOException("YouTube HTTP " + status);
        try (InputStream input = connection.getInputStream()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] bytes = new byte[8192];
            int read;
            while ((read = input.read(bytes)) != -1) {
                if (output.size() + read > 4 * 1024 * 1024) throw new IOException("Respons terlalu besar.");
                output.write(bytes, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static boolean isHttps(String url) {
        return url.startsWith("https://");
    }

    static JSONObject child(JSONObject object, String name) {
        JSONObject value = object.optJSONObject(name);
        return value == null ? new JSONObject() : value;
    }

    static JSONObject item(JSONArray array, int index) {
        JSONObject value = array.optJSONObject(index);
        return value == null ? new JSONObject() : value;
    }

    static JSONArray array(JSONObject object, String name) {
        JSONArray value = object.optJSONArray(name);
        return value == null ? new JSONArray() : value;
    }
}
