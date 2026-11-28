// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

import static my.ytmhu.MusicApi.array;
import static my.ytmhu.MusicApi.child;
import static my.ytmhu.MusicApi.displayText;
import static my.ytmhu.MusicApi.find;
import static my.ytmhu.MusicApi.item;

final class Browse {
    static final int MAX_COLLECTION_TRACKS = 500;
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

    enum Library {
        Playlists("continuation", SAVED_PLAYLISTS, false),
        Songs("browseId", "FEmusic_liked_videos", true),
        Albums("continuation", "4qmFsgIoEhRGRW11c2ljX2xpa2VkX2FsYnVtcxoQZ2dNR0tnUUlBQkFCb0FZQg%3D%3D", false),
        Artists("browseId", "FEmusic_library_corpus_artists", false),
        History("browseId", "FEmusic_history", true);

        final String key, value;
        final boolean tracks;

        Library(String key, String value, boolean tracks) {
            this.key = key;
            this.value = value;
            this.tracks = tracks;
        }

        JSONObject request() throws JSONException {
            JSONObject body = new JSONObject().put(key, value);
            if (this == History) body.put("params", "oggECgIIAQ%3D%3D");
            return body;
        }

        boolean accepts(Card card) {
            switch (this) {
                case Artists: return artistId(card.browseId);
                case Albums: return MusicApi.AlbumCredit.isBrowseId(card.browseId);
                case Playlists: return card.browseId.matches("VL[A-Za-z0-9_-]{2,190}");
                default: return card.track != null;
            }
        }
    }

    static final class Card {
        final String title, subtitle, image, browseId;
        final String playlistEntry;
        final MusicApi.Track track;

        Card(String title, String subtitle, String image, String browseId, MusicApi.Track track) {
            this(title, subtitle, image, browseId, track, "");
        }

        Card(String title, String subtitle, String image, String browseId, MusicApi.Track track, String playlistEntry) {
            this.title = title;
            this.subtitle = subtitle;
            this.image = image;
            this.browseId = browseId;
            this.track = track;
            this.playlistEntry = playlistEntry;
        }
    }

    static final class Shelf {
        final String title, cursor;
        final List<Card> cards;
        final boolean preferArtwork;
        final boolean manualOrder;

        Shelf(String title, List<Card> cards, boolean preferArtwork) {
            this(title, cards, preferArtwork, "");
        }

        Shelf(String title, List<Card> cards, boolean preferArtwork, String cursor) {
            this(title, cards, preferArtwork, cursor, false);
        }

        Shelf(String title, List<Card> cards, boolean preferArtwork, String cursor, boolean manualOrder) {
            this.title = title;
            this.cards = cards;
            this.preferArtwork = preferArtwork;
            this.cursor = cursor;
            this.manualOrder = manualOrder;
        }
    }

    static final class CollectionHeader {
        final String title, subtitle, image, description;
        final boolean artist;
        final String editablePlaylistId;

        CollectionHeader(String title, String subtitle, String image, String description, boolean artist) {
            this(title, subtitle, image, description, artist, "");
        }

        CollectionHeader(String title, String subtitle, String image, String description, boolean artist, String editablePlaylistId) {
            this.title = title;
            this.subtitle = subtitle;
            this.image = image;
            this.description = description;
            this.artist = artist;
            this.editablePlaylistId = editablePlaylistId;
        }
    }

