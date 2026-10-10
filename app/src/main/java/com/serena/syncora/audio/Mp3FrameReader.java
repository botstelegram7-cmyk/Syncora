package com.serena.syncora.audio;

import java.io.IOException;

/** Cuts a raw MP3 (MPEG audio layer III) byte stream into frames, skipping tags and re-synchronising after damage. */
final class Mp3FrameReader {
    private static final int[][] SAMPLE_RATES = {
            {44100, 48000, 32000}, // MPEG 1
            {22050, 24000, 16000}, // MPEG 2
            {11025, 12000, 8000}   // MPEG 2.5
    };
    private static final int[] BITRATE_V1_L3 = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0};
    private static final int[] BITRATE_V2_L3 = {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0};

    private final PeekStream stream;
    private int sampleRate = 44100;
    private int channels = 2;
    private int samplesPerFrame = 1152;

    Mp3FrameReader(PeekStream stream) {
        this.stream = stream;
    }

    int sampleRate() {
        return sampleRate;
    }

    int channels() {
        return channels;
    }

    int samplesPerFrame() {
        return samplesPerFrame;
    }

    /** Skips to the first frame and learns the stream parameters without consuming that frame. */
    boolean prime(int maxScan) throws IOException {
        int offset = probe(stream, maxScan);
        if (offset < 0) return false;
        stream.skip(offset);
        readParams(stream, 0);
        return true;
    }

    /** Returns the next complete frame (with header) or null at the end of the stream. */
    byte[] nextFrame() throws IOException {
        while (true) {
            if (!stream.skipId3()) return null;
            if (!stream.ensure(4)) return null;
            int length = frameLength(stream, 0);
            if (length > 0) {
                boolean confirmed;
                if (stream.ensure(length + 4)) {
                    confirmed = frameLength(stream, length) > 0 || stream.isId3At(length);
                } else {
                    confirmed = stream.ensure(length);
                    if (!confirmed) return null;
                }
                if (confirmed) {
                    readParams(stream, 0);
                    return stream.take(length);
                }
            }
            stream.skip(1);
        }
    }

    /** Offset of the first position that starts two consecutive valid frames within {@code maxScan} bytes, or -1. */
    static int probe(PeekStream stream, int maxScan) throws IOException {
        stream.skipId3();
        for (int offset = 0; offset < maxScan; offset++) {
            if (!stream.ensure(offset + 4)) return -1;
            int length = frameLength(stream, offset);
            if (length <= 0) continue;
            if (!stream.ensure(offset + length + 4)) return -1;
            if (frameLength(stream, offset + length) > 0) return offset;
        }
        return -1;
    }

    /** Frame length in bytes, or -1 when there is no valid layer III header at {@code at}. */
    static int frameLength(PeekStream s, int at) {
        int b0 = s.peek(at);
        int b1 = s.peek(at + 1);
        int b2 = s.peek(at + 2);
        if (b0 != 0xFF || (b1 & 0xE0) != 0xE0) return -1;
        int version = (b1 >> 3) & 3;
        int layer = (b1 >> 1) & 3;
        if (version == 1 || layer != 1) return -1; // only layer III is supported
        int bitrateIndex = (b2 >> 4) & 0xF;
        int rateIndex = (b2 >> 2) & 3;
        int padding = (b2 >> 1) & 1;
        if (bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) return -1;
        boolean mpeg1 = version == 3;
        int rate = SAMPLE_RATES[mpeg1 ? 0 : (version == 2 ? 1 : 2)][rateIndex];
        int bitrate = (mpeg1 ? BITRATE_V1_L3 : BITRATE_V2_L3)[bitrateIndex] * 1000;
        return (mpeg1 ? 144 : 72) * bitrate / rate + padding;
    }

    private void readParams(PeekStream s, int at) {
        int b1 = s.peek(at + 1);
        int b2 = s.peek(at + 2);
        int b3 = s.peek(at + 3);
        int version = (b1 >> 3) & 3;
        int rateIndex = (b2 >> 2) & 3;
        sampleRate = SAMPLE_RATES[version == 3 ? 0 : (version == 2 ? 1 : 2)][rateIndex];
        channels = ((b3 >> 6) & 3) == 3 ? 1 : 2;
        samplesPerFrame = version == 3 ? 1152 : 576;
    }
}
