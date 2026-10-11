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
 * Internet radio: pulls MP3 or AAC audio from a direct stream or HLS playlist, strips stream metadata and decodes to
 * PCM. The player does this once, so speakers on the room never need internet access of their own.
 */
public final class RadioSource implements PcmSource {

    public interface Listener {
        void onStationName(String name);

        void onTitle(String streamTitle);

        void onStatus(String status);
    }

    private static final String USER_AGENT = "Syncora/0.6 (Android; Internet radio)";
    private static final int MAX_RETRIES = 6;
    /** How much of the stream is read before deciding whether it is MP3 or AAC (a few seconds of audio). */
    private static final int SNIFF_BYTES = 48 * 1024;

    private final String url;
    private final Listener listener;
    private final ArrayBlockingQueue<byte[]> frames = new ArrayBlockingQueue<>(300);

    private PcmCodec codec;
    private Thread reader;
    private volatile boolean aborted;
    private volatile boolean readerDone;
    private volatile IOException readerError;
    private volatile HttpURLConnection connection;
    private volatile HlsStream hlsStream;
    private volatile HlsStream.HttpFetcher hlsFetcher;
    private volatile Mp3FrameReader mp3;
    private volatile AdtsFrameReader adts;
    private volatile boolean aac;
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
        HlsStream stream = hlsStream;
        status(stream == null ? "Live" : stream.describe());
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

    /** Follows redirects and ordinary .pls/.m3u playlists before choosing a direct or HLS audio stream. */
    private void connect() throws IOException {
        URL target = new URL(url);
        for (int hop = 0; hop < 8; hop++) {
            if (aborted) throw new IOException("Stopped");
            HttpURLConnection c = (HttpURLConnection) target.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            c.setRequestProperty("Icy-MetaData", "1");
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Accept", "*/*");
            int code;
            try {
                code = c.getResponseCode();
            } catch (IOException e) {
                c.disconnect();
                throw e;
            }
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
            if (isHls(type, path)) {
                c.disconnect();
                connectHls(target.toExternalForm());
                return;
            }
            if (isPlaylist(type, path)) {
                String next;
                try (InputStream in = c.getInputStream()) {
                    next = readPlaylistEntry(in);
                } finally {
                    c.disconnect();
                }
                if (next == null) throw new IOException("This playlist does not contain a playable stream");
                target = new URL(target, next);
                continue;
            }
            if (type.contains("ogg") || type.contains("opus") || type.contains("flac") || type.contains("vorbis")) {
                c.disconnect();
                throw new IOException("This station streams Ogg/FLAC audio, which is not supported yet");
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
            InputStream raw;
            try {
                raw = c.getInputStream();
            } catch (IOException e) {
                c.disconnect();
                throw e;
            }
            if (metaInt > 0) {
                raw = new IcyInputStream(raw, metaInt, new IcyInputStream.Listener() {
                    @Override
                    public void onTitle(String streamTitle) {
                        if (listener != null) listener.onTitle(streamTitle);
                    }
                });
            }
            synchronized (this) {
                if (aborted) {
                    c.disconnect();
                    throw new IOException("Stopped");
                }
                connection = c;
            }
            try {
                configureAudio(raw, type);
            } catch (IOException e) {
                closeDirectConnection(c);
                throw e;
            }
            return;
        }
        throw new IOException("Too many redirects or playlists from " + url);
    }

    /** Opens the HLS media playlist and sniffs its elementary audio (raw AAC/MP3 or AAC/MP3 in MPEG-TS). */
    private void connectHls(String address) throws IOException {
        final HlsStream.HttpFetcher fetcher = new HlsStream.HttpFetcher(USER_AGENT, 15_000);
        synchronized (this) {
            if (aborted) {
                fetcher.close();
                throw new IOException("Stopped");
            }
            hlsFetcher = fetcher;
        }

        HlsStream stream = null;
        try {
            stream = HlsStream.open(address, fetcher, new HlsStream.Listener() {
                @Override
                public void onInfo(String info) {
                    status(info);
                }
            });
            synchronized (this) {
                if (aborted || hlsFetcher != fetcher) {
                    stream.close();
                    throw new IOException("Stopped");
                }
                hlsStream = stream;
            }
            configureAudio(stream, "application/vnd.apple.mpegurl");
        } catch (IOException e) {
            if (stream != null) stream.close();
            fetcher.close();
            synchronized (this) {
                if (hlsStream == stream) hlsStream = null;
                if (hlsFetcher == fetcher) hlsFetcher = null;
            }
            throw e;
        } catch (RuntimeException e) {
            if (stream != null) stream.close();
            fetcher.close();
            synchronized (this) {
                if (hlsStream == stream) hlsStream = null;
                if (hlsFetcher == fetcher) hlsFetcher = null;
            }
            throw new IOException("Could not open the HLS stream", e);
        }
    }

    /** Detects the transport container, then selects the matching MP3 or AAC frame reader. */
    private void configureAudio(InputStream raw, String type) throws IOException {
        PeekStream initial = new PeekStream(raw);
        MpegTsReader transport = null;
        PeekStream audio = initial;
        if (MpegTsReader.looksLikeTransportStream(initial)) {
            transport = new MpegTsReader(initial);
            audio = new PeekStream(transport);
        }

        int mp3Offset = Mp3FrameReader.probe(audio, SNIFF_BYTES);
        int aacOffset = AdtsFrameReader.probe(audio, SNIFF_BYTES);
        boolean useAac;
        if (mp3Offset < 0 && aacOffset < 0) {
            String sent;
            if (transport != null) {
                sent = transport.describe();
            } else {
                sent = type.isEmpty() ? "an unknown format" : type;
            }
            throw new IOException("Could not find MP3 or AAC audio in this stream (it sent " + sent + ")");
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
            newAdts = new AdtsFrameReader(audio);
            if (!newAdts.prime(SNIFF_BYTES)) throw new IOException("Could not read the AAC stream");
        } else {
            newMp3 = new Mp3FrameReader(audio);
            if (!newMp3.prime(SNIFF_BYTES)) throw new IOException("Could not read the MP3 stream");
        }
        synchronized (this) {
            if (aborted) throw new IOException("Stopped");
            aac = useAac;
            mp3 = newMp3;
            adts = newAdts;
        }
    }

    private void closeDirectConnection(HttpURLConnection target) {
        synchronized (this) {
            if (connection != target) return;
            connection = null;
        }
        try {
            target.disconnect();
        } catch (RuntimeException ignored) {
            // nothing to do
        }
    }

    private void closeConnection() {
        HttpURLConnection c;
        HlsStream stream;
        HlsStream.HttpFetcher fetcher;
        synchronized (this) {
            c = connection;
            connection = null;
            stream = hlsStream;
            hlsStream = null;
            fetcher = hlsFetcher;
            hlsFetcher = null;
        }
        if (stream != null) stream.abort();
        if (fetcher != null) fetcher.close();
        if (c != null) {
            try {
                c.disconnect();
            } catch (RuntimeException ignored) {
                // nothing to do
            }
        }
    }

    private static boolean isHls(String type, String path) {
        return path.endsWith(".m3u8") || (type.contains("mpegurl") && !path.endsWith(".m3u"));
    }

    private static boolean isPlaylist(String type, String path) {
        return type.contains("x-scpls") || type.contains("pls+xml") || type.contains("x-mpegurl")
                || path.endsWith(".pls") || path.endsWith(".m3u");
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
