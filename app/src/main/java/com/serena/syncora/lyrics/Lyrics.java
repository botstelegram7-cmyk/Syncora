package com.serena.syncora.lyrics;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Lyrics (or subtitles) of one song: timed lines when synchronised, plain lines otherwise. */
public final class Lyrics {
    public final List<LyricLine> lines;
    /** Where they came from, e.g. "LRCLIB" or "YouTube captions". */
    public final String source;
    private final boolean sortedByTime;

    public Lyrics(List<LyricLine> lines, String source) {
        List<LyricLine> copy = new ArrayList<>(lines == null ? Collections.<LyricLine>emptyList() : lines);
        this.sortedByTime = isSorted(copy);
        this.lines = Collections.unmodifiableList(copy);
        this.source = source == null ? "" : source;
    }

    private static boolean isSorted(List<LyricLine> lines) {
        long previous = -1;
        boolean seenTimed = false;
        boolean seenPlainAfterTimed = false;
        for (LyricLine line : lines) {
            if (!line.timed()) {
                if (seenTimed) seenPlainAfterTimed = true;
                continue;
            }
            if (seenPlainAfterTimed || (seenTimed && line.timeMs < previous)) return false;
            previous = line.timeMs;
            seenTimed = true;
        }
        return true;
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    /** True when at least one line carries a timestamp. */
    public boolean synced() {
        for (LyricLine line : lines) if (line.timed()) return true;
        return false;
    }

    /** True only when the source supplied one or more real word timestamps. */
    public boolean wordSynced() {
        for (LyricLine line : lines) if (line.hasWordTimings()) return true;
        return false;
    }

    /**
     * Index of the line that is current at {@code positionMs}: the last timed line that started at or before it.
     * Returns -1 before the first line or when the lyrics are not synchronised.
     */
    public int indexAt(long positionMs) {
        if (!synced()) return -1;
        if (!sortedByTime) return linearIndexAt(positionMs);
        int low = 0;
        int high = lines.size();
        // Find the first line after positionMs; equal timestamps intentionally select the last duplicate.
        while (low < high) {
            int mid = (low + high) >>> 1;
            LyricLine line = lines.get(mid);
            if (!line.timed() || line.timeMs <= positionMs) low = mid + 1;
            else high = mid;
        }
        int index = low - 1;
        while (index >= 0 && !lines.get(index).timed()) index--;
        return index;
    }

    private int linearIndexAt(long positionMs) {
        int result = -1;
        long latest = Long.MIN_VALUE;
        for (int i = 0; i < lines.size(); i++) {
            LyricLine line = lines.get(i);
            if (line.timed() && line.timeMs <= positionMs && line.timeMs >= latest) {
                latest = line.timeMs;
                result = i;
            }
        }
        return result;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("source", source);
            o.put("synced", synced());
            JSONArray array = new JSONArray();
            for (LyricLine line : lines) {
                JSONObject item = new JSONObject();
                item.put("t", line.timeMs);
                item.put("x", line.text);
                if (!line.words.isEmpty()) {
                    JSONArray words = new JSONArray();
                    for (LyricWord word : line.words) {
                        JSONObject w = new JSONObject();
                        w.put("t", word.timeMs);
                        w.put("x", word.text);
                        words.put(w);
                    }
                    item.put("w", words);
                }
                array.put(item);
            }
            o.put("lines", array);
        } catch (JSONException ignored) {
            // cannot happen for these types
        }
        return o;
    }

    /** Returns null when the JSON does not describe lyrics. */
    public static Lyrics fromJson(JSONObject o) {
        if (o == null) return null;
        JSONArray array = o.optJSONArray("lines");
        if (array == null) return null;
        List<LyricLine> lines = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject line = array.optJSONObject(i);
            if (line == null) continue;
            List<LyricWord> words = new ArrayList<>();
            JSONArray wordArray = line.optJSONArray("w");
            if (wordArray != null) {
                for (int j = 0; j < wordArray.length(); j++) {
                    JSONObject word = wordArray.optJSONObject(j);
                    if (word != null) words.add(new LyricWord(word.optLong("t", -1), word.optString("x", "")));
                }
            }
            lines.add(new LyricLine(line.optLong("t", -1), line.optString("x", ""), words));
        }
        return new Lyrics(lines, o.optString("source", ""));
    }
}
