package com.serena.syncora.audio;

import java.io.IOException;

/** Anything that can produce PCM16 audio for the group: a decoded file, a radio stream, the microphone... */
public interface PcmSource {

    /** A block of interleaved PCM16 audio (1 or 2 channels). */
    final class Pcm {
        public final short[] samples;
        public final int frames;
        public final int sampleRate;
        public final int channels;
        /** Position of the first frame inside the media (0 for live sources). */
        public final long mediaTimeUs;

        public Pcm(short[] samples, int frames, int sampleRate, int channels, long mediaTimeUs) {
            this.samples = samples;
            this.frames = frames;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.mediaTimeUs = mediaTimeUs;
        }
    }

    void open() throws IOException;

    /** Blocks until the next block is available. Returns null at the end of the stream. */
    Pcm read() throws IOException, InterruptedException;

    /** Only for seekable sources. */
    void seekTo(long positionMs) throws IOException;

    /** Total length or -1 when unknown / live. */
    long durationMs();

    boolean isLive();

    /** May be called from any thread to unblock {@link #read()}. */
    void abort();

    /** Releases everything. Called on the thread that runs the pump. */
    void close();
}
