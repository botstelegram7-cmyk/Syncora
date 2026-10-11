package com.serena.syncora.youtube;

import java.util.Locale;

/** One downloadable / playable stream of a YouTube video. */
public final class YouTubeFormat {

    public enum Kind {
        /** Video and audio in one file (small resolutions). */
        MUXED,
        /** Video only (needs an audio stream to be muxed). */
        VIDEO_ONLY,
        /** Audio only. */
        AUDIO_ONLY
    }

    public final int itag;
    public final Kind kind;
    /** The MIME type without parameters, e.g. {@code video/mp4} or {@code audio/webm}. */
    public final String mime;
    /** The codecs parameter, e.g. {@code avc1.42001E, mp4a.40.2}. Empty when unknown. */
    public final String codecs;
    public final String url;
    public final long bitrate;
    public final int width;
    public final int height;
    public final String qualityLabel;
    public final int sampleRate;
    public final int channels;
    public final long contentLength;

    public YouTubeFormat(int itag, Kind kind, String mime, String codecs, String url, long bitrate, int width, int height,
                         String qualityLabel, int sampleRate, int channels, long contentLength) {
        this.itag = itag;
        this.kind = kind;
        this.mime = mime == null ? "" : mime;
        this.codecs = codecs == null ? "" : codecs;
        this.url = url == null ? "" : url;
        this.bitrate = bitrate;
        this.width = width;
        this.height = height;
        this.qualityLabel = qualityLabel == null ? "" : qualityLabel;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.contentLength = contentLength;
    }

    public boolean isAudio() {
        return kind == Kind.AUDIO_ONLY;
    }

    public boolean isMp4() {
        return mime.contains("mp4");
    }

    /** AAC audio (MPEG-4 container audio), which Android decodes and muxes everywhere. */
    public boolean isAac() {
        return codecs.toLowerCase(Locale.US).contains("mp4a");
    }

    /** H.264 video, which can be muxed into an MP4 file with AAC audio. */
    public boolean isH264() {
        return codecs.toLowerCase(Locale.US).contains("avc1");
    }

    public boolean isOpus() {
        return codecs.toLowerCase(Locale.US).contains("opus");
    }

    /** File extension for saving this stream as it is. */
    public String extension() {
        if (isAudio()) return isAac() || mime.contains("mp4") ? "m4a" : "webm";
        return isMp4() ? "mp4" : "webm";
    }

    /** Short human label, e.g. "AAC · 128 kbps" or "1080p MP4". */
    public String label() {
        if (isAudio()) {
            String codec = isAac() ? "AAC" : (isOpus() ? "Opus" : "Audio");
            return codec + " \u00B7 " + Math.round(bitrate / 1000.0) + " kbps";
        }
        String quality = !qualityLabel.isEmpty() ? qualityLabel : height + "p";
        return quality + " \u00B7 " + extension().toUpperCase(Locale.US);
    }
}
