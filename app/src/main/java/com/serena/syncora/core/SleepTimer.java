package com.serena.syncora.core;

/** Small, clock-injected sleep timer state machine shared by the settings UI and playback service. */
public final class SleepTimer {
    public enum Kind { OFF, COUNTDOWN, END_OF_TRACK }

    private Kind kind = Kind.OFF;
    private long deadlineWallTimeMs;

    public void startAfter(long nowWallTimeMs, long durationMs) {
        if (durationMs <= 0) {
            cancel();
            return;
        }
        kind = Kind.COUNTDOWN;
        long deadline;
        try {
            deadline = Math.addExact(nowWallTimeMs, durationMs);
        } catch (ArithmeticException ignored) {
            deadline = Long.MAX_VALUE;
        }
        deadlineWallTimeMs = deadline;
    }

    public void endAtTrackBoundary() {
        kind = Kind.END_OF_TRACK;
        deadlineWallTimeMs = 0;
    }

    /** Restores a persisted timer. Expired countdowns are returned as expired, not silently extended. */
    public void restore(long deadlineWallTimeMs, boolean endOfTrack) {
        if (endOfTrack) {
            endAtTrackBoundary();
        } else if (deadlineWallTimeMs > 0) {
            kind = Kind.COUNTDOWN;
            this.deadlineWallTimeMs = deadlineWallTimeMs;
        } else {
            cancel();
        }
    }

    public void cancel() {
        kind = Kind.OFF;
        deadlineWallTimeMs = 0;
    }

    public Kind kind() {
        return kind;
    }

    public boolean active() {
        return kind != Kind.OFF;
    }

    public long deadlineWallTimeMs() {
        return kind == Kind.COUNTDOWN ? deadlineWallTimeMs : 0;
    }

    public long remainingMs(long nowWallTimeMs) {
        if (kind != Kind.COUNTDOWN) return 0;
        return Math.max(0, deadlineWallTimeMs - nowWallTimeMs);
    }

    public boolean isDue(long nowWallTimeMs) {
        return kind == Kind.COUNTDOWN && nowWallTimeMs >= deadlineWallTimeMs;
    }

    /** Consume an end-of-track timer exactly once at a clean track boundary. */
    public boolean consumeTrackBoundary() {
        if (kind != Kind.END_OF_TRACK) return false;
        cancel();
        return true;
    }

    /** Consume a countdown when its deadline arrives exactly once. */
    public boolean consumeDeadline(long nowWallTimeMs) {
        if (!isDue(nowWallTimeMs)) return false;
        cancel();
        return true;
    }
}
