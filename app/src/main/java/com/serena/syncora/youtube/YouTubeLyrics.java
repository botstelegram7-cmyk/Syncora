package com.serena.syncora.youtube;

import com.serena.syncora.lyrics.Lyrics;
import com.serena.syncora.lyrics.LyricsClient;
import com.serena.syncora.lyrics.SubtitleParser;

import java.util.List;

/** Finds lyrics for a YouTube video: synchronized lyrics from LRCLIB first, then the video's captions. */
public final class YouTubeLyrics {

    private YouTubeLyrics() {
    }

    /** Blocking. Returns null when nothing was found. */
    public static Lyrics find(YouTubeVideo video) {
        return find(video, new LyricsClient.Request());
    }

    /** Blocking and cancellable for the LRCLIB lookup. */
    public static Lyrics find(YouTubeVideo video, LyricsClient.Request request) {
        String[] parts = splitTitle(video.title, video.author);
        Lyrics found = request.find(parts[1], parts[0], "", video.durationMs);
        if (found != null && found.synced()) return found;
        if (request.isCancelled()) return null;
        Lyrics captions = captions(video);
        if (captions != null) return captions;
        return found;
    }

    /** The caption track as timed lyrics. Null when there is no usable track. */
    public static Lyrics captions(YouTubeVideo video) {
        YouTubeVideo.Caption caption = pickCaption(video.captions);
        if (caption == null) return null;
        List<SubtitleParser.Cue> cues = YouTubeClient.fetchCues(caption, video.userAgent);
        if (cues.isEmpty()) return null;
        return SubtitleParser.toLyrics(cues, "YouTube captions");
    }

    /**
     * Prefers a human-written track in English, then any human-written track, then automatic English, then any track.
     */
    public static YouTubeVideo.Caption pickCaption(List<YouTubeVideo.Caption> tracks) {
        if (tracks == null || tracks.isEmpty()) return null;
        YouTubeVideo.Caption manualEn = null;
        YouTubeVideo.Caption manual = null;
        YouTubeVideo.Caption autoEn = null;
        for (YouTubeVideo.Caption c : tracks) {
            boolean en = c.languageCode.toLowerCase(java.util.Locale.US).startsWith("en");
            if (!c.auto && en && manualEn == null) manualEn = c;
            if (!c.auto && manual == null) manual = c;
            if (c.auto && en && autoEn == null) autoEn = c;
        }
        if (manualEn != null) return manualEn;
        if (manual != null) return manual;
        if (autoEn != null) return autoEn;
        return tracks.get(0);
    }

    /**
     * Splits "Artist - Title" from a music video title. Without a dash the channel name is the artist. Returns
     * {artist, title}.
     */
    public static String[] splitTitle(String title, String author) {
        String t = title == null ? "" : title;
        int dash = t.indexOf(" - ");
        if (dash > 0 && dash + 3 < t.length()) {
            return new String[]{t.substring(0, dash).trim(), LyricsClient.cleanTitle(t.substring(dash + 3))};
        }
        String artist = author == null ? "" : author.replace(" - Topic", "").replace("VEVO", "").trim();
        return new String[]{artist, LyricsClient.cleanTitle(t)};
    }
}
