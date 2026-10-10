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

    public Lyrics(List<LyricLine> lines, String source) {
        this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
        this.source = source == null ? "" : source;
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    /** True when at least one line carries a timestamp. */
    public boolean synced() {
        for (LyricLine l : lines) if (l.timed()) return true;
        return false;
    }

    /**
     * Index of the line that is current at {@code positionMs}: the last timed line that started at or before it.
     * Returns -1 before the first line or when the lyrics are not synchronised.
     */
    public int indexAt(long positionMs) {
        if (!synced()) return -1;
        int index = -1;
        for (int i = 0; i < lines.size(); i++) {
            LyricLine l = lines.get(i);
            if (!l.timed()) continue;
            if (l.timeMs <= positionMs) index = i;
            else break;
        }
        return index;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("source", source);
            o.put("synced", synced());
            JSONArray array = new JSONArray();
            for (LyricLine l : lines) {
                JSONObject line = new JSONObject();
                line.put("t", l.timeMs);
                line.put("x", l.text);
                array.put(line);
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
            lines.add(new LyricLine(line.optLong("t", -1), line.optString("x", "")));
        }
        return new Lyrics(lines, o.optString("source", ""));
    }
}
