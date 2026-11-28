// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import java.text.Collator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class CollectionTracks {
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    enum Order {
        ORIGINAL("Original order"), TITLE("Title"), ARTIST("Artist");

        final String label;

        Order(String label) { this.label = label; }

        static Order saved(String name) {
            for (Order value : values()) if (value.name().equals(name)) return value;
            return ORIGINAL;
        }
    }

    static List<Browse.Card> visible(List<Browse.Card> cards, String filter, Order order) {
        String needle = normalized(filter.trim());
        List<Browse.Card> result = new ArrayList<>(cards.size());
        for (Browse.Card card : cards) {
            if (card.track != null && (needle.isEmpty()
                    || normalized(card.track.title + " " + card.track.artist).contains(needle))) result.add(card);
        }
        if (order != Order.ORIGINAL) {
            Collator sort = Collator.getInstance();
            sort.setStrength(Collator.PRIMARY);
            result.sort((left, right) -> {
                if (order == Order.ARTIST) {
                    int group = sort.compare(left.track.artist, right.track.artist);
                    if (group != 0) return group;
                }
                return sort.compare(left.track.title, right.track.title);
            });
        }
        return result;
    }

    private static String normalized(String text) {
        return MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFKD))
                .replaceAll("").toLowerCase(Locale.ROOT);
    }
}
