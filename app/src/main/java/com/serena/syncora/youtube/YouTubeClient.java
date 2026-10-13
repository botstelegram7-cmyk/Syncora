package com.serena.syncora.youtube;

import com.serena.syncora.lyrics.SubtitleParser;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    private static final String BROWSE_URL = "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false";
    private static final int MAX_PLAYLIST_ENTRIES = 200;
    private static final int MAX_PLAYLIST_PAGES = 3;
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
        Locale locale = Locale.getDefault();
        String language = locale.getLanguage();
        if (language == null || language.trim().isEmpty()) language = "en";
        String country = locale.getCountry();
        if (country == null || !country.matches("[A-Za-z]{2}")) country = "US";
        country = country.toUpperCase(Locale.US);
        // Try the device's selected region first
        addProfiles(list, language, country);
        // Also provide generic international / US profiles as fallback so international
        // videos with region licensing restrictions or missing streams in the local region play seamlessly.
        if (!"US".equalsIgnoreCase(country)) {
            addProfiles(list, "en", "US");
        }
        return list;
    }

    private static void addProfiles(List<Profile> list, String language, String country) throws JSONException {
        JSONObject vr = new JSONObject()
                .put("clientName", "ANDROID_VR").put("clientVersion", "1.65.10")
                .put("deviceMake", "Oculus").put("deviceModel", "Quest 3")
                .put("osName", "Android").put("osVersion", "12L").put("androidSdkVersion", 32)
                .put("hl", language).put("gl", country).put("utcOffsetMinutes", 0);
        list.add(new Profile(28, "1.65.10",
                "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
                new JSONObject().put("client", vr)));
        JSONObject android = new JSONObject()
                .put("clientName", "ANDROID").put("clientVersion", "20.10.38")
                .put("osName", "Android").put("osVersion", "11").put("androidSdkVersion", 30)
                .put("hl", language).put("gl", country).put("utcOffsetMinutes", 0);
        list.add(new Profile(3, "20.10.38", "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip",
                new JSONObject().put("client", android)));
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
                    // One client profile can be blocked while another still returns this public, region-available video.
                    last = e;
                }
            }
        } catch (JSONException e) {
            throw new IOException("Internal error while asking YouTube", e);
        }
        throw last != null ? last : new IOException("YouTube did not answer");
    }

    /**
     * Fetches the available subtitle tracks without requiring a playable media format. TypeTube does not include
     * captions in its stream response, so the YouTube player API is queried separately after a TypeTube resolve.
     */
    public static List<YouTubeVideo.Caption> fetchCaptions(String videoId) throws IOException {
        IOException last = null;
        try {
            for (Profile profile : profiles()) {
                try {
                    JSONObject root = new JSONObject(postPlayer(videoId, profile));
                    List<YouTubeVideo.Caption> captions = captions(root);
                    if (!captions.isEmpty()) return captions;
                } catch (IOException e) {
                    last = e;
                } catch (JSONException e) {
                    last = new IOException("YouTube sent unreadable caption information", e);
                }
            }
        } catch (JSONException e) {
            throw new IOException("Internal error while asking YouTube for captions", e);
        }
        if (last != null && !(last instanceof VideoUnavailableException)) throw last;
        return new ArrayList<>();
    }

    /** Loads a public playlist and up to its first 200 playable videos. Blocking; use a worker thread. */
    public static YouTubePlaylist fetchPlaylist(String playlistId) throws IOException {
        if (playlistId == null || !playlistId.matches("[A-Za-z0-9_-]{2,128}")) {
            throw new IOException("That doesn't look like a YouTube playlist ID.");
        }
        IOException last = null;
        try {
            for (Profile profile : profiles()) {
                try {
                    LinkedHashMap<String, YouTubePlaylist.Entry> entries = new LinkedHashMap<>();
                    String title = "";
                    String continuation = null;
                    String previousContinuation = null;
                    for (int page = 0; page < MAX_PLAYLIST_PAGES && entries.size() < MAX_PLAYLIST_ENTRIES; page++) {
                        String json = postBrowse(playlistId, continuation, profile);
                        PlaylistPage result = parsePlaylistPage(json, playlistId);
                        if (title.isEmpty()) title = result.title;
                        for (YouTubePlaylist.Entry entry : result.entries) {
                            if (entries.size() >= MAX_PLAYLIST_ENTRIES) break;
                            if (!entry.videoId.isEmpty()) entries.put(entry.videoId, entry);
                        }
                        previousContinuation = continuation;
                        continuation = result.continuation;
                        if (continuation == null || continuation.isEmpty()
                                || continuation.equals(previousContinuation)) break;
                    }
                    if (!entries.isEmpty()) {
                        return new YouTubePlaylist(playlistId, title, new ArrayList<>(entries.values()),
                                continuation != null && !continuation.isEmpty());
                    }
                    last = new IOException("This playlist is private, empty, or does not contain public videos.");
                } catch (IOException e) {
                    last = e;
                    if (e instanceof VideoUnavailableException) break;
                } catch (JSONException e) {
                    last = new IOException("YouTube sent a playlist response Syncora could not read", e);
                }
            }
        } catch (JSONException e) {
            throw new IOException("Internal error while asking YouTube for a playlist", e);
        }
        throw last != null ? last : new IOException("YouTube did not return this playlist");
    }

    /** Parses one YouTube browse response for playlist metadata and video rows. Public for unit tests. */
    public static YouTubePlaylist parsePlaylistResponse(String json, String playlistId) throws IOException {
        try {
            PlaylistPage page = parsePlaylistPage(json, playlistId);
            if (page.entries.isEmpty()) throw new IOException("This playlist has no public videos.");
            return new YouTubePlaylist(playlistId, page.title, page.entries, page.continuation != null);
        } catch (JSONException e) {
            throw new IOException("YouTube sent a playlist response Syncora could not read", e);
        }
    }

    /** Thrown when YouTube says the video itself cannot be played (private, removed, age or region limits). */
    public static final class VideoUnavailableException extends IOException {
        VideoUnavailableException(String message) {
            super(message);
        }
    }

    private static final class PlaylistPage {
        final String title;
        final List<YouTubePlaylist.Entry> entries;
        final String continuation;

        PlaylistPage(String title, List<YouTubePlaylist.Entry> entries, String continuation) {
            this.title = title;
            this.entries = entries;
            this.continuation = continuation;
        }
    }

    private static PlaylistPage parsePlaylistPage(String json, String playlistId) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONObject header = findObject(root, "playlistHeaderRenderer", 0);
        JSONObject metadata = findObject(root, "playlistMetadataRenderer", 0);
        String title = header == null ? "" : text(header.opt("title"));
        if (title.isEmpty() && metadata != null) title = text(metadata.opt("title"));

        List<YouTubePlaylist.Entry> entries = new ArrayList<>();
        String[] continuation = {null};
        collectPlaylist(root, entries, continuation, 0);
        return new PlaylistPage(title, entries, continuation[0]);
    }

    private static void collectPlaylist(Object node, List<YouTubePlaylist.Entry> entries, String[] continuation,
                                        int depth) throws JSONException {
        if (node == null || depth > 64 || entries.size() >= MAX_PLAYLIST_ENTRIES) return;
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONObject renderer = object.optJSONObject("playlistVideoRenderer");
            if (renderer != null) {
                YouTubePlaylist.Entry entry = playlistEntry(renderer);
                if (!entry.videoId.isEmpty()) entries.add(entry);
            } else {
                JSONObject musicRenderer = object.optJSONObject("musicResponsiveListItemRenderer");
                if (musicRenderer != null) {
                    YouTubePlaylist.Entry entry = musicPlaylistEntry(musicRenderer);
                    if (!entry.videoId.isEmpty()) entries.add(entry);
                }
            }
            if (continuation[0] == null) continuation[0] = continuationToken(object);
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) collectPlaylist(object.opt(keys.next()), entries, continuation, depth + 1);
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length() && entries.size() < MAX_PLAYLIST_ENTRIES; i++) {
                collectPlaylist(array.opt(i), entries, continuation, depth + 1);
            }
        }
    }

    private static YouTubePlaylist.Entry playlistEntry(JSONObject renderer) {
        String id = renderer.optString("videoId", "");
        String title = text(renderer.opt("title"));
        if (title.isEmpty()) title = text(renderer.opt("headline"));
        String author = text(renderer.opt("shortBylineText"));
        if (author.isEmpty()) author = text(renderer.opt("longBylineText"));
        String thumbnail = "";
        JSONObject image = renderer.optJSONObject("thumbnail");
        JSONArray thumbs = image == null ? null : image.optJSONArray("thumbnails");
        if (thumbs != null && thumbs.length() > 0) {
            JSONObject last = thumbs.optJSONObject(thumbs.length() - 1);
            if (last != null) thumbnail = last.optString("url", "");
        }
        long duration = parseDuration(text(renderer.opt("lengthText")));
        return new YouTubePlaylist.Entry(id, title, author, duration, thumbnail);
    }

    private static YouTubePlaylist.Entry musicPlaylistEntry(JSONObject renderer) {
        String id = "";
        JSONObject itemData = renderer.optJSONObject("playlistItemData");
        if (itemData != null) id = itemData.optString("videoId", "");
        if (id.isEmpty()) {
            JSONObject navigation = findObject(renderer, "watchEndpoint", 0);
            if (navigation != null) id = navigation.optString("videoId", "");
        }

        JSONArray columns = renderer.optJSONArray("flexColumns");
        String title = musicColumn(columns, 0);
        String author = musicColumn(columns, 1);
        String durationText = "";
        JSONArray fixedColumns = renderer.optJSONArray("fixedColumns");
        if (fixedColumns != null && fixedColumns.length() > 0) {
            JSONObject fixed = fixedColumns.optJSONObject(0);
            JSONObject fixedRenderer = fixed == null ? null
                    : fixed.optJSONObject("musicResponsiveListItemFixedColumnRenderer");
            if (fixedRenderer != null) durationText = text(fixedRenderer.opt("text"));
        }

        String thumbnail = "";
        JSONObject image = renderer.optJSONObject("thumbnail");
        JSONObject musicThumbnail = image == null ? null : image.optJSONObject("musicThumbnailRenderer");
        JSONObject innerImage = musicThumbnail == null ? image : musicThumbnail.optJSONObject("thumbnail");
        JSONArray thumbs = innerImage == null ? null : innerImage.optJSONArray("thumbnails");
        if (thumbs != null && thumbs.length() > 0) {
            JSONObject last = thumbs.optJSONObject(thumbs.length() - 1);
            if (last != null) thumbnail = last.optString("url", "");
        }
        return new YouTubePlaylist.Entry(id, title, author, parseDuration(durationText), thumbnail);
    }

    private static String musicColumn(JSONArray columns, int index) {
        if (columns == null || index < 0 || index >= columns.length()) return "";
        JSONObject column = columns.optJSONObject(index);
        JSONObject columnRenderer = column == null ? null
                : column.optJSONObject("musicResponsiveListItemFlexColumnRenderer");
        return columnRenderer == null ? "" : text(columnRenderer.opt("text"));
    }

    private static String text(Object value) {
        if (value instanceof String) return ((String) value).trim();
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            String simple = object.optString("simpleText", "").trim();
            if (!simple.isEmpty()) return simple;
            JSONArray runs = object.optJSONArray("runs");
            if (runs != null) {
                StringBuilder out = new StringBuilder();
                for (int i = 0; i < runs.length(); i++) {
                    JSONObject run = runs.optJSONObject(i);
                    if (run != null) out.append(run.optString("text", ""));
                }
                return out.toString().trim();
            }
            JSONObject accessibility = object.optJSONObject("accessibility");
            JSONObject data = accessibility == null ? null : accessibility.optJSONObject("accessibilityData");
            if (data != null) return data.optString("label", "").trim();
        }
        return "";
    }

    private static long parseDuration(String duration) {
        if (duration == null || duration.trim().isEmpty() || duration.toLowerCase(Locale.US).contains("live")) return 0;
        try {
            long total = 0;
            String[] parts = duration.trim().split(":");
            for (String part : parts) total = total * 60 + Long.parseLong(part);
            return total * 1000L;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String continuationToken(JSONObject object) {
        JSONObject command = object.optJSONObject("continuationCommand");
        if (command != null) {
            String token = command.optString("token", "");
            if (!token.isEmpty()) return token;
        }
        JSONObject next = object.optJSONObject("nextContinuationData");
        if (next != null) {
            String token = next.optString("continuation", "");
            if (!token.isEmpty()) return token;
        }
        JSONObject reload = object.optJSONObject("reloadContinuationData");
        if (reload != null) {
            String token = reload.optString("continuation", "");
            if (!token.isEmpty()) return token;
        }
        return null;
    }

    private static JSONObject findObject(Object node, String name, int depth) {
        if (node == null || depth > 64) return null;
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONObject match = object.optJSONObject(name);
            if (match != null) return match;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                match = findObject(object.opt(keys.next()), name, depth + 1);
                if (match != null) return match;
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                JSONObject match = findObject(array.opt(i), name, depth + 1);
                if (match != null) return match;
            }
        }
        return null;
    }

    private static String postBrowse(String playlistId, String continuation, Profile profile)
            throws IOException, JSONException {
        JSONObject body = new JSONObject().put("context", profile.context);
        if (continuation == null || continuation.isEmpty()) {
            String browseId = playlistId.startsWith("VL") ? playlistId : "VL" + playlistId;
            body.put("browseId", browseId);
        } else {
            body.put("continuation", continuation);
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(BROWSE_URL).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(20_000);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", profile.userAgent);
            connection.setRequestProperty("X-YouTube-Client-Name", String.valueOf(profile.nameId));
            connection.setRequestProperty("X-YouTube-Client-Version", profile.version);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.toString().getBytes("UTF-8"));
            }
            int code = connection.getResponseCode();
            if (code / 100 != 2) throw new IOException("YouTube answered HTTP " + code + " while loading the playlist");
            return read(connection.getInputStream(), 8 * 1024 * 1024);
        } finally {
            connection.disconnect();
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

            List<YouTubeVideo.Caption> captions = captions(root);
            return new YouTubeVideo(videoId, title, author, durationMs, thumb, userAgent, formats, captions);
        } catch (JSONException e) {
            throw new IOException("YouTube sent a response Syncora could not read", e);
        }
    }

    private static List<YouTubeVideo.Caption> captions(JSONObject root) {
        List<YouTubeVideo.Caption> out = new ArrayList<>();
        JSONObject captionRoot = root == null ? null : root.optJSONObject("captions");
        JSONObject renderer = captionRoot == null ? null : captionRoot.optJSONObject("playerCaptionsTracklistRenderer");
        JSONArray tracks = renderer == null ? null : renderer.optJSONArray("captionTracks");
        if (tracks == null) return out;
        for (int i = 0; i < tracks.length(); i++) {
            JSONObject track = tracks.optJSONObject(i);
            if (track == null) continue;
            String base = track.optString("baseUrl", "");
            if (base.isEmpty()) continue;
            String language = track.optString("languageCode", "");
            String name = text(track.opt("name"));
            out.add(new YouTubeVideo.Caption(language, name, base,
                    "asr".equals(track.optString("kind", ""))));
        }
        return out;
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

    /** Caption text as cues: json3, WebVTT, then YouTube's XML caption formats. Empty when no format is available. */
    public static List<SubtitleParser.Cue> fetchCues(YouTubeVideo.Caption caption, String userAgent) {
        List<SubtitleParser.Cue> empty = new ArrayList<>();
        if (caption == null || caption.baseUrl.isEmpty()) return empty;
        try {
            List<SubtitleParser.Cue> cues = parseCaptionBody(read(get(caption.baseUrl, userAgent), 4 * 1024 * 1024));
            if (!cues.isEmpty()) return cues;
        } catch (IOException | RuntimeException ignored) {
            // Some tracks require an explicit output format.
        }
        // A supplied fmt can be part of YouTube's signed sparams. Keep that URL intact rather than invalidating it.
        if (hasFormatParameter(caption.baseUrl)) return empty;
        String[] formats = {"json3", "vtt", "ttml", "srv3", "srv1"};
        for (String format : formats) {
            try {
                List<SubtitleParser.Cue> cues = parseCaptionBody(
                        read(get(withFormat(caption.baseUrl, format), userAgent), 4 * 1024 * 1024));
                if (!cues.isEmpty()) return cues;
            } catch (IOException | RuntimeException ignored) {
                // Caption servers differ by track and format; try the next supported representation.
            }
        }
        return empty;
    }

    /**
     * Attempts to fetch cues for the given caption track, falling back across other available tracks
     * in the video if the chosen track produces no cues.
     */
    public static List<SubtitleParser.Cue> fetchCuesWithFallback(YouTubeVideo.Caption requested, List<YouTubeVideo.Caption> allTracks, String userAgent) {
        if (requested != null) {
            List<SubtitleParser.Cue> cues = fetchCues(requested, userAgent);
            if (!cues.isEmpty()) return cues;
        }
        if (allTracks != null) {
            for (YouTubeVideo.Caption candidate : allTracks) {
                if (candidate == requested) continue;
                List<SubtitleParser.Cue> cues = fetchCues(candidate, userAgent);
                if (!cues.isEmpty()) return cues;
            }
        }
        return new ArrayList<>();
    }

    private static List<SubtitleParser.Cue> parseCaptionBody(String body) {
        String text = body == null ? "" : body.replace("\uFEFF", "").trim();
        if (text.startsWith(")]}'")) {
            int newline = text.indexOf('\n');
            if (newline >= 0) text = text.substring(newline + 1).trim();
        }
        if (text.startsWith("WEBVTT")) return SubtitleParser.parseVtt(text);
        if (text.startsWith("{")) return SubtitleParser.parseJson3(text);
        return SubtitleParser.parseTtml(text);
    }

    private static boolean hasFormatParameter(String address) {
        int queryAt = address.indexOf('?');
        int fragmentAt = address.indexOf('#');
        if (queryAt < 0 || (fragmentAt >= 0 && fragmentAt < queryAt)) return false;
        String query = address.substring(queryAt + 1, fragmentAt >= 0 ? fragmentAt : address.length());
        for (String part : query.split("&")) {
            int equals = part.indexOf('=');
            String key = equals < 0 ? part : part.substring(0, equals);
            if ("fmt".equalsIgnoreCase(key)) return true;
        }
        return false;
    }

    private static String withFormat(String address, String format) {
        int fragmentAt = address.indexOf('#');
        String fragment = fragmentAt < 0 ? "" : address.substring(fragmentAt);
        String withoutFragment = fragmentAt < 0 ? address : address.substring(0, fragmentAt);
        int queryAt = withoutFragment.indexOf('?');
        String path = queryAt < 0 ? withoutFragment : withoutFragment.substring(0, queryAt);
        String query = queryAt < 0 ? "" : withoutFragment.substring(queryAt + 1);
        StringBuilder out = new StringBuilder(path).append('?');
        boolean first = true;
        if (!query.isEmpty()) {
            for (String part : query.split("&")) {
                if (part.isEmpty()) continue;
                int equals = part.indexOf('=');
                String name = equals < 0 ? part : part.substring(0, equals);
                if ("fmt".equalsIgnoreCase(name)) continue;
                if (!first) out.append('&');
                out.append(part);
                first = false;
            }
        }
        if (!first) out.append('&');
        try {
            out.append("fmt=").append(URLEncoder.encode(format, "UTF-8"));
        } catch (Exception ignored) {
            out.append("fmt=").append(format);
        }
        return out.append(fragment).toString();
    }

    /** Opens a GET request (used for captions). The caller reads and closes the stream. */
    static InputStream get(String address, String userAgent) throws IOException {
        final HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(20_000);
        if (userAgent != null && !userAgent.isEmpty()) connection.setRequestProperty("User-Agent", userAgent);
        int code = connection.getResponseCode();
        if (code / 100 != 2) {
            connection.disconnect();
            throw new IOException("Caption server answered HTTP " + code);
        }
        return new FilterInputStream(connection.getInputStream()) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    connection.disconnect();
                }
            }
        };
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