    static CollectionHeader collectionHeader(JSONObject page, String browseId) {
        JSONObject root = child(page, "header");
        JSONObject editable = editablePlaylistHeader(page, browseId);
        JSONObject header = root.optJSONObject("musicImmersiveHeaderRenderer");
        if (header == null) header = root.optJSONObject("musicVisualHeaderRenderer");
        boolean artist = header != null || browseId.startsWith("UC");
        if (header == null) header = root.optJSONObject("musicDetailHeaderRenderer");
        if (header == null && editable != null) header = editable;
        if (header == null) header = find(page, "musicResponsiveHeaderRenderer", 0);
        if (header == null) return new CollectionHeader("", "", "", "", artist);
        String title = displayText(header.optJSONObject("title"));
        String subtitle = displayText(header.optJSONObject("subtitle"));
        if (artist && subtitle.isEmpty()) {
            JSONObject subscription = child(child(header, "subscriptionButton"), "subscribeButtonRenderer");
            subtitle = displayText(subscription.optJSONObject("subscriberCountText"));
        } else if (!artist) {
            String credits = displayText(header.optJSONObject("straplineTextOne"));
            if (!credits.isEmpty()) subtitle = subtitle.isEmpty() ? credits : credits + " · " + subtitle;
        }
        JSONObject image = child(header, "thumbnail");
        String art = image(image);
        if (art.isEmpty()) art = thumbnail(child(image, "croppedSquareThumbnailRenderer"));
        String description = displayText(header.optJSONObject("description"));
        if (description.isEmpty()) {
            JSONObject shelf = find(artist ? page : child(header, "description"), "musicDescriptionShelfRenderer", 0);
            if (shelf != null) description = displayText(shelf.optJSONObject("description"));
        }
        int limit = editable != null ? PlaylistEdits.DESCRIPTION_LIMIT : 3000;
        if (description.length() > limit) description = description.substring(0, limit - 1).trim() + "…";
        return new CollectionHeader(title, subtitle, art, description, artist, editable != null ? browseId.substring(2) : "");
    }

    private static JSONObject editablePlaylistHeader(JSONObject page, String browseId) {
        if (!browseId.startsWith("VL") || !PlaylistEdits.isPlaylistId(browseId.substring(2))) return null;
        JSONObject columns = child(child(page, "contents"), "twoColumnBrowseResultsRenderer");
        JSONObject tab = child(item(array(columns, "tabs"), 0), "tabRenderer");
        JSONObject section = child(child(tab, "content"), "sectionListRenderer");
        JSONObject editable = child(item(array(section, "contents"), 0), "musicEditablePlaylistDetailHeaderRenderer");
        String id = editable.optString("playlistId");
        if (!id.isEmpty() && !id.equals(browseId.substring(2))) return null;
        return child(editable, "header").optJSONObject("musicResponsiveHeaderRenderer");
    }

    static final class Mood {
        final String title, params;

        Mood(String title, String params) {
            this.title = title;
            this.params = params;
        }
    }

    static final class MoodGroup {
        final String title;
        final List<Mood> choices;

        MoodGroup(String title, List<Mood> choices) {
            this.title = title;
            this.choices = choices;
        }
    }

    static List<MoodGroup> genreGroups(JSONObject page) {
        List<MoodGroup> groups = new ArrayList<>();
        JSONObject sectionList = find(page, "sectionListRenderer", 0);
        if (sectionList == null) return groups;
        JSONArray sections = array(sectionList, "contents");
        for (int i = 0; i < Math.min(sections.length(), 16); i++) {
            JSONObject grid = child(item(sections, i), "gridRenderer");
            JSONObject header = child(child(grid, "header"), "gridHeaderRenderer");
            String title = displayText(header.optJSONObject("title"));
            if (title.isEmpty()) continue;
            List<Mood> choices = new ArrayList<>();
            JSONArray rows = array(grid, "items");
            for (int j = 0; j < Math.min(rows.length(), 48); j++) {
                JSONObject button = child(item(rows, j), "musicNavigationButtonRenderer");
                JSONObject route = child(child(button, "clickCommand"), "browseEndpoint");
                if (!"FEmusic_moods_and_genres_category".equals(route.optString("browseId"))) continue;
                String params = route.optString("params");
                String label = displayText(button.optJSONObject("buttonText"));
                if (!label.isEmpty() && params.matches("[A-Za-z0-9_+/=%-]{1,4096}")) choices.add(new Mood(label, params));
            }
            if (!choices.isEmpty()) groups.add(new MoodGroup(title, choices));
        }
        return groups;
    }

    static String genreTitle(JSONObject page) {
        return displayText(child(child(page, "header"), "musicHeaderRenderer").optJSONObject("title"));
    }

    static final class Suggestion {
        final String title;
        final Card card;

