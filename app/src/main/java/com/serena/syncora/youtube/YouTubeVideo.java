package com.serena.syncora.youtube;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** What YouTube told us about one video: details, streams and caption tracks. */
public final class YouTubeVideo {

    /** A subtitle or closed-caption track. {@code auto} is true for automatic speech recognition captions. */
    public static final class Caption {
        public final String languageCode;
        public final String name;
        public final String baseUrl;
        public final boolean auto;

        public Caption(String languageCode, String name, String baseUrl, boolean auto) {
            this.languageCode = languageCode == null ? "" : languageCode;
            this.name = name == null || name.isEmpty() ? this.languageCode : name;
            this.baseUrl = baseUrl;
            this.auto = auto;
        }
    }

    public final String id;
    public final String title;
    public final String author;
    public final long durationMs;
    public final String thumbnailUrl;
    public final String userAgent;
    public final List<YouTubeFormat> formats;
    public final List<Caption> captions;

    public YouTubeVideo(String id, String title, String author, long durationMs, String thumbnailUrl, String userAgent,
                        List<YouTubeFormat> formats, List<Caption> captions) {
        this.id = id;
        this.title = title == null ? "" : title;
        this.author = author == null ? "" : author;
        this.durationMs = durationMs;
        this.thumbnailUrl = thumbnailUrl;
        this.userAgent = userAgent;
        this.formats = Collections.unmodifiableList(new ArrayList<>(formats));
        this.captions = Collections.unmodifiableList(new ArrayList<>(captions));
    }

    /** Art key used on the wire and in the artwork cache. */
    public String artKey() {
        return "y" + id;
    }

    /** Audio-only streams, best bitrate first. */
    public List<YouTubeFormat> audioFormats() {
        List<YouTubeFormat> out = new ArrayList<>();
        for (YouTubeFormat f : formats) if (f.isAudio()) out.add(f);
        Collections.sort(out, new java.util.Comparator<YouTubeFormat>() {
            @Override
            public int compare(YouTubeFormat a, YouTubeFormat b) {
                return Long.compare(b.bitrate, a.bitrate);
            }
        });
        return out;
    }

    /**
     * The best audio stream to play in sync. AAC is preferred because every Android decoder handles it; the
     * highest bitrate wins within that choice.
     */
    public YouTubeFormat bestAudioForPlayback() {
        YouTubeFormat best = null;
        for (YouTubeFormat f : audioFormats()) {
            if (best == null || (f.isAac() && !best.isAac())) best = f;
        }
        return best;
    }

    /**
     * Video formats that the app can save as an MP4 file with audio: muxed MP4 files and H.264 video streams (the
     * matching AAC audio is added while saving). One entry per resolution, best bitrate first.
     */
    public List<YouTubeFormat> mp4VideoFormats() {
        List<YouTubeFormat> out = new ArrayList<>();
        for (YouTubeFormat f : formats) {
            if (f.isAudio() || f.height <= 0) continue;
            if (f.kind == YouTubeFormat.Kind.MUXED && f.isMp4()) {
                out.add(f);
            } else if (f.kind == YouTubeFormat.Kind.VIDEO_ONLY && f.isMp4() && f.isH264()) {
                out.add(f);
            }
        }
        Collections.sort(out, new java.util.Comparator<YouTubeFormat>() {
            @Override
            public int compare(YouTubeFormat a, YouTubeFormat b) {
                if (a.height != b.height) return Integer.compare(b.height, a.height);
                return Long.compare(b.bitrate, a.bitrate);
            }
        });
        // one per height
        List<YouTubeFormat> unique = new ArrayList<>();
        for (YouTubeFormat f : out) {
            boolean seen = false;
            for (YouTubeFormat u : unique) if (u.height == f.height) seen = true;
            if (!seen) unique.add(f);
        }
        return unique;
    }

    /** The best AAC audio stream, used to add sound to a video-only stream. Null when there is none. */
    public YouTubeFormat bestMuxAudio() {
        YouTubeFormat best = null;
        for (YouTubeFormat f : audioFormats()) {
            if (f.isAac() && (best == null || f.bitrate > best.bitrate)) best = f;
        }
        return best;
    }
}
