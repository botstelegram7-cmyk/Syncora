package com.serena.syncora.lyrics;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.Normalizer;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Finds synchronised lyrics (or plain lyrics) with the free, open LRCLIB service (lrclib.net).
 * Blocking: call it from a worker thread only. Use {@link Request} when the caller needs cancellation.
 */
public final class LyricsClient {

    private static final String BASE = "https://lrclib.net/api";
    private static final String USER_AGENT = "Syncora/0.8 (https://github.com/botstelegram7-cmyk/Syncora)";
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    private LyricsClient() {
    }

    /**
     * A single cancellable lookup. {@link #cancel()} disconnects the active HTTP connection, not just the worker
     * thread, so leaving a track does not leave an obsolete request holding a socket or response body open.
     */
    public static final class Request {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile HttpURLConnection active;
        private volatile String failure = "";

        public void cancel() {
            cancelled.set(true);
            HttpURLConnection connection = active;
            if (connection != null) connection.disconnect();
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        /** A user-readable network failure, empty when the request completed without a transport error. */
        public String failure() {
            return failure;
        }

        public Lyrics find(String title, String artist, String album, long durationMs) {
            String cleanTitle = cleanTitle(title);
            if (cleanTitle.isEmpty()) return null;
            try {
                checkCancelled();
                if (artist != null && !artist.trim().isEmpty()) {
                    StringBuilder address = new StringBuilder(BASE + "/get?track_name=").append(enc(cleanTitle))
                            .append("&artist_name=").append(enc(artist.trim()));
                    if (album != null && !album.trim().isEmpty()) address.append("&album_name=").append(enc(album.trim()));
                    if (durationMs > 0) address.append("&duration=").append(Math.round(durationMs / 1000.0));
                    String body = get(address.toString());
                    if (body != null) {
                        JSONObject record = new JSONObject(body);
                        if (matches(record, cleanTitle, artist, durationMs)) {
                            Lyrics found = fromRecord(record, "LRCLIB");
                            if (found != null) return found;
                        }
                    }
                }
                checkCancelled();
                String safeArtist = artist == null ? "" : artist.trim();
                String search = safeArtist.isEmpty()
                        ? BASE + "/search?q=" + enc(cleanTitle)
                        : BASE + "/search?track_name=" + enc(cleanTitle) + "&artist_name=" + enc(safeArtist);
                String body = get(search);
                if (body == null) return null;
                return pickBest(new JSONArray(body), cleanTitle, safeArtist, durationMs);
            } catch (InterruptedIOException e) {
                failure = cancelled.get() ? "" : "Lyrics request timed out";
                return null;
            } catch (IOException e) {
                failure = cancelled.get() ? "" : "Lyrics service is unavailable";
                return null;
            } catch (JSONException | RuntimeException e) {
                failure = cancelled.get() ? "" : "Lyrics service returned an invalid response";
                return null;
            } finally {
                HttpURLConnection connection = active;
                active = null;
                if (connection != null) connection.disconnect();
            }
        }

        private void checkCancelled() throws InterruptedIOException {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("lyrics request cancelled");
            }
        }