        Suggestion(String title, Card card) {
            this.title = title;
            this.card = card;
        }

        @Override public String toString() { return title; }
    }

    static List<Suggestion> searchSuggestions(JSONObject page, boolean hideExplicit) {
        List<Suggestion> terms = new ArrayList<>(), hits = new ArrayList<>();
        Set<String> seenTerms = new LinkedHashSet<>(), seenHits = new LinkedHashSet<>();
        JSONArray sections = array(page, "contents");
        for (int i = 0; i < Math.min(sections.length(), 6); i++) {
            JSONArray rows = array(child(item(sections, i), "searchSuggestionsSectionRenderer"), "contents");
            for (int j = 0; j < Math.min(rows.length(), 40); j++) {
                JSONObject row = item(rows, j);
                if (terms.size() < 5) {
                    String term = displayText(child(row, "searchSuggestionRenderer").optJSONObject("suggestion")).trim();
                    if (!term.isEmpty() && term.length() <= 120 && seenTerms.add(term)) terms.add(new Suggestion(term, null));
                }
                if (hits.size() >= 3 || !row.has("musicResponsiveListItemRenderer")) continue;
                Card result = card(row);
                if (result == null || (hideExplicit && result.track != null && result.track.explicit)) continue;
                if (result.track == null && !artistId(result.browseId) && !MusicApi.AlbumCredit.isBrowseId(result.browseId)) continue;
                String id = result.track == null ? result.browseId : result.track.id;
                if (seenHits.add(id)) hits.add(new Suggestion(result.title, result));
            }
        }
        terms.addAll(hits);
        return terms;
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
        JSONObject sections = find(page, "sectionListRenderer", 0);
        if (sections == null) sections = find(page, "sectionListContinuation", 0);
        return sections == null ? new ArrayList<>() : parseShelves(array(sections, "contents"), hideExplicit, 40);
    }

    static List<Shelf> collectionShelves(JSONObject page, boolean hideExplicit) {
        JSONObject columns = child(child(page, "contents"), "twoColumnBrowseResultsRenderer");
        JSONObject secondary = child(columns, "secondaryContents");
        JSONObject sections = secondary.optJSONObject("sectionListRenderer");
        if (sections != null && array(sections, "contents").length() > 0) {
            return parseShelves(array(sections, "contents"), hideExplicit, MAX_COLLECTION_TRACKS);
        }
        sections = find(page, "sectionListRenderer", 0);
        return sections == null ? new ArrayList<>() : parseShelves(array(sections, "contents"), hideExplicit, MAX_COLLECTION_TRACKS);
    }

    static Shelf libraryShelf(JSONObject page, Library tab, boolean hideExplicit) {
        JSONObject sections = find(page, "sectionListContinuation", 0);
        if (sections == null) sections = find(page, "sectionListRenderer", 0);
        JSONArray contents = array(sections == null ? new JSONObject() : sections, "contents");
        List<Card> cards = new ArrayList<>();
        String next = "";
        for (int i = 0; i < Math.min(contents.length(), 32) && cards.size() < MAX_COLLECTION_TRACKS; i++) {
            JSONObject section = item(contents, i);
            JSONObject grid = section.optJSONObject("gridRenderer");
            JSONObject shelf = grid != null ? grid : section.optJSONObject("musicShelfRenderer");
            if (shelf == null) continue;
            JSONArray rows = array(shelf, grid != null ? "items" : "contents");
            cards.addAll(libraryCards(rows, tab, hideExplicit, MAX_COLLECTION_TRACKS - cards.size()));
            if (next.isEmpty() && tab != Library.History) next = cursor(shelf, rows);
        }
        return uniqueLibrary(tab, new Shelf(tab.name(), cards, !tab.tracks, next));
    }

