package com.serena.syncora.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.serena.syncora.R;
import com.serena.syncora.SessionState;
import com.serena.syncora.SyncoraService;
import com.serena.syncora.core.ChannelMode;

/** Third tab: the room and every device in it. */
public final class DevicesPage extends FrameLayout implements StateListener {
    private final UiHost host;
    private final LinearLayout column;
    private final LinearLayout titleBlock;
    private final TextView subtitleView;
    private final LinearLayout roomCard;
    private final TextView roomCode;
    private final TextView roomAddress;
    private final TextView roomHint;
    private final TextView startRoom;
    private final ToggleView shareToggle;
    private final LinearLayout shareRow;
    private final LinearLayout connectionCard;
    private final TextView connectionTitle;
    private final TextView connectionDetail;
    private final TextView connectionButton;
    private final LinearLayout list;
    private final TextView listLabel;
    private final ToggleView lowLatency;
    private final TextView syncInfo;
    private String listSignature = "";
    private SessionState state = new SessionState();

    public DevicesPage(final UiHost host) {
        super(host.activity());
        this.host = host;
        final Context c = host.activity();
        setBackgroundColor(Theme.bg);

        ScrollView scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        addView(scroll, Ui.frame(Ui.MATCH, Ui.MATCH));
        column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(Ui.dp(20), Ui.dp(16), Ui.dp(20), Ui.dp(28));
        scroll.addView(column, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

        titleBlock = new LinearLayout(c);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleBlock.addView(Ui.text(c, "Devices", 28, Theme.text, Ui.BOLD), Ui.lin(Ui.MATCH, Ui.WRAP));
        subtitleView = Ui.text(c, "", 13, Theme.muted, Ui.REGULAR);
        titleBlock.addView(subtitleView, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 4, 0, 0));
        column.addView(titleBlock, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 0, 0, 16));

