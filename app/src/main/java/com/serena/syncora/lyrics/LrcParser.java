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
 * Enhanced LRC word stamps such as {@code <00:12.34>word} are preserved only when present in the source.
 * Plain text without line stamps becomes unsynchronised lines.
 */
public final class LrcParser {

    private static final Pattern STAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern WORD_STAMP = Pattern.compile("<(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?>");
    private static final Pattern OFFSET = Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\s*\\]", Pattern.CASE_INSENSITIVE);

    private LrcParser() {
    }

    public static Lyrics parse(String text, String source) {
        if (text == null) return new Lyrics(new ArrayList<LyricLine>(), source);
        long offsetMs = readOffset(text);
        List<LyricLine> timed = new ArrayList<>();
        List<LyricLine> plain = new ArrayList<>();
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            Matcher matcher = STAMP.matcher(line);
            List<Long> stamps = new ArrayList<>();
            int last = 0;
            try {
                while (matcher.find()) {
                    stamps.add(toMillis(matcher.group(1), matcher.group(2), matcher.group(3)));
                    last = matcher.end();
                }
            } catch (NumberFormatException ignored) {
                // A malformed timestamp must not hide other lines or crash the lyrics screen.
                continue;
            }
            String body = line.substring(last).trim();
            if (stamps.isEmpty()) {
                // Metadata tags such as [ar:...] and [ti:...] are not lyrics.
                if (!line.matches("^\\[[a-zA-Z]+:.*\\]$")) plain.add(new LyricLine(-1, cleanLine(body)));
                continue;
            }
            List<LyricWord> sourceWords = parseWords(body, offsetMs);
            String cleanText = cleanLine(stripWordStamps(body));
            for (long stamp : stamps) {
                long lineTime = addClamped(stamp, offsetMs);
                long shift = stamp - stamps.get(0);
                List<LyricWord> shifted = shiftWords(sourceWords, shift);
                timed.add(new LyricLine(lineTime, cleanText, shifted));
            }
        }
        if (!timed.isEmpty()) {
            Collections.sort(timed, new Comparator<LyricLine>() {
                @Override
                public int compare(LyricLine a, LyricLine b) {
                    return Long.compare(a.timeMs, b.timeMs);
                }
            });
            return new Lyrics(removeExactDuplicates(timed), source);
        }
        return new Lyrics(plain, source);
    }

    private static long readOffset(String text) {
        Matcher offset = OFFSET.matcher(text);
        if (!offset.find()) return 0;
        try {
            return Long.parseLong(offset.group(1));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /** Preserves the actual timed fragments embedded in enhanced LRC; no timestamps are synthesized. */
    private static List<LyricWord> parseWords(String body, long offsetMs) {
        List<LyricWord> words = new ArrayList<>();
        Matcher matcher = WORD_STAMP.matcher(body);
        int cursor = 0;
        long pendingTime = -1;
        StringBuilder pendingText = new StringBuilder();
        String prefix = "";
        boolean found = false;
        while (matcher.find()) {
            String fragment = body.substring(cursor, matcher.start());
            if (pendingTime >= 0) {
                pendingText.append(fragment);
                appendFragment(words, pendingTime, pendingText.toString());
                pendingText.setLength(0);
            } else if (!found) {
                prefix = fragment;
            }
            long wordTime;
            try {
                // Enhanced-LRC word tags are absolute source timestamps. Keep them as supplied.
                wordTime = addClamped(toMillis(matcher.group(1), matcher.group(2), matcher.group(3)), offsetMs);
            } catch (NumberFormatException ignored) {
                cursor = matcher.end();
                continue;
            }
            pendingTime = wordTime;
            found = true;
            cursor = matcher.end();
        }
        if (pendingTime >= 0) {
            pendingText.append(body.substring(cursor));
            String first = prefix.trim();
            if (!first.isEmpty()) pendingText.insert(0, first + " ");
            appendFragment(words, pendingTime, pendingText.toString());
        }
        if (words.isEmpty()) return words;
        Collections.sort(words, new Comparator<LyricWord>() {
            @Override
            public int compare(LyricWord a, LyricWord b) {
                return Long.compare(a.timeMs, b.timeMs);
            }
        });
        return words;
    }

    private static void appendFragment(List<LyricWord> words, long time, String text) {
        String clean = cleanLine(text);
        if (!clean.isEmpty()) words.add(new LyricWord(time, clean));
    }

    private static List<LyricWord> shiftWords(List<LyricWord> source, long shift) {
        if (source.isEmpty() || shift == 0) return source;
        List<LyricWord> shifted = new ArrayList<>(source.size());
        for (LyricWord word : source) shifted.add(new LyricWord(addClamped(word.timeMs, shift), word.text));
        return shifted;
    }

    private static List<LyricLine> removeExactDuplicates(List<LyricLine> source) {
        List<LyricLine> out = new ArrayList<>(source.size());
        LyricLine previous = null;
        for (LyricLine line : source) {
            if (previous != null && previous.timeMs == line.timeMs && previous.text.equals(line.text)) continue;
            out.add(line);
            previous = line;
        }
        return out;
    }

    private static String stripWordStamps(String text) {
        return WORD_STAMP.matcher(text).replaceAll("");
    }

    private static String cleanLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private static long addClamped(long time, long delta) {
        long value;
        try {
            value = Math.addExact(time, delta);
        } catch (ArithmeticException ignored) {
            value = delta < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        return Math.max(0, value);
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
        for (LyricLine line : lyrics.lines) {
            if (line.timed()) out.append(stamp(line.timeMs));
            out.append(line.text).append('\n');
        }
        return out.toString();
    }
}
