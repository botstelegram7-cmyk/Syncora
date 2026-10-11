package com.serena.syncora.youtube;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Saves a {@link DirectLink} to the download folder, using its download variant when it has one
 * (the {@code &d=true} file-to-links link).
 */
public final class DirectLinkActions {

    private DirectLinkActions() {
    }

    /** One saved file, whatever the server calls it. */
    public static DownloadQueue.Work save(final Context context, final DirectLink link) {
        return new DownloadQueue.Work() {
            @Override
            public void run(DownloadQueue.Job job) throws Exception {
                HttpURLConnection c = (HttpURLConnection) new URL(link.downloadUrl).openConnection();
                c.setConnectTimeout(15_000);
                c.setReadTimeout(30_000);
                c.setRequestProperty("User-Agent", YouTubeDownloader.USER_AGENT);
                File file = new File(context.getCacheDir(), "link-"
                        + Integer.toHexString(link.downloadUrl.hashCode()) + ".part");
                try {
                    int code = c.getResponseCode();
                    if (code / 100 != 2) throw new IOException("The link answered HTTP " + code);
                    String mime = contentType(c);
                    String name = fileName(c);
                    long total = c.getContentLengthLong();
                    try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(file)) {
                        copy(in, out, total, job);
                    }
                    if (file.length() == 0) throw new IOException("The link sent an empty file");
                    String display = name.isEmpty()
                            ? DownloadStore.safeName(link.title, link.fileId.isEmpty() ? "link" : link.fileId,
                            extensionFor(mime))
                            : sanitize(name);
                    job.location = DownloadStore.save(context, file, display,
                            mime.isEmpty() ? "application/octet-stream" : mime).location;
                } catch (IOException e) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                    throw e;
                } finally {
                    c.disconnect();
                }
            }
        };
    }

    private static void copy(InputStream in, OutputStream out, long total, DownloadQueue.Job job)
            throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long done = 0;
        int n;
        while ((n = in.read(buffer)) > 0) {
            if (job.isCancelled()) throw new IOException("Cancelled");
            out.write(buffer, 0, n);
            done += n;
            if (total > 0) job.setProgress(Math.min(1f, done / (float) total));
        }
        job.setProgress(1f);
    }

    private static String contentType(HttpURLConnection c) {
        String type = c.getContentType();
        if (type == null) return "";
        int at = type.indexOf(';');
        return (at < 0 ? type : type.substring(0, at)).trim().toLowerCase(Locale.US);
    }

    /** The file name the server suggests, or an empty string. */
    private static String fileName(HttpURLConnection c) {
        String disposition = c.getHeaderField("Content-Disposition");
        if (disposition == null) return "";
        // RFC 5987 first: filename*=UTF-8''the%20name
        int star = disposition.toLowerCase(Locale.US).indexOf("filename*=");
        if (star >= 0) {
            String rest = disposition.substring(star + 10).trim();
            int eq = rest.indexOf("''");
            String encoded = eq >= 0 ? rest.substring(eq + 2) : rest;
            int semi = encoded.indexOf(';');
            if (semi >= 0) encoded = encoded.substring(0, semi);
            try {
                return sanitize(java.net.URLDecoder.decode(unquote(encoded), "UTF-8"));
            } catch (Exception ignored) {
                // fall through to the plain filename below
            }
        }
        int at = disposition.toLowerCase(Locale.US).indexOf("filename=");
        if (at < 0) return "";
        String rest = disposition.substring(at + 9).trim();
        int semi = rest.indexOf(';');
        if (semi >= 0) rest = rest.substring(0, semi);
        return sanitize(unquote(rest));
    }

    private static String unquote(String value) {
        String s = value.trim();
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            s = s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String sanitize(String name) {
        String base = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").replaceAll("\\s+", " ").trim();
        return base.length() > 80 ? base.substring(0, 80).trim() : base;
    }

    /** File extension for a media type, used when the server does not name the file. */
    static String extensionFor(String mime) {
        if ("audio/mpeg".equals(mime) || "audio/mp3".equals(mime)) return "mp3";
        if ("audio/mp4".equals(mime) || "audio/x-m4a".equals(mime) || "audio/m4a".equals(mime)) return "m4a";
        if ("audio/aac".equals(mime) || "audio/x-hx-aac-adts".equals(mime)) return "aac";
        if ("audio/ogg".equals(mime) || "application/ogg".equals(mime)) return "ogg";
        if ("audio/wav".equals(mime) || "audio/x-wav".equals(mime) || "audio/wave".equals(mime)) return "wav";
        if ("audio/flac".equals(mime) || "audio/x-flac".equals(mime)) return "flac";
        if ("audio/webm".equals(mime)) return "webm";
        if ("video/mp4".equals(mime)) return "mp4";
        if ("video/webm".equals(mime)) return "webm";
        if ("video/x-matroska".equals(mime)) return "mkv";
        if ("video/3gpp".equals(mime)) return "3gp";
        return "bin";
    }
}
