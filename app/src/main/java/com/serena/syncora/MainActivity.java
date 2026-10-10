package com.serena.syncora;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Minimal, native Syncora dashboard. */
public final class MainActivity extends Activity {
    private static final int REQUEST_AUDIO = 2101;
    private static final int REQUEST_NOTIFICATIONS = 2102;

    private static final int BG = 0xFF0B100F;
    private static final int PANEL = 0xFF141C19;
    private static final int PANEL_2 = 0xFF1B2521;
    private static final int LINE = 0xFF29352F;
    private static final int MINT = 0xFFC9F66B;
    private static final int MINT_DARK = 0xFF25331A;
    private static final int WHITE = 0xFFF3F5EF;
    private static final int MUTED = 0xFF9AA69E;
    private static final int SUBTLE = 0xFF718078;
    private static final int ORANGE = 0xFFF0A26E;

    private final LinkedHashMap<String, NearbyRoom> nearbyRooms = new LinkedHashMap<>();
    private String mode = SyncoraService.MODE_IDLE;
    private String roomCode = "";
    private String title = "";
    private String hostName = "";
    private String hostIp = "";
    private int hostPort = 8787;
    private int positionMs;
    private int durationMs;
    private int clientCount;
    private ArrayList<String> connectedDevices = new ArrayList<>();
    private boolean playing;
    private String joiningMessage = "";
    private String renderKey = "";

    private ScrollView scrollView;
    private LinearLayout page;
    private TextView positionLabel;
    private TextView playButton;
    private ProgressBar playbackProgress;
    private WaveView playbackWave;
    private BroadcastReceiver receiver;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable roomExpiryTicker = new Runnable() {
        @Override public void run() {
            if (SyncoraService.MODE_IDLE.equals(mode)) {
                int before = nearbyRooms.size();
                pruneNearbyRooms();
                if (before != nearbyRooms.size()) {
                    renderKey = "";
                    updateViewState();
                }
            }
            if (receiver != null) uiHandler.postDelayed(this, 3000);
        }
    };

    private static final class NearbyRoom {
        final String code;
        final String ip;
        final int port;
        final String name;
        final String title;
        long seenAt;
        NearbyRoom(String code, String ip, int port, String name, String title) {
            this.code = code;
            this.ip = ip;
            this.port = port;
            this.name = name;
            this.title = title;
            this.seenAt = System.currentTimeMillis();
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Window window = getWindow();
        window.setStatusBarColor(BG);
        window.setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(true);
        window.getDecorView().setSystemUiVisibility(0);
        requestNotificationPermission();
        updateViewState();
    }

    @Override protected void onStart() {
        super.onStart();
        registerEvents();
        sendServiceAction(SyncoraService.ACTION_DISCOVER, null, false);
        uiHandler.removeCallbacks(roomExpiryTicker);
        uiHandler.postDelayed(roomExpiryTicker, 3000);
    }

    @Override protected void onStop() {
        uiHandler.removeCallbacks(roomExpiryTicker);
        unregisterEvents();
        sendServiceAction(SyncoraService.ACTION_STOP_DISCOVER, null, false);
        super.onStop();
    }

    private void registerEvents() {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent event) {
                String action = event.getAction();
                if (SyncoraService.ACTION_STATUS.equals(action)) {
                    applyStatus(event);
                } else if (SyncoraService.ACTION_FOUND.equals(action)) {
                    applyFoundRoom(event);
                } else if (SyncoraService.ACTION_ERROR.equals(action)) {
                    String message = event.getStringExtra("message");
                    if (message != null && !message.isEmpty()) Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(SyncoraService.ACTION_STATUS);
        filter.addAction(SyncoraService.ACTION_FOUND);
        filter.addAction(SyncoraService.ACTION_ERROR);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, filter);
    }

    private void unregisterEvents() {
        if (receiver != null) {
            try { unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) { }
            receiver = null;
        }
    }

    private void applyStatus(Intent event) {
        String newMode = event.getStringExtra("mode");
        if (newMode != null) mode = newMode;
        roomCode = value(event.getStringExtra("roomCode"));
        title = value(event.getStringExtra("title"));
        hostName = value(event.getStringExtra("hostName"));
        hostIp = value(event.getStringExtra("hostIp"));
        hostPort = event.getIntExtra("hostPort", 8787);
        positionMs = event.getIntExtra("position", 0);
        durationMs = event.getIntExtra("duration", 0);
        playing = event.getBooleanExtra("playing", false);
        clientCount = event.getIntExtra("clientCount", 0);
        ArrayList<String> names = event.getStringArrayListExtra("devices");
        connectedDevices = names == null ? new ArrayList<>() : names;
        joiningMessage = value(event.getStringExtra("joiningMessage"));
        updateViewState();
    }

