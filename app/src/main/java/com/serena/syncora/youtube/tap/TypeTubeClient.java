package com.serena.syncora.youtube.tap;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads a YouTube video's direct stream links through the TypeTube cluster (the service behind the
 * typetube package: <a href="https://github.com/botstelegram7-cmyk/typetube">typetube</a>). The app
 * uses it when YouTube's own player API does not hand out playable streams.
 *
 * <p>Blocking: call from a worker thread only. Public access is rate limited per IP address by the
 * cluster (about 60 resolves a minute and 1500 a day).
 */
public final class TypeTubeClient {

    /** One audio-only stream (an itag 140/141/251 style googlevideo URL). */
    public static final class AudioStream {
        public final int itag;
        public final String quality;
        public final String mimeType;
        public final long bitrate;
        public final String url;
        public final boolean hiFi;

        public AudioStream(int itag, String quality, String mimeType, long bitrate, String url, boolean hiFi) {
            this.itag = itag;
            this.quality = quality == null ? "" : quality;
            this.mimeType = mimeType == null ? "" : mimeType;
            this.bitrate = bitrate;
            this.url = url == null ? "" : url;
            this.hiFi = hiFi;
        }
    }

    /** One video stream (adaptive, without sound). */
    public static final class VideoStream {
        public final int itag;
        public final String quality;
        public final String resolution;
        public final String mimeType;
        public final String url;

        public VideoStream(int itag, String quality, String resolution, String mimeType, String url) {
            this.itag = itag;
            this.quality = quality == null ? "" : quality;
            this.resolution = resolution == null ? "" : resolution;
            this.mimeType = mimeType == null ? "" : mimeType;
            this.url = url == null ? "" : url;
        }
    }

    /** Everything TypeTube knows about one resolved video. */
    public static final class Track {
        public final String id;
        public final String title;
        public final String author;
        public final String uploader;
        public final long durationSeconds;
        public final String thumbnail;
        public final List<AudioStream> audioStreams;
        public final List<VideoStream> videoStreams;

        public Track(String id, String title, String author, String uploader, long durationSeconds, String thumbnail,
              List<AudioStream> audioStreams, List<VideoStream> videoStreams) {
            this.id = id == null ? "" : id;
            this.title = title == null ? "" : title;
            this.author = author == null ? "" : author;
            this.uploader = uploader == null || uploader.isEmpty() ? this.author : uploader;
            this.durationSeconds = durationSeconds;
            this.thumbnail = thumbnail == null ? "" : thumbnail;
            this.audioStreams = Collections.unmodifiableList(new ArrayList<>(audioStreams));
            this.videoStreams = Collections.unmodifiableList(new ArrayList<>(videoStreams));
        }
    }

    /** Thrown when TypeTube answers that it could not resolve the query. */
    public static final class ResolveException extends IOException {
        public ResolveException(String message) {
            super(message);
        }
    }

    private final String host;
    private final int port;
    private final int timeoutMs;
    private TapConnection connection;

    public TypeTubeClient() {
        this(TapConnection.DEFAULT_HOST, TapConnection.DEFAULT_PORT, 15000);
    }

    public TypeTubeClient(String host, int port, int timeoutMs) {
        this.host = host;
        this.port = port;
        this.timeoutMs = timeoutMs;
    }

    /**
     * Resolves a video URL, an 11-character video id or a search query into direct stream links.
     * Reconnects and tries once more when the connection drops mid-call.
     */
    public synchronized Track resolve(String queryOrUrl) throws IOException {
        byte[] payload = queryOrUrl.trim().getBytes("UTF-8");
        try {
            return invokeResolve(payload);
        } catch (IOException first) {
            // one reconnect, like the typetube client's autoRetry
            drop();
            try {
                return invokeResolve(payload);
            } catch (IOException second) {
                // a real answer from the cluster beats a connection blip
                boolean answered = second instanceof ResolveException
                        || (second.getMessage() != null && second.getMessage().startsWith("TypeTube:"));
                throw answered ? second : first;
            }
        }
    }

    private Track invokeResolve(byte[] payload) throws IOException {
        if (connection == null || !connection.isConnected()) {
            drop();
            connection = new TapConnection(host, port, timeoutMs);
        }
        byte[] answer = connection.invoke("resolve", payload);
        return parseTrackEnvelope(answer);
    }

    private void drop() {
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }

    /** Closes the cluster connection (the next resolve opens a new one). */
    public synchronized void close() {
        drop();
    }