        private String get(String address) throws IOException {
            checkCancelled();
            HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
            active = connection;
            try {
                // Close the cancel-before-publication race: cancel() may run after the first check but before
                // this connection becomes visible in `active`.
                checkCancelled();
                connection.setConnectTimeout(6000);
                connection.setReadTimeout(10000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setRequestProperty("Accept", "application/json");
                connection.setUseCaches(false);
                int status = connection.getResponseCode();
                checkCancelled();
                if (status / 100 != 2) {
                    if (status == 429) failure = "Lyrics service is busy; try again shortly";
                    else if (status >= 500) failure = "Lyrics service is temporarily unavailable";
                    InputStream error = connection.getErrorStream();
                    if (error != null) try { error.close(); } catch (IOException ignored) { }
                    return null;
                }
                try (InputStream in = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = in.read(buffer)) >= 0) {
                        checkCancelled();
                        if (n == 0) continue;
                        if (bytes.size() + n > MAX_RESPONSE_BYTES) throw new IOException("lyrics response too large");
                        bytes.write(buffer, 0, n);
                    }
                    return bytes.toString("UTF-8");
                }
            } finally {
                if (active == connection) active = null;
                connection.disconnect();
            }
        }
    }

    /** Convenience lookup for callers without a cancellation handle. */
    public static Lyrics find(String title, String artist, String album, long durationMs) {
        return new Request().find(title, artist, album, durationMs);
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
            Lyrics lyrics = LrcParser.parse(synced, source);
            if (!lyrics.isEmpty()) return lyrics;
        }
        String plain = record.optString("plainLyrics", "");
        if (!plain.trim().isEmpty()) return LrcParser.parse(plain, source);
        return null;
    }

    /**
     * Prefers a synchronized result with a strong title/artist match and a close duration. Unrelated records are
     * rejected instead of displaying plausible-looking but incorrect words.
     */
    static Lyrics pickBest(JSONArray results, String title, String artist, long durationMs) {
        Lyrics best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < results.length(); i++) {
            JSONObject record = results.optJSONObject(i);
            if (record == null || !matches(record, title, artist, durationMs)) continue;
            Lyrics lyrics = fromRecord(record, "LRCLIB");
            if (lyrics == null) continue;
            int score = matchScore(record, title, artist, durationMs);
            if (lyrics.synced()) score += 20;
            if (score > bestScore) {
                best = lyrics;
                bestScore = score;
            }
        }
        return best;
    }

    /** Backward-compatible selector used by tests and callers that have no track metadata. */
    static Lyrics pickBest(JSONArray results, long durationMs) {
        Lyrics bestSynced = null;
        long bestDiff = Long.MAX_VALUE;
        Lyrics firstPlain = null;
        for (int i = 0; i < results.length(); i++) {
            JSONObject record = results.optJSONObject(i);
            if (record == null) continue;
            Lyrics lyrics = fromRecord(record, "LRCLIB");
            if (lyrics == null) continue;
            if (lyrics.synced()) {
                long diff = durationMs > 0 ? Math.abs(Math.round(record.optDouble("duration", 0) * 1000) - durationMs)
                        : 0;
                if (bestSynced == null || diff < bestDiff) {
                    bestSynced = lyrics;
                    bestDiff = diff;
                }
            } else if (firstPlain == null) {
                firstPlain = lyrics;
            }
        }
        return bestSynced != null ? bestSynced : firstPlain;
    }

    private static boolean matches(JSONObject record, String title, String artist, long durationMs) {
        String expectedTitle = normalize(cleanTitle(title));
        String recordTitle = normalize(cleanTitle(record.optString("trackName", "")));
        if (!expectedTitle.isEmpty() && !recordTitle.isEmpty() && !expectedTitle.equals(recordTitle)
                && tokenSimilarity(expectedTitle, recordTitle) < 0.80) return false;

        String expectedArtist = normalize(artist);
        String recordArtist = normalize(record.optString("artistName", ""));
        if (!expectedArtist.isEmpty() && !recordArtist.isEmpty() && !expectedArtist.equals(recordArtist)
                && !expectedArtist.contains(recordArtist) && !recordArtist.contains(expectedArtist)
                && tokenSimilarity(expectedArtist, recordArtist) < 0.5) return false;

        double recordSeconds = record.optDouble("duration", -1);
        if (durationMs > 0 && recordSeconds > 0) {
            double difference = Math.abs(recordSeconds * 1000d - durationMs);
            if (difference > Math.max(35_000d, durationMs * 0.22d)) return false;
        }
        return true;
    }

    private static int matchScore(JSONObject record, String title, String artist, long durationMs) {
        String expectedTitle = normalize(cleanTitle(title));
        String recordTitle = normalize(cleanTitle(record.optString("trackName", "")));
        String expectedArtist = normalize(artist);
        String recordArtist = normalize(record.optString("artistName", ""));
        int score = 0;
        if (!expectedTitle.isEmpty() && expectedTitle.equals(recordTitle)) score += 100;
        else score += (int) (tokenSimilarity(expectedTitle, recordTitle) * 70);
        if (!expectedArtist.isEmpty() && expectedArtist.equals(recordArtist)) score += 50;
        else if (!expectedArtist.isEmpty() && (!recordArtist.isEmpty()
                && (expectedArtist.contains(recordArtist) || recordArtist.contains(expectedArtist)))) score += 25;
        double seconds = record.optDouble("duration", -1);
        if (durationMs > 0 && seconds > 0) {
            score += Math.max(0, 30 - (int) (Math.abs(seconds * 1000d - durationMs) / 1000d));
        }
        return score;
    }

    private static String normalize(String text) {
        if (text == null) return "";
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKD).toLowerCase(Locale.US);
        normalized = normalized.replaceAll("\\p{M}+", "").replaceAll("[^a-z0-9]+", " ").trim();
        return normalized.replaceAll("\\s+", " ");
    }

    private static double tokenSimilarity(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        String[] aa = a.split(" ");
        String[] bb = b.split(" ");
        int common = 0;
        for (String token : aa) {
            if (token.length() <= 1) continue;
            for (String candidate : bb) {
                if (token.equals(candidate)) {
                    common++;
                    break;
                }
            }
        }
        int total = Math.max(aa.length, bb.length);
        return total == 0 ? 0 : common / (double) total;
    }

    private static String enc(String value) throws IOException {
        return URLEncoder.encode(value, "UTF-8");
    }
}
