package com.serena.syncora.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** The host's play queue with shuffle and repeat (off / loop the queue / repeat one). */
public final class PlayQueue {
    public enum Repeat {
        OFF("off"), ALL("all"), ONE("one");

        public final String id;

        Repeat(String id) {
            this.id = id;
        }

        public Repeat next() {
            switch (this) {
                case OFF:
                    return ALL;
                case ALL:
                    return ONE;
                default:
                    return OFF;
            }
        }

        public static Repeat fromId(String id) {
            for (Repeat r : values()) if (r.id.equals(id)) return r;
            return OFF;
        }
    }

    private final List<Track> tracks = new ArrayList<>();
    private int[] order = new int[0];
    private int cursor = -1;
    private boolean shuffle;
    private Repeat repeat = Repeat.OFF;
    private final Random random;

    public PlayQueue() {
        this(new Random());
    }

    public PlayQueue(Random random) {
        this.random = random;
    }

    /** Replaces the queue. {@code startIndex} refers to {@code list}; it becomes the current track. */
    public synchronized void setTracks(List<Track> list, int startIndex) {
        tracks.clear();
        tracks.addAll(list);
        int n = tracks.size();
        order = new int[n];
        for (int i = 0; i < n; i++) order[i] = i;
        if (n == 0) {
            cursor = -1;
            return;
        }
        int first = Math.max(0, Math.min(startIndex, n - 1));
        if (shuffle) {
            shuffleOrder(first);
            cursor = 0;
        } else {
            cursor = first;
        }
    }

    public synchronized void setShuffle(boolean on) {
        if (on == shuffle) return;
        shuffle = on;
        int n = tracks.size();
        if (n == 0) return;
        int currentIndex = cursor >= 0 ? order[cursor] : 0;
        if (on) {
            shuffleOrder(currentIndex);
            cursor = 0;
        } else {
            for (int i = 0; i < n; i++) order[i] = i;
            cursor = currentIndex;
        }
    }

    public synchronized boolean isShuffle() {
        return shuffle;
    }

    public synchronized void setRepeat(Repeat mode) {
        repeat = mode == null ? Repeat.OFF : mode;
    }

    public synchronized Repeat getRepeat() {
        return repeat;
    }

    public synchronized int size() {
        return tracks.size();
    }

    public synchronized boolean isEmpty() {
        return tracks.isEmpty();
    }

    public synchronized Track current() {
        return cursor < 0 || cursor >= order.length ? null : tracks.get(order[cursor]);
    }

    /** Position of the current track inside {@link #playOrder()}; -1 when empty. */
    public synchronized int cursor() {
        return cursor;
    }

    /** Tracks in the order they will be played. */
    public synchronized List<Track> playOrder() {
        List<Track> out = new ArrayList<>(order.length);
        for (int index : order) out.add(tracks.get(index));
        return out;
    }

    /** Jumps to a position of {@link #playOrder()}. */
    public synchronized Track jumpTo(int playOrderIndex) {
        if (playOrderIndex < 0 || playOrderIndex >= order.length) return current();
        cursor = playOrderIndex;
        return current();
    }

    /** User pressed "next": always moves on, wrapping to the start of the queue. */
    public synchronized Track next() {
        if (tracks.isEmpty()) return null;
        if (cursor + 1 < order.length) {
            cursor++;
        } else {
            wrap();
        }
        return current();
    }

    /** User pressed "previous" (when the song just started): moves back one track, wrapping to the end. */
    public synchronized Track previous() {
        if (tracks.isEmpty()) return null;
        cursor = cursor > 0 ? cursor - 1 : order.length - 1;
        return current();
    }

    /** The current track finished by itself. Returns what to play next, or null when playback should stop. */
    public synchronized Track onTrackEnded() {
        if (tracks.isEmpty()) return null;
        if (repeat == Repeat.ONE) return current();
        if (cursor + 1 < order.length) {
            cursor++;
            return current();
        }
        if (repeat == Repeat.ALL) {
            wrap();
            return current();
        }
        return null;
    }

    /** Peek at the track {@link #onTrackEnded()} would pick (used to prefetch); does not move. */
    public synchronized Track peekAfterEnd() {
        if (tracks.isEmpty()) return null;
        if (repeat == Repeat.ONE) return current();
        if (cursor + 1 < order.length) return tracks.get(order[cursor + 1]);
        if (repeat == Repeat.ALL) return tracks.get(order[0]);
        return null;
    }

    private void wrap() {
        int n = order.length;
        if (shuffle && n > 1) {
            int last = order[cursor];
            shuffleOrder(-1);
            if (order[0] == last) {
                int swap = 1 + random.nextInt(n - 1);
                int t = order[0];
                order[0] = order[swap];
                order[swap] = t;
            }
        }
        cursor = 0;
    }

    /** Fisher-Yates; when {@code first >= 0} that track is pinned to the front. */
    private void shuffleOrder(int first) {
        int n = order.length;
        for (int i = 0; i < n; i++) order[i] = i;
        for (int i = n - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int t = order[i];
            order[i] = order[j];
            order[j] = t;
        }
        if (first >= 0) {
            for (int i = 0; i < n; i++) {
                if (order[i] == first) {
                    int t = order[0];
                    order[0] = order[i];
                    order[i] = t;
                    break;
                }
            }
        }
    }
}
