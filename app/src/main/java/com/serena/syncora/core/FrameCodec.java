package com.serena.syncora.core;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

/**
 * Binary framing used on the host <-> speaker TCP stream.
 * Every frame is: type (1 byte) | payload length (4 bytes, little endian) | payload.
 */
public final class FrameCodec {
    public static final int T_WELCOME = 1;
    public static final int T_STATE = 2;
    public static final int T_AUDIO = 3;
    public static final int T_FLUSH = 4;
    public static final int T_SET = 5;
    public static final int T_PING = 6;
    public static final int T_BYE = 7;
    public static final int T_HELLO = 16;
    public static final int T_CLIENT_STATE = 17;
    public static final int T_COMMAND = 18;
    public static final int T_PONG = 19;

    public static final int MAX_PAYLOAD = 4 * 1024 * 1024;
    private static final int AUDIO_HEADER = 4 + 4 + 8 + 4 + 1 + 4;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private FrameCodec() {
    }

    public static final class Frame {
        public final int type;
        public final byte[] payload;

        public Frame(int type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }

        public String text() {
            return new String(payload, UTF8);
        }
    }

    public static byte[] encode(int type, byte[] payload) {
        byte[] out = new byte[5 + payload.length];
        out[0] = (byte) type;
        putInt(out, 1, payload.length);
        System.arraycopy(payload, 0, out, 5, payload.length);
        return out;
    }

    public static byte[] encodeText(int type, String text) {
        return encode(type, text.getBytes(UTF8));
    }

    public static byte[] encodeFlush(int epoch) {
        byte[] payload = new byte[4];
        putInt(payload, 0, epoch);
        return encode(T_FLUSH, payload);
    }

    public static int decodeFlush(byte[] payload) {
        return getInt(payload, 0);
    }

    public static byte[] encodeAudio(PcmChunk c) {
        int count = c.frames * c.channels;
        int payload = AUDIO_HEADER + count * 2;
        byte[] out = new byte[5 + payload];
        out[0] = (byte) T_AUDIO;
        putInt(out, 1, payload);
        int p = 5;
        putInt(out, p, c.epoch);
        p += 4;
        putInt(out, p, c.seq);
        p += 4;
        putLong(out, p, c.playAtNanos);
        p += 8;
        putInt(out, p, c.sampleRate);
        p += 4;
        out[p++] = (byte) c.channels;
        putInt(out, p, c.frames);
        p += 4;
        short[] samples = c.samples;
        for (int i = 0; i < count; i++) {
            short s = samples[i];
            out[p++] = (byte) s;
            out[p++] = (byte) (s >> 8);
        }
        return out;
    }

    public static PcmChunk decodeAudio(byte[] payload) throws IOException {
        if (payload.length < AUDIO_HEADER) throw new IOException("short audio frame");
        int p = 0;
        int epoch = getInt(payload, p);
        p += 4;
        int seq = getInt(payload, p);
        p += 4;
        long playAt = getLong(payload, p);
        p += 8;
        int rate = getInt(payload, p);
        p += 4;
        int channels = payload[p++] & 0xFF;
        int frames = getInt(payload, p);
        p += 4;
        if (rate < 4000 || rate > 384000 || channels < 1 || channels > 2 || frames < 0) {
            throw new IOException("bad audio header");
        }
        int count = frames * channels;
        if (payload.length - p < count * 2) throw new IOException("truncated audio frame");
        short[] samples = new short[count];
        for (int i = 0; i < count; i++) {
            samples[i] = (short) ((payload[p] & 0xFF) | (payload[p + 1] << 8));
            p += 2;
        }
        return new PcmChunk(epoch, seq, playAt, rate, channels, frames, samples);
    }

    /** Reads one frame; returns null when the stream ended cleanly between frames. */
    public static Frame read(InputStream in) throws IOException {
        int type = in.read();
        if (type < 0) return null;
        byte[] header = new byte[4];
        readFully(in, header, 0, 4);
        int length = getInt(header, 0);
        if (length < 0 || length > MAX_PAYLOAD) throw new IOException("bad frame length " + length);
        byte[] payload = new byte[length];
        readFully(in, payload, 0, length);
        return new Frame(type, payload);
    }

    public static void readFully(InputStream in, byte[] buffer, int offset, int length) throws IOException {
        int done = 0;
        while (done < length) {
            int n = in.read(buffer, offset + done, length - done);
            if (n < 0) throw new EOFException("stream closed");
            done += n;
        }
    }

    static void putInt(byte[] b, int p, int v) {
        b[p] = (byte) v;
        b[p + 1] = (byte) (v >> 8);
        b[p + 2] = (byte) (v >> 16);
        b[p + 3] = (byte) (v >> 24);
    }

    static void putLong(byte[] b, int p, long v) {
        for (int i = 0; i < 8; i++) b[p + i] = (byte) (v >> (8 * i));
    }

    static int getInt(byte[] b, int p) {
        return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8) | ((b[p + 2] & 0xFF) << 16) | ((b[p + 3] & 0xFF) << 24);
    }

    static long getLong(byte[] b, int p) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[p + i] & 0xFF);
        return v;
    }
}
