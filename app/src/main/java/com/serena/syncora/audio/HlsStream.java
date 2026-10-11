package com.serena.syncora.audio;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * An HLS (`.m3u8`) stream read as one endless stream of audio bytes, which is what every Indian
 * broadcaster (All India Radio / Akashvani, most private stations) and most national broadcasters
 * publish today.
 *
 * <p>It resolves a master playlist down to an audio-only media playlist when there is one — so a
 * link that also offers video is played as audio only — then follows the media playlist for as long
 * as the show runs: fetch segments, hand their bytes out, refetch the playlist, wait for the next
 * segment. AES-128 encrypted segments are decrypted on the way.
 *
 * <p>What the segments contain (MPEG-TS, fragmented MP4, plain AAC/MP3) is decided afterwards by
 * {@link StreamSniffer}: this class only produces the bytes.
 */
final class HlsStream extends InputStream {

    /** One HTTP answer: status, content type, the address after redirects and the body. */
    static final class Response {
        final int status;
        final String contentType;
        final String url;
        final byte[] body;

        Response(int status, String contentType, String url, byte[] body) {
            this.status = status;
            this.contentType = contentType == null ? "" : contentType;
            this.url = url;
            this.body = body == null ? new byte[0] : body;
        }
    }

    /** Fetches playlists, keys and segments. Separated so tests can serve a whole stream from memory. */
    interface Fetcher {
        /** {@code length} below 0 means "to the end of the file". */
        Response get(String url, long offset, long length) throws IOException;
    }

    interface Listener {
        void onInfo(String info);
    }

    /** How far behind the live edge a stream starts: enough to survive a slow segment, little enough to be live. */
    private static final int LIVE_BACKLOG = 3;
    private static final int MAX_PLAYLIST_HOPS = 4;
    private static final int MAX_SEGMENT_TRIES = 3;
    private static final int MAX_IDLE_ROUNDS = 25;

    private final Fetcher fetcher;
    private final Listener listener;

    private URL mediaBase;
    private String mediaUrl;
    private List<HlsPlaylist.Segment> segments = new ArrayList<>();
    private int nextIndex;
    private long lastSequence = -1;
    private int targetDurationSec = 6;
    private boolean endList;
    private boolean started;

    private byte[] buffer = new byte[0];
    private int position;
    private String pendingInitUrl = "";
    private final Map<String, byte[]> keys = new HashMap<>();
    private volatile boolean closed;
    private String description = "HLS";

    private HlsStream(Fetcher fetcher, Listener listener) {
        this.fetcher = fetcher;
        this.listener = listener;
    }

    /** Opens a playlist address, following master playlists down to the audio they carry. */
    static HlsStream open(String url, Fetcher fetcher, Listener listener) throws IOException {
        HlsStream stream = new HlsStream(fetcher, listener);
        stream.resolve(url);
        return stream;
    }

    private void resolve(String url) throws IOException {
        String current = url;
        for (int hop = 0; hop < MAX_PLAYLIST_HOPS; hop++) {
            Response response = fetcher.get(current, 0, -1);
            if (response.status / 100 != 2) throw new IOException("The station answered HTTP " + response.status);
            URL base = new URL(current);
            if (response.url != null && !response.url.isEmpty()) {
                try {
                    base = new URL(response.url);
                } catch (IOException ignored) {
                    // keep the address we asked for
                }
            }
            String type = response.contentType.toLowerCase(Locale.US);
            if (response.body.length == 0) throw new IOException("The station sent an empty playlist");
            if (!looksLikePlaylist(response.body) && !type.contains("mpegurl") && !type.contains("m3u")) {
                throw new IOException("This address is not an HLS playlist (it sent "
                        + (type.isEmpty() ? "unknown data" : type) + ")");
            }
            HlsPlaylist playlist = HlsPlaylist.parse(response.body, base);
            mediaBase = base;
            mediaUrl = response.url == null || response.url.isEmpty() ? current : response.url;
            targetDurationSec = playlist.targetDurationSec > 0 ? playlist.targetDurationSec : 6;
            endList = playlist.endList;
            if (playlist.isMaster() && playlist.segments.isEmpty()) {
                String next = playlist.pickMediaUrl();
                if (next == null) throw new IOException("This HLS playlist offers nothing to play");
                info("HLS \u00B7 " + playlist.describeChoices());
                current = next;
                continue;
            }
            load(playlist, !started);
            return;
        }
        throw new IOException("This HLS playlist keeps pointing at other playlists");
    }

