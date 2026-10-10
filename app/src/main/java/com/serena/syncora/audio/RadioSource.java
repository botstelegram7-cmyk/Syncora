package com.serena.syncora.audio;

import android.media.MediaFormat;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Internet radio: pulls an MP3 or AAC (ADTS) stream, strips the in-band song titles and decodes it to PCM.
 * The host does this once, so the speakers never need an internet connection of their own.
 */
public final class RadioSource implements PcmSource {

    public interface Listener {
        void onStationName(String name);

        void onTitle(String streamTitle);

        void onStatus(String status);
    }

    private static final String USER_AGENT = "Syncora/0.2 (Android; Internet radio)";
    private static final int MAX_RETRIES = 6;
    private static final int SNIFF_BYTES = 24 * 1024;

    private final String url;
    private final Listener listener;
    private final ArrayBlockingQueue<byte[]> frames = new ArrayBlockingQueue<>(300);

    private PcmCodec codec;
    private Thread reader;
    private volatile boolean aborted;
    private volatile boolean readerDone;
    private volatile IOException readerError;
    private HttpURLConnection connection;
    private Mp3FrameReader mp3;
    private AdtsFrameReader adts;
    private boolean aac;
    private long ptsUs;
    private long frameDurationUs = 26_000;

    public RadioSource(String url, Listener listener) {
        this.url = url;
        this.listener = listener;
    }

    @Override
    public void open() throws IOException {
        status("Connecting\u2026");
        connect();
        MediaFormat format;
        if (aac) {
            format = MediaFormat.createAudioFormat("audio/mp4a-latm", adts.sampleRate(), adts.channels());
            format.setByteBuffer("csd-0", ByteBuffer.wrap(adts.audioSpecificConfig()));
            frameDurationUs = 1024L * 1_000_000L / adts.sampleRate();
        } else {
            format = MediaFormat.createAudioFormat("audio/mpeg", mp3.sampleRate(), mp3.channels());
            frameDurationUs = mp3.samplesPerFrame() * 1_000_000L / mp3.sampleRate();
        }
        codec = PcmCodec.create(format);
        reader = new Thread(new Runnable() {
            @Override
            public void run() {
                readerLoop();
            }
        }, "syncora-radio-reader");
        reader.setDaemon(true);
        reader.start();
        status("Live");
    }

    @Override
    public Pcm read() throws IOException, InterruptedException {
        try {
            while (true) {
                if (aborted || Thread.interrupted()) throw new InterruptedException();
                byte[] next = frames.peek();
                if (next != null) {
                    int index = codec.dequeueInputBuffer(0);
                    if (index >= 0) {
                        frames.poll();
                        ByteBuffer buffer = codec.inputBuffer(index);
                        buffer.put(next);
                        codec.queueInput(index, next.length, ptsUs);
                        ptsUs += frameDurationUs;
                    }
                }
                Pcm out = codec.dequeueOutput(frames.isEmpty() ? 10_000 : 1_000);
                if (out != null) return out;
                if (frames.isEmpty()) {
                    IOException error = readerError;
                    if (error != null) throw error;
                    if (readerDone) return null;
                }
            }
        } catch (IllegalStateException e) {
            throw new IOException("Decoder error", e);
        }
    }

    @Override
    public void seekTo(long positionMs) {
        // live
    }

    @Override
    public long durationMs() {
        return -1;
    }

    @Override
    public boolean isLive() {
        return true;
    }

    @Override
    public void abort() {
        aborted = true;
        closeConnection();
        Thread t = reader;
        if (t != null) t.interrupt();
    }

    @Override
    public void close() {
        abort();
        if (codec != null) codec.release();
        codec = null;
    }

    // ---------------------------------------------------------------- network

    private void readerLoop() {
        int failures = 0;
        while (!aborted) {
            try {
                byte[] frame = nextFrame();
                if (frame == null) throw new EOFException("The station closed the stream");
                frames.put(frame);
                failures = 0;
            } catch (InterruptedException e) {
                break;
            } catch (IOException e) {
                closeConnection();
                if (aborted) break;
                if (++failures > MAX_RETRIES) {
                    readerError = e;
                    break;
                }
                status("Reconnecting\u2026");
                try {
                    Thread.sleep(Math.min(8000L, 600L << failures));
                    if (!aborted) connect();
                } catch (InterruptedException ie) {
                    break;
                } catch (IOException ignored) {
                    // counted as a failure by the next iteration
                }
            }
        }
        readerDone = true;
    }

    private byte[] nextFrame() throws IOException {
        Mp3FrameReader m = mp3;
        AdtsFrameReader a = adts;
        if (aac) {
            if (a == null) throw new IOException("Not connected");
            return a.nextFrame();
        }
        if (m == null) throw new IOException("Not connected");
        return m.nextFrame();
    }

