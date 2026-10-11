package com.serena.syncora.youtube;

import com.serena.syncora.youtube.tap.TypeTubeClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads a video's streams through the TypeTube cluster (the typetube package) and presents them as the
 * same {@link YouTubeVideo} the YouTube page already plays and downloads. TypeTube is the primary way
 * Syncora resolves YouTube videos; {@link YouTubeClient} (YouTube's own player API) is the fallback.
 */
public final class TypeTube {

    private static final TypeTubeClient CLIENT = new TypeTubeClient();

    private TypeTube() {
    }

    /** Looks the video up on TypeTube. Throws an IOException with a readable reason when it cannot. */
    public static YouTubeVideo fetch(String videoId) throws IOException {
        TypeTubeClient.Track track = CLIENT.resolve(YouTubeIds.watchUrl(videoId));
        return toVideo(track, videoId);
    }

    /** Turns a TypeTube track into the video model the rest of the app uses. Public for tests. */
    public static YouTubeVideo toVideo(TypeTubeClient.Track track, String fallbackId) throws IOException {
        String id = track.id.isEmpty() ? fallbackId : track.id;
        List<YouTubeFormat> formats = new ArrayList<>();
        for (TypeTubeClient.AudioStream a : track.audioStreams) {
            if (a.url.isEmpty()) continue;
            formats.add(new YouTubeFormat(a.itag, YouTubeFormat.Kind.AUDIO_ONLY,
                    mimeOf(a.mimeType), codecsOf(a.mimeType), a.url, a.bitrate,
                    0, 0, "", 0, 2, 0));
        }
        for (TypeTubeClient.VideoStream v : track.videoStreams) {
            if (v.url.isEmpty()) continue;
            formats.add(new YouTubeFormat(v.itag, YouTubeFormat.Kind.VIDEO_ONLY,
                    mimeOf(v.mimeType), codecsOf(v.mimeType), v.url, 0,
                    0, heightOf(v.resolution), v.resolution, 0, 2, 0));
        }
        if (formats.isEmpty()) {
            throw new IOException("TypeTube found no playable streams for this video");
        }
        String thumb = track.thumbnail;
        if (thumb.isEmpty()) thumb = String.format(Locale.US, "https://i.ytimg.com/vi/%s/hqdefault.jpg", id);
        return new YouTubeVideo(id, track.title, track.author, track.durationSeconds * 1000L, thumb,
                YouTubeDownloader.USER_AGENT, formats, new ArrayList<YouTubeVideo.Caption>());
    }

    /** {@code audio/mp4; codecs="mp4a.40.2"} to {@code audio/mp4}. */
    private static String mimeOf(String mimeType) {
        int at = mimeType.indexOf(';');
        return (at < 0 ? mimeType : mimeType.substring(0, at)).trim();
    }

    /** {@code audio/mp4; codecs="mp4a.40.2"} to {@code mp4a.40.2}. */
    private static String codecsOf(String mimeType) {
        int start = mimeType.toLowerCase(Locale.US).indexOf("codecs=");
        if (start < 0) return "";
        String rest = mimeType.substring(start + 7).trim();
        if (rest.startsWith("\"")) rest = rest.substring(1);
        int end = rest.indexOf('"');
        return (end < 0 ? rest : rest.substring(0, end)).trim();
    }

    /** {@code 1080p} to 1080; 0 when unknown. */
    private static int heightOf(String resolution) {
        String digits = resolution.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return 0;
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