    /** Takes the segment list of a freshly read media playlist. */
    private void load(HlsPlaylist playlist, boolean first) {
        List<HlsPlaylist.Segment> fresh = new ArrayList<>();
        for (HlsPlaylist.Segment s : playlist.segments) {
            if (s.init) continue;
            fresh.add(s);
        }
        targetDurationSec = playlist.targetDurationSec > 0 ? playlist.targetDurationSec : targetDurationSec;
        endList = playlist.endList;
        if (first) {
            started = true;
            int start = endList ? 0 : Math.max(0, fresh.size() - LIVE_BACKLOG);
            if (start > 0) {
                double seconds = 0;
                for (int i = start; i < fresh.size(); i++) seconds += fresh.get(i).seconds;
                info("HLS \u00B7 joining " + Math.round(seconds) + " s behind live");
            } else {
                info(endList ? "HLS \u00B7 finished stream" : "HLS \u00B7 live");
            }
            segments = fresh;
            nextIndex = start;
            lastSequence = start > 0 ? fresh.get(start - 1).sequence : -1;
            return;
        }
        // Keep only what has not been played yet: the playlist window slides forward as the show runs.
        List<HlsPlaylist.Segment> keep = new ArrayList<>();
        for (HlsPlaylist.Segment s : fresh) {
            if (lastSequence < 0 || s.sequence > lastSequence) keep.add(s);
        }
        if (keep.isEmpty() && !fresh.isEmpty() && lastSequence >= 0) {
            // The station restarted its sequence numbers: treat the tail of the new list as new audio.
            int from = Math.max(0, fresh.size() - 1);
            keep.add(fresh.get(from));
        }
        segments = keep;
        nextIndex = 0;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] out, int offset, int length) throws IOException {
        if (length == 0) return 0;
        while (true) {
            if (closed) return -1;
            if (position < buffer.length) {
                int n = Math.min(length, buffer.length - position);
                System.arraycopy(buffer, position, out, offset, n);
                position += n;
                return n;
            }
            if (!fill()) return -1;
        }
    }

    /** Loads the next segment (waiting for the station to publish one when we are at the live edge). */
    private boolean fill() throws IOException {
        while (!closed) {
            if (nextIndex < segments.size()) {
                HlsPlaylist.Segment segment = segments.get(nextIndex++);
                lastSequence = Math.max(lastSequence, segment.sequence);
                byte[] bytes = fetchSegment(segment);
                if (bytes.length == 0) continue;
                buffer = bytes;
                position = 0;
                return true;
            }
            if (endList) return false;
            if (!waitForMore()) return false;
        }
        return false;
    }

    /** Rereads the playlist until new segments appear; false when the station clearly stopped. */
    private boolean waitForMore() throws IOException {
        int idle = 0;
        while (!closed) {
            long waitMs = Math.max(400L, Math.min(6000L, targetDurationSec * 400L));
            if (!sleep(waitMs)) return false;
            HlsPlaylist playlist;
            try {
                Response response = fetcher.get(mediaUrl, 0, -1);
                if (response.status / 100 != 2) throw new IOException("HTTP " + response.status);
                playlist = HlsPlaylist.parse(response.body, response.url == null || response.url.isEmpty()
                        ? mediaBase : new URL(response.url));
            } catch (IOException e) {
                if (++idle > MAX_IDLE_ROUNDS) {
                    throw new IOException("The station stopped publishing its stream (" + e.getMessage() + ")");
                }
                continue;
            }
            load(playlist, false);
            if (nextIndex < segments.size()) return true;
            if (endList) return false;
            if (++idle > MAX_IDLE_ROUNDS) {
                throw new IOException("The station stopped publishing new audio");
            }
        }
        return false;
    }

    private byte[] fetchSegment(HlsPlaylist.Segment segment) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_SEGMENT_TRIES && !closed; attempt++) {
            try {
                byte[] bytes = range(segment.url, segment.offset, segment.length);
                if (!segment.initUrl.isEmpty() && !segment.initUrl.equals(pendingInitUrl)) {
                    byte[] init = range(segment.initUrl, segment.initOffset, segment.initLength);
                    pendingInitUrl = segment.initUrl;
                    byte[] joined = new byte[init.length + bytes.length];
                    System.arraycopy(init, 0, joined, 0, init.length);
                    System.arraycopy(bytes, 0, joined, init.length, bytes.length);
                    bytes = joined;
                }
                if (segment.key.encrypted()) bytes = decrypt(bytes, segment);
                return bytes;
            } catch (IOException e) {
                last = e;
                if (!sleep(300L * attempt)) break;
            }
        }
        if (closed) return new byte[0];
        // One broken segment must not kill the station: skip it and stay with the stream.
        info("HLS \u00B7 skipped a damaged segment");
        if (last != null && endList) throw last;
        return new byte[0];
    }

    private byte[] range(String url, long offset, long length) throws IOException {
        Response response = fetcher.get(url, offset, length);
        if (response.status == 404) throw new IOException("The station no longer has this segment");
        if (response.status / 100 != 2) throw new IOException("The station answered HTTP " + response.status);
        return response.body;
    }

    /** AES-128 CBC with an explicit IV, or the segment sequence number as the IV (what HLS means). */
    private byte[] decrypt(byte[] body, HlsPlaylist.Segment segment) throws IOException {
        if (!segment.key.supported()) {
            throw new IOException("This stream is protected with " + segment.key.method + ", which Syncora cannot open");
        }
        byte[] key = keys.get(segment.key.url);
        if (key == null) {
            key = range(segment.key.url, 0, -1);
            if (key.length != 16) throw new IOException("The station sent a key of " + key.length + " bytes");
            keys.put(segment.key.url, key);
        }
        byte[] iv = segment.key.iv;
        if (iv == null) {
            iv = new byte[16];
            long sequence = segment.sequence < 0 ? 0 : segment.sequence;
            for (int i = 0; i < 8; i++) iv[15 - i] = (byte) (sequence >> (8 * i));
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(body);
        } catch (Exception e) {
            try {
                Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
                return cipher.doFinal(body);
            } catch (Exception ignored) {
                throw new IOException("Could not decrypt this stream (" + e.getClass().getSimpleName() + ")");
            }
        }
    }

    /** Sleeps in small steps so that {@link #close()} ends the wait quickly. False when closed. */
    private boolean sleep(long millis) {
        long left = millis;
        while (left > 0 && !closed) {
            try {
                Thread.sleep(Math.min(120L, left));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            left -= 120L;
        }
        return !closed;
    }

    private static boolean looksLikePlaylist(byte[] body) {
        String head = new String(body, 0, Math.min(body.length, 64), java.nio.charset.Charset.forName("UTF-8"));
        return head.replace("\uFEFF", "").trim().startsWith("#EXTM3U");
    }

    private void info(String text) {
        description = text;
        if (listener != null) listener.onInfo(text);
    }

    /** What is being played, for the status line. */
    String describe() {
        return description;
    }

    boolean started() {
        return started;
    }

    @Override
    public void close() {
        closed = true;
    }

    /** Lets RadioSource stop a waiting stream from another thread. */
    void abort() {
        closed = true;
    }

    // ------------------------------------------------------------------ the default HTTP fetcher

    /** Fetches over HTTP(S), following the http↔https redirects stations like to send. */
    static final class HttpFetcher implements Fetcher {
        private final String userAgent;
        private final int readTimeoutMs;

        HttpFetcher(String userAgent, int readTimeoutMs) {
            this.userAgent = userAgent;
            this.readTimeoutMs = readTimeoutMs;
        }

        @Override
        public Response get(String url, long offset, long length) throws IOException {
            URL target = new URL(url);
            HttpURLConnection connection = null;
            for (int hop = 0; hop < 8; hop++) {
                connection = (HttpURLConnection) target.openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(readTimeoutMs);
                connection.setRequestProperty("User-Agent", userAgent);
                connection.setRequestProperty("Accept", "*/*");
                if (offset > 0 || length >= 0) {
                    String end = length > 0 ? String.valueOf(offset + length - 1) : "";
                    connection.setRequestProperty("Range", "bytes=" + offset + "-" + end);
                }
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    connection.disconnect();
                    if (location == null) throw new IOException("The station redirected without a new address");
                    target = new URL(target, location);
                    continue;
                }
                if (status == 416) {
                    connection.disconnect();
                    throw new IOException("The station does not have these bytes");
                }
                if (status / 100 != 2) {
                    connection.disconnect();
                    return new Response(status, connection.getContentType(), target.toString(), new byte[0]);
                }
                InputStream in = connection.getInputStream();
                byte[] body;
                try {
                    body = HlsPlaylist.readAll(in);
                } finally {
                    try {
                        in.close();
                    } catch (IOException ignored) {
                        // the connection is closed anyway
                    }
                    connection.disconnect();
                }
                return new Response(status, connection.getContentType(), target.toString(), body);
            }
            throw new IOException("Too many redirects from " + url);
        }
    }

    /** Serves fixed addresses from memory: used by the unit tests to play a whole HLS stream offline. */
    static final class MapFetcher implements Fetcher {
        final Map<String, byte[]> files = new HashMap<>();
        final List<String> requested = new ArrayList<>();

        MapFetcher put(String url, String text) {
            try {
                files.put(url, text.getBytes("UTF-8"));
            } catch (java.io.UnsupportedEncodingException ignored) {
                // UTF-8 always exists
            }
            return this;
        }

        MapFetcher put(String url, byte[] bytes) {
            files.put(url, bytes);
            return this;
        }

        @Override
        public Response get(String url, long offset, long length) throws IOException {
            requested.add(url);
            byte[] body = files.get(url);
            if (body == null) return new Response(404, "", url, new byte[0]);
            if (offset > 0 || length >= 0) {
                int from = (int) Math.min(offset, body.length);
                int to = length > 0 ? (int) Math.min(body.length, offset + length) : body.length;
                byte[] slice = new byte[Math.max(0, to - from)];
                System.arraycopy(body, from, slice, 0, slice.length);
                return new Response(206, "application/octet-stream", url, slice);
            }
            return new Response(200, url.endsWith(".m3u8") ? "application/vnd.apple.mpegurl" : "video/mp2t", url, body);
        }
    }
}
