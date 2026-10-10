package com.serena.syncora.audio;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

/**
 * Removes SHOUTcast/Icecast in-band metadata from a radio stream and reports the current song title.
 * The server inserts a metadata block after every {@code metaInt} audio bytes: one length byte (x16) and the text.
 */
final class IcyInputStream extends InputStream {
    interface Listener {
        void onTitle(String streamTitle);
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");

    private final InputStream in;
    private final int metaInt;
    private final Listener listener;
    private int remaining;

    IcyInputStream(InputStream in, int metaInt, Listener listener) {
        this.in = in;
        this.metaInt = metaInt;
        this.listener = listener;
        this.remaining = metaInt;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (metaInt <= 0) return in.read(buffer, offset, length);
        if (length == 0) return 0;
        if (remaining == 0) {
            readMetadata();
            remaining = metaInt;
        }
        int n = in.read(buffer, offset, Math.min(length, remaining));
        if (n > 0) remaining -= n;
        return n;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    private void readMetadata() throws IOException {
        int lengthByte = in.read();
        if (lengthByte < 0) throw new EOFException("stream ended");
        int size = lengthByte * 16;
        if (size == 0) return;
        byte[] meta = new byte[size];
        int done = 0;
        while (done < size) {
            int n = in.read(meta, done, size - done);
            if (n < 0) throw new EOFException("stream ended inside metadata");
            done += n;
        }
        int end = size;
        while (end > 0 && meta[end - 1] == 0) end--;
        String text = new String(meta, 0, end, UTF8);
        if (text.indexOf('\uFFFD') >= 0) text = new String(meta, 0, end, LATIN1);
        String title = parseTitle(text);
        if (title != null && listener != null) listener.onTitle(title);
    }

    /** Extracts the value of {@code StreamTitle='...';} (may be empty). */
    static String parseTitle(String metadata) {
        String key = "StreamTitle='";
        int start = metadata.indexOf(key);
        if (start < 0) return null;
        start += key.length();
        int end = metadata.indexOf("';", start);
        if (end < 0) end = metadata.lastIndexOf('\'');
        if (end < start) end = metadata.length();
        return metadata.substring(start, end).trim();
    }
}
