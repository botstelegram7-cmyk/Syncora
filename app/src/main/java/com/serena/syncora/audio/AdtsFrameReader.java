package com.serena.syncora.audio;

import java.io.IOException;

/** Cuts an AAC stream with ADTS headers into raw AAC frames. */
final class AdtsFrameReader {
    private static final int[] SAMPLE_RATES = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};
    private static final int[] CHANNELS = {0, 1, 2, 3, 4, 5, 6, 8};

    private final PeekStream stream;
    private int sampleRate = 44100;
    private int channels = 2;
    private int objectType = 2;
    private int rateIndex = 4;
    private int channelConfig = 2;

    AdtsFrameReader(PeekStream stream) {
        this.stream = stream;
    }

    int sampleRate() {
        return sampleRate;
    }

    int channels() {
        return channels;
    }

    /** MPEG-4 AudioSpecificConfig for the last frame (csd-0 for MediaCodec). */
    byte[] audioSpecificConfig() {
        return new byte[]{
                (byte) ((objectType << 3) | (rateIndex >> 1)),
                (byte) (((rateIndex & 1) << 7) | (channelConfig << 3))};
    }

    /** Skips to the first frame and learns the stream parameters without consuming that frame. */
    boolean prime(int maxScan) throws IOException {
        int offset = probe(stream, maxScan);
        if (offset < 0) return false;
        stream.skip(offset);
        readParams(stream, 0);
        return true;
    }

    /** Returns the next raw AAC frame (ADTS header removed) or null at the end of the stream. */
    byte[] nextFrame() throws IOException {
        while (true) {
            if (!stream.skipId3()) return null;
            if (!stream.ensure(9)) {
                // The final bytes of a stream: nothing sensible left.
                if (stream.available() < 7) return null;
            }
            int length = frameLength(stream, 0);
            if (length > 0) {
                boolean confirmed;
                if (stream.ensure(length + 7)) {
                    confirmed = frameLength(stream, length) > 0 || stream.isId3At(length);
                } else {
                    confirmed = stream.ensure(length);
                    if (!confirmed) return null;
                }
                if (confirmed) {
                    readParams(stream, 0);
                    int header = (stream.peek(1) & 1) == 1 ? 7 : 9;
                    byte[] frame = stream.take(length);
                    byte[] payload = new byte[length - header];
                    System.arraycopy(frame, header, payload, 0, payload.length);
                    return payload;
                }
            }
            stream.skip(1);
        }
    }

    static int probe(PeekStream stream, int maxScan) throws IOException {
        stream.skipId3();
        for (int offset = 0; offset < maxScan; offset++) {
            if (!stream.ensure(offset + 7)) return -1;
            int length = frameLength(stream, offset);
            if (length <= 0) continue;
            if (!stream.ensure(offset + length + 7)) return -1;
            if (frameLength(stream, offset + length) > 0) return offset;
        }
        return -1;
    }

    /** Full frame length (header included) or -1 when no valid ADTS header starts at {@code at}. */
    static int frameLength(PeekStream s, int at) {
        if (s.available() < at + 7) return -1;
        int b0 = s.peek(at);
        int b1 = s.peek(at + 1);
        int b2 = s.peek(at + 2);
        int b3 = s.peek(at + 3);
        int b4 = s.peek(at + 4);
        int b5 = s.peek(at + 5);
        if (b0 != 0xFF || (b1 & 0xF6) != 0xF0) return -1;
        int rateIndex = (b2 >> 2) & 0xF;
        if (rateIndex >= SAMPLE_RATES.length) return -1;
        int channelConfig = ((b2 & 1) << 2) | ((b3 >> 6) & 3);
        if (channelConfig == 0) return -1;
        int length = ((b3 & 3) << 11) | (b4 << 3) | ((b5 >> 5) & 7);
        int header = (b1 & 1) == 1 ? 7 : 9;
        if (length <= header) return -1;
        return length;
    }

    private void readParams(PeekStream s, int at) {
        int b2 = s.peek(at + 2);
        int b3 = s.peek(at + 3);
        objectType = ((b2 >> 6) & 3) + 1;
        rateIndex = (b2 >> 2) & 0xF;
        channelConfig = ((b2 & 1) << 2) | ((b3 >> 6) & 3);
        sampleRate = SAMPLE_RATES[rateIndex];
        channels = CHANNELS[channelConfig];
    }
}
