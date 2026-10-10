package com.serena.syncora.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.serena.syncora.lyrics.LyricLine;
import com.serena.syncora.lyrics.Lyrics;

import java.util.ArrayList;
import java.util.List;

/**
 * The lyrics page next to the cover art. Synced lyrics follow the playback position: the current line is highlighted
 * and kept near the middle of the screen. Tapping a synced line jumps to it. Plain lyrics just scroll.
 */
final class LyricsView extends ScrollView {

    /** Called when the user taps a timed line. */
    interface SeekListener {
        void onSeek(long positionMs);
    }

    private final LinearLayout column;
    private final List<TextView> lineViews = new ArrayList<>();
    private Lyrics shown;
    private String shownKey;
    private String shownStatus;
    private int active = -1;
    private SeekListener seekListener;

    LyricsView(Context context) {
        super(context);
        setVerticalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(Ui.dp(28), Ui.dp(16), Ui.dp(28), Ui.dp(96));
        addView(column, new ScrollView.LayoutParams(Ui.MATCH, Ui.WRAP));
    }

    void setSeekListener(SeekListener listener) {
        seekListener = listener;
    }

    /**
     * Shows the lyrics of one song. Without lyrics, shows the status line instead (e.g. "Looking for lyrics…").
     * Calling it again with the same song and lyrics does nothing.
     */
    void show(String key, Lyrics lyrics, String status) {
        String safeKey = key == null ? "" : key;
        String safeStatus = status == null ? "" : status;
        if (lyrics == shown && safeKey.equals(shownKey) && safeStatus.equals(shownStatus)) return;
        shown = lyrics;
        shownKey = safeKey;
        shownStatus = safeStatus;
        rebuild();
    }

    private void rebuild() {
        column.removeAllViews();
        lineViews.clear();
        active = -1;
        scrollTo(0, 0);
        Context c = getContext();
        if (shown == null || shown.isEmpty()) {
            String text = shownStatus.isEmpty() ? "Play a song to see its lyrics" : shownStatus;
            column.addView(Ui.text(c, text, 16, Theme.muted, Ui.MEDIUM), Ui.lin(Ui.MATCH, Ui.WRAP, 0, Ui.dp(48), 0, 0));
            return;
        }
        boolean synced = shown.synced();
        for (final LyricLine line : shown.lines) {
            TextView t = Ui.text(c, line.text.isEmpty() ? " " : line.text, 19, Theme.text, Ui.REGULAR);
            t.setLineSpacing(Ui.dpf(4), 1f);
            t.setGravity(Gravity.START);
            style(t, synced, false);
            if (synced && line.timed()) {
                t.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        if (seekListener != null) seekListener.onSeek(line.timeMs);
                    }
                });
            }
            column.addView(t, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 0, 0, Ui.dp(14)));
            lineViews.add(t);
        }
        column.addView(Ui.text(c, "Lyrics from " + shown.source, 11, Theme.subtle, Ui.MEDIUM),
                Ui.lin(Ui.MATCH, Ui.WRAP, 0, Ui.dp(8), 0, 0));
    }

    /** Moves the highlight to the line being sung at this position (synced lyrics only). */
    void setPosition(long positionMs) {
        if (shown == null || !shown.synced()) return;
        int index = shown.indexAt(positionMs);
        if (index == active) return;
        if (active >= 0 && active < lineViews.size()) style(lineViews.get(active), true, false);
        active = index;
        if (index < 0 || index >= lineViews.size()) return;
        TextView line = lineViews.get(index);
        style(line, true, true);
        if (getHeight() > 0) {
            int target = line.getTop() - (getHeight() - line.getHeight()) / 2;
            smoothScrollTo(0, Math.max(0, target));
        }
    }

    private static void style(TextView t, boolean synced, boolean active) {
        if (!synced) {
            t.setTextColor(Theme.alpha(Theme.text, 235));
            t.setTypeface(Ui.REGULAR);
        } else if (active) {
            t.setTextColor(Theme.accentHi);
            t.setTypeface(Ui.BOLD);
        } else {
            t.setTextColor(Theme.muted);
            t.setTypeface(Ui.REGULAR);
        }
    }
}
