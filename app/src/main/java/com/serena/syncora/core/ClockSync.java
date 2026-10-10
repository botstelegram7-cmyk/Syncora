package com.serena.syncora.core;

/**
 * NTP-style estimate of the offset between this device's monotonic clock and the host's.
 * {@code hostTime = localTime + offset}. Samples with the smallest round-trip time are the most trustworthy.
 */
public final class ClockSync {
    private static final int WINDOW = 12;
    private static final long JUMP_NANOS = 15_000_000L;

    private final long[] offsets = new long[WINDOW];
    private final long[] rtts = new long[WINDOW];
    private int count;
    private int next;
    private long offsetNanos;
    private long bestRttNanos = -1;
    private boolean valid;
    private boolean measured;

    /**
     * @param t0 local time the request was sent
     * @param t1 host time the request arrived
     * @param t2 host time the reply was sent
     * @param t3 local time the reply arrived
     */
    public synchronized void addSample(long t0, long t1, long t2, long t3) {
        long rtt = (t3 - t0) - (t2 - t1);
        if (rtt < 0) return;
        long offset = ((t1 - t0) + (t2 - t3)) / 2;
        offsets[next] = offset;
        rtts[next] = rtt;
        next = (next + 1) % WINDOW;
        if (count < WINDOW) count++;

        long best = Long.MAX_VALUE;
        for (int i = 0; i < count; i++) best = Math.min(best, rtts[i]);
        long limit = best + Math.max(best / 2, 1_000_000L);
        long sum = 0;
        int n = 0;
        for (int i = 0; i < count; i++) {
            if (rtts[i] <= limit) {
                sum += offsets[i];
                n++;
            }
        }
        long candidate = sum / n;
        if (!valid || !measured) {
            offsetNanos = candidate;
        } else {
            long diff = candidate - offsetNanos;
            offsetNanos += Math.abs(diff) > JUMP_NANOS ? diff : diff / 3;
        }
        bestRttNanos = best;
        valid = true;
        measured = true;
    }

    /** Rough estimate (e.g. from the handshake) used until real samples arrive. */
    public synchronized void seedEstimate(long offset) {
        if (measured) return;
        offsetNanos = offset;
        valid = true;
    }

    /** Forget old samples (after a long pause or when the user asks for a re-sync) but keep the last offset. */
    public synchronized void restartSampling() {
        count = 0;
        next = 0;
        bestRttNanos = -1;
    }

    public synchronized void reset() {
        restartSampling();
        valid = false;
        measured = false;
        offsetNanos = 0;
    }

    public synchronized long offsetNanos() {
        return offsetNanos;
    }

    public synchronized boolean hasEstimate() {
        return valid;
    }

    public synchronized boolean hasMeasurement() {
        return measured;
    }

    public synchronized long bestRttNanos() {
        return bestRttNanos;
    }

    public synchronized int sampleCount() {
        return count;
    }
}
