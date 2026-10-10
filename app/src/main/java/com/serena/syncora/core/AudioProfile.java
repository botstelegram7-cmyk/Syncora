package com.serena.syncora.core;

/**
 * Latency / stability trade-off for the whole group.
 * The host sends audio {@code lookaheadMs} before it is due, every device plays it exactly at its due time.
 */
public final class AudioProfile {
    public static final String STABLE = "stable";
    public static final String BALANCED = "balanced";
    public static final String LOW_LATENCY = "low";

    public final String id;
    public final String label;
    public final String description;
    /** How far ahead of its play time audio is sent (also the delay between pressing play and hearing it). */
    public final int lookaheadMs;
    /** AudioTrack buffer on every device. */
    public final int sinkBufferMs;
    /** Misalignment that is tolerated before a device nudges its playback. */
    public final int toleranceMs;
    public final boolean lowLatencyTrack;

    private AudioProfile(String id, String label, String description, int lookaheadMs, int sinkBufferMs,
                         int toleranceMs, boolean lowLatencyTrack) {
        this.id = id;
        this.label = label;
        this.description = description;
        this.lookaheadMs = lookaheadMs;
        this.sinkBufferMs = sinkBufferMs;
        this.toleranceMs = toleranceMs;
        this.lowLatencyTrack = lowLatencyTrack;
    }

    private static final AudioProfile STABLE_PROFILE = new AudioProfile(STABLE, "Stable",
            "Biggest buffer. Best for busy Wi-Fi and hotspots.", 800, 300, 35, false);
    private static final AudioProfile BALANCED_PROFILE = new AudioProfile(BALANCED, "Balanced",
            "Good sync and good protection against drop-outs.", 450, 160, 20, false);
    private static final AudioProfile LOW_PROFILE = new AudioProfile(LOW_LATENCY, "Low latency",
            "Fastest response and tightest sync. Needs a strong Wi-Fi link.", 260, 100, 10, true);

    public static AudioProfile of(String id) {
        if (LOW_LATENCY.equals(id)) return LOW_PROFILE;
        if (STABLE.equals(id)) return STABLE_PROFILE;
        return BALANCED_PROFILE;
    }

    public static AudioProfile[] all() {
        return new AudioProfile[]{STABLE_PROFILE, BALANCED_PROFILE, LOW_PROFILE};
    }
}