        // ---- room card (player role)
        roomCard = new LinearLayout(c);
        roomCard.setOrientation(LinearLayout.VERTICAL);
        roomCard.setPadding(Ui.dp(20), Ui.dp(18), Ui.dp(20), Ui.dp(18));
        roomCard.setBackground(Ui.gradient(Theme.mix(Theme.surface, Theme.accent, 0.16f), Theme.surface,
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR, 24));
        roomCard.addView(Ui.label(c, "Your room"), Ui.lin(Ui.MATCH, Ui.WRAP));
        roomCode = Ui.text(c, "SYNC-------", 30, Theme.text, Ui.MONO);
        roomCode.setLetterSpacing(0.08f);
        roomCard.addView(roomCode, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 10, 0, 0));
        roomAddress = Ui.text(c, "", 14, Theme.muted, Ui.MONO);
        roomCard.addView(roomAddress, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 8, 0, 0));
        roomHint = Ui.text(c, "On another device open Syncora \u2192 Source \u2192 Speaker Mode. It finds this room automatically, "
                + "or type the IP address above.", 12, Theme.muted, Ui.REGULAR);
        roomHint.setLineSpacing(Ui.dpf(3), 1f);
        roomCard.addView(roomHint, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 10, 0, 0));
        LinearLayout actions = new LinearLayout(c);
        TextView copy = Ui.secondaryButton(c, "Copy IP");
        copy.setCompoundDrawablePadding(Ui.dp(8));
        copy.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                String text = state.roomIp.isEmpty() ? state.roomCode : state.roomIp + ":" + state.roomPort;
                ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Syncora", text));
                host.toast("Copied " + text);
            }
        });
        actions.addView(copy, Ui.linW(1f, Ui.WRAP, 0, 0, 6, 0));
        TextView share = Ui.secondaryButton(c, "Share");
        share.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                String text = "Join my Syncora room: open Syncora, choose Source \u2192 Speaker Mode and connect to "
                        + (state.roomIp.isEmpty() ? state.roomCode : state.roomIp + ":" + state.roomPort) + " (" + state.roomCode + ")";
                Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
                host.activity().startActivity(Intent.createChooser(send, "Share room"));
            }
        });
        actions.addView(share, Ui.linW(1f, Ui.WRAP, 6, 0, 0, 0));
        roomCard.addView(actions, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 14, 0, 0));
        startRoom = Ui.primaryButton(c, "Start sharing");
        startRoom.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                SyncoraService s = host.service();
                if (s != null) {
                    host.prefs().setShareRoom(true);
                    s.startRoom();
                }
            }
        });
        roomCard.addView(startRoom, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 14, 0, 0));
        shareRow = new LinearLayout(c);
        shareRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout shareTexts = new LinearLayout(c);
        shareTexts.setOrientation(LinearLayout.VERTICAL);
        shareTexts.addView(Ui.text(c, "Visible to nearby speakers", 14, Theme.text, Ui.MEDIUM), Ui.lin(Ui.MATCH, Ui.WRAP));
        shareTexts.addView(Ui.text(c, "Lets speakers on this Wi-Fi find the room", 12, Theme.muted, Ui.REGULAR), Ui.lin(Ui.MATCH, Ui.WRAP, 0, 3, 0, 0));
        shareRow.addView(shareTexts, Ui.lin(0, Ui.WRAP, 1f));
        shareToggle = new ToggleView(c);
        shareToggle.setListener(new ToggleView.Listener() {
            @Override
            public void onChanged(boolean checked) {
                SyncoraService s = host.service();
                if (s != null) s.setShareRoom(checked);
            }
        });
        shareRow.addView(shareToggle, Ui.lin(Ui.WRAP, Ui.WRAP));
        roomCard.addView(shareRow, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 16, 0, 0));
        column.addView(roomCard, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 0, 0, 14));

        // ---- connection card (speaker role)
        connectionCard = new LinearLayout(c);
        connectionCard.setOrientation(LinearLayout.VERTICAL);
        connectionCard.setPadding(Ui.dp(20), Ui.dp(18), Ui.dp(20), Ui.dp(18));
        connectionCard.setBackground(Ui.card(24));
        connectionCard.addView(Ui.label(c, "Connection"), Ui.lin(Ui.MATCH, Ui.WRAP));
        connectionTitle = Ui.text(c, "", 22, Theme.text, Ui.BOLD);
        connectionCard.addView(connectionTitle, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 8, 0, 0));
        connectionDetail = Ui.text(c, "", 13, Theme.muted, Ui.REGULAR);
        connectionDetail.setLineSpacing(Ui.dpf(3), 1f);
        connectionCard.addView(connectionDetail, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 6, 0, 0));
        connectionButton = Ui.secondaryButton(c, "Find players");
        connectionButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (state.connection == SessionState.Connection.CONNECTED) {
                    SyncoraService s = host.service();
                    if (s != null) s.disconnectSpeaker();
                } else {
                    host.navigate(UiHost.TAB_SOURCE);
                }
            }
        });
        connectionCard.addView(connectionButton, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 14, 0, 0));
        column.addView(connectionCard, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 0, 0, 14));

        // ---- sync card
        LinearLayout sync = new LinearLayout(c);
        sync.setOrientation(LinearLayout.VERTICAL);
        sync.setPadding(Ui.dp(18), Ui.dp(16), Ui.dp(18), Ui.dp(16));
        sync.setBackground(Ui.card(22));
        LinearLayout syncHead = new LinearLayout(c);
        syncHead.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout syncTexts = new LinearLayout(c);
        syncTexts.setOrientation(LinearLayout.VERTICAL);
        syncTexts.addView(Ui.text(c, "Low-latency sync", 15, Theme.text, Ui.BOLD), Ui.lin(Ui.MATCH, Ui.WRAP));
        syncInfo = Ui.text(c, "", 12, Theme.muted, Ui.REGULAR);
        syncTexts.addView(syncInfo, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 3, 0, 0));
        syncHead.addView(syncTexts, Ui.lin(0, Ui.WRAP, 1f));
        lowLatency = new ToggleView(c);
        lowLatency.setListener(new ToggleView.Listener() {
            @Override
            public void onChanged(boolean checked) {
                SyncoraService s = host.service();
                if (s != null) s.setLowLatency(checked);
            }
        });
        syncHead.addView(lowLatency, Ui.lin(Ui.WRAP, Ui.WRAP));
        sync.addView(syncHead, Ui.lin(Ui.MATCH, Ui.WRAP));
        TextView syncNow = Ui.primaryButton(c, "Sync now");
        syncNow.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                SyncoraService s = host.service();
                if (s != null) s.syncNow();
            }
        });
        sync.addView(syncNow, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 14, 0, 0));
        column.addView(sync, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 0, 0, 18));

        // ---- device list
        listLabel = Ui.label(c, "Connected devices");
        column.addView(listLabel, Ui.lin(Ui.MATCH, Ui.WRAP, 4, 0, 0, 10));
        list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        column.addView(list, Ui.lin(Ui.MATCH, Ui.WRAP));
    }

    public void setTopInset(int px) {
        column.setPadding(Ui.dp(20), Ui.dp(16) + px, Ui.dp(20), Ui.dp(28));
    }

    @Override
    public void onState(SessionState s) {
        state = s;
        boolean speaker = s.isSpeaker();
        roomCard.setVisibility(speaker ? GONE : VISIBLE);
        connectionCard.setVisibility(speaker ? VISIBLE : GONE);

        if (!speaker) {
            boolean open = s.roomOpen;
            roomCode.setText(open ? s.roomCode : "Not sharing");
            roomCode.setTextSize(open ? 30 : 22);
            roomAddress.setText(open && !s.roomIp.isEmpty() ? s.roomIp + ":" + s.roomPort
                    : open ? "Connect to Wi-Fi to share" : "Start sharing to let other devices join");
            roomHint.setVisibility(open ? VISIBLE : GONE);
            startRoom.setVisibility(open ? GONE : VISIBLE);
            shareRow.setVisibility(open ? VISIBLE : GONE);
            if (shareToggle.isChecked() != s.shareRoom) shareToggle.setChecked(s.shareRoom, false);
            int speakers = s.connectedSpeakers();
            subtitleView.setText(speakers == 0 ? "Share your music with speakers on this Wi-Fi"
                    : speakers + (speakers == 1 ? " speaker connected" : " speakers connected"));
        } else {
            boolean connected = s.connection == SessionState.Connection.CONNECTED;
            connectionTitle.setText(connected ? (s.hostName.isEmpty() ? "Connected" : s.hostName) : "Waiting for connection");
            connectionDetail.setText(connected ? "This device is a speaker. Everything the player plays comes out here too, in sync."
                    : (s.connectionText.isEmpty() ? "Open Syncora on the player device and start playing." : s.connectionText));
            connectionButton.setText(connected ? "Disconnect" : "Find players");
            subtitleView.setText(connected ? "Speaker mode" : "Speaker mode \u00B7 not connected");
        }

        if (lowLatency.isChecked() != s.lowLatency()) lowLatency.setChecked(s.lowLatency(), false);
        syncInfo.setText(s.lowLatency() ? "On \u2014 about " + s.lookaheadMs + " ms delay" : "Off \u2014 steady, buffered playback");

        listLabel.setText((speaker ? "Devices in this room" : "Connected devices").toUpperCase(java.util.Locale.getDefault()));
        StringBuilder sig = new StringBuilder();
        for (SessionState.Device d : s.devices) {
            sig.append(d.id).append(d.name).append(d.status).append(d.channel).append(d.offsetMs).append(d.syncErrMs)
                    .append(Math.round(d.volume * 100)).append(d.muted).append(';');
        }
        if (!sig.toString().equals(listSignature)) {
            listSignature = sig.toString();
            renderList(s);
        }
    }

    private void renderList(SessionState s) {
        Context c = getContext();
        list.removeAllViews();
        if (s.devices.isEmpty() || (s.devices.size() == 1 && s.isSpeaker() && s.connection != SessionState.Connection.CONNECTED)) {
            TextView empty = Ui.text(c, "No other devices yet.", 14, Theme.muted, Ui.REGULAR);
            list.addView(empty, Ui.lin(Ui.MATCH, Ui.WRAP, 4, 0, 0, 0));
        }
        for (final SessionState.Device d : s.devices) {
            LinearLayout card = new LinearLayout(c);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(Ui.dp(14), Ui.dp(14), Ui.dp(8), Ui.dp(14));
            card.setBackground(Ui.ripple(Ui.card(20), 20));
            card.setClickable(true);
            card.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    Sheets.audio(host, d.id);
                }
            });
            LinearLayout badge = new LinearLayout(c);
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(Ui.rect(d.self ? Theme.alpha(Theme.accent, 46) : Theme.surface3, 16));
            badge.addView(Ui.icon(c, d.host ? R.drawable.ic_phone : R.drawable.ic_speaker, 24, d.self ? Theme.accent : Theme.muted),
                    Ui.lin(Ui.dp(24), Ui.dp(24)));
            card.addView(badge, Ui.lin(Ui.dp(48), Ui.dp(48), 0, 0, 14, 0));

            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView name = Ui.text(c, Sheets.deviceName(d), 15, Theme.text, Ui.BOLD);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(name, Ui.lin(Ui.MATCH, Ui.WRAP));
            LinearLayout statusLine = new LinearLayout(c);
            statusLine.setGravity(Gravity.CENTER_VERTICAL);
            View dot = new View(c);
            dot.setBackground(Ui.oval(Sheets.statusColor(d)));
            statusLine.addView(dot, Ui.lin(Ui.dp(7), Ui.dp(7), 0, 0, 7, 0));
            TextView status = Ui.text(c, Sheets.roleLabel(d) + " \u00B7 " + Sheets.statusText(d), 12, Theme.muted, Ui.REGULAR);
            status.setSingleLine(true);
            statusLine.addView(status, Ui.lin(Ui.WRAP, Ui.WRAP));
            texts.addView(statusLine, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 4, 0, 0));
            String detail = (d.address.isEmpty() ? "" : d.address + " \u00B7 ") + ChannelMode.fromId(d.channel).label
                    + (d.offsetMs != 0 ? " \u00B7 " + (d.offsetMs > 0 ? "+" : "") + d.offsetMs + " ms" : "")
                    + " \u00B7 " + (d.muted ? "muted" : Math.round(d.volume * 100) + "%");
            TextView meta = Ui.text(c, detail, 11, Theme.subtle, Ui.REGULAR);
            meta.setSingleLine(true);
            meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(meta, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 3, 0, 0));
            card.addView(texts, Ui.lin(0, Ui.WRAP, 1f));

            if (!s.isSpeaker() && !d.self) {
                ImageView remove = Ui.iconButton(c, R.drawable.ic_close, 44, 20, Theme.muted, "Disconnect " + d.name);
                remove.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        SyncoraService service = host.service();
                        if (service != null) service.kickDevice(d.id);
                    }
                });
                card.addView(remove, Ui.lin(Ui.dp(44), Ui.dp(44)));
            } else {
                card.addView(Ui.icon(c, R.drawable.ic_chevron, 22, Theme.subtle), Ui.lin(Ui.dp(22), Ui.dp(22), 0, 0, 8, 0));
            }
            list.addView(card, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 0, 0, 10));
        }
    }
}
