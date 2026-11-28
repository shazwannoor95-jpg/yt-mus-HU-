// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import java.util.ArrayList;
import java.util.List;

/** Bounded in-memory navigation history for public browse destinations. */
final class BrowseHistory {
    private static final int LIMIT = 16;
    private final List<Route> routes = new ArrayList<>();
    private int index = -1;

    static final class Route {
        final String page, id, title, query, mood;
        final Browse.Search scope;
        int row, offset;

        private Route(String page, String id, String title, String query, String mood, Browse.Search scope) {
            this.page = page;
            this.id = id;
            this.title = title;
            this.query = query;
            this.mood = mood;
            this.scope = scope;
        }

        static Route publicRoute(String page, String id, String title, String query, String mood, Browse.Search scope) {
            switch (page) {
                case "Home": return new Route(page, "", "", "", mood, Browse.Search.All);
                case "Explore": return new Route(page, "", "", "", "", Browse.Search.All);
                case "Search": return new Route(page, "", "", query, "", scope);
                case "Discography": case "Related artists":
                    return Browse.artistId(id) ? new Route(page, id, title, "", "", Browse.Search.All) : null;
                case "Collection":
                    if (Browse.artistId(id) || MusicApi.AlbumCredit.isBrowseId(id)) {
                        return new Route(page, id, title, "", "", Browse.Search.All);
                    }
                    return null;
                default: return null;
            }
        }

        boolean sameDestination(Route other) {
            return page.equals(other.page) && id.equals(other.id) && query.equals(other.query)
                    && mood.equals(other.mood) && scope == other.scope;
        }
    }

    Route current() { return index < 0 ? null : routes.get(index); }

    void visit(Route route) {
        if (route == null) {
            routes.clear();
            index = -1;
            return;
        }
        Route current = current();
        if (current != null && current.sameDestination(route)) return;
        routes.subList(index + 1, routes.size()).clear();
        routes.add(route);
        if (routes.size() > LIMIT) routes.remove(0);
        index = routes.size() - 1;
    }

    void capture(int row, int offset) {
        Route current = current();
        if (current == null) return;
        current.row = row;
        current.offset = offset;
    }

    boolean canMove(int delta) {
        return index >= 0 && index + delta >= 0 && index + delta < routes.size();
    }

    Route move(int delta) {
        if (!canMove(delta)) return null;
        index += delta;
        return routes.get(index);
    }
}
