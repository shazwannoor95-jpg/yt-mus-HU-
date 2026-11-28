// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import static my.ytmhu.MusicApi.array;
import static my.ytmhu.MusicApi.child;
import static my.ytmhu.MusicApi.displayText;
import static my.ytmhu.MusicApi.find;
import static my.ytmhu.MusicApi.item;

final class Browse {
    static final String SAVED_PLAYLISTS = "4qmFsgIrEhdGRW11c2ljX2xpa2VkX3BsYXlsaXN0cxoQZ2dNR0tnUUlBQkFCb0FZQg%3D%3D";

    enum Search {
        All(""), Songs(""), Albums("EgWKAQIYAWoQEAMQBBAJEAoQBRAREBAQFQ%3D%3D"),
        Artists("EgWKAQIgAWoQEAMQBBAJEAoQBRAREBAQFQ%3D%3D"),
        Playlists("EgeKAQQoADgBahIQAxAEEAkQDhAKEAUQERAQEBU%3D");

        final String params;

        Search(String params) {
            this.params = params;
        }
    }

    static final class Card {
        final String title, subtitle, image, browseId;
        final MusicApi.Track track;

        Card(String title, String subtitle, String image, String browseId, MusicApi.Track track) {
            this.title = title;
            this.subtitle = subtitle;
            this.image = image;
            this.browseId = browseId;
            this.track = track;
        }
    }

    static final class Shelf {
        final String title;
        final List<Card> cards;
        final boolean preferArtwork;

        Shelf(String title, List<Card> cards, boolean preferArtwork) {
            this.title = title;
            this.cards = cards;
            this.preferArtwork = preferArtwork;
        }
    }

    static final class Mood {
        final String title, params;

        Mood(String title, String params) {
            this.title = title;
            this.params = params;
        }
    }

    static List<Mood> moods(JSONObject page) {
        List<Mood> out = new ArrayList<>();
        JSONObject cloud = find(page, "chipCloudRenderer", 0);
        if (cloud == null) return out;
        JSONArray chips = array(cloud, "chips");
        for (int i = 0; i < chips.length(); i++) {
            JSONObject chip = child(item(chips, i), "chipCloudChipRenderer");
            String title = displayText(chip.optJSONObject("text"));
            JSONObject action = child(chip, chip.optBoolean("isSelected") ? "onDeselectedCommand" : "navigationEndpoint");
            String params = child(action, "browseEndpoint").optString("params");
            if (!title.isEmpty()) out.add(new Mood(title, params));
        }
        return out;
    }

    static List<Shelf> shelves(JSONObject page) {
        return shelves(page, false);
    }

    static List<Shelf> shelves(JSONObject page, boolean hideExplicit) {
        List<Shelf> out = new ArrayList<>();
        JSONObject sections = find(page, "sectionListRenderer", 0);
        if (sections == null) sections = find(page, "sectionListContinuation", 0);
        if (sections == null) return out;
        JSONArray rows = array(sections, "contents");
        for (int i = 0; i < rows.length() && out.size() < 16; i++) {
            JSONObject row = item(rows, i);
            JSONObject top = row.optJSONObject("musicCardShelfRenderer");
            if (top != null) {
                Card ranked = topResult(top);
                if (ranked != null && !(hideExplicit && ranked.track != null && ranked.track.explicit)) {
                    out.add(new Shelf("Top result", java.util.Collections.singletonList(ranked), true));
                }
            }
            JSONObject carousel = row.optJSONObject("musicCarouselShelfRenderer");
            JSONObject grid = row.optJSONObject("gridRenderer");
            JSONObject list = row.optJSONObject("musicPlaylistShelfRenderer");
            if (list == null) list = row.optJSONObject("musicShelfRenderer");
            JSONObject renderer = carousel != null ? carousel : grid != null ? grid : list;
            if (renderer == null) continue;
            JSONObject header = child(child(renderer, "header"), "musicCarouselShelfBasicHeaderRenderer");
            String title = displayText(header.optJSONObject("title"));
            if (title.isEmpty()) title = displayText(renderer.optJSONObject("title"));
            JSONArray contents = array(renderer, grid != null ? "items" : "contents");
            List<Card> cards = new ArrayList<>();
            for (int j = 0; j < contents.length() && cards.size() < 40; j++) {
                Card card = card(item(contents, j));
                if (card != null && !(hideExplicit && card.track != null && card.track.explicit)) cards.add(card);
            }
            int specifiedRows = renderer.optInt("numItemsPerColumn", 0);
            String display = renderer.optString("itemSize");
            boolean artwork = grid != null || specifiedRows == 1 || display.contains("LARGE");
            for (int j = 0; j < contents.length() && !artwork; j++) {
                artwork = item(contents, j).has("musicTwoRowItemRenderer");
            }
            if (!cards.isEmpty()) out.add(new Shelf(title, cards, artwork));
        }
        return out;
    }

