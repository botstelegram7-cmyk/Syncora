package com.serena.syncora.lyrics;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Finds synchronised lyrics (or plain lyrics) with the free, open LRCLIB service (lrclib.net). Blocking: call it
 * from a worker thread only.
 */
public final class LyricsClient {

    private static final String BASE = "https://lrclib.net/api";
    private static final String USER_AGENT = "Syncora/0.5 (https://github.com/botstelegram7-cmyk/Syncora)";

    private LyricsClient() {
    }

    /**
     * Looks the song up. {@code artist} may be empty (then a free-text search is used). Returns null when nothing
     * usable was found or the network is unavailable.
     */
    public static Lyrics find(String title, String artist, String album, long durationMs) {
        String cleanTitle = cleanTitle(title);
        if (cleanTitle.isEmpty()) return null;
        try {
            if (!artist.trim().isEmpty()) {
                StringBuilder q = new StringBuilder(BASE + "/get?track_name=").append(enc(cleanTitle))
                        .append("&artist_name=").append(enc(artist.trim()));
                if (album != null && !album.trim().isEmpty()) q.append("&album_name=").append(enc(album.trim()));
                if (durationMs > 0) q.append("&duration=").append(Math.round(durationMs / 1000.0));
                String body = get(q.toString());
                if (body != null) {
                    Lyrics found = fromRecord(new JSONObject(body), "LRCLIB");
                    if (found != null) return found;
                }
            }
            String search = artist.trim().isEmpty()
                    ? BASE + "/search?q=" + enc(cleanTitle)
                    : BASE + "/search?track_name=" + enc(cleanTitle) + "&artist_name=" + enc(artist.trim());
            String body = get(search);
            if (body == null) return null;
            return pickBest(new JSONArray(body), durationMs);
        } catch (IOException | JSONException | RuntimeException e) {
            return null;
        }
    }

    /** Removes decorations that stop a lookup from matching, e.g. "(Official Video)" or "[HD]". */
    public static String cleanTitle(String title) {
        if (title == null) return "";
        String s = title.replaceAll("\\s*[\\(\\[](?i:official|lyrics?|audio|video|hd|hq|4k|remaster(ed)?)[^\\)\\]]*[\\)\\]]", "");
        return s.replaceAll("\\s+", " ").trim();
    }

    /** Lyrics from one LRCLIB record, or null when the record has none. */
    static Lyrics fromRecord(JSONObject record, String source) {
        if (record == null) return null;
        String synced = record.optString("syncedLyrics", "");
        if (!synced.trim().isEmpty()) {
            Lyrics l = LrcParser.parse(synced, source);
            if (!l.isEmpty()) return l;
        }
        String plain = record.optString("plainLyrics", "");
        if (!plain.trim().isEmpty()) return LrcParser.parse(plain, source);
        return null;
    }

    /** Prefers a synchronised record whose length matches the song, then any record with lyrics. */
    static Lyrics pickBest(JSONArray results, long durationMs) {
        Lyrics bestSynced = null;
        long bestDiff = Long.MAX_VALUE;
        Lyrics firstPlain = null;
        for (int i = 0; i < results.length(); i++) {
            JSONObject record = results.optJSONObject(i);
            if (record == null) continue;
            Lyrics l = fromRecord(record, "LRCLIB");
            if (l == null) continue;
            if (l.synced()) {
                long diff = durationMs > 0 ? Math.abs(Math.round(record.optDouble("duration", 0) * 1000) - durationMs)
                        : 0;
                if (bestSynced == null || diff < bestDiff) {
                    bestSynced = l;
                    bestDiff = diff;
                }
            } else if (firstPlain == null) {
                firstPlain = l;
            }
        }
        return bestSynced != null ? bestSynced : firstPlain;
    }

    private static String enc(String value) throws IOException {
        return URLEncoder.encode(value, "UTF-8");
    }

    /** Returns the body of a 200 response, or null for anything else. */
    private static String get(String address) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        try {
            c.setConnectTimeout(6000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Accept", "application/json");
            if (c.getResponseCode() / 100 != 2) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0 && bytes.size() < 2 * 1024 * 1024) bytes.write(buffer, 0, n);
                return bytes.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }
}
