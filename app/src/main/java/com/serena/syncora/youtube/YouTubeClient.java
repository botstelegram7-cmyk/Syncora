package com.serena.syncora.youtube;

import com.serena.syncora.lyrics.SubtitleParser;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a video's streams and caption tracks from YouTube's player API (the same API the YouTube apps use).
 *
 * <p>Blocking: call from a worker thread only. YouTube changes this API regularly; when it stops answering, the
 * error says so and the client profiles below are the place to update.
 */
public final class YouTubeClient {

    private static final String PLAYER_URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false";
    private static final Pattern CODECS = Pattern.compile("codecs=\"([^\"]*)\"");
    private static final String THUMB = "https://i.ytimg.com/vi/%s/hqdefault.jpg";

    /** One client profile: the context YouTube expects for that app. */
    private static final class Profile {
        final int nameId;
        final String version;
        final String userAgent;
        final JSONObject context;

        Profile(int nameId, String version, String userAgent, JSONObject context) {
            this.nameId = nameId;
            this.version = version;
            this.userAgent = userAgent;
            this.context = context;
        }
    }

    private YouTubeClient() {
    }

    private static List<Profile> profiles() throws JSONException {
        List<Profile> list = new ArrayList<>();
        JSONObject vr = new JSONObject()
                .put("clientName", "ANDROID_VR").put("clientVersion", "1.65.10")
                .put("deviceMake", "Oculus").put("deviceModel", "Quest 3")
                .put("osName", "Android").put("osVersion", "12L").put("androidSdkVersion", 32)
                .put("hl", "en").put("gl", "US").put("utcOffsetMinutes", 0);
        list.add(new Profile(28, "1.65.10",
                "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
                new JSONObject().put("client", vr)));
        JSONObject android = new JSONObject()
                .put("clientName", "ANDROID").put("clientVersion", "20.10.38")
                .put("osName", "Android").put("osVersion", "11").put("androidSdkVersion", 30)
                .put("hl", "en").put("gl", "US").put("utcOffsetMinutes", 0);
        list.add(new Profile(3, "20.10.38", "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip",
                new JSONObject().put("client", android)));
        return list;
    }

    /** Looks the video up. Throws an IOException with a readable reason when it cannot be played or read. */
    public static YouTubeVideo fetch(String videoId) throws IOException {
        IOException last = null;
        try {
            for (Profile p : profiles()) {
                try {
                    String json = postPlayer(videoId, p);
                    return parse(json, videoId, p.userAgent);
                } catch (IOException e) {
                    last = e;
                    if (e instanceof VideoUnavailableException) break;
                }
            }
        } catch (JSONException e) {
            throw new IOException("Internal error while asking YouTube", e);
        }
        throw last != null ? last : new IOException("YouTube did not answer");
    }

    /** Thrown when YouTube says the video itself cannot be played (private, removed, age or region limits). */
    public static final class VideoUnavailableException extends IOException {
        VideoUnavailableException(String message) {
            super(message);
        }
    }

