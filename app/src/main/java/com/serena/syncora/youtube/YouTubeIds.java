package com.serena.syncora.youtube;

import java.net.URI;
import java.net.URLDecoder;
import java.util.Locale;
import java.util.regex.Pattern;

/** Finds a video id or playlist id in common YouTube links (or accepts a bare video id). */
public final class YouTubeIds {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Pattern PLAYLIST_ID = Pattern.compile("[A-Za-z0-9_-]{2,128}");

    private YouTubeIds() {
    }

    /** Returns the 11-character video id, or null when the text is not a recognized YouTube video link. */
    public static String parse(String input) {
        if (input == null) return null;
        String value = input.trim();
        if (ID.matcher(value).matches()) return value;
        URI uri = youtubeUri(value);
        if (uri == null) return null;

        String host = uri.getHost().toLowerCase(Locale.US);
        if ("youtu.be".equals(host)) return firstPathId(uri.getRawPath());

        String video = queryParameter(uri.getRawQuery(), "v");
        if (isVideoId(video)) return video;
        String path = uri.getRawPath();
        if (path == null) return null;
        String[] markers = {"/shorts/", "/embed/", "/live/", "/v/"};
        String lower = path.toLowerCase(Locale.US);
        for (String marker : markers) {
            int at = lower.indexOf(marker);
            if (at >= 0) {
                String candidate = firstPathId(path.substring(at + marker.length()));
                if (candidate != null) return candidate;
            }
        }
        return null;
    }

    /** Returns the playlist id from a YouTube playlist/watch/share URL, or null if it has no list parameter. */
    public static String playlistId(String input) {
        URI uri = youtubeUri(input);
        if (uri == null) return null;
        String id = queryParameter(uri.getRawQuery(), "list");
        return id != null && PLAYLIST_ID.matcher(id).matches() ? id : null;
    }

    private static URI youtubeUri(String input) {
        if (input == null) return null;
        String value = input.trim();
        if (value.isEmpty() || !value.toLowerCase(Locale.US).contains("youtu")) return null;
        if (value.startsWith("//")) value = "https:" + value;
        else if (!value.matches("(?i)^https?://.*")) value = "https://" + value;
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (host == null || !isYouTubeHost(host.toLowerCase(Locale.US))) return null;
            return uri;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isYouTubeHost(String host) {
        return "youtube.com".equals(host) || host.endsWith(".youtube.com") || "youtu.be".equals(host)
                || "youtube-nocookie.com".equals(host) || host.endsWith(".youtube-nocookie.com");
    }

    private static String firstPathId(String path) {
        if (path == null) return null;
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length; i++) {
            if (isVideoId(parts[i])) return parts[i];
        }
        return null;
    }

    private static boolean isVideoId(String value) {
        return value != null && ID.matcher(value).matches();
    }

    private static String queryParameter(String query, String key) {
        if (query == null || query.isEmpty()) return null;
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int equals = pair.indexOf('=');
            String rawKey = equals < 0 ? pair : pair.substring(0, equals);
            if (!key.equalsIgnoreCase(decode(rawKey))) continue;
            String rawValue = equals < 0 ? "" : pair.substring(equals + 1);
            return decode(rawValue);
        }
        return null;
    }

    private static String decode(String text) {
        try {
            return URLDecoder.decode(text, "UTF-8");
        } catch (Exception ignored) {
            return text;
        }
    }

    /** Address of the video's standard page, for the user's browser. */
    public static String watchUrl(String id) {
        return "https://www.youtube.com/watch?v=" + id;
    }
}
