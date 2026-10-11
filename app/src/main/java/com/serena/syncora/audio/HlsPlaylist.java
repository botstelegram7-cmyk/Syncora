package com.serena.syncora.audio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * An HLS playlist (`.m3u8`), the container almost every broadcaster uses today — including all the
 * All India Radio channels. A playlist is either a <b>master</b> playlist that offers the same
 * programme in several bitrates (plus, often, an audio-only rendition) or a <b>media</b> playlist
 * that lists the segments to fetch, of which the last few keep appearing as the show goes on.
 *
 * <p>Parsing only: no network, so it can be unit tested from strings.
 */
final class HlsPlaylist {

    /** One `EXT-X-STREAM-INF` line: the same programme at one bitrate. */
    static final class Variant {
        final String url;
        final int bandwidth;
        final String codecs;
        final String audioGroup;

        Variant(String url, int bandwidth, String codecs, String audioGroup) {
            this.url = url;
            this.bandwidth = bandwidth;
            this.codecs = codecs == null ? "" : codecs;
            this.audioGroup = audioGroup == null ? "" : audioGroup;
        }

        /** True when CODECS lists audio only (an audio rendition of a video stream). */
        boolean audioOnly() {
            return !codecs.isEmpty() && !hasVideoCodec(codecs);
        }

        static boolean hasVideoCodec(String codecs) {
            String c = codecs.toLowerCase(Locale.US);
            return c.contains("avc1") || c.contains("avc3") || c.contains("hvc1") || c.contains("hev1")
                    || c.contains("vp09") || c.contains("vp9") || c.contains("av01") || c.contains("av1")
                    || c.contains("mp4v") || c.contains("dvh1") || c.contains("dvhe") || c.contains("h264");
        }
    }

    /** One `EXT-X-MEDIA:TYPE=AUDIO` line: a separate audio-only stream of the programme. */
    static final class Rendition {
        final String url;
        final String group;
        final boolean isDefault;
        final String language;
        final String name;

        Rendition(String url, String group, boolean isDefault, String language, String name) {
            this.url = url == null ? "" : url;
            this.group = group == null ? "" : group;
            this.isDefault = isDefault;
            this.language = language == null ? "" : language;
            this.name = name == null ? "" : name;
        }

        boolean playable() {
            return !url.isEmpty();
        }
    }

    /** `EXT-X-KEY`: how the following segments are encrypted. */
    static final class Key {
        final String method;
        final String url;
        final byte[] iv;

        Key(String method, String url, byte[] iv) {
            this.method = method == null || method.isEmpty() ? "NONE" : method.toUpperCase(Locale.US);
            this.url = url == null ? "" : url;
            this.iv = iv;
        }

        boolean encrypted() {
            return !method.equals("NONE") && !url.isEmpty();
        }

        /** AES-128 (whole segment, CBC) is the only method Syncora can undo itself. */
        boolean supported() {
            return !encrypted() || method.equals("AES-128");
        }

        static final Key NONE = new Key("NONE", "", null);
    }

    /** One segment of a media playlist, or the `EXT-X-MAP` initialisation segment of fMP4 streams. */
    static final class Segment {
        final String url;
        final double seconds;
        /** Position in the stream (`EXT-X-MEDIA-SEQUENCE` + index); -1 for an init segment. */
        final long sequence;
        final Key key;
        /** `EXT-X-BYTERANGE`; a length below 0 means "the whole file". */
        final long offset;
        final long length;
        /** Initialisation segment that belongs to this one (fragmented MP4 streams). */
        final String initUrl;
        final long initOffset;
        final long initLength;
        final boolean init;
        final boolean discontinuity;

        Segment(String url, double seconds, long sequence, Key key, long offset, long length,
                String initUrl, long initOffset, long initLength, boolean init, boolean discontinuity) {
            this.url = url;
            this.seconds = seconds;
            this.sequence = sequence;
            this.key = key == null ? Key.NONE : key;
            this.offset = offset;
            this.length = length;
            this.initUrl = initUrl == null ? "" : initUrl;
            this.initOffset = initOffset;
            this.initLength = initLength;
            this.init = init;
            this.discontinuity = discontinuity;
        }
    }

    final boolean master;
    final List<Variant> variants;
    final List<Rendition> renditions;
    final List<Segment> segments;
    final int targetDurationSec;
    final long mediaSequence;
    final boolean endList;

    private HlsPlaylist(boolean master, List<Variant> variants, List<Rendition> renditions, List<Segment> segments,
                        int targetDurationSec, long mediaSequence, boolean endList) {
        this.master = master;
        this.variants = variants;
        this.renditions = renditions;
        this.segments = segments;
        this.targetDurationSec = targetDurationSec;
        this.mediaSequence = mediaSequence;
        this.endList = endList;
    }

