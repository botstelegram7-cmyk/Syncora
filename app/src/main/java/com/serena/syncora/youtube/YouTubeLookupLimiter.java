package com.serena.syncora.youtube;

/**
 * A small process-wide gate for YouTube lookups. It prevents rapid retries and overlapping requests from the UI
 * without sleeping on the main thread. Call {@link #release()} when the accepted lookup finishes.
 */
public final class YouTubeLookupLimiter {

    public interface Clock {
        long nowMillis();
    }

    private final long minimumIntervalMs;
    private final Clock clock;
    private long lastStartMs = Long.MIN_VALUE;
    private boolean inFlight;

    public YouTubeLookupLimiter(long minimumIntervalMs) {
        this(minimumIntervalMs, new Clock() {
            @Override
            public long nowMillis() {
                return System.nanoTime() / 1_000_000L;
            }
        });
    }

    public YouTubeLookupLimiter(long minimumIntervalMs, Clock clock) {
        this.minimumIntervalMs = Math.max(0, minimumIntervalMs);
        this.clock = clock;
    }

    /**
     * Returns 0 when acquired, a positive number of milliseconds when cooling down, and -1 when another lookup is
     * already in progress.
     */
    public synchronized long tryAcquire() {
        if (inFlight) return -1;
        long now = clock.nowMillis();
        if (lastStartMs != Long.MIN_VALUE) {
            long elapsed = Math.max(0, now - lastStartMs);
            long remaining = minimumIntervalMs - elapsed;
            if (remaining > 0) return remaining;
        }
        lastStartMs = now;
        inFlight = true;
        return 0;
    }

    public synchronized void release() {
        inFlight = false;
    }

    /** Milliseconds until another lookup can start, or 0 if it can start now. */
    public synchronized long remainingMillis() {
        if (lastStartMs == Long.MIN_VALUE) return 0;
        long elapsed = Math.max(0, clock.nowMillis() - lastStartMs);
        return Math.max(0, minimumIntervalMs - elapsed);
    }

    public synchronized boolean isInFlight() {
        return inFlight;
    }
}
