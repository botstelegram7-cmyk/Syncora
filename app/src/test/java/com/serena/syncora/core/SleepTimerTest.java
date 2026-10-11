package com.serena.syncora.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SleepTimerTest {
    @Test
    public void countdownUsesDeadlineAndExpiresOnlyOnce() {
        SleepTimer timer = new SleepTimer();
        timer.startAfter(10_000L, 90_000L);
        assertEquals(SleepTimer.Kind.COUNTDOWN, timer.kind());
        assertEquals(90_000L, timer.remainingMs(10_000L));
        assertFalse(timer.consumeDeadline(99_999L));
        assertTrue(timer.consumeDeadline(100_000L));
        assertFalse(timer.consumeDeadline(200_000L));
        assertEquals(SleepTimer.Kind.OFF, timer.kind());
    }

    @Test
    public void restoreDoesNotExtendAnExpiredTimer() {
        SleepTimer timer = new SleepTimer();
        timer.restore(50_000L, false);
        assertEquals(0L, timer.remainingMs(90_000L));
        assertTrue(timer.isDue(50_000L));
        assertTrue(timer.consumeDeadline(90_000L));
    }

    @Test
    public void endOfTrackWaitsForARealBoundaryAndCanBeCancelled() {
        SleepTimer timer = new SleepTimer();
        timer.endAtTrackBoundary();
        assertEquals(SleepTimer.Kind.END_OF_TRACK, timer.kind());
        assertEquals(0L, timer.remainingMs(123_456L));
        assertTrue(timer.consumeTrackBoundary());
        assertFalse(timer.consumeTrackBoundary());
        timer.endAtTrackBoundary();
        timer.cancel();
        assertFalse(timer.active());
    }

    @Test
    public void countdownOverflowIsCappedInsteadOfWrappingIntoThePast() {
        SleepTimer timer = new SleepTimer();
        timer.startAfter(Long.MAX_VALUE - 1, 100);
        assertEquals(Long.MAX_VALUE, timer.deadlineWallTimeMs());
        assertEquals(0L, timer.remainingMs(Long.MAX_VALUE));
    }
}