    private void applyFoundRoom(Intent event) {
        String code = value(event.getStringExtra("code"));
        if (code.isEmpty()) return;
        NearbyRoom room = new NearbyRoom(code, value(event.getStringExtra("ip")), event.getIntExtra("port", 8787),
                value(event.getStringExtra("name")), value(event.getStringExtra("title")));
        nearbyRooms.put(code.toUpperCase(Locale.US), room);
        pruneNearbyRooms();
        if (SyncoraService.MODE_IDLE.equals(mode)) updateViewState();
    }

    private void pruneNearbyRooms() {
        long now = System.currentTimeMillis();
        ArrayList<String> expired = new ArrayList<>();
        for (Map.Entry<String, NearbyRoom> item : nearbyRooms.entrySet()) {
            if (now - item.getValue().seenAt > 18_000) expired.add(item.getKey());
        }
        for (String key : expired) nearbyRooms.remove(key);
    }

    private String nearbyRoomsKey() {
        StringBuilder key = new StringBuilder();
        for (NearbyRoom room : nearbyRooms.values()) {
            key.append(room.code).append(':').append(room.name).append(':').append(room.title).append(':').append(room.ip).append('|');
        }
        return key.toString();
    }

    private void updateViewState() {
        pruneNearbyRooms();
        String nextKey = mode + "|" + title + "|" + roomCode + "|" + hostName + "|" + hostIp + "|" + playing + "|" + clientCount + "|" + connectedDevices + "|" + joiningMessage + "|" + nearbyRoomsKey();
        if (!Objects.equals(renderKey, nextKey) || page == null) {
            renderKey = nextKey;
            buildScreen();
        } else {
            updateProgressViews();
        }
    }

    private void buildScreen() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        scrollView.setVerticalScrollBarEnabled(false);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(22), dp(12), dp(22), dp(30));
        scrollView.addView(page, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scrollView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        addBrandHeader();
        if (SyncoraService.MODE_HOST.equals(mode) || SyncoraService.MODE_CLIENT.equals(mode)) renderSession();
        else if (SyncoraService.MODE_JOINING.equals(mode)) renderJoining();
        else renderHome();
    }

