package com.serena.syncora.lyrics;

/**
 * A word (or timed text fragment) inside a lyric line. The timestamp is supplied by the lyrics source;
 * it is never inferred from the line or neighboring words.
 */
public final class LyricWord {
    public final long timeMs;
    public final String text;

    public LyricWord(long timeMs, String text) {
        this.timeMs = timeMs;
        this.text = text == null ? "" : text;
    }

    public boolean timed() {
        return timeMs >= 0;
    }
}
