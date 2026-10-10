package com.serena.syncora.youtube;

import java.util.Locale;
import java.util.regex.Pattern;

/** Finds the 11-character video id in a pasted YouTube address (or accepts a bare id). */
public final class YouTubeIds {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    private YouTubeIds() {
    }

    /** Returns the video id, or null when the text does not contain one. */
    public static String parse(String input) {
        if (input == null) return null;
        String s = input.trim();
        if (s.isEmpty()) return null;
        if (ID.matcher(s).matches()) return s;
        String lower = s.toLowerCase(Locale.US);
        if (!lower.contains("youtu")) return null;
        String[] markers = {"v=", "youtu.be/", "/shorts/", "/embed/", "/live/", "/v/"};
        for (String marker : markers) {
            int at = lower.indexOf(marker);
            if (at < 0) continue;
            int start = at + marker.length();
            int end = start;
            while (end < s.length() && end - start < 11 && isIdChar(s.charAt(end))) end++;
            String candidate = s.substring(start, end);
            if (candidate.length() == 11 && ID.matcher(candidate).matches()) return candidate;
        }
        return null;
    }

    private static boolean isIdChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
    }

    /** Address of the video's standard page, for the user's browser. */
    public static String watchUrl(String id) {
        return "https://www.youtube.com/watch?v=" + id;
    }
}