    private void addBrandHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(2), dp(6), dp(2), dp(20));
        BrandMark mark = new BrandMark(this, MINT);
        row.addView(mark, new LinearLayout.LayoutParams(dp(34), dp(34)));
        LinearLayout wordmark = column();
        wordmark.setPadding(dp(10), 0, 0, 0);
        TextView name = text("Syncora", 19, WHITE, true);
        wordmark.addView(name);
        TextView tagline = text("ONE BEAT. EVERY DEVICE.", 9, SUBTLE, true);
        tagline.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams tagParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tagParams.topMargin = dp(2);
        wordmark.addView(tagline, tagParams);
        row.addView(wordmark, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView offline = pill("NO CLOUD", MINT, 0x20213715, 0x334A5C35);
        row.addView(offline);
        page.addView(row);
    }

    private void renderHome() {
        LinearLayout hero = column();
        hero.setPadding(dp(22), dp(21), dp(22), dp(18));
        hero.setBackground(gradient(new int[]{0xFF202C25, 0xFF151D19}, 25));
        TextView eyebrow = text("A BETTER WAY TO LISTEN TOGETHER", 9, MINT, true);
        eyebrow.setLetterSpacing(0.13f);
        hero.addView(eyebrow);
        TextView titleView = text("One beat.\nEvery device.", 31, WHITE, true);
        titleView.setLineSpacing(dp(1), 1f);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(13);
        hero.addView(titleView, titleParams);
        TextView copy = text("Turn the phones around you into one\nin-sync sound system.", 13, MUTED, false);
        copy.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        copyParams.topMargin = dp(9);
        hero.addView(copy, copyParams);
        WaveView heroWave = new WaveView(this, MINT);
        heroWave.setActive(false, true);
        LinearLayout.LayoutParams waveParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38));
        waveParams.topMargin = dp(15);
        hero.addView(heroWave, waveParams);
        page.addView(hero, fullWidth());

        addGap(page, 25);
        addSectionLabel("GET THE ROOM GOING", "01 / LISTEN");
        addGap(page, 12);
        View create = actionCard("Create a room", "Play a track and share it nearby", "HOST", true, this::pickAudio);
        page.addView(create, fullWidth());
        addGap(page, 10);
        View join = actionCard("Join a room", "Connect by room ID or IP address", "JOIN", false, this::showJoinDialog);
        page.addView(join, fullWidth());

        addGap(page, 27);
        LinearLayout nearbyHeading = new LinearLayout(this);
        nearbyHeading.setGravity(Gravity.CENTER_VERTICAL);
        nearbyHeading.addView(text("Rooms nearby", 18, WHITE, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView scan = text("SCANNING", 9, MINT, true);
        scan.setLetterSpacing(0.12f);
        nearbyHeading.addView(scan);
        page.addView(nearbyHeading);
        TextView nearbySub = text("On this Wi-Fi or personal hotspot", 12, SUBTLE, false);
        LinearLayout.LayoutParams nearbySubParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nearbySubParams.topMargin = dp(4);
        page.addView(nearbySub, nearbySubParams);
        addGap(page, 12);

        LinearLayout roomPanel = column();
        roomPanel.setBackground(rounded(PANEL, 20, 0, 0));
        if (nearbyRooms.isEmpty()) {
            LinearLayout empty = column();
            empty.setPadding(dp(18), dp(18), dp(18), dp(17));
            TextView emptyTitle = text("No rooms found yet", 14, WHITE, true);
            TextView emptyCopy = text("Start a room on another phone. Both devices\nneed the same Wi-Fi or hotspot.", 12, MUTED, false);
            emptyCopy.setLineSpacing(dp(3), 1f);
            LinearLayout.LayoutParams emptyCopyParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            emptyCopyParams.topMargin = dp(6);
            empty.addView(emptyTitle);
            empty.addView(emptyCopy, emptyCopyParams);
            roomPanel.addView(empty);
        } else {
            int index = 0;
            for (NearbyRoom room : nearbyRooms.values()) {
                if (index++ > 0) roomPanel.addView(separator());
                roomPanel.addView(nearbyRoomRow(room));
            }
        }
        page.addView(roomPanel, fullWidth());

        addGap(page, 10);
        TextView manual = text("＋   Add with room ID or IP address", 13, MUTED, true);
        manual.setGravity(Gravity.CENTER);
        manual.setPadding(dp(16), dp(14), dp(16), dp(14));
        manual.setBackground(selectableBackground(0x00141C19, 17, 0x22374333));
        manual.setOnClickListener(view -> showJoinDialog());
        page.addView(manual, fullWidth());

        addGap(page, 22);
        addFootnote("NO ACCOUNT     ·     NO INTERNET     ·     JUST YOUR LOCAL NETWORK");
    }

    private View actionCard(String titleText, String subtitle, String flag, boolean primary, Runnable onClick) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(17), dp(15), dp(16), dp(15));
        card.setMinimumHeight(dp(84));
        card.setBackground(selectableBackground(primary ? MINT : PANEL, 21, primary ? MINT : LINE));
        View icon = new BrandMark(this, primary ? 0xFF26351A : MINT);
        if (primary) icon.setBackground(rounded(0x1A26351A, 13, 0, 0));
        else icon.setBackground(rounded(PANEL_2, 13, 0, 0));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(42), dp(42));
        iconParams.rightMargin = dp(13);
        card.addView(icon, iconParams);
        LinearLayout copy = column();
        TextView name = text(titleText, 16, primary ? 0xFF152012 : WHITE, true);
        TextView desc = text(subtitle, 11, primary ? 0xFF51643F : MUTED, false);
        LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descParams.topMargin = dp(4);
        copy.addView(name);
        copy.addView(desc, descParams);
        card.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView arrow = text("↗", 18, primary ? 0xFF25351C : MINT, true);
        arrow.setGravity(Gravity.CENTER);
        card.addView(arrow, new LinearLayout.LayoutParams(dp(25), dp(30)));
        card.setOnClickListener(view -> onClick.run());
        return card;
    }

    private View nearbyRoomRow(NearbyRoom room) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(16), dp(14), dp(15), dp(14));
        View dot = new View(this);
        dot.setBackground(rounded(MINT, 7, 0, 0));
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotParams.rightMargin = dp(12);
        row.addView(dot, dotParams);
        LinearLayout details = column();
        details.addView(text(room.name.isEmpty() ? "Nearby Syncora room" : room.name, 14, WHITE, true));
        String track = room.title.isEmpty() ? room.code : room.title + "  ·  " + room.code;
        TextView secondary = text(track, 11, MUTED, false);
        secondary.setSingleLine(true);
        secondary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams secondaryParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        secondaryParams.topMargin = dp(4);
        details.addView(secondary, secondaryParams);
        row.addView(details, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView arrow = text("→", 19, MINT, true);
        row.addView(arrow);
        row.setBackground(selectableBackground(0x00141C19, 20, 0));
        row.setOnClickListener(view -> joinRoom(room.code));
        return row;
    }

    private void renderSession() {
        boolean isHost = SyncoraService.MODE_HOST.equals(mode);
        LinearLayout hero = column();
        hero.setPadding(dp(21), dp(20), dp(21), dp(19));
        hero.setBackground(rounded(PANEL, 25, LINE, 1));

        LinearLayout liveRow = new LinearLayout(this);
        liveRow.setGravity(Gravity.CENTER_VERTICAL);
        View liveDot = new View(this);
        liveDot.setBackground(rounded(playing ? MINT : ORANGE, 5, 0, 0));
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(7), dp(7));
        dotParams.rightMargin = dp(7);
        liveRow.addView(liveDot, dotParams);
        TextView liveText = text(isHost ? "ROOM LIVE" : "CONNECTED TO ROOM", 9, playing ? MINT : ORANGE, true);
        liveText.setLetterSpacing(0.14f);
        liveRow.addView(liveText);
        TextView noCloud = pill("OFFLINE", MUTED, 0x1CFFFFFF, 0x22515D56);
        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipParams.leftMargin = dp(9);
        liveRow.addView(noCloud, chipParams);
        hero.addView(liveRow);

        TextView track = text(title.isEmpty() ? "Getting your track ready…" : title, 23, WHITE, true);
        track.setMaxLines(2);
        track.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams trackParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        trackParams.topMargin = dp(15);
        hero.addView(track, trackParams);
        TextView source = text(isHost ? "Playing from this device" : "Streaming from " + (hostName.isEmpty() ? "your host" : hostName), 12, MUTED, false);
        LinearLayout.LayoutParams sourceParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sourceParams.topMargin = dp(4);
        hero.addView(source, sourceParams);

        playbackWave = new WaveView(this, MINT);
        playbackWave.setActive(true, playing);
        LinearLayout.LayoutParams waveParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38));
        waveParams.topMargin = dp(16);
        hero.addView(playbackWave, waveParams);

        playbackProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        playbackProgress.setMax(1000);
        playbackProgress.setProgressTintList(ColorStateList.valueOf(MINT));
        playbackProgress.setProgressBackgroundTintList(ColorStateList.valueOf(LINE));
        playbackProgress.setMinimumHeight(dp(3));
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        progressParams.topMargin = dp(8);
        hero.addView(playbackProgress, progressParams);

        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setGravity(Gravity.CENTER_VERTICAL);
        timeRow.setPadding(0, dp(7), 0, 0);
        positionLabel = text(formatTime(positionMs) + "  /  " + formatTime(durationMs), 10, SUBTLE, false);
        timeRow.addView(positionLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView syncLabel = text(isHost ? "LOCAL SOURCE" : "SYNC ACTIVE", 9, SUBTLE, true);
        syncLabel.setLetterSpacing(0.08f);
        timeRow.addView(syncLabel);
        hero.addView(timeRow);
        page.addView(hero, fullWidth());

        addGap(page, 12);
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        playButton = controlButton(playing ? "Ⅱ   Pause" : "▶   Play", true, SyncoraService.ACTION_TOGGLE);
        controls.addView(playButton, new LinearLayout.LayoutParams(0, dp(52), 1));
        ViewGroup.MarginLayoutParams playMargins = (ViewGroup.MarginLayoutParams) playButton.getLayoutParams();
        playMargins.rightMargin = dp(9);
        playButton.setLayoutParams(playMargins);
        TextView secondary = controlButton(isHost ? "Change track" : "Leave room", false,
                isHost ? null : SyncoraService.ACTION_STOP);
        if (isHost) secondary.setOnClickListener(view -> pickAudio());
        controls.addView(secondary, new LinearLayout.LayoutParams(0, dp(52), 1));
        page.addView(controls, fullWidth());

        addGap(page, 19);
        if (isHost) renderHostConnectionCard();
        else renderClientConnectionCard();

        addGap(page, 22);
        addFootnote("A LITTLE LATENCY IS NORMAL ON WI-FI. SYNCORA CORRECTS DRIFT AS YOU LISTEN.");
    }

    private void renderHostConnectionCard() {
        LinearLayout card = column();
        card.setPadding(dp(18), dp(17), dp(18), dp(16));
        card.setBackground(rounded(PANEL, 21, LINE, 1));
        addCardLabel(card, "INVITE DEVICES", "ROOM ID");
        TextView code = text(roomCode.isEmpty() ? "SYNC-———" : roomCode, 22, MINT, true);
        code.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        LinearLayout.LayoutParams codeParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        codeParams.topMargin = dp(10);
        card.addView(code, codeParams);
        TextView instruction = text("Enter this ID on another phone, or connect using the host IP below.", 11, MUTED, false);
        instruction.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams instructionParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        instructionParams.topMargin = dp(5);
        card.addView(instruction, instructionParams);

        LinearLayout addressRow = new LinearLayout(this);
        addressRow.setGravity(Gravity.CENTER_VERTICAL);
        addressRow.setPadding(dp(12), dp(11), dp(10), dp(11));
        addressRow.setBackground(rounded(PANEL_2, 13, 0, 0));
        LinearLayout addressCopy = column();
        addressCopy.addView(text("HOST IP", 8, SUBTLE, true));
        String address = hostIp.isEmpty() || "Unavailable".equals(hostIp) ? "Finding local address…" : hostIp + (hostPort == 8787 ? "" : ":" + hostPort);
        TextView ipText = text(address, 13, WHITE, true);
        ipText.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
        LinearLayout.LayoutParams ipParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ipParams.topMargin = dp(3);
        addressCopy.addView(ipText, ipParams);
        addressRow.addView(addressCopy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView copy = text("COPY", 9, MINT, true);
        copy.setLetterSpacing(0.08f);
        addressRow.addView(copy);
        addressRow.setOnClickListener(view -> copyToClipboard("Syncora host address", address));
        LinearLayout.LayoutParams addressParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addressParams.topMargin = dp(14);
        card.addView(addressRow, addressParams);

        LinearLayout inviteRow = new LinearLayout(this);
        inviteRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView share = smallOutlineButton("Share room ID", () -> shareRoomInfo());
        TextView add = smallOutlineButton("＋  Add device", () -> shareRoomInfo());
        inviteRow.addView(share, new LinearLayout.LayoutParams(0, dp(42), 1));
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(0, dp(42), 1);
        addParams.leftMargin = dp(8);
        inviteRow.addView(add, addParams);
        LinearLayout.LayoutParams inviteParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inviteParams.topMargin = dp(10);
        card.addView(inviteRow, inviteParams);

        addDeviceList(card, true);
        page.addView(card, fullWidth());
    }

    private void renderClientConnectionCard() {
        LinearLayout card = column();
        card.setPadding(dp(18), dp(17), dp(18), dp(17));
        card.setBackground(rounded(PANEL, 21, LINE, 1));
        addCardLabel(card, "ROOM CONNECTION", "JOINED VIA WI-FI");
        String codeText = roomCode.isEmpty() ? "Connecting…" : roomCode;
        TextView code = text(codeText, 18, MINT, true);
        code.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        LinearLayout.LayoutParams codeParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        codeParams.topMargin = dp(11);
        card.addView(code, codeParams);
        LinearLayout addressRow = new LinearLayout(this);
        addressRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView host = text((hostName.isEmpty() ? "Host" : hostName) + "  ·  " + (hostIp.isEmpty() ? "local network" : hostIp), 12, MUTED, false);
        addressRow.addView(host, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView state = pill("IN SYNC", MINT, 0x20213715, 0x334A5C35);
        addressRow.addView(state);
        LinearLayout.LayoutParams addressParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addressParams.topMargin = dp(9);
        card.addView(addressRow, addressParams);
        addDeviceList(card, false);
        page.addView(card, fullWidth());
    }

    private void addDeviceList(LinearLayout card, boolean host) {
        View divider = separator();
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dividerParams.topMargin = dp(15);
        dividerParams.bottomMargin = dp(12);
        card.addView(divider, dividerParams);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        View icon = new View(this);
        icon.setBackground(rounded(MINT_DARK, 10, 0, 0));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(34), dp(34));
        iconParams.rightMargin = dp(10);
        row.addView(icon, iconParams);
        LinearLayout labels = column();
        labels.addView(text(host ? "This device" : (hostName.isEmpty() ? "Host device" : hostName), 12, WHITE, true));
        labels.addView(text(host ? "Host · audio source" : "Streaming this room", 10, SUBTLE, false));
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView state = pill("READY", MINT, 0x20213715, 0x334A5C35);
        row.addView(state);
        card.addView(row);

        if (host && clientCount > 0) {
            if (connectedDevices.isEmpty()) {
                TextView connected = text(clientCount + (clientCount == 1 ? " device connected" : " devices connected"), 11, MINT, true);
                LinearLayout.LayoutParams connectedParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                connectedParams.leftMargin = dp(44);
                connectedParams.topMargin = dp(11);
                card.addView(connected, connectedParams);
            } else {
                for (String deviceName : connectedDevices) {
                    LinearLayout clientRow = new LinearLayout(this);
                    clientRow.setGravity(Gravity.CENTER_VERTICAL);
                    View speakerDot = new View(this);
                    speakerDot.setBackground(rounded(MINT, 5, 0, 0));
                    LinearLayout.LayoutParams speakerParams = new LinearLayout.LayoutParams(dp(7), dp(7));
                    speakerParams.leftMargin = dp(14);
                    speakerParams.rightMargin = dp(12);
                    clientRow.addView(speakerDot, speakerParams);
                    clientRow.addView(text(deviceName, 11, WHITE, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                    clientRow.addView(pill("SYNCING", MINT, 0x20213715, 0x334A5C35));
                    LinearLayout.LayoutParams clientParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    clientParams.topMargin = dp(11);
                    card.addView(clientRow, clientParams);
                }
            }
        }

        if (host) {
            if (clientCount == 0) {
                TextView waiting = text("Waiting for nearby speakers to join…", 11, MUTED, false);
                LinearLayout.LayoutParams waitingParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                waitingParams.leftMargin = dp(44);
                waitingParams.topMargin = dp(11);
                card.addView(waiting, waitingParams);
            }
        }
    }

    private void renderJoining() {
        LinearLayout card = column();
        card.setPadding(dp(22), dp(22), dp(22), dp(20));
        card.setBackground(rounded(PANEL, 25, LINE, 1));
        TextView eyebrow = text("CONNECTING", 9, MINT, true);
        eyebrow.setLetterSpacing(0.15f);
        card.addView(eyebrow);
        TextView heading = text("Finding your\nlistening room.", 27, WHITE, true);
        heading.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headingParams.topMargin = dp(14);
        card.addView(heading, headingParams);
        TextView copy = text(joiningMessage.isEmpty() ? "Looking for a room on this network…" : joiningMessage, 13, MUTED, false);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        copyParams.topMargin = dp(8);
        card.addView(copy, copyParams);
        WaveView wave = new WaveView(this, MINT);
        wave.setActive(true, true);
        LinearLayout.LayoutParams waveParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        waveParams.topMargin = dp(24);
        card.addView(wave, waveParams);
        page.addView(card, fullWidth());
        addGap(page, 13);
        TextView cancel = controlButton("Cancel connection", false, SyncoraService.ACTION_STOP);
        page.addView(cancel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
        addGap(page, 22);
        addFootnote("KEEP BOTH PHONES ON THE SAME WI-FI OR PERSONAL HOTSPOT.");
    }

    private void addCardLabel(LinearLayout parent, String title, String meta) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text(title, 14, WHITE, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView detail = text(meta, 8, SUBTLE, true);
        detail.setLetterSpacing(0.1f);
        row.addView(detail);
        parent.addView(row);
    }

    private TextView controlButton(String label, boolean primary, String serviceAction) {
        TextView button = text(label, 13, primary ? 0xFF152012 : WHITE, true);
        button.setGravity(Gravity.CENTER);
        button.setBackground(selectableBackground(primary ? MINT : PANEL_2, 16, primary ? MINT : LINE));
        if (serviceAction != null) button.setOnClickListener(view -> sendServiceAction(serviceAction, null, true));
        return button;
    }

    private TextView smallOutlineButton(String label, Runnable action) {
        TextView button = text(label, 11, WHITE, true);
        button.setGravity(Gravity.CENTER);
        button.setBackground(selectableBackground(PANEL_2, 13, LINE));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private View separator() {
        View line = new View(this);
        line.setBackgroundColor(LINE);
        return line;
    }

    private void addFootnote(String text) {
        TextView footer = text(text, 8, SUBTLE, true);
        footer.setLetterSpacing(0.08f);
        footer.setGravity(Gravity.CENTER);
        footer.setLineSpacing(dp(3), 1f);
        footer.setPadding(dp(8), 0, dp(8), dp(5));
        page.addView(footer, fullWidth());
    }

    private void updateProgressViews() {
        if (positionLabel != null) positionLabel.setText(formatTime(positionMs) + "  /  " + formatTime(durationMs));
        if (playbackProgress != null) {
            int progress = durationMs <= 0 ? 0 : Math.min(1000, Math.max(0, (int) ((positionMs * 1000L) / durationMs)));
            playbackProgress.setProgress(progress);
        }
        if (playButton != null) playButton.setText(playing ? "Ⅱ   Pause" : "▶   Play");
        if (playbackWave != null) playbackWave.setActive(true, playing);
    }

    private void pickAudio() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("audio/*");
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(picker, REQUEST_AUDIO); }
        catch (Exception error) { Toast.makeText(this, "No audio picker is available on this device.", Toast.LENGTH_LONG).show(); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_AUDIO || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri audio = data.getData();
        try {
            int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            getContentResolver().takePersistableUriPermission(audio, flags);
        } catch (Exception ignored) { }
        Intent service = new Intent(this, SyncoraService.class)
                .setAction(SyncoraService.MODE_HOST.equals(mode) ? SyncoraService.ACTION_CHANGE_TRACK : SyncoraService.ACTION_HOST)
                .putExtra(SyncoraService.EXTRA_URI, audio);
        startServiceSafely(service, true);
    }

    private void showJoinDialog() {
        LinearLayout dialogContent = column();
        dialogContent.setPadding(dp(20), dp(8), dp(20), dp(4));
        TextView explanation = text("Enter the room ID shown on the host, or its local IP address. No internet is needed.", 12, MUTED, false);
        explanation.setLineSpacing(dp(3), 1f);
        dialogContent.addView(explanation);
        EditText entry = new EditText(this);
        entry.setSingleLine(true);
        entry.setTextColor(WHITE);
        entry.setHintTextColor(SUBTLE);
        entry.setHint("SYNC-AB23CD or 192.168.1.20");
        entry.setTextSize(14);
        entry.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        entry.setPadding(dp(14), dp(12), dp(14), dp(12));
        entry.setBackground(rounded(PANEL_2, 13, LINE, 1));
        LinearLayout.LayoutParams entryParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        entryParams.topMargin = dp(15);
        dialogContent.addView(entry, entryParams);

        AlertDialog dialog = new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Join a room")
                .setView(dialogContent)
                .setNegativeButton("Not now", (d, which) -> d.dismiss())
                .setPositiveButton("Connect", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String target = entry.getText().toString().trim();
            if (target.isEmpty()) {
                entry.setError("Enter a room ID or IP address");
                return;
            }
            hideKeyboard(entry);
            dialog.dismiss();
            joinRoom(target);
        }));
        dialog.getWindow();
        dialog.show();
        entry.requestFocus();
    }

    private void joinRoom(String target) {
        Intent service = new Intent(this, SyncoraService.class)
                .setAction(SyncoraService.ACTION_JOIN)
                .putExtra(SyncoraService.EXTRA_TARGET, target);
        startServiceSafely(service, true);
    }

    private void startServiceSafely(Intent intent, boolean foreground) {
        try {
            if (foreground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
            else startService(intent);
        } catch (SecurityException error) {
            Toast.makeText(this, "Syncora needs permission to keep local playback running.", Toast.LENGTH_LONG).show();
        }
    }

    private void sendServiceAction(String action, String target, boolean foreground) {
        Intent intent = new Intent(this, SyncoraService.class).setAction(action);
        if (target != null) intent.putExtra(SyncoraService.EXTRA_TARGET, target);
        startServiceSafely(intent, foreground);
    }

    private void shareRoomInfo() {
        String id = roomCode.isEmpty() ? "Syncora room" : roomCode;
        String address = hostIp.isEmpty() || "Unavailable".equals(hostIp) ? "" : "\nHost IP: " + hostIp + (hostPort == 8787 ? "" : ":" + hostPort);
        String message = "Join my Syncora room " + id + " on the same Wi-Fi or personal hotspot." + address + "\nOne Beat. Every Device.";
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, message);
        startActivity(Intent.createChooser(share, "Share your Syncora room"));
    }

    private void copyToClipboard(String label, String value) {
        if (value == null || value.isEmpty() || value.startsWith("Finding")) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
            Toast.makeText(this, "Host address copied", Toast.LENGTH_SHORT).show();
        }
    }

    private void hideKeyboard(View view) {
        try {
            InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (manager != null) manager.hideSoftInputFromWindow(view.getWindowToken(), 0);
        } catch (Exception ignored) { }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    private TextView pill(String label, int textColor, int fill, int stroke) {
        TextView view = text(label, 8, textColor, true);
        view.setLetterSpacing(0.1f);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(9), dp(6), dp(9), dp(6));
        view.setBackground(rounded(fill, 20, stroke, 1));
        return view;
    }

    private TextView text(String value, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        return view;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void addGap(LinearLayout parent, int heightDp) {
        View gap = new View(this);
        parent.addView(gap, new LinearLayout.LayoutParams(1, dp(heightDp)));
    }

    private GradientDrawable rounded(int color, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) shape.setStroke(dp(strokeDp), strokeColor);
        return shape;
    }

    private GradientDrawable gradient(int[] colors, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private android.graphics.drawable.Drawable selectableBackground(int color, int radiusDp, int borderColor) {
        GradientDrawable shape = rounded(color, radiusDp, borderColor, borderColor == 0 ? 0 : 1);
        if (Build.VERSION.SDK_INT >= 21) return new RippleDrawable(ColorStateList.valueOf(0x26FFFFFF), shape, null);
        return shape;
    }

    private static int dp(float value) {
        return (int) (value * android.content.res.Resources.getSystem().getDisplayMetrics().density + 0.5f);
    }

    private static String formatTime(int milliseconds) {
        int total = Math.max(0, milliseconds) / 1000;
        int minutes = total / 60;
        int seconds = total % 60;
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    private static String value(String value) { return value == null ? "" : value; }

    private static final class BrandMark extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int color;
        BrandMark(Context context, int color) {
            super(context);
            this.color = color;
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float unit = getResources().getDisplayMetrics().density;
            float width = getWidth();
            float height = getHeight();
            float centerY = height / 2f;
            float barWidth = Math.max(2.5f * unit, width * 0.09f);
            float gap = (width - 5 * barWidth) / 6f;
            float[] heights = {0.30f, 0.57f, 0.82f, 0.57f, 0.30f};
            paint.setColor(color);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(barWidth);
            for (int i = 0; i < heights.length; i++) {
                float x = gap + barWidth / 2f + i * (barWidth + gap);
                float half = height * heights[i] / 2f;
                canvas.drawLine(x, centerY - half, x, centerY + half, paint);
            }
        }
    }

    private static final class WaveView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int color;
        private boolean active;
        private boolean animate;
        private final float[] levels = {0.22f, 0.31f, 0.44f, 0.29f, 0.63f, 0.38f, 0.80f, 0.46f, 0.96f, 0.54f, 0.77f, 0.35f, 0.65f, 0.41f, 0.88f, 0.38f, 0.59f, 0.29f, 0.43f, 0.23f};
        WaveView(Context context, int color) { super(context); this.color = color; }
        void setActive(boolean active, boolean animate) {
            this.active = active;
            this.animate = animate;
            invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            int count = levels.length;
            float barWidth = Math.min(dp(3), Math.max(dp(2), width / (count * 2.2f)));
            float gap = (width - count * barWidth) / Math.max(1, count - 1);
            float center = height / 2f;
            float phase = animate ? (System.currentTimeMillis() % 1800L) / 1800f : 0f;
            for (int i = 0; i < count; i++) {
                float modifier = animate ? (float) (0.73 + 0.27 * Math.sin((i * 0.71f) + phase * Math.PI * 2)) : 0.88f;
                float level = levels[i] * modifier;
                if (!active) level *= 0.68f;
                float barHeight = Math.max(dp(4), height * level);
                float left = i * (barWidth + gap);
                float top = center - barHeight / 2f;
                float alpha = i > count * 0.66f ? 0.54f : 1f;
                paint.setColor(color);
                paint.setAlpha((int) (255 * alpha));
                RectF rect = new RectF(left, top, left + barWidth, top + barHeight);
                canvas.drawRoundRect(rect, barWidth, barWidth, paint);
            }
            if (animate && isShown()) postInvalidateDelayed(100);
        }
    }
}
