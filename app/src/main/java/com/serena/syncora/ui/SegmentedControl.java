package com.serena.syncora.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A row of mutually exclusive options (Mono / Stereo / Left / Right, theme choices...). */
public final class SegmentedControl extends LinearLayout {
    public interface Listener {
        void onSelected(int index);
    }

    private final TextView[] segments;
    private int selected;
    private final Listener listener;

    public SegmentedControl(Context context, String[] labels, int selected, Listener listener) {
        super(context);
        this.listener = listener;
        this.selected = selected;
        setOrientation(HORIZONTAL);
        setPadding(Ui.dp(3), Ui.dp(3), Ui.dp(3), Ui.dp(3));
        setBackground(Ui.rect(Theme.surface2, 14));
        segments = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView t = Ui.text(context, labels[i], 13, Theme.muted, Ui.MEDIUM);
            t.setGravity(Gravity.CENTER);
            t.setPadding(Ui.dp(6), Ui.dp(10), Ui.dp(6), Ui.dp(10));
            t.setSingleLine(true);
            t.setClickable(true);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    select(index, true);
                }
            });
            segments[i] = t;
            addView(t, Ui.lin(0, Ui.WRAP, 1f));
        }
        refresh();
    }

    public void select(int index, boolean notify) {
        if (index == selected && !notify) return;
        selected = index;
        refresh();
        if (notify && listener != null) listener.onSelected(index);
    }

    public int getSelected() {
        return selected;
    }

    private void refresh() {
        for (int i = 0; i < segments.length; i++) {
            boolean on = i == selected;
            segments[i].setTextColor(on ? Theme.onAccent : Theme.muted);
            segments[i].setBackground(on ? Ui.rect(Theme.accent, 11) : null);
        }
    }
}