    private void connect() throws IOException {
        URL target = new URL(url);
        HttpURLConnection c = null;
        for (int hop = 0; hop < 8; hop++) {
            if (aborted) throw new IOException("Stopped");
            c = (HttpURLConnection) target.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            c.setRequestProperty("Icy-MetaData", "1");
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Accept", "*/*");
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = c.getHeaderField("Location");
                c.disconnect();
                if (location == null) throw new IOException("The station redirected without a new address");
                target = new URL(target, location);
                continue;
            }
            if (code / 100 != 2) {
                c.disconnect();
                throw new IOException("The station answered HTTP " + code);
            }
            String type = c.getContentType() == null ? "" : c.getContentType().toLowerCase(Locale.US);
            String path = target.getPath() == null ? "" : target.getPath().toLowerCase(Locale.US);
            if (isPlaylist(type, path)) {
                String next = readPlaylistEntry(c.getInputStream());
                c.disconnect();
                if (next == null) throw new IOException("This playlist does not contain a playable stream");
                target = new URL(target, next);
                continue;
            }
            break;
        }
        if (c == null) throw new IOException("Could not connect");

        String type = c.getContentType() == null ? "" : c.getContentType().toLowerCase(Locale.US);
        if (type.contains("ogg") || type.contains("opus") || type.contains("flac") || type.contains("vorbis")) {
            c.disconnect();
            throw new IOException("This station streams Ogg/FLAC audio, which is not supported yet");
        }
        if (type.contains("mpegurl") && !type.contains("x-mpegurl")) {
            c.disconnect();
            throw new IOException("HLS (.m3u8) streams are not supported yet");
        }
        String name = c.getHeaderField("icy-name");
        if (name != null && !name.trim().isEmpty() && listener != null) listener.onStationName(name.trim());
        int metaInt = 0;
        try {
            String header = c.getHeaderField("icy-metaint");
            if (header != null) metaInt = Integer.parseInt(header.trim());
        } catch (NumberFormatException ignored) {
            metaInt = 0;
        }
        InputStream raw = c.getInputStream();
        if (metaInt > 0) {
            raw = new IcyInputStream(raw, metaInt, new IcyInputStream.Listener() {
                @Override
                public void onTitle(String streamTitle) {
                    if (listener != null) listener.onTitle(streamTitle);
                }
            });
        }
        PeekStream peek = new PeekStream(raw);

        int mp3Offset = Mp3FrameReader.probe(peek, SNIFF_BYTES);
        int aacOffset = AdtsFrameReader.probe(peek, SNIFF_BYTES);
        boolean useAac;
        if (mp3Offset < 0 && aacOffset < 0) {
            c.disconnect();
            throw new IOException("Unrecognised audio format (only MP3 and AAC streams are supported)");
        } else if (mp3Offset < 0) {
            useAac = true;
        } else if (aacOffset < 0) {
            useAac = false;
        } else if (mp3Offset != aacOffset) {
            useAac = aacOffset < mp3Offset;
        } else {
            useAac = type.contains("aac");
        }
        Mp3FrameReader newMp3 = null;
        AdtsFrameReader newAdts = null;
        if (useAac) {
            newAdts = new AdtsFrameReader(peek);
            if (!newAdts.prime(SNIFF_BYTES)) {
                c.disconnect();
                throw new IOException("Could not read the AAC stream");
            }
        } else {
            newMp3 = new Mp3FrameReader(peek);
            if (!newMp3.prime(SNIFF_BYTES)) {
                c.disconnect();
                throw new IOException("Could not read the MP3 stream");
            }
        }
        synchronized (this) {
            if (aborted) {
                c.disconnect();
                throw new IOException("Stopped");
            }
            connection = c;
            aac = useAac;
            mp3 = newMp3;
            adts = newAdts;
        }
    }

    private synchronized void closeConnection() {
        HttpURLConnection c = connection;
        connection = null;
        if (c != null) {
            try {
                c.disconnect();
            } catch (RuntimeException ignored) {
                // nothing to do
            }
        }
    }

    private static boolean isPlaylist(String type, String path) {
        return type.contains("x-scpls") || type.contains("pls") || type.contains("x-mpegurl") || path.endsWith(".pls")
                || path.endsWith(".m3u");
    }

    /** Returns the first stream address of a .pls / .m3u playlist. */
    static String readPlaylistEntry(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        int n;
        while (bytes.size() < 16 * 1024 && (n = in.read(buffer)) > 0) bytes.write(buffer, 0, n);
        BufferedReader lines = new BufferedReader(new InputStreamReader(new java.io.ByteArrayInputStream(bytes.toByteArray()), "UTF-8"));
        String line;
        while ((line = lines.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq > 0 && line.regionMatches(true, 0, "File", 0, 4)) line = line.substring(eq + 1).trim();
            String lower = line.toLowerCase(Locale.US);
            if (lower.startsWith("http://") || lower.startsWith("https://")) return line;
        }
        return null;
    }

    private void status(String text) {
        if (listener != null) listener.onStatus(text);
    }
}