    private static Card topResult(JSONObject renderer) {
        JSONObject title = item(array(child(renderer, "title"), "runs"), 0);
        String label = title.optString("text").trim();
        if (label.isEmpty()) return null;
        JSONObject endpoint = child(title, "navigationEndpoint");
        String subtitle = displayText(renderer.optJSONObject("subtitle"));
        String art = image(renderer.optJSONObject("thumbnail"));
        String video = child(endpoint, "watchEndpoint").optString("videoId");
        if (video.matches("[A-Za-z0-9_-]{11}")) {
            MusicApi.Track track = MusicApi.trackWithByline(video, label, art, MusicApi.explicit(renderer),
                    array(child(renderer, "subtitle"), "runs"));
            return new Card(label, subtitle, art, "", track);
        }
        String browseId = child(endpoint, "browseEndpoint").optString("browseId");
        return browseId.isEmpty() ? null : new Card(label, subtitle, art, browseId, null);
    }

    private static Card card(JSONObject item) {
        JSONObject row = item.optJSONObject("musicResponsiveListItemRenderer");
        if (row != null) {
            MusicApi.Track track = MusicApi.track(row);
            String art = image(row.optJSONObject("thumbnail"));
            if (track != null) return new Card(track.title, track.artist, art, "", track);
            JSONObject nav = row.optJSONObject("navigationEndpoint");
            String browseId = nav == null ? "" : child(nav, "browseEndpoint").optString("browseId");
            if (browseId.isEmpty()) {
                JSONObject endpoint = find(row, "browseEndpoint", 0);
                browseId = endpoint == null ? "" : endpoint.optString("browseId");
            }
            String title = textColumn(row, 0);
            return browseId.isEmpty() || title.isEmpty() ? null :
                    new Card(title, textColumn(row, 1), art, browseId, null);
        }
        JSONObject tile = item.optJSONObject("musicTwoRowItemRenderer");
        if (tile == null) return null;
        String title = displayText(tile.optJSONObject("title"));
        String subtitle = displayText(tile.optJSONObject("subtitle"));
        String art = image(tile.optJSONObject("thumbnailRenderer"));
        JSONObject nav = child(tile, "navigationEndpoint");
        String id = child(nav, "watchEndpoint").optString("videoId");
        if (title.isEmpty()) return null;
        if (id.matches("[A-Za-z0-9_-]{11}")) {
            MusicApi.Track track = MusicApi.trackWithByline(id, title, art, MusicApi.explicit(tile),
                    array(child(tile, "subtitle"), "runs"));
            return new Card(title, subtitle, art, "", track);
        }
        String browseId = child(nav, "browseEndpoint").optString("browseId");
        if (browseId.isEmpty()) {
            String playlist = child(nav, "watchPlaylistEndpoint").optString("playlistId");
            if (!playlist.isEmpty()) browseId = "VL" + playlist;
        }
        return browseId.isEmpty() ? null : new Card(title, subtitle, art, browseId, null);
    }

    private static String textColumn(JSONObject row, int index) {
        JSONObject column = child(item(array(row, "flexColumns"), index), "musicResponsiveListItemFlexColumnRenderer");
        return displayText(column.optJSONObject("text"));
    }

    static String image(JSONObject object) {
        if (object == null) return "";
        return thumbnail(child(child(object, "musicThumbnailRenderer"), "thumbnail"));
    }

    static String thumbnail(JSONObject object) {
        if (object == null) return "";
        JSONArray thumbs = array(object, "thumbnails");
        for (int i = 0; i < thumbs.length(); i++) {
            JSONObject thumbnail = item(thumbs, i);
            String url = thumbnail.optString("url");
            if (thumbnail.optInt("width") >= 100 || i == thumbs.length() - 1) return url;
        }
        return "";
    }
}
