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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

/** Android port of the anonymous search and player paths in ytm-rs. */
final class MusicApi {
    private static final String MUSIC = "https://music.youtube.com";
    private static final String VIDEO = "https://www.youtube.com";
    private static final String WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";
    static final String PLAYER_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15";
    private static final String CONSENT = "SOCS=CAISAiAD";
    private String version = "1.20261006.10.00";
    private String visitor = "";
    private long nextRefresh;

    static final class Track {
        final String id, title, artist;

        Track(String id, String title, String artist) {
            this.id = id;
            this.title = title;
            this.artist = artist;
        }
    }

    List<Track> search(String query) throws Exception {
        refreshVisitor();
        JSONObject client = client("WEB_REMIX", version)
                .put("platform", "DESKTOP").put("originalUrl", MUSIC);
        JSONObject body = context(client).put("query", query)
                .put("params", "EgWKAQIIAWoQEAMQBBAJEAoQBRAREBAQFQ%3D%3D");
        return parseSearch(post(MUSIC, "search", body, WEB_UA, "67", version));
    }

    List<String> audio(Track track) throws Exception {
        refreshVisitor();
        JSONObject client = client("VISIONOS", "1.02")
                .put("deviceMake", "Apple").put("deviceModel", "RealityDevice17,1")
                .put("userAgent", PLAYER_UA).put("osName", "visionOS")
                .put("osVersion", "26.5.23O471");
        JSONObject result = post(VIDEO, "player", context(client).put("videoId", track.id)
                .put("contentCheckOk", true).put("racyCheckOk", true), PLAYER_UA, "101", "1.02");
        JSONObject status = child(result, "playabilityStatus");
        if (!"OK".equals(status.optString("status"))) {
            throw new IOException(status.optString("reason", "Lagu ini tidak boleh dimainkan."));
        }
        JSONObject data = child(result, "streamingData");
        List<String> urls = new ArrayList<>();
        String master = data.optString("hlsManifestUrl");
        if (isHttps(master)) {
            try {
                String playlist = get(master, PLAYER_UA, false);
                String hls = audioPlaylist(master, playlist);
                if (hls != null) urls.add(hls);
            } catch (IOException ignored) {
                // A progressive AAC stream can still be available.
            }
        }
        JSONArray formats = array(data, "adaptiveFormats");
        for (int itag : new int[]{140, 139}) {
            for (int index = 0; index < formats.length(); index++) {
                JSONObject format = item(formats, index);
                String url = format.optString("url");
                if (format.optInt("itag") == itag && isHttps(url)) urls.add(url);
            }
        }
        if (urls.isEmpty()) throw new IOException("Tiada penstriman audio AAC tersedia.");
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
                JSONArray columns = array(row, "flexColumns");
                JSONArray titleRuns = column(columns, 0);
                JSONObject title = item(titleRuns, 0);
                String id = child(row, "playlistItemData").optString("videoId");
                if (id.isEmpty()) {
                    id = child(child(title, "navigationEndpoint"), "watchEndpoint").optString("videoId");
                }
                if (!id.matches("[A-Za-z0-9_-]{11}") || tracks.containsKey(id)) continue;
                String name = title.optString("text").trim();
                if (name.isEmpty()) continue;
                String artist = item(column(columns, 1), 0).optString("text").trim();
                tracks.put(id, new Track(id, name, artist));
            }
        }
        return new ArrayList<>(tracks.values());
    }

    private static JSONArray column(JSONArray columns, int index) {
        return array(child(child(item(columns, index), "musicResponsiveListItemFlexColumnRenderer"), "text"), "runs");
    }

    private JSONObject client(String name, String clientVersion) throws Exception {
        return new JSONObject().put("clientName", name).put("clientVersion", clientVersion)
                .put("hl", "en").put("gl", "MY").put("visitorData", visitor);
    }

    private static JSONObject context(JSONObject client) throws Exception {
        return new JSONObject().put("context", new JSONObject().put("client", client));
    }

    private void refreshVisitor() {
        long now = System.currentTimeMillis();
        if (now < nextRefresh) return;
        nextRefresh = now + 60_000;
        try {
            String page = get(MUSIC + "/", WEB_UA, true);
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
            if (MUSIC.equals(origin)) connection.setRequestProperty("Cookie", CONSENT);
            if (!visitor.isEmpty()) connection.setRequestProperty("X-Goog-Visitor-Id", visitor);
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }
            return new JSONObject(read(connection));
        } finally {
            connection.disconnect();
        }
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

    private static JSONObject child(JSONObject object, String name) {
        JSONObject value = object.optJSONObject(name);
        return value == null ? new JSONObject() : value;
    }

    private static JSONObject item(JSONArray array, int index) {
        JSONObject value = array.optJSONObject(index);
        return value == null ? new JSONObject() : value;
    }

    private static JSONArray array(JSONObject object, String name) {
        JSONArray value = object.optJSONArray(name);
        return value == null ? new JSONArray() : value;
    }
}