    /** The TrackResult envelope from protocol.ts. Public for tests. */
    static Track parseTrackEnvelope(byte[] payload) throws IOException {
        TapProto r = new TapProto(payload);
        boolean success = false;
        Track track = null;
        String errorMessage = null;
        while (r.hasMore()) {
            int tag = r.readTag();
            if (tag < 0) break;
            int field = TapProto.fieldOf(tag);
            int wire = TapProto.wireOf(tag);
            if (field == 1) {
                success = r.readVarint() == 1;
            } else if (field == 2) {
                track = readTrack(r.readMessage());
            } else if (field == 4) {
                errorMessage = readErrorMessage(r.readMessage());
            } else {
                r.skip(wire);
            }
        }
        if (!success) {
            throw new ResolveException(errorMessage == null || errorMessage.isEmpty()
                    ? "TypeTube could not resolve this video" : errorMessage);
        }
        if (track == null) throw new IOException("TypeTube sent an empty answer");
        return track;
    }

    private static String readErrorMessage(TapProto r) throws IOException {
        String message = "";
        while (r.hasMore()) {
            int tag = r.readTag();
            if (tag < 0) break;
            int field = TapProto.fieldOf(tag);
            if (field == 2) message = r.readString();
            else r.skip(TapProto.wireOf(tag));
        }
        return message;
    }

    private static Track readTrack(TapProto r) throws IOException {
        String id = "", title = "", author = "", uploader = "", thumbnail = "";
        long durationSeconds = 0;
        List<AudioStream> audioStreams = new ArrayList<>();
        List<VideoStream> videoStreams = new ArrayList<>();
        AudioStream bestAudio = null;
        while (r.hasMore()) {
            int tag = r.readTag();
            if (tag < 0) break;
            int field = TapProto.fieldOf(tag);
            int wire = TapProto.wireOf(tag);
            switch (field) {
                case 1:
                    id = r.readString();
                    break;
                case 2:
                    title = r.readString();
                    break;
                case 3:
                    author = r.readString();
                    break;
                case 4:
                    uploader = r.readString();
                    break;
                case 6:
                    durationSeconds = r.readVarint();
                    break;
                case 7:
                    thumbnail = r.readString();
                    break;
                case 9:
                    bestAudio = readAudioStream(r.readMessage());
                    break;
                case 11:
                    audioStreams.add(readAudioStream(r.readMessage()));
                    break;
                case 12:
                    videoStreams.add(readVideoStream(r.readMessage()));
                    break;
                default:
                    r.skip(wire);
                    break;
            }
        }
        // Keep the server's preferred stream even if it omitted it from the full adaptive-format list.
        if (bestAudio != null) {
            boolean present = false;
            for (AudioStream stream : audioStreams) {
                if ((bestAudio.itag != 0 && stream.itag == bestAudio.itag)
                        || (!bestAudio.url.isEmpty() && bestAudio.url.equals(stream.url))) {
                    present = true;
                    break;
                }
            }
            if (!present) audioStreams.add(0, bestAudio);
        }
        return new Track(id, title, author, uploader, durationSeconds, thumbnail, audioStreams, videoStreams);
    }

    private static AudioStream readAudioStream(TapProto r) throws IOException {
        int itag = 0;
        long bitrate = 0;
        boolean hiFi = false;
        String quality = "", mimeType = "", url = "";
        while (r.hasMore()) {
            int tag = r.readTag();
            if (tag < 0) break;
            int field = TapProto.fieldOf(tag);
            int wire = TapProto.wireOf(tag);
            switch (field) {
                case 1:
                    itag = (int) r.readVarint();
                    break;
                case 2:
                    quality = r.readString();
                    break;
                case 3:
                    mimeType = r.readString();
                    break;
                case 4:
                    bitrate = r.readVarint();
                    break;
                case 5:
                    url = r.readString();
                    break;
                case 6:
                    hiFi = r.readVarint() == 1;
                    break;
                default:
                    r.skip(wire);
                    break;
            }
        }
        return new AudioStream(itag, quality, mimeType, bitrate, url, hiFi);
    }

    private static VideoStream readVideoStream(TapProto r) throws IOException {
        int itag = 0;
        String quality = "", resolution = "", mimeType = "", url = "";
        while (r.hasMore()) {
            int tag = r.readTag();
            if (tag < 0) break;
            int field = TapProto.fieldOf(tag);
            int wire = TapProto.wireOf(tag);
            switch (field) {
                case 1:
                    itag = (int) r.readVarint();
                    break;
                case 2:
                    quality = r.readString();
                    break;
                case 3:
                    resolution = r.readString();
                    break;
                case 4:
                    mimeType = r.readString();
                    break;
                case 5:
                    url = r.readString();
                    break;
                default:
                    r.skip(wire);
                    break;
            }
        }
        return new VideoStream(itag, quality, resolution, mimeType, url);
    }
}
