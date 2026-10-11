package com.serena.syncora.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Random;
import org.junit.Test;

public class ClockSyncTest {
    /** Simulates one ping/pong with the given one-way delays. */
    private static void ping(ClockSync sync, long trueOffset, long localNow, long up, long down, long hostProcessing) {
        long t0 = localNow;
        long t1 = t0 + up + trueOffset;
        long t2 = t1 + hostProcessing;
        long t3 = t2 - trueOffset + down;
        sync.addSample(t0, t1, t2, t3);
    }

    @Test
    public void symmetricDelayGivesExactOffset() {
        ClockSync sync = new ClockSync();
        ping(sync, 5_000_000_000L, 1_000_000_000L, 3_000_000L, 3_000_000L, 50_000L);
        assertEquals(5_000_000_000L, sync.offsetNanos(), 1000);
        assertTrue(sync.hasEstimate());
        assertTrue(sync.hasMeasurement());
    }

    @Test
    public void jitterIsFilteredByPreferringTheFastestRoundTrips() {
        ClockSync sync = new ClockSync();
        Random random = new Random(5);
        long trueOffset = -73_456_789_012L;
        long now = 10_000_000_000L;
        for (int i = 0; i < 40; i++) {
            // most samples have large, asymmetric delays; a few are clean
            boolean clean = i % 7 == 0;
            long up = clean ? 2_000_000L : 2_000_000L + random.nextInt(60_000_000);
            long down = clean ? 2_000_000L : 2_000_000L + random.nextInt(60_000_000);
            ping(sync, trueOffset, now, up, down, 20_000L);
            now += 100_000_000L;
        }
        assertEquals(trueOffset, sync.offsetNanos(), 2_000_000.0);
    }

    @Test
    public void seedIsReplacedByRealMeasurement() {
        ClockSync sync = new ClockSync();
        sync.seedEstimate(123);
        assertTrue(sync.hasEstimate());
        assertFalse(sync.hasMeasurement());
        ping(sync, 1_000_000_000L, 0, 1_000_000L, 1_000_000L, 0);
        assertEquals(1_000_000_000L, sync.offsetNanos(), 1000);
        sync.seedEstimate(5); // ignored once measured
        assertEquals(1_000_000_000L, sync.offsetNanos(), 1000);
    }

    @Test
    public void largeStepIsFollowedImmediatelyAndSmallDriftIsSmoothed() {
        ClockSync sync = new ClockSync();
        ping(sync, 0, 0, 1_000_000L, 1_000_000L, 0);
        // device slept: offset jumps by 2 seconds, old samples are dropped
        sync.restartSampling();
        ping(sync, 2_000_000_000L, 1_000_000_000L, 1_000_000L, 1_000_000L, 0);
        assertEquals(2_000_000_000L, sync.offsetNanos(), 1000);
        // tiny drift: 6 ms, new best sample is moved towards slowly
        sync.restartSampling();
        ping(sync, 2_006_000_000L, 2_000_000_000L, 1_000_000L, 1_000_000L, 0);
        assertEquals(2_002_000_000L, sync.offsetNanos(), 10_000.0);
    }

    @Test
    public void reportsAConservativeOffsetUncertaintyFromTheBestRtt() {
        ClockSync sync = new ClockSync();
        sync.addSample(100, 110, 112, 122);
        assertEquals(0L, sync.offsetNanos());
        assertEquals(10L, sync.uncertaintyNanos());
        assertEquals(20L, sync.bestRttNanos());
    }

    @Test
    public void negativeRoundTripIsIgnored() {
        ClockSync sync = new ClockSync();
        sync.addSample(100, 0, 0, 50); // reply 'arrives' before the request was sent: nonsense
        assertFalse(sync.hasEstimate());
    }
}
