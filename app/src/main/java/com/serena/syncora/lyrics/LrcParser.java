package com.serena.syncora.lyrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads LRC text ({@code [mm:ss.xx]line}, several stamps per line allowed, {@code [offset:+-ms]} honoured).
 * Plain text without stamps becomes unsynchronised lines.
 */
public final class LrcParser {

    private static final Pattern STAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern OFFSET = Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\s*\\]", Pattern.CASE_INSENSITIVE);

    private LrcParser() {
    }

    public static Lyrics parse(String text, String source) {
        if (text == null) return new Lyrics(new ArrayList<LyricLine>(), source);
        long offsetMs = 0;
        Matcher offset = OFFSET.matcher(text);
        if (offset.find()) {
            try {
                offsetMs = Long.parseLong(offset.group(1));
            } catch (NumberFormatException ignored) {
                offsetMs = 0;
            }
        }
        List<LyricLine> timed = new ArrayList<>();
        List<LyricLine> plain = new ArrayList<>();
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            Matcher m = STAMP.matcher(line);
            int last = 0;
            List<Long> stamps = new ArrayList<>();
            while (m.find()) {
                stamps.add(toMillis(m.group(1), m.group(2), m.group(3)));
                last = m.end();
            }
            String body = line.substring(last).trim();
            if (stamps.isEmpty()) {
                // metadata tags such as [ar:...] or [ti:...] are not lyrics
                if (!line.matches("^\\[[a-zA-Z]+:.*\\]$")) plain.add(new LyricLine(-1, line));
                continue;
            }
            for (long stamp : stamps) timed.add(new LyricLine(Math.max(0, stamp + offsetMs), body));
        }
        if (!timed.isEmpty()) {
            Collections.sort(timed, new Comparator<LyricLine>() {
                @Override
                public int compare(LyricLine a, LyricLine b) {
                    return Long.compare(a.timeMs, b.timeMs);
                }
            });
            return new Lyrics(timed, source);
        }
        return new Lyrics(plain, source);
    }

    /** {@code mm:ss} with optional fraction ({@code .xx} = centiseconds, {@code .xxx} = milliseconds). */
    static long toMillis(String minutes, String seconds, String fraction) {
        long ms = Long.parseLong(minutes) * 60_000L + Long.parseLong(seconds) * 1000L;
        if (fraction != null && !fraction.isEmpty()) {
            String f = fraction.length() == 1 ? fraction + "00" : (fraction.length() == 2 ? fraction + "0" : fraction);
            ms += Long.parseLong(f.substring(0, 3));
        }
        return ms;
    }

    /** Formats a time as an LRC stamp, e.g. {@code [01:02.35]}. */
    public static String stamp(long ms) {
        long cs = Math.max(0, ms) / 10;
        return String.format(Locale.US, "[%02d:%02d.%02d]", cs / 6000, (cs / 100) % 60, cs % 100);
    }

    /** Writes synchronised lyrics back as LRC text. */
    public static String toLrc(Lyrics lyrics) {
        StringBuilder out = new StringBuilder();
        for (LyricLine l : lyrics.lines) {
            if (l.timed()) out.append(stamp(l.timeMs));
            out.append(l.text).append('\n');
        }
        return out.toString();
    }
}
