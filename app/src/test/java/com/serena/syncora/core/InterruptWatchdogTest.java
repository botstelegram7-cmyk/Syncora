package com.serena.syncora.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class InterruptWatchdogTest {

    @Test
    public void interruptionMutesAndWaitsBeforeTheFirstAttempt() {
        InterruptWatchdog watchdog = new InterruptWatchdog();
        watchdog.onInterrupt(10_000L);
        assertTrue(watchdog.isInterrupted());
        assertTrue(watchdog.isRetrying());
        assertFalse("must not hammer the audio focus immediately", watchdog.shouldRetry(11_000L));
        assertTrue(watchdog.shouldRetry(11_500L));
    }

    @Test
    public void keepsTryingUntilTheFocusComesBack() {
        InterruptWatchdog watchdog = new InterruptWatchdog();
        watchdog.onInterrupt(0L);
        long now = 0L;
        for (int i = 0; i < 4; i++) {
            now += InterruptWatchdog.RETRY_MS;
            assertTrue(watchdog.shouldRetry(now));
            watchdog.onAttempt(false, now);
            assertTrue("still recovering after " + (i + 1) + " failed attempts", watchdog.isRetrying());
            assertTrue(watchdog.isInterrupted());
        }
        now += InterruptWatchdog.RETRY_MS;
        watchdog.onAttempt(true, now);
        assertFalse(watchdog.isInterrupted());
        assertFalse(watchdog.isRetrying());
        assertFalse("no more attempts once the focus is back", watchdog.shouldRetry(now + 60_000L));
    }

    @Test
    public void gainingTheFocusBackCancelsEverything() {
        InterruptWatchdog watchdog = new InterruptWatchdog();
        watchdog.onInterrupt(0L);
        watchdog.onFocusGained();
        assertFalse(watchdog.isInterrupted());
        assertFalse(watchdog.isRetrying());
        assertFalse(watchdog.shouldRetry(1_000_000L));
    }

    @Test
    public void givesUpAfterTheBudgetAndAsksTheUser() {
        InterruptWatchdog watchdog = new InterruptWatchdog();
        watchdog.onInterrupt(0L);
        long now = 0L;
        for (int i = 0; i < InterruptWatchdog.MAX_ATTEMPTS; i++) {
            now += InterruptWatchdog.RETRY_MS;
            assertTrue(watchdog.shouldRetry(now));
            watchdog.onAttempt(false, now);
        }
        assertTrue("the badge stays until the user acts", watchdog.isInterrupted());
        assertFalse(watchdog.isRetrying());
        assertFalse(watchdog.shouldRetry(now + 60_000L));
    }

    @Test
    public void waitingOutACallDoesNotSpendAttempts() {
        InterruptWatchdog watchdog = new InterruptWatchdog();
        watchdog.onInterrupt(0L);
        long now = 0L;
        // a long call: every look is deferred, no attempt is used up
        for (int i = 0; i < 40; i++) {
            now += InterruptWatchdog.RETRY_MS;
            assertTrue(watchdog.shouldRetry(now));
            watchdog.defer(now);
            assertTrue(watchdog.isRetrying());
        }
        now += InterruptWatchdog.RETRY_MS;
        // the call is over: the full budget is still available
        for (int i = 0; i < InterruptWatchdog.MAX_ATTEMPTS - 1; i++) {
            watchdog.onAttempt(false, now);
            now += InterruptWatchdog.RETRY_MS;
            assertTrue("attempt budget was consumed while waiting", watchdog.isRetrying());
        }
        watchdog.onAttempt(false, now);
        assertFalse(watchdog.isRetrying());
        assertTrue(watchdog.isInterrupted());
    }

    @Test
    public void unpluggedHeadphonesShowTheBadgeButWaitForTheUser() {
        InterruptWatchdog watchdog = new InterruptWatchdog();
        watchdog.onManualInterrupt();
        assertTrue(watchdog.isInterrupted());
        assertFalse("must not start playing out of the phone speaker", watchdog.isRetrying());
        assertFalse(watchdog.shouldRetry(1_000_000L));
        watchdog.reset();
        assertFalse(watchdog.isInterrupted());
    }

    @Test
    public void aNewInterruptionRestartsTheBudget() {
        InterruptWatchdog watchdog = new InterruptWatchdog();
        watchdog.onInterrupt(0L);
        for (int i = 0; i < InterruptWatchdog.MAX_ATTEMPTS; i++) watchdog.onAttempt(false, i * InterruptWatchdog.RETRY_MS);
        assertFalse(watchdog.isRetrying());
        watchdog.onInterrupt(500_000L);
        assertTrue(watchdog.isRetrying());
        assertFalse(watchdog.shouldRetry(500_000L));
        assertTrue(watchdog.shouldRetry(500_000L + InterruptWatchdog.RETRY_MS));
    }
}
