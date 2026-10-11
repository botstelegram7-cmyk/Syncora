package com.serena.syncora.lyrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One line of lyrics. {@code timeMs} is -1 for lines of unsynchronised (plain) lyrics. */
public final class LyricLine {
    public final long timeMs;
    public final String text;
    /** Only populated when the source supplied real word timestamps (for example enhanced LRC). */
    public final List<LyricWord> words;

    public LyricLine(long timeMs, String text) {
        this(timeMs, text, Collections.<LyricWord>emptyList());
    }

    public LyricLine(long timeMs, String text, List<LyricWord> words) {
        this.timeMs = timeMs;
        this.text = text == null ? "" : text;
        this.words = Collections.unmodifiableList(new ArrayList<>(words == null
                ? Collections.<LyricWord>emptyList() : words));
    }

    public boolean timed() {
        return timeMs >= 0;
    }

    /** Index of the word whose source timestamp is latest but not after {@code positionMs}. */
    public int wordIndexAt(long positionMs) {
        int low = 0;
        int high = words.size();
        // upper_bound: when a source repeats a stamp, the final fragment at that stamp wins.
        while (low < high) {
            int mid = (low + high) >>> 1;
            LyricWord word = words.get(mid);
            if (!word.timed() || word.timeMs <= positionMs) low = mid + 1;
            else high = mid;
        }
        int index = low - 1;
        if (index < 0 || index >= words.size() || !words.get(index).timed()) return -1;
        return index;
    }

    public boolean hasWordTimings() {
        for (LyricWord word : words) if (word.timed()) return true;
        return false;
    }
}
