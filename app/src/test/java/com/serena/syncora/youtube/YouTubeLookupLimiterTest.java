package com.serena.syncora.youtube;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class YouTubeLookupLimiterTest {

    private static final class FakeClock implements YouTubeLookupLimiter.Clock {
        long now;

        @Override
        public long nowMillis() {
            return now;
        }
    }

    @Test
    public void onlyOneLookupCanRunAndASecondMustWaitForTheCooldown() {
        FakeClock clock = new FakeClock();
        YouTubeLookupLimiter limiter = new YouTubeLookupLimiter(2000, clock);

        assertEquals(0, limiter.tryAcquire());
        assertTrue(limiter.isInFlight());
        assertEquals(-1, limiter.tryAcquire());
        limiter.release();
        assertEquals(2000, limiter.tryAcquire());

        clock.now = 1999;
        assertEquals(1, limiter.tryAcquire());
        clock.now = 2000;
        assertEquals(0, limiter.tryAcquire());
        limiter.release();
        assertFalse(limiter.isInFlight());
    }

    @Test
    public void aSlowRequestHasAlreadyUsedItsCooldownWhenItFinishes() {
        FakeClock clock = new FakeClock();
        YouTubeLookupLimiter limiter = new YouTubeLookupLimiter(1000, clock);
        assertEquals(0, limiter.tryAcquire());
        clock.now = 1500;
        limiter.release();
        assertEquals(0, limiter.remainingMillis());
        assertEquals(0, limiter.tryAcquire());
        limiter.release();
    }
}
