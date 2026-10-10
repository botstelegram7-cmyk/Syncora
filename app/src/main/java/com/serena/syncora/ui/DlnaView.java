package com.serena.syncora.ui;

import android.content.Context;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.serena.syncora.R;
import com.serena.syncora.SessionState;

/** DLNA renderer mode: other apps on the network cast to this device. */
final class DlnaView extends SourceView {
    private final RingsView rings;
    private final TextView headline;
    private final TextView detail;
    private final TextView nameValue;
    private final TextView addressValue;
    private final LinearLayout castCard;
    private final TextView castTitle;
    private final TextView castSubtitle;

    DlnaView(Context c, UiHost host) {
        super(c, host);
        ScrollView scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        addView(scroll, Ui.frame(Ui.MATCH, Ui.MATCH));
        LinearLayout column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(Ui.dp(20), Ui.dp(8), Ui.dp(20), Ui.dp(28));
        scroll.addView(column, new android.widget.FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

        rings = new RingsView(c, R.drawable.ic_cast);
        column.addView(rings, Ui.lin(Ui.dp(200), Ui.dp(200)));
        headline = Ui.text(c, "Ready to cast", 22, Theme.text, Ui.BOLD);
        headline.setGravity(Gravity.CENTER);
        column.addView(headline, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 6, 0, 0));
        detail = Ui.text(c, "", 14, Theme.muted, Ui.REGULAR);
        detail.setGravity(Gravity.CENTER);
        detail.setLineSpacing(Ui.dpf(3), 1f);
        column.addView(detail, Ui.lin(Ui.MATCH, Ui.WRAP, 24, 8, 24, 0));

        castCard = new LinearLayout(c);
        castCard.setOrientation(LinearLayout.VERTICAL);
        castCard.setPadding(Ui.dp(18), Ui.dp(14), Ui.dp(18), Ui.dp(14));
        castCard.setBackground(Ui.stroke(Theme.accentSoft, Theme.alpha(Theme.accent, 100), 1, 18));
        castCard.addView(Ui.label(c, "Now casting"), Ui.lin(Ui.MATCH, Ui.WRAP));
        castTitle = Ui.text(c, "", 16, Theme.text, Ui.BOLD);
        castTitle.setSingleLine(true);
        castCard.addView(castTitle, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 6, 0, 0));
        castSubtitle = Ui.text(c, "", 13, Theme.muted, Ui.REGULAR);
        castSubtitle.setSingleLine(true);
        castCard.addView(castSubtitle, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 3, 0, 0));
        column.addView(castCard, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 18, 0, 0));

        LinearLayout info = new LinearLayout(c);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(Ui.dp(18), Ui.dp(14), Ui.dp(18), Ui.dp(14));
        info.setBackground(Ui.card(18));
        info.addView(Ui.label(c, "Visible as"), Ui.lin(Ui.MATCH, Ui.WRAP));
        nameValue = Ui.text(c, "", 16, Theme.text, Ui.BOLD);
        info.addView(nameValue, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 6, 0, 12));
        info.addView(Ui.label(c, "Address"), Ui.lin(Ui.MATCH, Ui.WRAP));
        addressValue = Ui.text(c, "", 14, Theme.muted, Ui.MONO);
        info.addView(addressValue, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 6, 0, 0));
        column.addView(info, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 14, 0, 0));

        LinearLayout steps = new LinearLayout(c);
        steps.setOrientation(LinearLayout.VERTICAL);
        steps.setPadding(Ui.dp(18), Ui.dp(14), Ui.dp(18), Ui.dp(16));
        steps.setBackground(Ui.card(18));
        steps.addView(Ui.label(c, "How to cast"), Ui.lin(Ui.MATCH, Ui.WRAP, 0, 0, 0, 10));
        String[] lines = {
                "Open any DLNA / UPnP app \u2014 BubbleUPnP, VLC, Kodi, Windows \u201CCast to device\u201D\u2026",
                "Pick this device as the player.",
                "Play a song. It plays here and on every connected speaker, in sync."
        };
        for (int i = 0; i < lines.length; i++) {
            LinearLayout line = new LinearLayout(c);
            TextView n = Ui.text(c, String.valueOf(i + 1), 12, Theme.onAccent, Ui.BOLD);
            n.setGravity(Gravity.CENTER);
            n.setBackground(Ui.oval(Theme.accent));
            line.addView(n, Ui.lin(Ui.dp(22), Ui.dp(22), 0, 1, 12, 0));
            TextView t = Ui.text(c, lines[i], 13, Theme.muted, Ui.REGULAR);
            t.setLineSpacing(Ui.dpf(3), 1f);
            line.addView(t, Ui.lin(0, Ui.WRAP, 1f));
            steps.addView(line, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 0, 0, i == lines.length - 1 ? 0 : 10));
        }
        column.addView(steps, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 14, 0, 0));
    }

    @Override
    String subtitle(SessionState s) {
        return s.dlnaOn ? "Discoverable on your network" : "Starting\u2026";
    }

    @Override
    public void onState(SessionState s) {
        rings.setActive(s.dlnaOn && !s.playing);
        nameValue.setText(s.dlnaName);
        String address = s.roomOpen && !s.roomIp.isEmpty() ? "http://" + s.roomIp + ":" + s.roomPort : "Waiting for Wi-Fi\u2026";
        addressValue.setText(address);
        if (!s.dlnaOn) {
            headline.setText("Starting\u2026");
            detail.setText("Syncora is making this device visible to DLNA apps.");
        } else if (s.playing) {
            headline.setText("Casting");
            detail.setText("Playing on " + (s.devices.size() > 1 ? s.devices.size() + " devices" : "this device") + ".");
        } else {
            headline.setText("Ready to cast");
            detail.setText("This device now shows up in DLNA apps on your Wi-Fi. Other Syncora speakers can join and play along.");
        }
        castCard.setVisibility(s.hasMedia ? VISIBLE : GONE);
        castTitle.setText(s.title);
        castSubtitle.setText(s.subtitle);
    }
}
