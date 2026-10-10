package com.serena.syncora.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.Test;

public class PlayQueueTest {
    private static List<Track> tracks(int n) {
        List<Track> list = new ArrayList<>();
        for (int i = 0; i < n; i++) list.add(new Track(i, "content://t/" + i, "Song " + i, "Artist", "Album", 1, 1000));
        return list;
    }

    @Test
    public void plainOrderStartsAtRequestedTrack() {
        PlayQueue q = new PlayQueue(new Random(1));
        q.setTracks(tracks(5), 2);
        assertEquals(2, q.current().id);
        assertEquals(3, q.next().id);
        assertEquals(4, q.next().id);
        assertEquals(0, q.next().id);
        assertEquals(4, q.previous().id);
    }

    @Test
    public void onTrackEndedStopsAtEndWhenRepeatOff() {
        PlayQueue q = new PlayQueue(new Random(1));
        q.setTracks(tracks(3), 1);
        assertEquals(2, q.onTrackEnded().id);
        assertNull(q.onTrackEnded());
    }

    @Test
    public void loopAllWrapsAround() {
        PlayQueue q = new PlayQueue(new Random(1));
        q.setRepeat(PlayQueue.Repeat.ALL);
        q.setTracks(tracks(3), 2);
        assertEquals(0, q.onTrackEnded().id);
        assertEquals(1, q.onTrackEnded().id);
        assertEquals(2, q.onTrackEnded().id);
        assertEquals(0, q.onTrackEnded().id);
    }

    @Test
    public void repeatOneKeepsPlayingTheSameTrack() {
        PlayQueue q = new PlayQueue(new Random(1));
        q.setRepeat(PlayQueue.Repeat.ONE);
        q.setTracks(tracks(4), 1);
        for (int i = 0; i < 5; i++) assertEquals(1, q.onTrackEnded().id);
        // the user can still skip manually
        assertEquals(2, q.next().id);
    }

    @Test
    public void shufflePlaysEveryTrackOnceStartingWithCurrent() {
        PlayQueue q = new PlayQueue(new Random(7));
        q.setShuffle(true);
        q.setTracks(tracks(20), 5);
        assertEquals(5, q.current().id);
        Set<Long> seen = new HashSet<>();
        seen.add(q.current().id);
        for (int i = 1; i < 20; i++) {
            Track t = q.onTrackEnded();
            assertTrue("track " + i, t != null);
            seen.add(t.id);
        }
        assertEquals(20, seen.size());
        assertNull(q.onTrackEnded());
    }

    @Test
    public void togglingShuffleKeepsCurrentTrackAndRestoresOrder() {
        PlayQueue q = new PlayQueue(new Random(3));
        q.setTracks(tracks(10), 4);
        q.setShuffle(true);
        assertEquals(4, q.current().id);
        assertEquals(0, q.cursor());
        q.setShuffle(false);
        assertEquals(4, q.current().id);
        assertEquals(4, q.cursor());
        assertEquals(5, q.next().id);
    }

    @Test
    public void shuffledLoopDoesNotRepeatLastTrackFirst() {
        PlayQueue q = new PlayQueue(new Random(11));
        q.setShuffle(true);
        q.setRepeat(PlayQueue.Repeat.ALL);
        q.setTracks(tracks(6), 0);
        for (int round = 0; round < 30; round++) {
            long last = -1;
            for (int i = 0; i < 6; i++) {
                last = q.current().id;
                if (i < 5) q.onTrackEnded();
            }
            Track first = q.onTrackEnded();
            assertTrue("new round must not begin with the track that just played", first.id != last);
        }
    }

    @Test
    public void jumpToAndEmptyQueue() {
        PlayQueue q = new PlayQueue(new Random(1));
        assertNull(q.current());
        assertNull(q.next());
        assertNull(q.previous());
        assertNull(q.onTrackEnded());
        q.setTracks(tracks(5), 0);
        assertEquals(3, q.jumpTo(3).id);
        assertEquals(3, q.current().id);
        assertEquals(3, q.jumpTo(99).id);
    }

    @Test
    public void repeatModeCycles() {
        assertEquals(PlayQueue.Repeat.ALL, PlayQueue.Repeat.OFF.next());
        assertEquals(PlayQueue.Repeat.ONE, PlayQueue.Repeat.ALL.next());
        assertEquals(PlayQueue.Repeat.OFF, PlayQueue.Repeat.ONE.next());
        assertEquals(PlayQueue.Repeat.ONE, PlayQueue.Repeat.fromId("one"));
    }
}
