package com.serena.syncora.youtube.tap;

import java.io.IOException;

/**
 * Minimal Protocol Buffers reader for the TypeTube track envelopes (varints, length-delimited fields,
 * doubles). Matches the hand-rolled schema in the typetube package's {@code protocol.ts}.
 */
final class TapProto {

    private final byte[] buf;
    private int pos;
    private final int end;

    TapProto(byte[] buf) {
        this(buf, 0, buf.length);
    }

    private TapProto(byte[] buf, int from, int to) {
        this.buf = buf;
        this.pos = from;
        this.end = to;
    }

    boolean hasMore() {
        return pos < end;
    }

    /** Next tag as {@code (field << 3) | wire}, or -1 at the end of the message. */
    int readTag() throws IOException {
        if (!hasMore()) return -1;
        return (int) varint();
    }

    static int fieldOf(int tag) {
        return tag >>> 3;
    }

    static int wireOf(int tag) {
        return tag & 7;
    }

    long readVarint() throws IOException {
        return varint();
    }

    String readString() throws IOException {
        int len = length();
        return new String(buf, pos - len, len, "UTF-8");
    }

    /** Nested message: a length-delimited block that gets its own reader. */
    TapProto readMessage() throws IOException {
        int len = length();
        return new TapProto(buf, pos - len, pos);
    }

    double readDouble() throws IOException {
        need(8);
        long bits = 0;
        // little-endian, as the typetube writer stores doubles
        for (int i = 7; i >= 0; i--) bits = (bits << 8) | (buf[pos + i] & 0xffL);
        pos += 8;
        return Double.longBitsToDouble(bits);
    }

    void skip(int wire) throws IOException {
        switch (wire) {
            case 0:
                varint();
                break;
            case 1:
                need(8);
                pos += 8;
                break;
            case 2:
                length();
                break;
            case 5:
                need(4);
                pos += 4;
                break;
            default:
                throw new IOException("Unsupported protobuf wire type " + wire);
        }
    }

    private int length() throws IOException {
        int len = (int) varint();
        if (len < 0 || len > end - pos) throw new IOException("Truncated protobuf field");
        pos += len;
        // readString/readMessage read from [pos - len, pos)
        return len;
    }

    private long varint() throws IOException {
        long res = 0;
        int shift = 0;
        while (pos < end) {
            int b = buf[pos++] & 0xff;
            res |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) return res;
            shift += 7;
            if (shift > 63) throw new IOException("Malformed protobuf varint");
        }
        throw new IOException("Truncated protobuf varint");
    }

    private void need(int count) throws IOException {
        if (end - pos < count) throw new IOException("Truncated protobuf value");
    }
}
