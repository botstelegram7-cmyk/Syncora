package com.serena.syncora.lyrics;

/** One line of lyrics. {@code timeMs} is -1 for lines of unsynchronised (plain) lyrics. */
public final class LyricLine {
    public final long timeMs;
    public final String text;

    public LyricLine(long timeMs, String text) {
        this.timeMs = timeMs;
        this.text = text == null ? "" : text;
    }

    public boolean timed() {
        return timeMs >= 0;
    }
}
