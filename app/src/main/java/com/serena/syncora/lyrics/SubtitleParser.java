package com.serena.syncora.lyrics;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads caption files (WebVTT and YouTube's json3 timed text) and writes SubRip (.srt). */
public final class SubtitleParser {

    /** One caption: shown from {@code startMs} until {@code endMs}. */
    public static final class Cue {
        public final long startMs;
        public final long endMs;
        public final String text;

        public Cue(long startMs, long endMs, String text) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.text = text;
        }
    }

    private static final Pattern TIMING = Pattern.compile(
            "((?:\\d{1,2}:)?\\d{1,2}:\\d{2}[.,]\\d{1,3})\\s*-->\\s*((?:\\d{1,2}:)?\\d{1,2}:\\d{2}[.,]\\d{1,3})");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern XML_CUE = Pattern.compile("<(?:p|text)\\b([^>]*)>(.*?)</(?:p|text)\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ATTRIBUTE = Pattern.compile("(?:^|\\s)%s\\s*=\\s*([\\\"'])(.*?)\\1",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]+|#\\d+|amp|lt|gt|quot|apos|nbsp);",
            Pattern.CASE_INSENSITIVE);

    private SubtitleParser() {
    }

    /** Parses WebVTT (YouTube {@code fmt=vtt}). Rolling duplicate captions are merged. */
    public static List<Cue> parseVtt(String vtt) {
        List<Cue> cues = new ArrayList<>();
        if (vtt == null) return cues;
        String[] lines = vtt.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        int i = 0;
        while (i < lines.length) {
            Matcher m = TIMING.matcher(lines[i]);
            if (!m.find()) {
                i++;
                continue;
            }
            long start = parseTime(m.group(1));
            long end = parseTime(m.group(2));
            i++;
            StringBuilder text = new StringBuilder();
            while (i < lines.length && !lines[i].trim().isEmpty()) {
                if (text.length() > 0) text.append(' ');
                text.append(lines[i].trim());
                i++;
            }
            String clean = clean(text.toString());
            if (!clean.isEmpty()) addCue(cues, new Cue(start, Math.max(end, start), clean));
        }
        return cues;
    }

    /**
     * Parses YouTube's XML caption formats (TTML and the older srv3 transcript). This is a fallback for videos whose
     * captions are not returned as json3 or WebVTT.
     */
    public static List<Cue> parseTtml(String xml) {
        List<Cue> cues = new ArrayList<>();
        if (xml == null || xml.isEmpty()) return cues;
        Matcher matcher = XML_CUE.matcher(xml);
        while (matcher.find()) {
            String attributes = matcher.group(1);
            String raw = matcher.group(2);
            String begin = attribute(attributes, "begin");
            if (begin.isEmpty()) begin = attribute(attributes, "start");
            String end = attribute(attributes, "end");
            String duration = attribute(attributes, "dur");
            if (begin.isEmpty()) continue;
            long start = parseXmlTime(begin);
            long finish = end.isEmpty()
                    ? start + (duration.isEmpty() ? 2000 : parseXmlTime(duration))
                    : parseXmlTime(end);
            String text = clean(raw);
            if (!text.isEmpty() && start >= 0) addCue(cues, new Cue(start, Math.max(start, finish), text));
        }
        return cues;
    }

    private static String attribute(String attributes, String name) {
        Matcher matcher = Pattern.compile(String.format(Locale.US, ATTRIBUTE.pattern(), Pattern.quote(name)))
                .matcher(attributes == null ? "" : attributes);
        return matcher.find() ? matcher.group(2) : "";
    }

    private static long parseXmlTime(String value) {
        if (value == null || value.trim().isEmpty()) return 0;
        String text = value.trim();
        try {
            String lower = text.toLowerCase(Locale.US);
            if (lower.endsWith("ms")) return Math.round(Double.parseDouble(lower.substring(0, lower.length() - 2)));
            if (lower.endsWith("s")) return Math.round(Double.parseDouble(lower.substring(0, lower.length() - 1)) * 1000.0);
            if (text.indexOf(':') >= 0) return parseTime(text);
            // YouTube's srv3 format uses plain decimal seconds for start/dur.
            return Math.round(Double.parseDouble(text) * 1000.0);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Parses YouTube json3 timed text ({@code fmt=json3}). */
    public static List<Cue> parseJson3(String json) {
        List<Cue> cues = new ArrayList<>();
        try {
            JSONArray events = new JSONObject(json).optJSONArray("events");
            if (events == null) return cues;
            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.optJSONObject(i);
                if (event == null) continue;
                JSONArray segs = event.optJSONArray("segs");
                if (segs == null) continue;
                StringBuilder text = new StringBuilder();
                for (int k = 0; k < segs.length(); k++) {
                    JSONObject seg = segs.optJSONObject(k);
                    if (seg != null) text.append(seg.optString("utf8", ""));
                }
                String clean = clean(text.toString());
                if (clean.isEmpty()) continue;
                long start = event.optLong("tStartMs", 0);
                long end = start + event.optLong("dDurationMs", 2000);
                addCue(cues, new Cue(start, end, clean));
            }
        } catch (JSONException ignored) {
            // not json3: no cues
        }
        return cues;
    }

    /** Cues as lyrics (one timed line per caption). */
    public static Lyrics toLyrics(List<Cue> cues, String source) {
        List<LyricLine> lines = new ArrayList<>();
        for (Cue c : cues) lines.add(new LyricLine(c.startMs, c.text));
        return new Lyrics(lines, source);
    }

    /** SubRip text for the cues. */
    public static String toSrt(List<Cue> cues) {
        StringBuilder out = new StringBuilder();
        int n = 1;
        for (Cue c : cues) {
            out.append(n++).append('\n');
            out.append(srtTime(c.startMs)).append(" --> ").append(srtTime(c.endMs)).append('\n');
            out.append(c.text).append("\n\n");
        }
        return out.toString();
    }

    /** Plain WebVTT for the cues (used when the caption file is written as .vtt by us). */
    public static String toVtt(List<Cue> cues) {
        StringBuilder out = new StringBuilder("WEBVTT\n\n");
        for (Cue c : cues) {
            out.append(vttTime(c.startMs)).append(" --> ").append(vttTime(c.endMs)).append('\n');
            out.append(c.text).append("\n\n");
        }
        return out.toString();
    }

    static long parseTime(String value) {
        String v = value.replace(',', '.');
        String[] parts = v.split(":");
        long hours = 0;
        int idx = 0;
        if (parts.length == 3) {
            hours = Long.parseLong(parts[0]);
            idx = 1;
        }
        long minutes = Long.parseLong(parts[idx]);
        String[] sec = parts[idx + 1].split("\\.");
        long seconds = Long.parseLong(sec[0]);
        long ms = 0;
        if (sec.length > 1) {
            String f = sec[1].length() == 1 ? sec[1] + "00" : (sec[1].length() == 2 ? sec[1] + "0" : sec[1]);
            ms = Long.parseLong(f.substring(0, 3));
        }
        return hours * 3_600_000L + minutes * 60_000L + seconds * 1000L + ms;
    }

    static String clean(String raw) {
        String s = TAG.matcher(raw == null ? "" : raw).replaceAll("");
        Matcher entities = ENTITY.matcher(s);
        StringBuffer decoded = new StringBuffer();
        while (entities.find()) {
            String entity = entities.group(1);
            String replacement;
            if (entity.startsWith("#")) {
                try {
                    int codePoint = entity.regionMatches(true, 0, "#x", 0, 2)
                            ? Integer.parseInt(entity.substring(2), 16)
                            : Integer.parseInt(entity.substring(1));
                    replacement = Character.isValidCodePoint(codePoint)
                            ? new String(Character.toChars(codePoint)) : entities.group();
                } catch (RuntimeException ignored) {
                    replacement = entities.group();
                }
            } else if ("amp".equalsIgnoreCase(entity)) replacement = "&";
            else if ("lt".equalsIgnoreCase(entity)) replacement = "<";
            else if ("gt".equalsIgnoreCase(entity)) replacement = ">";
            else if ("quot".equalsIgnoreCase(entity)) replacement = "\"";
            else if ("apos".equalsIgnoreCase(entity)) replacement = "'";
            else replacement = " ";
            entities.appendReplacement(decoded, Matcher.quoteReplacement(replacement));
        }
        entities.appendTail(decoded);
        return decoded.toString().replace('\u200b', ' ').replaceAll("\\s+", " ").trim();
    }

    /** Adds a cue, merging it into the previous one when the text repeats (rolling auto-captions). */
    private static void addCue(List<Cue> cues, Cue cue) {
        if (!cues.isEmpty()) {
            Cue last = cues.get(cues.size() - 1);
            if (last.text.equals(cue.text) && cue.startMs <= last.endMs + 50) {
                cues.set(cues.size() - 1, new Cue(last.startMs, Math.max(last.endMs, cue.endMs), last.text));
                return;
            }
        }
        cues.add(cue);
    }

    private static String srtTime(long ms) {
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", ms / 3_600_000L, (ms / 60_000L) % 60, (ms / 1000L) % 60, ms % 1000);
    }

    private static String vttTime(long ms) {
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", ms / 3_600_000L, (ms / 60_000L) % 60, (ms / 1000L) % 60, ms % 1000);
    }
}
