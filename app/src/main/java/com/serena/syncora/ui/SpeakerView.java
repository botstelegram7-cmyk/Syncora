package com.serena.syncora.ui;

import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.serena.syncora.R;
import com.serena.syncora.SessionState;
import com.serena.syncora.SyncoraService;
import com.serena.syncora.net.RoomDiscovery;

/** Speaker mode: wait for a player, show the connection and nearby players. */
final class SpeakerView extends SourceView {
    private final RingsView rings;
    private final TextView appName;
    private final TextView statusPill;
    private final TextView detail;
    private final TextView disconnect;
    private final LinearLayout roomsList;
    private final TextView roomsEmpty;
    private final TextView ipValue;
    private final ToggleView autoConnect;
    private final TextView[] resyncChips = new TextView[5];
    private final int[] resyncValues = {0, 5, 15, 30, 60};
    private String roomsSignature = "";
    private int resync = -1;

    SpeakerView(final Context c, final UiHost host) {
        super(c, host);
        ScrollView scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        addView(scroll, Ui.frame(Ui.MATCH, Ui.MATCH));
        LinearLayout column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(Ui.dp(20), Ui.dp(4), Ui.dp(20), Ui.dp(28));
        scroll.addView(column, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

        rings = new RingsView(c, R.drawable.ic_speaker);
        column.addView(rings, Ui.lin(Ui.dp(190), Ui.dp(190)));

        // the "player" card: app name + status
        LinearLayout player = new LinearLayout(c);
        player.setOrientation(LinearLayout.VERTICAL);
        player.setGravity(Gravity.CENTER_HORIZONTAL);
        player.setPadding(Ui.dp(20), Ui.dp(18), Ui.dp(20), Ui.dp(18));
        player.setBackground(Ui.card(22));
        player.addView(Ui.label(c, "Connected to player"), Ui.lin(Ui.WRAP, Ui.WRAP));
        appName = Ui.text(c, "Syncora Player", 24, Theme.text, Ui.BOLD);
        appName.setGravity(Gravity.CENTER);
        player.addView(appName, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 8, 0, 0));
        statusPill = Ui.text(c, "Waiting for connection", 13, Theme.warn, Ui.BOLD);
        statusPill.setPadding(Ui.dp(14), Ui.dp(7), Ui.dp(14), Ui.dp(7));
        statusPill.setGravity(Gravity.CENTER);
        player.addView(statusPill, Ui.lin(Ui.WRAP, Ui.WRAP, 0, 12, 0, 0));
        detail = Ui.text(c, "", 13, Theme.muted, Ui.REGULAR);
        detail.setGravity(Gravity.CENTER);
        detail.setLineSpacing(0, 1.15f);
        player.addView(detail, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 12, 0, 0));
        disconnect = Ui.secondaryButton(c, "Disconnect");
        disconnect.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                SyncoraService s = host.service();
                if (s != null) s.disconnectSpeaker();
            }
        });
        player.addView(disconnect, Ui.lin(Ui.WRAP, Ui.WRAP, 0, 14, 0, 0));
        column.addView(player, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 4, 0, 0));

        // players nearby
        LinearLayout nearby = new LinearLayout(c);
        nearby.setOrientation(LinearLayout.VERTICAL);
        nearby.setPadding(Ui.dp(16), Ui.dp(14), Ui.dp(16), Ui.dp(10));
        nearby.setBackground(Ui.card(20));
        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.label(c, "Players nearby"), Ui.lin(0, Ui.WRAP, 1f));
        TextView scan = Ui.text(c, "Scan again", 12, Theme.accent, Ui.BOLD);
        scan.setPadding(Ui.dp(8), Ui.dp(6), Ui.dp(8), Ui.dp(6));
        scan.setClickable(true);
        scan.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                SyncoraService s = host.service();
                if (s != null) s.rescan();
            }
        });
        head.addView(scan, Ui.lin(Ui.WRAP, Ui.WRAP));
        nearby.addView(head, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 0, 0, 6));
        roomsEmpty = Ui.text(c, "Looking for players on this Wi-Fi\u2026", 13, Theme.muted, Ui.REGULAR);
        roomsEmpty.setPadding(0, Ui.dp(8), 0, Ui.dp(8));
        nearby.addView(roomsEmpty, Ui.lin(Ui.MATCH, Ui.WRAP));
        roomsList = new LinearLayout(c);
        roomsList.setOrientation(LinearLayout.VERTICAL);
        nearby.addView(roomsList, Ui.lin(Ui.MATCH, Ui.WRAP));
        column.addView(nearby, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 14, 0, 0));

        // connect by IP
        LinearLayout ip = new LinearLayout(c);
        ip.setGravity(Gravity.CENTER_VERTICAL);
        ip.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(12), Ui.dp(12));
        ip.setBackground(Ui.card(20));
        LinearLayout ipTexts = new LinearLayout(c);
        ipTexts.setOrientation(LinearLayout.VERTICAL);
        ipTexts.addView(Ui.label(c, "Connect with IP address"), Ui.lin(Ui.MATCH, Ui.WRAP));
        ipValue = Ui.text(c, "", 15, Theme.text, Ui.MEDIUM);
        ipTexts.addView(ipValue, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 6, 0, 0));
        ip.addView(ipTexts, Ui.lin(0, Ui.WRAP, 1f));
        TextView connect = Ui.primaryButton(c, "Connect");
        connect.setPadding(Ui.dp(18), Ui.dp(11), Ui.dp(18), Ui.dp(11));
        connect.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Sheets.input(host, "Connect to a player", "Type the IP address shown on the player's Devices tab",
                        "192.168.1.20", host.prefs().connectIp(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                        "Connect", new Sheets.TextListener() {
                            @Override
                            public void onText(String text) {
                                if (text.isEmpty()) return;
                                host.prefs().setConnectIp(text);
                                SyncoraService s = host.service();
                                if (s != null) s.connectByAddress(text);
                            }
                        });
            }
        });
        ip.addView(connect, Ui.lin(Ui.WRAP, Ui.WRAP));
        column.addView(ip, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 14, 0, 0));

        // speaker settings
        LinearLayout settings = new LinearLayout(c);
        settings.setOrientation(LinearLayout.VERTICAL);
        settings.setPadding(Ui.dp(16), Ui.dp(14), Ui.dp(16), Ui.dp(14));
        settings.setBackground(Ui.card(20));
        settings.addView(Ui.label(c, "Speaker settings"), Ui.lin(Ui.MATCH, Ui.WRAP, 0, 0, 0, 10));
        LinearLayout auto = new LinearLayout(c);
        auto.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout autoTexts = new LinearLayout(c);
        autoTexts.setOrientation(LinearLayout.VERTICAL);
        autoTexts.addView(Ui.text(c, "Auto connect", 15, Theme.text, Ui.MEDIUM), Ui.lin(Ui.MATCH, Ui.WRAP));
        autoTexts.addView(Ui.text(c, "Join the last player automatically", 12, Theme.muted, Ui.REGULAR), Ui.lin(Ui.MATCH, Ui.WRAP, 0, 3, 0, 0));
        auto.addView(autoTexts, Ui.lin(0, Ui.WRAP, 1f));
        autoConnect = new ToggleView(c);
        autoConnect.setListener(new ToggleView.Listener() {
            @Override
            public void onChanged(boolean checked) {
                host.prefs().setAutoConnect(checked);
                SyncoraService s = host.service();
                if (s != null) s.settingsChanged();
            }
        });
        auto.addView(autoConnect, Ui.lin(Ui.WRAP, Ui.WRAP));
        settings.addView(auto, Ui.lin(Ui.MATCH, Ui.WRAP));
        settings.addView(Ui.text(c, "Re-sync frequency", 15, Theme.text, Ui.MEDIUM), Ui.lin(Ui.MATCH, Ui.WRAP, 0, 16, 0, 0));
        settings.addView(Ui.text(c, "How often this speaker re-checks its timing with the player", 12, Theme.muted, Ui.REGULAR),
                Ui.lin(Ui.MATCH, Ui.WRAP, 0, 3, 0, 10));
        LinearLayout chips = new LinearLayout(c);
        for (int i = 0; i < resyncValues.length; i++) {
            final int value = resyncValues[i];
            TextView chip = Ui.text(c, value == 0 ? "Off" : value + " s", 13, Theme.muted, Ui.MEDIUM);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(Ui.dp(4), Ui.dp(9), Ui.dp(4), Ui.dp(9));
            chip.setClickable(true);
            chip.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    host.prefs().setResyncSeconds(value);
                    SyncoraService s = host.service();
                    if (s != null) s.settingsChanged();
                    updateResync(value);
                }
            });
            resyncChips[i] = chip;
            chips.addView(chip, Ui.linW(1f, Ui.WRAP, i == 0 ? 0 : 6, 0, 0, 0));
        }
        settings.addView(chips, Ui.lin(Ui.MATCH, Ui.WRAP));
        column.addView(settings, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 14, 0, 0));
        updateResync(host.prefs().resyncSeconds());
    }

    private void updateResync(int value) {
        resync = value;
        for (int i = 0; i < resyncChips.length; i++) {
            boolean on = resyncValues[i] == value;
            resyncChips[i].setTextColor(on ? Theme.onAccent : Theme.muted);
            resyncChips[i].setBackground(on ? Ui.rect(Theme.accent, 14) : Ui.stroke(Theme.surface2, Theme.line, 1, 14));
        }
    }

    @Override
    String subtitle(SessionState s) {
        switch (s.connection) {
            case CONNECTED:
                return "Playing what " + (s.hostName.isEmpty() ? "the player" : s.hostName) + " plays";
            case CONNECTING:
                return "Connecting\u2026";
            default:
                return "Waiting for connection";
        }
    }

    @Override
    public void onState(SessionState s) {
        boolean connected = s.connection == SessionState.Connection.CONNECTED;
        rings.setActive(!connected);
        appName.setText(connected && !s.hostName.isEmpty() ? s.hostName : "Syncora Player");
        String text;
        int color;
        switch (s.connection) {
            case CONNECTED:
                text = "Connected";
                color = Theme.good;
                break;
            case CONNECTING:
                text = "Connecting\u2026";
                color = Theme.warn;
                break;
            case SCANNING:
                text = "Looking for players\u2026";
                color = Theme.warn;
                break;
            default:
                text = "Waiting for connection";
                color = Theme.warn;
                break;
        }
        statusPill.setText(text);
        statusPill.setTextColor(color);
        statusPill.setBackground(Ui.rect(Theme.alpha(color, 36), 16));
        detail.setText(connected
                ? (s.hasMedia ? "Now playing: " + s.title : "Nothing is playing yet \u2014 start a song on the player.")
                : (s.connectionText.isEmpty() ? "Open Syncora on the other device, pick a song and this speaker will join." : s.connectionText));
        disconnect.setVisibility(connected ? VISIBLE : GONE);
        ipValue.setText(host.prefs().connectIp().isEmpty() ? "Not set" : host.prefs().connectIp());
        if (autoConnect.isChecked() != s.autoConnect) autoConnect.setChecked(s.autoConnect, false);
        if (resync != host.prefs().resyncSeconds()) updateResync(host.prefs().resyncSeconds());

        StringBuilder sig = new StringBuilder();
        for (RoomDiscovery.RoomInfo r : s.rooms) sig.append(r.code).append(r.name).append(r.title).append(r.clients).append(r.playing).append(';');
        sig.append(connected).append(s.roomCode);
        if (!sig.toString().equals(roomsSignature)) {
            roomsSignature = sig.toString();
            renderRooms(s);
        }
        roomsEmpty.setVisibility(s.rooms.isEmpty() ? VISIBLE : GONE);
    }

    private void renderRooms(final SessionState s) {
        Context c = getContext();
        roomsList.removeAllViews();
        for (final RoomDiscovery.RoomInfo room : s.rooms) {
            boolean current = s.connection == SessionState.Connection.CONNECTED && room.code.equalsIgnoreCase(s.roomCode);
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(4), Ui.dp(10), Ui.dp(4), Ui.dp(10));
            LinearLayout badge = new LinearLayout(c);
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(Ui.rect(current ? Theme.alpha(Theme.accent, 46) : Theme.surface3, 14));
            badge.addView(Ui.icon(c, R.drawable.ic_phone, 22, current ? Theme.accent : Theme.muted), Ui.lin(Ui.dp(22), Ui.dp(22)));
            row.addView(badge, Ui.lin(Ui.dp(44), Ui.dp(44), 0, 0, 14, 0));
            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView name = Ui.text(c, room.name, 15, Theme.text, Ui.BOLD);
            name.setSingleLine(true);
            texts.addView(name, Ui.lin(Ui.MATCH, Ui.WRAP));
            String sub = (room.playing && !room.title.isEmpty() ? "\u266A " + room.title : "Not playing") + " \u00B7 " + room.clients
                    + (room.clients == 1 ? " speaker" : " speakers");
            TextView subtitle = Ui.text(c, sub, 12, Theme.muted, Ui.REGULAR);
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(subtitle, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 3, 0, 0));
            row.addView(texts, Ui.lin(0, Ui.WRAP, 1f));
            TextView button = current ? Ui.text(c, "Connected", 13, Theme.good, Ui.BOLD) : Ui.primaryButton(c, "Connect");
            if (!current) {
                button.setPadding(Ui.dp(16), Ui.dp(9), Ui.dp(16), Ui.dp(9));
                button.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        SyncoraService service = host.service();
                        if (service != null) service.connectToRoom(room);
                    }
                });
            }
            row.addView(button, Ui.lin(Ui.WRAP, Ui.WRAP, 8, 0, 0, 0));
            roomsList.addView(row, Ui.lin(Ui.MATCH, Ui.WRAP));
        }
    }
}