    static Shelf libraryPage(JSONObject response, Library tab, boolean hideExplicit) {
        JSONObject container = find(response, "appendContinuationItemsAction", 0);
        JSONArray rows = container == null ? null : container.optJSONArray("continuationItems");
        if (rows == null) {
            JSONObject continuation = child(response, "continuationContents");
            container = continuation.optJSONObject("gridContinuation");
            if (container == null) container = continuation.optJSONObject("musicShelfContinuation");
            if (container == null) container = continuation.optJSONObject("musicPlaylistShelfContinuation");
            if (container == null) return null;
            rows = container.optJSONArray("items");
            if (rows == null) rows = container.optJSONArray("contents");
            if (rows == null) return null;
        }
        return new Shelf(tab.name(), libraryCards(rows, tab, hideExplicit, MAX_COLLECTION_TRACKS),
                !tab.tracks, cursor(container, rows));
    }

    static Shelf appendLibraryPage(Library tab, Shelf current, Shelf next) {
        List<Card> cards = new ArrayList<>(current.cards);
        cards.addAll(next.cards);
        Shelf merged = uniqueLibrary(tab, new Shelf(current.title, cards, current.preferArtwork, next.cursor));
        if (merged.cards.size() == current.cards.size() || current.cursor.equals(next.cursor)) {
            return new Shelf(merged.title, merged.cards, merged.preferArtwork);
        }
        return merged;
    }

    private static List<Card> libraryCards(JSONArray rows, Library tab, boolean hideExplicit, int limit) {
        List<Card> cards = new ArrayList<>();
        for (int i = 0; i < Math.min(rows.length(), MAX_COLLECTION_TRACKS * 2) && cards.size() < limit; i++) {
            Card card = card(item(rows, i));
            if (card != null && tab.accepts(card) && !(hideExplicit && card.track != null && card.track.explicit)) cards.add(card);
        }
        return cards;
    }

    private static Shelf uniqueLibrary(Library tab, Shelf shelf) {
        Set<String> seen = new LinkedHashSet<>();
        List<Card> unique = new ArrayList<>();
        for (Card card : shelf.cards) {
            if (seen.add(tab.tracks ? card.track.id : card.browseId)) unique.add(card);
            if (unique.size() >= MAX_COLLECTION_TRACKS) break;
        }
        return new Shelf(shelf.title, unique, shelf.preferArtwork, unique.size() == MAX_COLLECTION_TRACKS ? "" : shelf.cursor);
    }

