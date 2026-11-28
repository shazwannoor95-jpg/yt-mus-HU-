// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;

/** Artist-level adaptation of the dithered merge in ytm-rs src/ui/shuffle.rs. */
final class SmartShuffle {
    static void spread(List<MusicApi.Track> tracks, MusicApi.Track previous, boolean hideExplicit, Random random) {
        Map<String, Bucket> artists = new LinkedHashMap<>();
        List<Bucket> groups = new ArrayList<>();
        List<MusicApi.Track> hidden = new ArrayList<>();
        int remaining = 0;
        for (MusicApi.Track track : tracks) {
            if (hideExplicit && track.explicit) {
                hidden.add(track);
                continue;
            }
            String key = artist(track);
            Bucket group = key.isEmpty() ? null : artists.get(key);
            if (group == null) {
                group = new Bucket(random.nextDouble());
                if (!key.isEmpty()) artists.put(key, group);
                groups.add(group);
            }
            group.tracks.add(track);
            remaining++;
        }
        Bucket previousGroup = previous == null ? null : artists.get(artist(previous));
        PriorityQueue<Place> places = new PriorityQueue<>(Comparator.comparingDouble(place -> place.time));
        PriorityQueue<Place> counts = new PriorityQueue<>((left, right) -> Integer.compare(right.left, left.left));
        int maxRun = 1;
        for (Bucket group : groups) {
            Collections.shuffle(group.tracks, random);
            group.total = group.tracks.size();
            int gaps = remaining - group.total + 1;
            maxRun = Math.max(maxRun, (group.total + (group == previousGroup ? 1 : 0) + gaps - 1) / gaps);
            offer(group, random, places, counts);
        }
        tracks.clear();
        while (remaining > 0) {
            discardStale(counts);
            discardStale(places);
            Place largest = counts.peek();
            Bucket next;
            if (largest.left > (long) maxRun * (remaining - largest.left)) {
                next = largest.group;
            } else {
                Place first = places.remove();
                discardStale(places);
                if (first.group == previousGroup && !places.isEmpty()) {
                    next = places.remove().group;
                    places.add(first);
                } else next = first.group;
            }
            tracks.add(next.tracks.get(next.taken));
            next.taken++;
            remaining--;
            previousGroup = next;
            if (next.left() > 0) offer(next, random, places, counts);
        }
        tracks.addAll(hidden);
    }

    private static String artist(MusicApi.Track track) {
        String name = track.artists.isEmpty() ? track.artist : track.artists.get(0).name;
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static void offer(Bucket group, Random random, PriorityQueue<Place> places, PriorityQueue<Place> counts) {
        double time = (group.taken + group.start + (random.nextDouble() - 0.5) * 0.2) / group.total;
        Place place = new Place(group, time);
        places.add(place);
        counts.add(place);
    }

    private static void discardStale(PriorityQueue<Place> heap) {
        while (!heap.isEmpty() && heap.peek().left != heap.peek().group.left()) heap.remove();
    }

    private static final class Bucket {
        final List<MusicApi.Track> tracks = new ArrayList<>();
        final double start;
        int total, taken;

        Bucket(double start) { this.start = start; }
        int left() { return total - taken; }
    }

    private static final class Place {
        final Bucket group;
        final double time;
        final int left;

        Place(Bucket group, double time) {
            this.group = group;
            this.time = time;
            left = group.left();
        }
    }
}