    /** True when this playlist offers variants/renditions instead of segments. */
    boolean isMaster() {
        return master || !variants.isEmpty() || !renditions.isEmpty();
    }

    static HlsPlaylist read(InputStream in, URL base) throws IOException {
        return parse(readAll(in), base);
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0 && bytes.size() < 4 * 1024 * 1024) bytes.write(buffer, 0, n);
        return bytes.toByteArray();
    }

    /** Parses a playlist and resolves every address against {@code base}. */
    static HlsPlaylist parse(byte[] body, URL base) throws IOException {
        String text = stripBom(new String(body, "UTF-8"));
        if (!text.startsWith("#EXTM3U")) throw new IOException("This is not an HLS playlist");

        List<Variant> variants = new ArrayList<>();
        List<Rendition> renditions = new ArrayList<>();
        List<Segment> segments = new ArrayList<>();
        int targetDuration = 0;
        long mediaSequence = 0;
        boolean endList = false;

        Key key = Key.NONE;
        String initUrl = "";
        long initOffset = 0;
        long initLength = -1;
        double pendingSeconds = 0;
        long pendingOffset = 0;
        long pendingLength = -1;
        boolean pendingRange = false;
        boolean pendingDiscontinuity = false;
        List<String[]> pendingVariant = null;
        long rangeEnd = 0;

        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("#EXT-X-STREAM-INF")) {
                pendingVariant = attributes(afterColon(line));
                continue;
            }
            if (line.startsWith("#EXT-X-MEDIA:")) {
                List<String[]> attrs = attributes(afterColon(line));
                if (!attribute(attrs, "TYPE").equalsIgnoreCase("AUDIO")) continue;
                String uri = attribute(attrs, "URI");
                renditions.add(new Rendition(uri.isEmpty() ? "" : resolve(base, uri), attribute(attrs, "GROUP-ID"),
                        attribute(attrs, "DEFAULT").equalsIgnoreCase("YES"), attribute(attrs, "LANGUAGE"),
                        attribute(attrs, "NAME")));
                continue;
            }
            if (line.startsWith("#EXT-X-TARGETDURATION")) {
                targetDuration = (int) number(afterColon(line), 0);
                continue;
            }
            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE")) {
                mediaSequence = (long) number(afterColon(line), 0);
                continue;
            }
            if (line.startsWith("#EXT-X-ENDLIST")) {
                endList = true;
                continue;
            }
            if (line.startsWith("#EXT-X-KEY")) {
                key = parseKey(attributes(afterColon(line)), base);
                continue;
            }
            if (line.startsWith("#EXT-X-MAP")) {
                List<String[]> attrs = attributes(afterColon(line));
                String uri = attribute(attrs, "URI");
                initUrl = uri.isEmpty() ? "" : resolve(base, uri);
                long[] range = byteRange(attribute(attrs, "BYTERANGE"), 0, -1);
                initOffset = range[0];
                initLength = range[1];
                continue;
            }
            if (line.startsWith("#EXTINF")) {
                pendingSeconds = number(afterColon(line).split(",")[0], 0);
                continue;
            }
            if (line.startsWith("#EXT-X-BYTERANGE")) {
                long[] range = byteRange(afterColon(line), rangeEnd, -1);
                pendingOffset = range[0];
                pendingLength = range[1];
                pendingRange = true;
                continue;
            }
            if (line.startsWith("#EXT-X-DISCONTINUITY")) {
                pendingDiscontinuity = !line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE");
                continue;
            }
            if (line.startsWith("#")) continue;

            String url = resolve(base, line);
            if (pendingVariant != null) {
                List<String[]> attrs = pendingVariant;
                pendingVariant = null;
                variants.add(new Variant(url, (int) number(attribute(attrs, "BANDWIDTH"), 0),
                        attribute(attrs, "CODECS"), attribute(attrs, "AUDIO")));
                continue;
            }
            segments.add(new Segment(url, pendingSeconds, mediaSequence + segments.size(), key,
                    pendingRange ? pendingOffset : 0, pendingRange ? pendingLength : -1,
                    initUrl, initOffset, initLength, false, pendingDiscontinuity));
            if (pendingRange && pendingLength > 0) rangeEnd = pendingOffset + pendingLength;
            pendingSeconds = 0;
            pendingOffset = 0;
            pendingLength = -1;
            pendingRange = false;
            pendingDiscontinuity = false;
        }

        return new HlsPlaylist(!variants.isEmpty() || !renditions.isEmpty(), variants, renditions, segments,
                targetDuration, mediaSequence, endList);
    }

    /** The media playlist to fetch for this master playlist: audio only whenever there is a choice. */
    String pickMediaUrl() {
        for (Rendition r : renditions) {
            if (r.isDefault && r.playable()) return r.url;
        }
        for (Rendition r : renditions) {
            if (r.playable()) return r.url;
        }
        Variant best = null;
        for (Variant v : variants) {
            if (v.audioOnly() && (best == null || v.bandwidth < best.bandwidth)) best = v;
        }
        if (best == null) {
            // Only video variants: play the cheapest one and keep just its audio track.
            for (Variant v : variants) {
                if (best == null || v.bandwidth < best.bandwidth) best = v;
            }
        }
        return best == null ? null : best.url;
    }

    /** "3 audio renditions" / "4 variants, 64–320 kbps" for the status line. */
    String describeChoices() {
        if (!renditions.isEmpty()) return renditions.size() + " audio rendition" + (renditions.size() == 1 ? "" : "s");
        if (!variants.isEmpty()) {
            int low = Integer.MAX_VALUE;
            int high = 0;
            for (Variant v : variants) {
                low = Math.min(low, v.bandwidth);
                high = Math.max(high, v.bandwidth);
            }
            return variants.size() + " variant" + (variants.size() == 1 ? "" : "s")
                    + (high > 0 ? ", " + low / 1000 + "\u2013" + high / 1000 + " kbps" : "");
        }
        return segments.size() + " segment" + (segments.size() == 1 ? "" : "s");
    }

    private static Key parseKey(List<String[]> attrs, URL base) {
        String method = attribute(attrs, "METHOD");
        if (method.isEmpty() || method.equalsIgnoreCase("NONE")) return Key.NONE;
        String uri = attribute(attrs, "URI");
        byte[] iv = null;
        String hex = attribute(attrs, "IV");
        if (hex.startsWith("0x") || hex.startsWith("0X")) hex = hex.substring(2);
        if (hex.length() == 32) {
            iv = new byte[16];
            for (int i = 0; i < 16; i++) {
                iv[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
        }
        return new Key(method, uri.isEmpty() ? "" : resolve(base, uri), iv);
    }

    /** Parses "length@offset" (or "length"); {@code defaultOffset} is used when no offset is given. */
    static long[] byteRange(String value, long defaultOffset, long defaultLength) {
        if (value == null || value.trim().isEmpty()) return new long[]{defaultOffset, defaultLength};
        String[] parts = value.trim().split("@");
        long length = (long) number(parts[0], defaultLength);
        long offset = parts.length > 1 ? (long) number(parts[1], defaultOffset) : defaultOffset;
        return new long[]{offset, length};
    }

    static String afterColon(String line) {
        int colon = line.indexOf(':');
        return colon < 0 ? "" : line.substring(colon + 1).trim();
    }

    /** Splits an attribute list, keeping commas inside quotes: {@code A=1,B="x,y"} → [A=1, B=x,y]. */
    static List<String[]> attributes(String value) {
        List<String[]> out = new ArrayList<>();
        int n = value.length();
        int i = 0;
        while (i < n) {
            int j = i;
            while (j < n && value.charAt(j) != '=') j++;
            if (j >= n) break;
            String name = value.substring(i, j).trim().toUpperCase(Locale.US);
            j++;
            String text;
            if (j < n && value.charAt(j) == '"') {
                int end = value.indexOf('"', j + 1);
                if (end < 0) end = n;
                text = value.substring(j + 1, end);
                j = end + 1;
            } else {
                int end = j;
                while (end < n && value.charAt(end) != ',') end++;
                text = value.substring(j, end).trim();
                j = end;
            }
            if (!name.isEmpty()) out.add(new String[]{name, text});
            i = j + 1;
        }
        return out;
    }

    static String attribute(List<String[]> attrs, String name) {
        for (String[] pair : attrs) {
            if (pair[0].equals(name)) return pair[1];
        }
        return "";
    }

    static double number(String value, double fallback) {
        try {
            return Double.parseDouble(value.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** Joins a (possibly relative) address to the playlist it came from. */
    static String resolve(URL base, String uri) {
        if (base == null) return uri;
        try {
            return new URL(base, uri).toString();
        } catch (IOException e) {
            return uri;
        }
    }

    private static String stripBom(String text) {
        String t = text.trim();
        return t.startsWith("\uFEFF") ? t.substring(1) : t;
    }
}
