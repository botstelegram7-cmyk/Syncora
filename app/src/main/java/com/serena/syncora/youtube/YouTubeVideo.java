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
            this.baseUrl = baseUrl == null ? "" : baseUrl;
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
     * Audio streams in decoder-friendly order. AAC is first because it is supported by every Android decoder; Opus
     * is the next choice, followed by any other advertised codec. Higher bitrates win within each codec group.
     * Remaining entries are useful as fallbacks if a particular CDN URL cannot be opened on this device.
     */
    public List<YouTubeFormat> playbackAudioFormats() {
        List<YouTubeFormat> out = audioFormats();
        Collections.sort(out, new java.util.Comparator<YouTubeFormat>() {
            @Override
            public int compare(YouTubeFormat a, YouTubeFormat b) {
                int preference = Integer.compare(audioPreference(a), audioPreference(b));
                return preference != 0 ? preference : Long.compare(b.bitrate, a.bitrate);
            }
        });
        List<YouTubeFormat> unique = new ArrayList<>();
        for (YouTubeFormat f : out) {
            boolean seen = false;
            for (YouTubeFormat existing : unique) {
                if ((!f.url.isEmpty() && f.url.equals(existing.url)) || (f.url.isEmpty() && f.itag == existing.itag)) {
                    seen = true;
                    break;
                }
            }
            if (!seen) unique.add(f);
        }
        return unique;
    }

    private static int audioPreference(YouTubeFormat format) {
        if (format.isAac()) return 0;
        if (format.isOpus()) return 1;
        return 2;
    }

    /** The first decoder-friendly audio stream, or null if the video has no audio-only streams. */
    public YouTubeFormat bestAudioForPlayback() {
        List<YouTubeFormat> ordered = playbackAudioFormats();
        return ordered.isEmpty() ? null : ordered.get(0);
    }

    /** Copies this metadata and streams with a caption list fetched from YouTube's player API. */
    public YouTubeVideo withCaptions(List<Caption> newCaptions) {
        return new YouTubeVideo(id, title, author, durationMs, thumbnailUrl, userAgent, formats, newCaptions);
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
