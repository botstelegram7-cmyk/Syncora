package com.serena.syncora.audio;

import java.io.IOException;
import java.io.InputStream;

/** Buffered stream that lets the frame readers look ahead before they commit to a frame. */
final class PeekStream {
    private final InputStream in;
    private byte[] buffer = new byte[16 * 1024];
    private int start;
    private int end;

    PeekStream(InputStream in) {
        this.in = in;
    }

    /** Makes sure at least {@code n} bytes are buffered; false when the stream ends first. */
    boolean ensure(int n) throws IOException {
        if (end - start >= n) return true;
        if (start > 0) {
            System.arraycopy(buffer, start, buffer, 0, end - start);
            end -= start;
            start = 0;
        }
        if (buffer.length < n) {
            byte[] bigger = new byte[Math.max(n, buffer.length * 2)];
            System.arraycopy(buffer, 0, bigger, 0, end);
            buffer = bigger;
        }
        while (end < n) {
            int read = in.read(buffer, end, buffer.length - end);
            if (read < 0) return false;
            end += read;
        }
        return true;
    }

    int available() {
        return end - start;
    }

    /** Byte at {@code index} from the current position; only valid after {@link #ensure}. */
    int peek(int index) {
        return buffer[start + index] & 0xFF;
    }

    void skip(int n) {
        start += n;
    }

    byte[] take(int n) {
        byte[] out = new byte[n];
        System.arraycopy(buffer, start, out, 0, n);
        start += n;
        return out;
    }

    /** Skips {@code n} bytes of the underlying stream, even if more than the buffer holds. */
    boolean skipLong(long n) throws IOException {
        while (n > 0) {
            if (end - start == 0 && !ensure(1)) return false;
            int step = (int) Math.min(n, end - start);
            start += step;
            n -= step;
        }
        return true;
    }

    /** True when an ID3v2 tag header starts {@code index} bytes ahead (the bytes must already be buffered). */
    boolean isId3At(int index) {
        return peek(index) == 'I' && peek(index + 1) == 'D' && peek(index + 2) == '3';
    }

    /** If an ID3v2 tag sits at the current position, skips it. Returns false at end of stream. */
    boolean skipId3() throws IOException {
        while (true) {
            if (!ensure(10)) return true; // too short to hold a tag: let the caller deal with the rest
            if (peek(0) == 'I' && peek(1) == 'D' && peek(2) == '3' && peek(3) < 0xFF && peek(4) < 0xFF) {
                int size = ((peek(6) & 0x7F) << 21) | ((peek(7) & 0x7F) << 14) | ((peek(8) & 0x7F) << 7) | (peek(9) & 0x7F);
                long total = 10L + size + ((peek(5) & 0x10) != 0 ? 10 : 0);
                if (!skipLong(total)) return false;
            } else {
                return true;
            }
        }
    }
}
