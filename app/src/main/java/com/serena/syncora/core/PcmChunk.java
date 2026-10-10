package com.serena.syncora.core;

/** A slice of PCM16 audio that is due to be heard at {@link #playAtNanos} on the host's monotonic clock. */
public final class PcmChunk {
    public final int epoch;
    public final int seq;
    public final long playAtNanos;
    public final int sampleRate;
    public final int channels;
    public final int frames;
    /** Interleaved samples; at least frames * channels long. Shared between outputs - never modify. */
    public final short[] samples;

    public PcmChunk(int epoch, int seq, long playAtNanos, int sampleRate, int channels, int frames, short[] samples) {
        this.epoch = epoch;
        this.seq = seq;
        this.playAtNanos = playAtNanos;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.frames = frames;
        this.samples = samples;
    }

    public long durationNanos() {
        return frames * 1_000_000_000L / sampleRate;
    }
}