    private static String postPlayer(String videoId, Profile p) throws IOException, JSONException {
        JSONObject body = new JSONObject()
                .put("videoId", videoId)
                .put("context", p.context)
                .put("contentCheckOk", true)
                .put("racyCheckOk", true);
        HttpURLConnection c = (HttpURLConnection) new URL(PLAYER_URL).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(10_000);
            c.setReadTimeout(20_000);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", p.userAgent);
            c.setRequestProperty("X-YouTube-Client-Name", String.valueOf(p.nameId));
            c.setRequestProperty("X-YouTube-Client-Version", p.version);
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.toString().getBytes("UTF-8"));
            }
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new IOException("YouTube answered HTTP " + code);
            return read(c.getInputStream(), 8 * 1024 * 1024);
        } finally {
            c.disconnect();
        }
    }

    /** Reads the player response. Public for tests. */
    public static YouTubeVideo parse(String json, String videoId, String userAgent) throws IOException {
        try {
            JSONObject root = new JSONObject(json);
            JSONObject playability = root.optJSONObject("playabilityStatus");
            String status = playability == null ? "" : playability.optString("status", "");
            if (!status.isEmpty() && !"OK".equals(status)) {
                String reason = playability.optString("reason", "");
                String message = reason.isEmpty() ? "This video cannot be played (" + status + ")" : reason;
                if ("LOGIN_REQUIRED".equals(status) || "UNPLAYABLE".equals(status) || "ERROR".equals(status)
                        || "CONTENT_CHECK_REQUIRED".equals(status)) {
                    throw new VideoUnavailableException(message);
                }
                throw new IOException(message);
            }
            JSONObject details = root.optJSONObject("videoDetails");
            String title = details == null ? "" : details.optString("title", "");
            String author = details == null ? "" : details.optString("author", "");
            long durationMs = details == null ? 0 : parseLong(details.optString("lengthSeconds", "0")) * 1000L;
            String thumb = String.format(THUMB, videoId);
            if (details != null) {
                JSONArray thumbs = details.optJSONObject("thumbnail") == null ? null
                        : details.optJSONObject("thumbnail").optJSONArray("thumbnails");
                if (thumbs != null && thumbs.length() > 0) {
                    String last = thumbs.optJSONObject(thumbs.length() - 1) == null ? ""
                            : thumbs.optJSONObject(thumbs.length() - 1).optString("url", "");
                    if (!last.isEmpty()) thumb = last;
                }
            }

            List<YouTubeFormat> formats = new ArrayList<>();
            JSONObject streaming = root.optJSONObject("streamingData");
            if (streaming != null) {
                addFormats(formats, streaming.optJSONArray("formats"), true);
                addFormats(formats, streaming.optJSONArray("adaptiveFormats"), false);
            }
            if (formats.isEmpty()) {
                throw new IOException("YouTube gave no direct streams for this video. It may use protection "
                        + "that this version of Syncora cannot read yet.");
            }

            List<YouTubeVideo.Caption> captions = new ArrayList<>();
            JSONObject captionRoot = root.optJSONObject("captions");
            JSONObject renderer = captionRoot == null ? null : captionRoot.optJSONObject("playerCaptionsTracklistRenderer");
            JSONArray tracks = renderer == null ? null : renderer.optJSONArray("captionTracks");
            if (tracks != null) {
                for (int i = 0; i < tracks.length(); i++) {
                    JSONObject t = tracks.optJSONObject(i);
                    if (t == null) continue;
                    String base = t.optString("baseUrl", "");
                    if (base.isEmpty()) continue;
                    JSONObject nameObj = t.optJSONObject("name");
                    String name = nameObj == null ? "" : nameObj.optString("simpleText", "");
                    captions.add(new YouTubeVideo.Caption(t.optString("languageCode", ""), name, base,
                            "asr".equals(t.optString("kind", ""))));
                }
            }
            return new YouTubeVideo(videoId, title, author, durationMs, thumb, userAgent, formats, captions);
        } catch (JSONException e) {
            throw new IOException("YouTube sent a response Syncora could not read", e);
        }
    }

    private static void addFormats(List<YouTubeFormat> out, JSONArray array, boolean progressive) {
        if (array == null) return;
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            if (o == null) continue;
            // Streams behind a signature cipher need JavaScript decoding; they are skipped.
            String url = o.optString("url", "");
            if (url.isEmpty()) continue;
            String mimeFull = o.optString("mimeType", "");
            String mime = mimeFull.contains(";") ? mimeFull.substring(0, mimeFull.indexOf(';')).trim() : mimeFull.trim();
            String codecs = "";
            Matcher m = CODECS.matcher(mimeFull);
            if (m.find()) codecs = m.group(1);
            String lower = codecs.toLowerCase(java.util.Locale.US);
            boolean hasVideoCodec = lower.contains("avc1") || lower.contains("vp9") || lower.contains("vp09")
                    || lower.contains("av01") || lower.contains("vp8");
            boolean hasAudioCodec = lower.contains("mp4a") || lower.contains("opus") || lower.contains("vorbis");
            YouTubeFormat.Kind kind;
            if (mime.startsWith("audio/")) {
                kind = YouTubeFormat.Kind.AUDIO_ONLY;
            } else if (mime.startsWith("video/")) {
                if (hasAudioCodec || (progressive && !hasVideoCodec)) kind = YouTubeFormat.Kind.MUXED;
                else kind = YouTubeFormat.Kind.VIDEO_ONLY;
            } else {
                continue;
            }
            long bitrate = o.optLong("bitrate", 0);
            if (bitrate <= 0) bitrate = o.optLong("averageBitrate", 0);
            out.add(new YouTubeFormat(
                    o.optInt("itag", 0), kind, mime, codecs, url, bitrate,
                    o.optInt("width", 0), o.optInt("height", 0), o.optString("qualityLabel", ""),
                    (int) parseLong(o.optString("audioSampleRate", "0")), o.optInt("audioChannels", 2),
                    parseLong(o.optString("contentLength", "0"))));
        }
    }

    /** Caption text as cues: json3 first (precise timing), then WebVTT. Empty when YouTube returns nothing. */
    public static List<SubtitleParser.Cue> fetchCues(YouTubeVideo.Caption caption, String userAgent) {
        String base = caption.baseUrl;
        String sep = base.contains("?") ? "&" : "?";
        try {
            String json = read(get(base + sep + "fmt=json3", userAgent), 4 * 1024 * 1024);
            List<SubtitleParser.Cue> cues = SubtitleParser.parseJson3(json);
            if (!cues.isEmpty()) return cues;
        } catch (IOException | RuntimeException ignored) {
            // try WebVTT below
        }
        try {
            String vtt = read(get(base + sep + "fmt=vtt", userAgent), 4 * 1024 * 1024);
            return SubtitleParser.parseVtt(vtt);
        } catch (IOException | RuntimeException e) {
            return new ArrayList<>();
        }
    }

    /** Opens a GET request (used for captions). The caller reads and closes the stream. */
    static InputStream get(String address, String userAgent) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("User-Agent", userAgent);
        if (c.getResponseCode() / 100 != 2) {
            c.disconnect();
            throw new IOException("HTTP " + c.getResponseCode());
        }
        return c.getInputStream();
    }

    static String read(InputStream in, int limit) throws IOException {
        try (InputStream src = in) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = src.read(buffer)) > 0 && bytes.size() < limit) bytes.write(buffer, 0, n);
            return bytes.toString("UTF-8");
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