    static List<Shelf> artistShelves(List<Shelf> original, String currentArtist, boolean releases) {
        List<Shelf> filtered = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Shelf shelf : original) {
            List<Card> cards = new ArrayList<>();
            for (Card card : shelf.cards) {
                String id = card.browseId;
                boolean valid = releases ? MusicApi.AlbumCredit.isBrowseId(id) : artistId(id) && !id.equals(currentArtist);
                if (valid && seen.add(id)) cards.add(card);
            }
            if (!cards.isEmpty()) filtered.add(new Shelf(shelf.title, cards, true));
        }
        return filtered;
    }

    static boolean artistId(String id) { return id.matches("UC[A-Za-z0-9_-]{22}"); }

    static Shelf playlistPage(JSONObject response, boolean hideExplicit) {
        JSONObject appended = find(response, "appendContinuationItemsAction", 0);
        JSONArray rows = appended == null ? null : appended.optJSONArray("continuationItems");
        JSONObject container = appended;
        if (rows == null) {
            JSONObject continuation = child(response, "continuationContents");
            container = continuation.optJSONObject("musicPlaylistShelfContinuation");
            if (container == null) container = continuation.optJSONObject("musicShelfContinuation");
            if (container == null) return null;
            rows = container.optJSONArray("contents");
            if (rows == null) return null;
        }
        return new Shelf("", readCards(rows, hideExplicit, MAX_COLLECTION_TRACKS), false, cursor(container, rows));
    }

    static Shelf appendPlaylistPage(Shelf current, Shelf next) {
        int remaining = MAX_COLLECTION_TRACKS - current.cards.size();
        List<Card> cards = new ArrayList<>(current.cards);
        if (remaining > 0) cards.addAll(next.cards.subList(0, Math.min(remaining, next.cards.size())));
        String cursor = cards.size() < MAX_COLLECTION_TRACKS && !current.cursor.equals(next.cursor)
                ? next.cursor : "";
        return new Shelf(current.title, cards, current.preferArtwork, cursor, current.manualOrder);
    }

    private static String cursor(JSONObject shelf, JSONArray rows) {
        JSONObject token = child(item(array(shelf, "continuations"), 0), "nextContinuationData");
        String value = token.optString("continuation");
        if (value.isEmpty() && rows.length() > 0) {
            JSONObject tail = child(item(rows, rows.length() - 1), "continuationItemRenderer");
            value = child(child(tail, "continuationEndpoint"), "continuationCommand").optString("token");
        }
        return value.length() <= 4096 ? value : "";
    }

    static String collectionLink(String id) {
        if (id.matches("UC[A-Za-z0-9_-]{22}")) return MusicApi.MUSIC + "/channel/" + id;
        if (id.matches("VL[A-Za-z0-9_-]{2,190}")) return MusicApi.MUSIC + "/playlist?list=" + id.substring(2);
        if (id.matches("(MPREb|OLAK5uy)[A-Za-z0-9_-]{2,190}")) return MusicApi.MUSIC + "/browse/" + id;
        return "";
    }

    private static List<Shelf> parseShelves(JSONArray rows, boolean hideExplicit, int listLimit) {
        List<Shelf> out = new ArrayList<>();
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
            int limit = list != null ? listLimit : 40;
            List<Card> cards = readCards(contents, hideExplicit, limit);
            int specifiedRows = renderer.optInt("numItemsPerColumn", 0);
            String display = renderer.optString("itemSize");
            boolean artwork = grid != null || specifiedRows == 1 || display.contains("LARGE");
            for (int j = 0; j < contents.length() && !artwork; j++) {
                artwork = item(contents, j).has("musicTwoRowItemRenderer");
            }
            String next = list != null && listLimit == MAX_COLLECTION_TRACKS && cards.size() < limit
                    ? cursor(renderer, contents) : "";
            if (!cards.isEmpty() || !next.isEmpty()) out.add(new Shelf(title, cards, artwork, next, list != null && manualOrder(list)));
        }
        return out;
    }

    private static boolean manualOrder(JSONObject shelf) {
        JSONObject header = child(child(shelf, "header"), "musicSideAlignedItemRenderer");
        JSONObject menu = child(item(array(header, "startItems"), 0), "sortFilterSubMenuRenderer");
        JSONArray items = array(menu, "subMenuItems");
        for (int i = 0; i < items.length(); i++) {
            JSONObject choice = item(items, i);
            if (!choice.optBoolean("selected")) continue;
            JSONObject endpoint = child(child(choice, "serviceEndpoint"), "playlistEditEndpoint");
            if (item(array(endpoint, "actions"), 0).optInt("playlistVideoOrder", -1) == 0) return true;
        }
        return false;
    }

    static String moveSuccessor(Shelf shelf, Card card, int direction) {
        if (!shelf.manualOrder || card.playlistEntry.isEmpty() || Math.abs(direction) != 1) return null;
        int index = shelf.cards.indexOf(card);
        if (index < 0 || index + direction < 0 || index + direction >= shelf.cards.size()) return null;
        int successorIndex = direction < 0 ? index - 1 : index + 2;
        if (successorIndex == shelf.cards.size()) return null;
        String successor = shelf.cards.get(successorIndex).playlistEntry;
        return successor.isEmpty() || successor.equals(card.playlistEntry) ? null : successor;
    }

    private static List<Card> readCards(JSONArray contents, boolean hideExplicit, int limit) {
        List<Card> cards = new ArrayList<>();
        for (int j = 0; j < contents.length() && cards.size() < limit; j++) {
            Card card = card(item(contents, j));
            if (card != null && !(hideExplicit && card.track != null && card.track.explicit)) cards.add(card);
        }
        return cards;
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
            if (track != null) {
                String entryId = child(row, "playlistItemData").optString("playlistSetVideoId");
                return new Card(track.title, track.artist, art, "", track, PlaylistEdits.isEntryId(entryId) ? entryId : "");
            }
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
