// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
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
import javax.net.ssl.SSLException;

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
    private WebSession selection = WebSession.EMPTY;
    private WebSession webSession = WebSession.EMPTY;

    void setSession(String cookies) {
        WebSession selected = hasSession(cookies) ? GoogleSignIn.selection(cookies) : WebSession.EMPTY;
        if (this.cookies.equals(cookies) && selection == selected) return;
        this.cookies = cookies;
        selection = selected;
        webSession = selected;
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

    String accountName() throws AccountFailure {
        if (!hasSession(cookies)) throw AccountFailure.signedOut("SESSION_MISSING");
        nextRefresh = 0;
        return verifyAccount(this::web);
    }

    interface AccountRequest {
        JSONObject send(String endpoint, JSONObject body) throws Exception;
    }

    static String verifyAccount(AccountRequest request) throws AccountFailure {
        JSONObject menu = accountRequest(request, "account/account_menu", new JSONObject(), "ACCOUNT_MENU");
        SessionState menuState = sessionState(menu);
        if (menuState == SessionState.SIGNED_OUT) throw AccountFailure.signedOut("ACCOUNT_MENU_SIGNED_OUT");
        JSONObject header = find(menu, "activeAccountHeaderRenderer", 0);
        if (header != null) {
            String name = displayText(header.optJSONObject("accountName")).trim();
            return name.isEmpty() ? "YouTube account" : name;
        }
        if (menuState == SessionState.SIGNED_IN) return "YouTube account";

        JSONObject homeRequest = new JSONObject(java.util.Collections.singletonMap("browseId", "FEmusic_home"));
        JSONObject home = accountRequest(request, "browse", homeRequest, "ACCOUNT_BROWSE");
        switch (sessionState(home)) {
            case SIGNED_IN: return "YouTube account";
            case SIGNED_OUT: throw AccountFailure.signedOut("ACCOUNT_BROWSE_SIGNED_OUT");
            default: throw new AccountFailure("ACCOUNT_RESPONSE_UNRECOGNIZED",
                    "YouTube Music did not confirm this account. Try checking again or choose an account.");
        }
    }

    private static JSONObject accountRequest(AccountRequest request, String endpoint, JSONObject body, String stage)
            throws AccountFailure {
        try {
            return request.send(endpoint, body);
        } catch (HttpStatus failure) {
            String code = stage + "_HTTP_" + failure.status;
            if (failure.status == 401) throw AccountFailure.signedOut(code);
            if (failure.status == 403) {
                throw new AccountFailure(code, "YouTube Music refused the account check (HTTP 403).");
            }
            if (failure.status == 429) {
                throw new AccountFailure(code, "YouTube Music is limiting requests. Try checking again later.");
            }
            throw new AccountFailure(code, "YouTube Music could not check the account (HTTP " + failure.status + ").");
        } catch (SSLException failure) {
            throw new AccountFailure(stage + "_TLS", "Could not establish a secure connection to YouTube Music.");
        } catch (SocketTimeoutException failure) {
            throw new AccountFailure(stage + "_TIMEOUT", "The YouTube Music account check timed out. Try checking again.");
        } catch (UnknownHostException failure) {
            throw new AccountFailure(stage + "_DNS", "Could not find YouTube Music. Check your connection.");
        } catch (JSONException failure) {
            throw new AccountFailure(stage + "_INVALID_RESPONSE", "YouTube Music returned an unreadable account response.");
        } catch (IOException failure) {
            throw new AccountFailure(stage + "_NETWORK", "Could not connect to YouTube Music. Try checking again.");
        } catch (Exception failure) {
            throw new AccountFailure(stage + "_CLIENT", "YouTube Music account check could not finish.");
        }
    }

    JSONObject browse(JSONObject body) throws Exception {
        return web("browse", body);
    }

    private JSONObject web(String endpoint, JSONObject body) throws Exception {
        refreshVisitor();
        JSONObject client = client("WEB_REMIX", version)
                .put("platform", "DESKTOP").put("originalUrl", MUSIC);
        JSONObject context = context(client).getJSONObject("context");
        JSONObject user = new JSONObject().put("lockedSafetyMode", false);
        if (settings != null && settings.flag("restricted", false)) user.put("enableSafetyMode", true);
        context.put("user", user);
        body.put("context", context);
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

    private final AppSettings settings;

    MusicApi() { this(null); }

    MusicApi(AppSettings settings) {
        this.settings = settings;
    }

    boolean connectionWorks() throws IOException {
        HttpsURLConnection connection = open(MUSIC + "/", WEB_UA);
        try {
            connection.setInstanceFollowRedirects(false);
            int response = connection.getResponseCode();
            return response >= 200 && response < 400;
        } finally {
            connection.disconnect();
        }
    }

    static final class SignInRequired extends IOException {
        private static final long serialVersionUID = 1L;

        SignInRequired(String reason) {
            super(reason);
        }
    }

    static final class ArtistCredit {
        final String name, browseId;

        ArtistCredit(String name, String browseId) {
            this.name = name;
            this.browseId = browseId;
        }

        static boolean usableName(String name) {
            String value = name.trim();
            return !value.equalsIgnoreCase("Song") && !value.equalsIgnoreCase("Video")
                    && value.codePoints().anyMatch(Character::isLetter);
        }
    }

    static final class AlbumCredit {
        final String name, browseId;

        AlbumCredit(String name, String browseId) {
            this.name = name;
            this.browseId = browseId;
        }

        static boolean isBrowseId(String id) { return id.matches("(MPREb|OLAK5uy)[A-Za-z0-9_-]{2,190}"); }
    }

    static final class Track {
        final String id, title, artist, artwork;
        final List<ArtistCredit> artists;
        final AlbumCredit album;
        final boolean explicit;

        Track(String id, String title, String artist, String artwork, String artistId) {
            this(id, title, artist, artwork, artistId, false);
        }

        Track(String id, String title, String artist, String artwork, String artistId, boolean explicit) {
            this(id, title, artist, artwork, explicit, artistId.isEmpty() ? java.util.Collections.emptyList()
                    : java.util.Collections.singletonList(new ArtistCredit(artist, artistId)));
        }

        Track(String id, String title, String artist, String artwork, boolean explicit, List<ArtistCredit> artists) {
            this(id, title, artist, artwork, explicit, artists, null);
        }

        Track(String id, String title, String artist, String artwork, boolean explicit, List<ArtistCredit> artists, AlbumCredit album) {
            this.id = id;
            this.title = title;
            this.artist = artist;
            this.artwork = artwork;
            this.explicit = explicit;
            this.artists = java.util.Collections.unmodifiableList(new ArrayList<>(artists));
            this.album = album;
        }
    }

    static Track trackWithByline(String id, String title, String artwork, boolean explicit, JSONArray runs) {
        return trackWithByline(id, title, artwork, explicit, runs, null);
    }

    private static Track trackWithByline(String id, String title, String artwork, boolean explicit, JSONArray runs, AlbumCredit album) {
        Map<String, ArtistCredit> artists = new LinkedHashMap<>();
        String display = "";
        for (int i = 0; i < Math.min(runs.length(), 48); i++) {
            JSONObject run = item(runs, i);
            JSONObject endpoint = child(child(run, "navigationEndpoint"), "browseEndpoint");
            String browseId = endpoint.optString("browseId");
            String type = child(child(endpoint, "browseEndpointContextSupportedConfigs"),
                    "browseEndpointContextMusicConfig").optString("pageType");
            String name = run.optString("text").trim();
            if (name.isEmpty()) continue;
            if (AlbumCredit.isBrowseId(browseId) || "MUSIC_PAGE_TYPE_ALBUM".equals(type)) {
                if (album == null || (album.browseId.isEmpty() && AlbumCredit.isBrowseId(browseId))) {
                    album = new AlbumCredit(name, AlbumCredit.isBrowseId(browseId) ? browseId : "");
                }
            } else if (artists.size() < 16 && browseId.matches("UC[A-Za-z0-9_-]{22}")
                    && (type.isEmpty() || "MUSIC_PAGE_TYPE_ARTIST".equals(type)
                        || "MUSIC_PAGE_TYPE_USER_CHANNEL".equals(type))) {
                artists.putIfAbsent(browseId, new ArtistCredit(name, browseId));
            } else if (display.isEmpty() && browseId.isEmpty() && ArtistCredit.usableName(name)) {
                display = name;
            }
        }
        List<ArtistCredit> credits = new ArrayList<>(artists.values());
        if (display.isEmpty()) display = item(runs, 0).optString("text").trim();
        return new Track(id, title, credits.isEmpty() ? display : credits.get(0).name, artwork, explicit, credits, album);
    }

    Track trackDetails(Track track) throws Exception {
        if (!track.id.matches("[A-Za-z0-9_-]{11}")) return track;
        return detailsForRecording(track, web("next", new JSONObject().put("videoId", track.id)));
    }

    static Track detailsForRecording(Track track, JSONObject response) {
        JSONObject panel = find(response, "playlistPanelRenderer", 0);
        if (panel == null) return track;
        JSONArray entries = array(panel, "contents");
        for (int i = 0; i < Math.min(entries.length(), 64); i++) {
            JSONObject song = child(item(entries, i), "playlistPanelVideoRenderer");
            if (!track.id.equals(song.optString("videoId"))) continue;
            Track details = trackWithByline(track.id, track.title, track.artwork, track.explicit,
                    array(child(song, "longBylineText"), "runs"), track.album);
            if (details.artists.isEmpty()) {
                details = trackWithByline(track.id, track.title, track.artwork, track.explicit,
                        array(child(song, "shortBylineText"), "runs"), details.album);
            }
            return new Track(track.id, track.title, track.artist.isEmpty() ? details.artist : track.artist,
                    track.artwork, track.explicit, details.artists.isEmpty() ? track.artists : details.artists, details.album);
        }
        return track;
    }

    List<Track> search(String query) throws Exception {
        List<Track> songs = parseSearch(search(query, "EgWKAQIIAWoQEAMQBBAJEAoQBRAREBAQFQ%3D%3D"));
        if (settings != null && settings.flag("hideExplicit", false)) songs.removeIf(song -> song.explicit);
        return songs;
    }

    JSONObject search(String query, String filter) throws Exception {
        JSONObject body = new JSONObject().put("query", query);
        if (!filter.isEmpty()) body.put("params", filter);
        return web("search", body);
    }

    String plainLyrics(String videoId) throws Exception {
        JSONObject page = web("next", new JSONObject().put("videoId", videoId));
        JSONObject results = find(page, "watchNextTabbedResultsRenderer", 0);
        if (results == null) return "";
        JSONArray tabs = array(results, "tabs");
        for (int i = 0; i < tabs.length(); i++) {
            JSONObject endpoint = child(child(item(tabs, i), "tabRenderer"), "endpoint");
            String id = child(endpoint, "browseEndpoint").optString("browseId");
            if (!id.startsWith("MPLY")) continue;
            JSONObject lyrics = browse(new JSONObject().put("browseId", id));
            JSONObject description = find(lyrics, "musicDescriptionShelfRenderer", 0);
            return description == null ? "" : displayText(description.optJSONObject("description")).trim();
        }
        return "";
    }

    List<Track> radio(Track seed) throws Exception {
        JSONObject body = new JSONObject().put("playlistId", "RDAMVM" + seed.id)
                .put("params", "wAEB8gECeAE%3D").put("enablePersistentPlaylistPanel", true)
                .put("isAudioOnly", true).put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL");
        JSONObject response = web("next", body);
        JSONObject queue = find(response, "playlistPanelRenderer", 0);
        if (queue == null) return java.util.Collections.emptyList();
        JSONArray entries = array(queue, "contents");
        Map<String, Track> tracks = new LinkedHashMap<>();
        tracks.put(seed.id, seed);
        for (int i = 0; i < entries.length() && tracks.size() < 40; i++) {
            JSONObject song = child(item(entries, i), "playlistPanelVideoRenderer");
            String id = song.optString("videoId");
            String title = displayText(song.optJSONObject("title"));
            if (!id.matches("[A-Za-z0-9_-]{11}") || title.isEmpty()) continue;
            tracks.putIfAbsent(id, trackWithByline(id, title, Browse.thumbnail(song.optJSONObject("thumbnail")),
                    explicit(song), array(child(song, "shortBylineText"), "runs")));
        }
        return new ArrayList<>(tracks.values());
    }

    void react(Track track, String action) throws Exception {
        if (!hasSession(cookies)) throw new IOException("Sign in to rate a song.");
        checkWrite(web("like/" + action, new JSONObject().put("target", new JSONObject().put("videoId", track.id))));
    }

    void addToPlaylist(Track track, String id) throws Exception {
        if (!hasSession(cookies)) throw new IOException("Sign in to edit playlists.");
        JSONObject edit = new JSONObject().put("action", "ACTION_ADD_VIDEO").put("addedVideoId", track.id)
                .put("dedupeOption", "DEDUPE_OPTION_DROP_DUPLICATE");
        checkWrite(web("browse/edit_playlist", new JSONObject().put("playlistId", id)
                .put("actions", new JSONArray().put(edit))));
    }

    private static void checkWrite(JSONObject result) throws IOException {
        String status = result.optString("status");
        if (!status.isEmpty() && !status.contains("SUCCEEDED")) throw new IOException("YouTube rejected the change.");
        if (sessionState(result) == SessionState.SIGNED_OUT) throw new IOException("YouTube ended the account session.");
    }

    private enum SessionState { UNKNOWN, SIGNED_IN, SIGNED_OUT }

    private static SessionState sessionState(JSONObject result) {
        JSONObject context = child(result, "responseContext");
        Object loggedOut = child(context, "mainAppWebResponseContext").opt("loggedOut");
        if (Boolean.TRUE.equals(loggedOut)) return SessionState.SIGNED_OUT;
        if (Boolean.FALSE.equals(loggedOut)) return SessionState.SIGNED_IN;
        boolean signedIn = false;
        JSONArray tracking = array(context, "serviceTrackingParams");
        for (int i = 0; i < tracking.length(); i++) {
            JSONObject service = item(tracking, i);
            if (!"GFEEDBACK".equals(service.optString("service"))) continue;
            JSONArray params = array(service, "params");
            for (int j = 0; j < params.length(); j++) {
                JSONObject param = item(params, j);
                if (!"logged_in".equals(param.optString("key"))) continue;
                String value = param.optString("value");
                if ("0".equals(value)) return SessionState.SIGNED_OUT;
                if ("1".equals(value)) signedIn = true;
            }
        }
        return signedIn ? SessionState.SIGNED_IN : SessionState.UNKNOWN;
    }

    static final class AudioSource {
        final String url, agent;
        final long bytes;
        final double loudness;

        AudioSource(String url, String agent, long bytes, double loudness) {
            this.url = url;
            this.agent = agent;
            this.bytes = bytes;
            this.loudness = loudness;
        }
    }

    List<AudioSource> audio(Track track) throws Exception {
        return audio(track, false);
    }

    List<AudioSource> audio(Track track, boolean download) throws Exception {
        try {
            return anonymousAudio(track, download);
        } catch (SignInRequired blocked) {
            if (!hasSession(cookies)) throw blocked;
            List<AudioSource> urls;
            try {
                urls = signedAudio(track, download);
            } catch (IOException | org.json.JSONException unavailable) {
                throw blocked;
            }
            if (urls.isEmpty()) throw blocked;
            return urls;
        }
    }

    private List<AudioSource> signedAudio(Track track, boolean download) throws Exception {
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
        return audioUrls(result, WEB_UA, download);
    }

    static int signatureTimestamp(String source) {
        Matcher timestamp = Pattern.compile("\"?signatureTimestamp\"?\\s*:\\s*([0-9]{1,8})").matcher(source);
        return timestamp.find() ? Integer.parseInt(timestamp.group(1)) : 0;
    }

    private List<AudioSource> anonymousAudio(Track track, boolean download) throws Exception {
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
        List<AudioSource> urls = audioUrls(result, PLAYER_UA, download);
        if (urls.isEmpty()) throw new IOException(download ? "YouTube did not offer a downloadable AAC file."
                : "No AAC audio stream is available.");
        return urls;
    }

    private static List<AudioSource> audioUrls(JSONObject result, String agent, boolean download) {
        JSONObject data = child(result, "streamingData");
        JSONObject volume = child(child(result, "playerConfig"), "audioConfig");
        double trackLoudness = volume.optDouble("trackAbsoluteLoudnessLkfs", volume.optDouble("perceptualLoudnessDb"))
                - volume.optDouble("loudnessTargetLkfs", -14);
        List<AudioSource> urls = new ArrayList<>();
        String master = data.optString("hlsManifestUrl");
        if (!download && isHttps(master)) {
            try {
                String playlist = get(master, agent, false);
                String hls = audioPlaylist(master, playlist);
                if (hls != null) urls.add(new AudioSource(hls, agent, 0, trackLoudness));
            } catch (IOException ignored) {
                // A progressive AAC stream can still be available.
            }
        }
        JSONArray formats = array(data, "adaptiveFormats");
        for (int itag : new int[]{140, 139}) {
            for (int index = 0; index < formats.length(); index++) {
                JSONObject format = item(formats, index);
                String url = format.optString("url");
                long bytes = format.optLong("contentLength");
                if (format.optInt("itag") == itag && isHttps(url) && (!download || bytes > 0)) {
                    urls.add(new AudioSource(url, agent, bytes, format.optDouble("loudnessDb", trackLoudness)));
                }
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
        Track track = trackWithByline(id, name, Browse.image(row.optJSONObject("thumbnail")), explicit(row), column(columns, 1));
        if (track.album != null) return track;
        Track extraColumn = trackWithByline(id, name, track.artwork, track.explicit, column(columns, 2));
        return extraColumn.album == null ? track : new Track(id, name, track.artist, track.artwork, track.explicit,
                track.artists, extraColumn.album);
    }

    static boolean explicit(JSONObject row) {
        JSONArray badges = array(row, "badges");
        if (badges.length() == 0) badges = array(row, "subtitleBadges");
        for (int i = 0; i < badges.length(); i++) {
            JSONObject badge = child(item(badges, i), "musicInlineBadgeRenderer");
            if ("MUSIC_EXPLICIT_BADGE".equals(child(badge, "icon").optString("iconType"))) return true;
        }
        return false;
    }

    private static JSONArray column(JSONArray columns, int index) {
        return array(child(child(item(columns, index), "musicResponsiveListItemFlexColumnRenderer"), "text"), "runs");
    }

    private JSONObject client(String name, String clientVersion) throws Exception {
        return new JSONObject().put("clientName", name).put("clientVersion", clientVersion)
                .put("hl", settings == null ? "en" : settings.language())
                .put("gl", settings == null ? "MY" : settings.country()).put("visitorData", visitor);
    }

    private static JSONObject context(JSONObject client) throws Exception {
        JSONObject context = new JSONObject().put("client", client)
                .put("request", new JSONObject().put("useSsl", true));
        return new JSONObject().put("context", context);
    }

    private void refreshVisitor() {
        long now = System.currentTimeMillis();
        if (now < nextRefresh) return;
        if (nextRefresh == 0 && selection.hasRequestContext()) {
            visitor = selection.visitor;
            version = selection.version;
            nextRefresh = now + 60 * 60_000L;
            return;
        }
        nextRefresh = now + 60_000;
        if (!selection.visitor.isEmpty()) visitor = selection.visitor;
        if (!selection.version.isEmpty()) version = selection.version;
        try {
            String page = landingPage(MUSIC + "/" + (selection.index == null ? "" : "?authuser=" + selection.index));
            webSession = selection.fillFrom(WebSession.fromLandingPage(page));
            if (!webSession.version.isEmpty()) version = webSession.version;
            if (!webSession.visitor.isEmpty()) visitor = webSession.visitor;
            nextRefresh = now + 24 * 60 * 60_000L;
        } catch (IOException ignored) {
            // Use the last known client information while the landing page is unavailable.
        }
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
                    connection.setRequestProperty("X-Goog-AuthUser", webSession.index == null ? "0" : webSession.index.toString());
                    if (webSession.pageId != null && !webSession.pageId.isEmpty()) {
                        connection.setRequestProperty("X-Goog-PageId", webSession.pageId);
                    }
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

    static final class AccountFailure extends IOException {
        final String code;
        final boolean signedOut;

        AccountFailure(String code, String message) { this(code, message, false); }

        private AccountFailure(String code, String message, boolean signedOut) {
            super(message);
            this.code = code;
            this.signedOut = signedOut;
        }

        static AccountFailure signedOut(String code) {
            return new AccountFailure(code, "YouTube Music did not accept this session. Sign in again or choose another account.", true);
        }
    }

    static final class HttpStatus extends IOException {
        final int status;

        HttpStatus(int status) {
            super("YouTube HTTP " + status);
            this.status = status;
        }
    }

    static String read(HttpsURLConnection connection) throws IOException {
        int status = connection.getResponseCode();
        if (status != 200) throw new HttpStatus(status);
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
