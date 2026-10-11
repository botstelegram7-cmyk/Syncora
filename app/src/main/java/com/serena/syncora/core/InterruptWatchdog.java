package com.serena.syncora.core;

/**
 * Speaker mode's audio focus watchdog.
 *
 * <p>Android is supposed to send {@code AUDIOFOCUS_GAIN} back once a call, a notification or another app has
 * finished with the audio output, but that callback is not always delivered. Speaker mode used to mute itself on
 * the first focus loss and then stay silent until the user tapped play. The watchdog turns any focus loss into
 * "muted + INTERRUPTED badge" and asks the service to take the focus back every {@link #RETRY_MS} until it
 * succeeds, or until the retry budget runs out and the badge has to tell the user to resume by hand.
 *
 * <p>It deliberately has no Android imports so the recovery rules can be unit tested.
 */
public final class InterruptWatchdog {
    /** Time between two attempts to take the audio focus back. */
    public static final long RETRY_MS = 1500L;
    /** Automatic attempts before the user has to tap play (roughly {@code MAX_ATTEMPTS * RETRY_MS}). */
    public static final int MAX_ATTEMPTS = 8;

    private boolean interrupted;
    private boolean retrying;
    private int attempts;
    private long nextAttemptAt;

    /** Another app took the audio focus (a call, a notification, a music app): mute, badge and take it back. */
    public void onInterrupt(long nowMs) {
        interrupted = true;
        retrying = true;
        attempts = 0;
        nextAttemptAt = nowMs + RETRY_MS;
    }

    /**
     * The audio focus is gone and coming back would blare out of the phone speaker by itself (headphones
     * unplugged): keep the badge, but leave the decision to take the sound back to the user.
     */
    public void onManualInterrupt() {
        interrupted = true;
        retrying = false;
        attempts = 0;
        nextAttemptAt = 0L;
    }

    /** {@code AUDIOFOCUS_GAIN} arrived: whoever interrupted is done and playback is normal again. */
    public void onFocusGained() {
        reset();
    }

    /** True when the service should try to take the audio focus back right now. */
    public boolean shouldRetry(long nowMs) {
        return retrying && nowMs >= nextAttemptAt;
    }

    /** Outcome of an automatic attempt; a granted request ends the interruption. */
    public void onAttempt(boolean granted, long nowMs) {
        if (!retrying) return;
        if (granted) {
            reset();
            return;
        }
        if (++attempts >= MAX_ATTEMPTS) {
            retrying = false;
            nextAttemptAt = 0L;
            return;
        }
        nextAttemptAt = nowMs + RETRY_MS;
    }

    /**
     * The interruption is still going on (a call is in progress): look again later. Waiting does not spend one of
     * the {@link #MAX_ATTEMPTS} attempts, so a long call is still followed by an automatic recovery.
     */
    public void defer(long nowMs) {
        if (retrying) nextAttemptAt = nowMs + RETRY_MS;
    }

    public boolean isInterrupted() {
        return interrupted;
    }

    /** True while the app is still trying on its own, i.e. the user has nothing to do yet. */
    public boolean isRetrying() {
        return retrying;
    }

    public void reset() {
        interrupted = false;
        retrying = false;
        attempts = 0;
        nextAttemptAt = 0L;
    }
}
