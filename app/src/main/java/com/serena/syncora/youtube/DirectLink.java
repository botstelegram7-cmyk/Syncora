package com.serena.syncora.youtube;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A direct media link pasted on the YouTube page: the telegram-file-to-links stream links
 * ({@code https://host/stream?id=888}, with {@code &d=true} as the download variant) or any other
 * http(s) media link. One link can be <b>streamed</b> (played in sync) and <b>downloaded</b> (saved).
 */
public final class DirectLink {

    /** The link as pasted (after {@link #normalize}). */
    public final String url;
    /** Host the link points at, shown on the card. */
    public final String host;
    /** What plays: the file-to-links stream variant (no {@code d=true}), or the link itself. */
    public final String streamUrl;
    /** What gets saved: the file-to-links download variant ({@code d=true}), or the link itself. */
    public final String downloadUrl;
    /** Short name for the UI and the saved file. */
    public final String title;
    /** File-to-links id (like {@code 888}), or an empty string. */
    public final String fileId;

    private DirectLink(String url, String host, String streamUrl, String downloadUrl, String title, String fileId) {
        this.url = url;
        this.host = host;
        this.streamUrl = streamUrl;
        this.downloadUrl = downloadUrl;
        this.title = title;
        this.fileId = fileId;
    }

    /** Untangles pasted text: trims it and turns HTML-escaped {@code &amp;} back into {@code &}. */
    public static String normalize(String input) {
        if (input == null) return "";
        String s = input.trim();
        while (s.contains("&amp;")) s = s.replace("&amp;", "&");
        return s;
    }

    /** Parses a direct media link; null when the text is not an http(s) link. */
    public static DirectLink parse(String input) {
        String s = normalize(input);
        if (!s.toLowerCase(Locale.US).startsWith("http://") && !s.toLowerCase(Locale.US).startsWith("https://")) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(s);
        } catch (Exception e) {
            return null;
        }
        String host = uri.getHost() == null ? "" : uri.getHost();
        if (host.isEmpty()) return null;
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        List<String[]> params = splitQuery(uri.getRawQuery());
        String id = param(params, "id");
        if (isFileToLinks(path, id)) {
            String stream = withQuery(uri, stripParam(params, "d"));
            String download = withQuery(uri, setParam(params, "d", "true"));
            String title = id.isEmpty() ? "Media file" : "File " + id;
            return new DirectLink(s, host, stream, download, title, id);
        }
        String title = titleFromPath(path, host);
        return new DirectLink(s, host, s, s, title, "");
    }

    /** File-to-links services answer at {@code /stream?id=N} and add {@code &d=true} for downloads. */
    private static boolean isFileToLinks(String path, String id) {
        return !id.isEmpty() && (path.endsWith("/stream") || path.equals("stream"));
    }

    /** True for the {@code &d=true} download variant of a file-to-links link. */
    public static boolean isDownloadVariant(String input) {
        String s = normalize(input).toLowerCase(Locale.US);
        int at = s.indexOf('?');
        return at >= 0 && s.substring(at).contains("d=true");
    }

    private static String titleFromPath(String path, String host) {
        String name = path;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        try {
            name = URLDecoder.decode(name, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException ignored) {
            // keep the raw name
        }
        if (name.isEmpty()) return host;
        return name;
    }

    // ================================================================= query strings

    private static List<String[]> splitQuery(String query) {
        List<String[]> out = new ArrayList<>();
        if (query == null || query.isEmpty()) return out;
        for (String part : query.split("&")) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            if (eq < 0) out.add(new String[]{part, ""});
            else out.add(new String[]{part.substring(0, eq), part.substring(eq + 1)});
        }
        return out;
    }

    private static String param(List<String[]> params, String name) {
        for (String[] p : params) {
            if (p[0].equals(name)) return p[1];
        }
        return "";
    }

    private static List<String[]> stripParam(List<String[]> params, String name) {
        List<String[]> out = new ArrayList<>();
        for (String[] p : params) {
            if (!p[0].equals(name)) out.add(p);
        }
        return out;
    }

    private static List<String[]> setParam(List<String[]> params, String name, String value) {
        List<String[]> out = stripParam(params, name);
        out.add(new String[]{name, value});
        return out;
    }

    private static String withQuery(URI uri, List<String[]> params) {
        StringBuilder q = new StringBuilder();
        for (String[] p : params) {
            if (q.length() > 0) q.append('&');
            q.append(p[0]);
            if (!p[1].isEmpty()) q.append('=').append(p[1]);
        }
        String base = uri.getScheme() + "://" + uri.getRawAuthority()
                + (uri.getRawPath() == null ? "" : uri.getRawPath());
        return q.length() == 0 ? base : base + "?" + q;
    }
}
