package com.serena.syncora.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.serena.syncora.R;
import com.serena.syncora.SessionState;
import com.serena.syncora.Source;
import com.serena.syncora.SyncoraService;
import com.serena.syncora.core.ChannelMode;
import com.serena.syncora.core.Track;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Every bottom sheet in the app. */
public final class Sheets {
    private Sheets() {
    }

    public interface ChoiceListener {
        void onChoice(int index);
    }

    public interface TextListener {
        void onText(String text);
    }

    public static int sourceIcon(Source source) {
        switch (source) {
            case RADIO:
                return R.drawable.ic_radio;
            case DLNA:
                return R.drawable.ic_cast;
            case MIC:
                return R.drawable.ic_mic;
            case SPEAKER:
                return R.drawable.ic_speaker;
            default:
                return R.drawable.ic_library;
        }
    }

    static String statusText(SessionState.Device d) {
        switch (d.status) {
            case "buffering":
                return "Buffering\u2026";
            case "resyncing":
                return "Re-syncing\u2026";
            case "connecting":
                return "Connecting\u2026";
            case "muted":
                return "Not playing here";
            default:
                return d.host || d.syncErrMs == 0 ? "In sync" : "In sync \u00B1" + Math.abs(d.syncErrMs) + " ms";
        }
    }

    static int statusColor(SessionState.Device d) {
        switch (d.status) {
            case "buffering":
            case "resyncing":
            case "connecting":
                return Theme.warn;
            case "muted":
                return Theme.subtle;
            default:
                return Theme.good;
        }
    }

    static String deviceTitle(SessionState.Device d) {
        String name = d.name.isEmpty() ? "Device" : d.name;
        return d.self ? name + " (this device)" : name;
    }

    static String deviceName(SessionState.Device d) {
        return d.name.isEmpty() ? "Device" : d.name;
    }

    /** "This device", "Player" or "Speaker". */
    static String roleLabel(SessionState.Device d) {
        if (d.self) return "This device";
        return d.host ? "Player" : "Speaker";
    }

    // ---------------------------------------------------------------- generic rows

    private static LinearLayout optionRow(Context c, int iconRes, String title, String subtitle, boolean selected) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(12), Ui.dp(12), Ui.dp(14), Ui.dp(12));
        row.setBackground(Ui.ripple(Ui.stroke(selected ? Theme.accentSoft : Theme.surface2, selected ? Theme.alpha(Theme.accent, 120) : Theme.line,
                1, 18), 18));
        row.setClickable(true);
        row.setFocusable(true);
        Ui.pressable(row);
        if (iconRes != 0) {
            FrameLayoutIcon badge = new FrameLayoutIcon(c, iconRes, selected);
            row.addView(badge, Ui.lin(Ui.dp(44), Ui.dp(44), 0, 0, 14, 0));
        }
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = Ui.text(c, title, 15, Theme.text, Ui.BOLD);
        t.setSingleLine(true);
        texts.addView(t, Ui.lin(Ui.MATCH, Ui.WRAP));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = Ui.text(c, subtitle, 12, Theme.muted, Ui.REGULAR);
            s.setMaxLines(2);
            texts.addView(s, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 3, 0, 0));
        }
        row.addView(texts, Ui.lin(0, Ui.WRAP, 1f));
        if (selected) row.addView(Ui.icon(c, R.drawable.ic_check, 22, Theme.accent), Ui.lin(Ui.dp(22), Ui.dp(22), 8, 0, 0, 0));
        return row;
    }

    /** Rounded square holding an icon. */
    private static final class FrameLayoutIcon extends android.widget.FrameLayout {
        FrameLayoutIcon(Context c, int iconRes, boolean highlighted) {
            super(c);
            setBackground(Ui.rect(highlighted ? Theme.alpha(Theme.accent, 46) : Theme.surface3, 14));
            ImageView icon = Ui.icon(c, iconRes, 24, highlighted ? Theme.accent : Theme.muted);
            addView(icon, Ui.frame(Ui.dp(24), Ui.dp(24), Gravity.CENTER));
        }
    }

    private static LinearLayout.LayoutParams rowParams() {
        return Ui.lin(Ui.MATCH, Ui.WRAP, 0, 0, 0, 10);
    }

    private abstract static class Live implements StateListener {
        final UiHost host;
        final Sheet sheet;

        Live(UiHost host, Sheet sheet) {
            this.host = host;
            this.sheet = sheet;
        }

        void attach() {
            sheet.setOnDismiss(new Runnable() {
                @Override
                public void run() {
                    host.removeStateListener(Live.this);
                }
            });
            host.addStateListener(this);
            host.showSheet(sheet);
        }
    }

    // ---------------------------------------------------------------- source picker

    public static void source(final UiHost host) {
        final Sheet sheet = new Sheet(host.activity(), "Current source", "Choose what Syncora plays", host.bottomInset(), host.maxSheetHeight());
        new Live(host, sheet) {
            private String built = "";

            @Override
            public void onState(SessionState s) {
                String signature = s.source.id + "|" + s.connection + "|" + s.hostName + "|" + s.dlnaOn + "|" + s.micOn;
                if (signature.equals(built)) return;
                built = signature;
                LinearLayout body = sheet.body();
                body.removeAllViews();
                for (final Source source : Source.values()) {
                    String detail = source.description;
                    if (source == s.source) {
                        if (source == Source.DLNA) detail = "Visible as \u201C" + s.dlnaName + "\u201D";
                        else if (source == Source.SPEAKER) {
                            detail = s.connection == SessionState.Connection.CONNECTED
                                    ? "Connected to " + (s.hostName.isEmpty() ? "a player" : s.hostName) : "Waiting for connection";
                        } else if (source == Source.MIC && s.micOn) detail = "Live \u2014 sharing the input";
                    }
                    LinearLayout row = optionRow(sheet.context(), sourceIcon(source), source.label, detail, source == s.source);
                    row.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            SyncoraService service = host.service();
                            sheet.dismiss();
                            if (service != null) service.selectSource(source);
                            host.navigate(UiHost.TAB_SOURCE);
                        }
                    });
                    body.addView(row, rowParams());
                }
            }
        }.attach();
    }

    // ---------------------------------------------------------------- volume (all devices) + sync

    private static final class VolumeRow {
        PremiumSeekBar bar;
        TextView percent;
        TextView status;
        ImageView mute;
        boolean dragging;
        String id;
        String role = "";
    }

    public static void volume(final UiHost host) {
        final Sheet sheet = new Sheet(host.activity(), "Volume", "", host.bottomInset(), host.maxSheetHeight());
        new Live(host, sheet) {
            private String signature = null;
            private final Map<String, VolumeRow> rows = new HashMap<>();
            private PremiumSeekBar master;
            private boolean masterDragging;
            private ToggleView lowLatency;
            private TextView syncInfo;
            private int lastDeviceCount = -1;

            @Override
            public void onState(SessionState s) {
                StringBuilder sig = new StringBuilder();
                for (SessionState.Device d : s.devices) sig.append(d.id).append(';');
                sig.append(s.source);
                if (!sig.toString().equals(signature)) {
                    signature = sig.toString();
                    rebuild(s);
                }
                update(s);
            }

            private void rebuild(SessionState s) {
                final Context c = sheet.context();
                LinearLayout body = sheet.body();
                body.removeAllViews();
                rows.clear();
                master = null;

                if (s.devices.size() > 1) {
                    LinearLayout masterRow = new LinearLayout(c);
                    masterRow.setOrientation(LinearLayout.VERTICAL);
                    masterRow.setPadding(Ui.dp(14), Ui.dp(12), Ui.dp(14), Ui.dp(8));
                    masterRow.setBackground(Ui.stroke(Theme.accentSoft, Theme.alpha(Theme.accent, 90), 1, 18));
                    LinearLayout head = new LinearLayout(c);
                    head.setGravity(Gravity.CENTER_VERTICAL);
                    head.addView(Ui.icon(c, R.drawable.ic_devices, 22, Theme.accent), Ui.lin(Ui.dp(22), Ui.dp(22), 0, 0, 12, 0));
                    head.addView(Ui.text(c, "All devices", 15, Theme.text, Ui.BOLD), Ui.lin(0, Ui.WRAP, 1f));
                    masterRow.addView(head, Ui.lin(Ui.MATCH, Ui.WRAP));
                    master = new PremiumSeekBar(c);
                    master.setListener(new PremiumSeekBar.Listener() {
                        @Override
                        public void onStart() {
                            masterDragging = true;
                        }

                        @Override
                        public void onChange(float f) {
                            SyncoraService service = host.service();
                            if (service == null) return;
                            for (SessionState.Device d : host.state().devices) service.setDeviceVolume(d.id, f);
                        }

                        @Override
                        public void onStop(float f) {
                            masterDragging = false;
                        }
                    });
                    masterRow.addView(master, Ui.lin(Ui.MATCH, Ui.dp(36), 0, 4, 0, 0));
                    body.addView(masterRow, rowParams());
                }

                for (final SessionState.Device d : s.devices) {
                    final VolumeRow row = new VolumeRow();
                    row.id = d.id;
                    LinearLayout card = new LinearLayout(c);
                    card.setOrientation(LinearLayout.VERTICAL);
                    card.setPadding(Ui.dp(14), Ui.dp(12), Ui.dp(8), Ui.dp(6));
                    card.setBackground(Ui.stroke(Theme.surface2, Theme.line, 1, 18));

                    LinearLayout head = new LinearLayout(c);
                    head.setGravity(Gravity.CENTER_VERTICAL);
                    head.addView(new FrameLayoutIcon(c, d.host ? R.drawable.ic_phone : R.drawable.ic_speaker, d.self),
                            Ui.lin(Ui.dp(40), Ui.dp(40), 0, 0, 12, 0));
                    LinearLayout names = new LinearLayout(c);
                    names.setOrientation(LinearLayout.VERTICAL);
                    TextView name = Ui.text(c, deviceName(d), 15, Theme.text, Ui.BOLD);
                    name.setSingleLine(true);
                    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    names.addView(name, Ui.lin(Ui.MATCH, Ui.WRAP));
                    row.status = Ui.text(c, "", 12, Theme.good, Ui.REGULAR);
                    row.status.setSingleLine(true);
                    row.role = roleLabel(d);
                    names.addView(row.status, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 3, 0, 0));
                    head.addView(names, Ui.lin(0, Ui.WRAP, 1f));
                    row.mute = Ui.iconButton(c, R.drawable.ic_volume, 44, 22, Theme.text, "Mute " + d.name);
                    row.mute.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            SyncoraService service = host.service();
                            if (service == null) return;
                            for (SessionState.Device cur : host.state().devices) {
                                if (cur.id.equals(row.id)) service.setDeviceMuted(row.id, !cur.muted);
                            }
                        }
                    });
                    head.addView(row.mute, Ui.lin(Ui.dp(44), Ui.dp(44)));
                    card.addView(head, Ui.lin(Ui.MATCH, Ui.WRAP));

                    LinearLayout sliderRow = new LinearLayout(c);
                    sliderRow.setGravity(Gravity.CENTER_VERTICAL);
                    row.bar = new PremiumSeekBar(c);
                    row.bar.setListener(new PremiumSeekBar.Listener() {
                        @Override
                        public void onStart() {
                            row.dragging = true;
                        }

                        @Override
                        public void onChange(float f) {
                            row.percent.setText(Math.round(f * 100) + "%");
                            SyncoraService service = host.service();
                            if (service != null) service.setDeviceVolume(row.id, f);
                        }

                        @Override
                        public void onStop(float f) {
                            row.dragging = false;
                        }
                    });
                    sliderRow.addView(row.bar, Ui.lin(0, Ui.dp(36), 1f));
                    row.percent = Ui.text(c, "100%", 13, Theme.muted, Ui.MEDIUM);
                    row.percent.setGravity(Gravity.END);
                    sliderRow.addView(row.percent, Ui.lin(Ui.dp(46), Ui.WRAP, 4, 0, 6, 0));
                    card.addView(sliderRow, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 2, 0, 0));
                    rows.put(d.id, row);
                    body.addView(card, rowParams());
                }

                // ---- sync section
                LinearLayout sync = new LinearLayout(c);
                sync.setOrientation(LinearLayout.VERTICAL);
                sync.setPadding(Ui.dp(14), Ui.dp(14), Ui.dp(14), Ui.dp(14));
                sync.setBackground(Ui.stroke(Theme.surface2, Theme.line, 1, 18));
                LinearLayout head = new LinearLayout(c);
                head.setGravity(Gravity.CENTER_VERTICAL);
                head.addView(new FrameLayoutIcon(c, R.drawable.ic_bolt, true), Ui.lin(Ui.dp(40), Ui.dp(40), 0, 0, 12, 0));
                LinearLayout texts = new LinearLayout(c);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.addView(Ui.text(c, "Low-latency sync", 15, Theme.text, Ui.BOLD), Ui.lin(Ui.MATCH, Ui.WRAP));
                syncInfo = Ui.text(c, "", 12, Theme.muted, Ui.REGULAR);
                texts.addView(syncInfo, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 3, 0, 0));
                head.addView(texts, Ui.lin(0, Ui.WRAP, 1f));
                lowLatency = new ToggleView(c);
                lowLatency.setListener(new ToggleView.Listener() {
                    @Override
                    public void onChanged(boolean checked) {
                        SyncoraService service = host.service();
                        if (service != null) service.setLowLatency(checked);
                    }
                });
                head.addView(lowLatency, Ui.lin(Ui.WRAP, Ui.WRAP));
                sync.addView(head, Ui.lin(Ui.MATCH, Ui.WRAP));
                TextView syncNow = Ui.primaryButton(c, "Sync now");
                syncNow.setCompoundDrawablePadding(Ui.dp(8));
                syncNow.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        SyncoraService service = host.service();
                        if (service != null) service.syncNow();
                    }
                });
                sync.addView(syncNow, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 14, 0, 0));
                body.addView(sync, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 4, 0, 0));
            }

            private void update(SessionState s) {
                int count = s.devices.size();
                if (count != lastDeviceCount) {
                    lastDeviceCount = count;
                    sheet.setSubtitle(count <= 1 ? "Only this device is connected" : count + " devices connected");
                }
                float sum = 0;
                for (SessionState.Device d : s.devices) {
                    VolumeRow row = rows.get(d.id);
                    if (row == null) continue;
                    sum += d.muted ? 0 : d.volume;
                    if (!row.dragging) {
                        row.bar.setProgress(d.volume);
                        row.percent.setText(Math.round(d.volume * 100) + "%");
                    }
                    row.bar.setEnabled(!d.muted);
                    row.mute.setImageResource(d.muted ? R.drawable.ic_volume_off : R.drawable.ic_volume);
                    row.mute.setColorFilter(d.muted ? Theme.danger : Theme.text);
                    row.status.setText(row.role + " \u00B7 " + statusText(d));
                    row.status.setTextColor(statusColor(d));
                }
                if (master != null && !masterDragging && count > 0) master.setProgress(sum / count);
                if (lowLatency != null) {
                    if (lowLatency.isChecked() != s.lowLatency()) lowLatency.setChecked(s.lowLatency(), false);
                    String info = s.lowLatency()
                            ? "On \u2014 about " + s.lookaheadMs + " ms delay, tight sync"
                            : "Off \u2014 balanced buffer (" + Math.max(s.lookaheadMs, 0) + " ms) for steady playback";
                    if (s.isSpeaker() && s.connection != SessionState.Connection.CONNECTED) info = "Connect to a player first";
                    syncInfo.setText(info);
                }
            }
        }.attach();
    }

    // ---------------------------------------------------------------- audio settings

    public static void audio(final UiHost host, final String initialDeviceId) {
        final Sheet sheet = new Sheet(host.activity(), "Audio settings", "", host.bottomInset(), host.maxSheetHeight());
        new Live(host, sheet) {
            private String deviceId = initialDeviceId;
            private String chipSignature = "";
            private LinearLayout chips;
            private TextView connectedText;
            private View connectedDot;
            private TextView deviceStatus;
            private TextView offsetValue;
            private SegmentedControl channels;
            private int step = 10;
            private TextView[] stepViews;
            private boolean built;

            @Override
            public void onState(SessionState s) {
                if (deviceId == null || !hasDevice(s, deviceId)) {
                    SessionState.Device self = s.self();
                    deviceId = self != null ? self.id : (s.devices.isEmpty() ? "" : s.devices.get(0).id);
                }
                if (!built) {
                    build(s);
                    built = true;
                }
                update(s);
            }

            private boolean hasDevice(SessionState s, String id) {
                for (SessionState.Device d : s.devices) if (d.id.equals(id)) return true;
                return false;
            }

            private SessionState.Device selected(SessionState s) {
                for (SessionState.Device d : s.devices) if (d.id.equals(deviceId)) return d;
                return null;
            }

            private void build(SessionState s) {
                final Context c = sheet.context();
                LinearLayout body = sheet.body();
                body.removeAllViews();

                HorizontalScrollView scroller = new HorizontalScrollView(c);
                scroller.setHorizontalScrollBarEnabled(false);
                chips = new LinearLayout(c);
                chips.setOrientation(LinearLayout.HORIZONTAL);
                scroller.addView(chips, new android.widget.FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP));
                body.addView(scroller, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 0, 0, 12));

                // connected state
                LinearLayout state = new LinearLayout(c);
                state.setGravity(Gravity.CENTER_VERTICAL);
                state.setPadding(Ui.dp(14), Ui.dp(12), Ui.dp(14), Ui.dp(12));
                state.setBackground(Ui.stroke(Theme.surface2, Theme.line, 1, 16));
                connectedDot = new View(c);
                connectedDot.setBackground(Ui.oval(Theme.good));
                state.addView(connectedDot, Ui.lin(Ui.dp(10), Ui.dp(10), 0, 0, 12, 0));
                LinearLayout texts = new LinearLayout(c);
                texts.setOrientation(LinearLayout.VERTICAL);
                connectedText = Ui.text(c, "", 14, Theme.text, Ui.BOLD);
                texts.addView(connectedText, Ui.lin(Ui.MATCH, Ui.WRAP));
                deviceStatus = Ui.text(c, "", 12, Theme.muted, Ui.REGULAR);
                texts.addView(deviceStatus, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 3, 0, 0));
                state.addView(texts, Ui.lin(0, Ui.WRAP, 1f));
                body.addView(state, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 0, 0, 14));

                // sync adjustment
                body.addView(Ui.label(c, "Sync"), Ui.lin(Ui.MATCH, Ui.WRAP, 2, 0, 0, 8));
                LinearLayout sync = new LinearLayout(c);
                sync.setOrientation(LinearLayout.VERTICAL);
                sync.setPadding(Ui.dp(14), Ui.dp(14), Ui.dp(14), Ui.dp(14));
                sync.setBackground(Ui.stroke(Theme.surface2, Theme.line, 1, 18));

                LinearLayout controls = new LinearLayout(c);
                controls.setGravity(Gravity.CENTER_VERTICAL);
                ImageView minus = Ui.iconButton(c, R.drawable.ic_remove, 52, 26, Theme.text, "Play earlier");
                minus.setBackground(Ui.rippleOval(Ui.oval(Theme.surface3)));
                minus.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        nudge(-step);
                    }
                });
                controls.addView(minus, Ui.lin(Ui.dp(52), Ui.dp(52)));
                LinearLayout value = new LinearLayout(c);
                value.setOrientation(LinearLayout.VERTICAL);
                value.setGravity(Gravity.CENTER);
                offsetValue = Ui.text(c, "0 ms", 28, Theme.text, Ui.BOLD);
                offsetValue.setGravity(Gravity.CENTER);
                value.addView(offsetValue, Ui.lin(Ui.WRAP, Ui.WRAP));
                TextView hint = Ui.text(c, "later (+) / earlier (\u2212)", 11, Theme.subtle, Ui.REGULAR);
                value.addView(hint, Ui.lin(Ui.WRAP, Ui.WRAP, 0, 4, 0, 0));
                controls.addView(value, Ui.lin(0, Ui.WRAP, 1f));
                ImageView plus = Ui.iconButton(c, R.drawable.ic_add, 52, 26, Theme.text, "Play later");
                plus.setBackground(Ui.rippleOval(Ui.oval(Theme.surface3)));
                plus.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        nudge(step);
                    }
                });
                controls.addView(plus, Ui.lin(Ui.dp(52), Ui.dp(52)));
                sync.addView(controls, Ui.lin(Ui.MATCH, Ui.WRAP));

                LinearLayout stepRow = new LinearLayout(c);
                stepRow.setGravity(Gravity.CENTER_VERTICAL);
                stepRow.addView(Ui.text(c, "Step", 12, Theme.muted, Ui.REGULAR), Ui.lin(Ui.WRAP, Ui.WRAP, 0, 0, 10, 0));
                final int[] steps = {1, 10, 50};
                stepViews = new TextView[steps.length];
                for (int i = 0; i < steps.length; i++) {
                    final int value2 = steps[i];
                    final TextView chip = Ui.text(c, value2 + " ms", 12, Theme.muted, Ui.MEDIUM);
                    chip.setPadding(Ui.dp(12), Ui.dp(7), Ui.dp(12), Ui.dp(7));
                    chip.setClickable(true);
                    chip.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            step = value2;
                            refreshSteps();
                        }
                    });
                    stepViews[i] = chip;
                    stepRow.addView(chip, Ui.lin(Ui.WRAP, Ui.WRAP, 0, 0, 6, 0));
                }
                View filler = new View(c);
                stepRow.addView(filler, Ui.lin(0, 1, 1f));
                TextView reset = Ui.secondaryButton(c, "Reset");
                reset.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(8));
                reset.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        SyncoraService service = host.service();
                        if (service != null && deviceId != null) service.resetDevice(deviceId);
                    }
                });
                stepRow.addView(reset, Ui.lin(Ui.WRAP, Ui.WRAP));
                sync.addView(stepRow, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 12, 0, 0));
                body.addView(sync, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 0, 0, 14));
                refreshSteps();

                // channels
                body.addView(Ui.label(c, "Channels"), Ui.lin(Ui.MATCH, Ui.WRAP, 2, 0, 0, 8));
                // order: Mono, Stereo, Left, Right
                final ChannelMode[] order = {ChannelMode.MONO, ChannelMode.STEREO, ChannelMode.LEFT, ChannelMode.RIGHT};
                String[] labels = new String[order.length];
                for (int i = 0; i < order.length; i++) labels[i] = order[i].label;
                channels = new SegmentedControl(c, labels, 1, new SegmentedControl.Listener() {
                    @Override
                    public void onSelected(int index) {
                        SyncoraService service = host.service();
                        if (service != null && deviceId != null) service.setDeviceChannel(deviceId, order[index]);
                    }
                });
                body.addView(channels, Ui.lin(Ui.MATCH, Ui.WRAP));
                TextView tip = Ui.text(c, "Use Left on one phone and Right on another to make a stereo pair. Mono mixes both channels together.",
                        12, Theme.muted, Ui.REGULAR);
                tip.setLineSpacing(Ui.dpf(3), 1f);
                body.addView(tip, Ui.lin(Ui.MATCH, Ui.WRAP, 2, 10, 2, 0));
            }

            private void refreshSteps() {
                if (stepViews == null) return;
                int[] steps = {1, 10, 50};
                for (int i = 0; i < stepViews.length; i++) {
                    boolean on = steps[i] == step;
                    stepViews[i].setTextColor(on ? Theme.onAccent : Theme.muted);
                    stepViews[i].setBackground(on ? Ui.rect(Theme.accent, 14) : Ui.stroke(0, Theme.line, 1, 14));
                }
            }

            private void nudge(int delta) {
                SyncoraService service = host.service();
                SessionState.Device d = selected(host.state());
                if (service == null || d == null) return;
                service.setDeviceOffset(d.id, d.offsetMs + delta);
            }

            private void update(SessionState s) {
                final Context c = sheet.context();
                // device chips
                StringBuilder sig = new StringBuilder();
                for (SessionState.Device d : s.devices) sig.append(d.id).append(d.name).append(';');
                sig.append(deviceId);
                if (!sig.toString().equals(chipSignature)) {
                    chipSignature = sig.toString();
                    chips.removeAllViews();
                    boolean multiple = s.devices.size() > 1;
                    ((View) chips.getParent()).setVisibility(multiple ? View.VISIBLE : View.GONE);
                    for (final SessionState.Device d : s.devices) {
                        boolean on = d.id.equals(deviceId);
                        TextView chip = Ui.text(c, d.self ? "This device" : d.name, 13, on ? Theme.onAccent : Theme.text, Ui.MEDIUM);
                        chip.setSingleLine(true);
                        chip.setPadding(Ui.dp(14), Ui.dp(9), Ui.dp(14), Ui.dp(9));
                        chip.setBackground(on ? Ui.rect(Theme.accent, 18) : Ui.stroke(Theme.surface2, Theme.line, 1, 18));
                        chip.setClickable(true);
                        chip.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                deviceId = d.id;
                                onState(host.state());
                            }
                        });
                        chips.addView(chip, Ui.lin(Ui.WRAP, Ui.WRAP, 0, 0, 8, 0));
                    }
                }
                SessionState.Device d = selected(s);
                // connected state
                boolean connected;
                String line;
                if (s.isSpeaker()) {
                    connected = s.connection == SessionState.Connection.CONNECTED;
                    line = connected ? "Connected to " + (s.hostName.isEmpty() ? "player" : s.hostName)
                            : (s.connectionText.isEmpty() ? "Waiting for connection" : s.connectionText);
                } else {
                    int speakers = s.connectedSpeakers();
                    connected = speakers > 0;
                    line = connected ? "Connected \u00B7 " + speakers + (speakers == 1 ? " speaker" : " speakers")
                            : "Not connected \u2014 playing on this device only";
                }
                connectedText.setText(line);
                ((GradientDrawable) connectedDot.getBackground()).setColor(connected ? Theme.good : Theme.warn);
                if (d != null) {
                    deviceStatus.setText(deviceTitle(d) + " \u00B7 " + statusText(d));
                    int offset = d.offsetMs;
                    offsetValue.setText((offset > 0 ? "+" : "") + offset + " ms");
                    ChannelMode mode = ChannelMode.fromId(d.channel);
                    int index = mode == ChannelMode.MONO ? 0 : mode == ChannelMode.STEREO ? 1 : mode == ChannelMode.LEFT ? 2 : 3;
                    if (channels.getSelected() != index) channels.select(index, false);
                }
            }
        }.attach();
    }

    // ---------------------------------------------------------------- queue

    public static void queue(final UiHost host) {
        final SessionState state = host.state();
        final SyncoraService service = host.service();
        if (service == null) return;
        final Sheet sheet = new Sheet(host.activity(), state.isSpeaker() ? "Up next" : "Queue", "", host.bottomInset(), host.maxSheetHeight());
        final Context c = sheet.context();
        LinearLayout body = sheet.body();
        if (state.source != Source.MUSIC && !state.isSpeaker()) {
            body.addView(Ui.text(c, "Queues are available for My Music.", 14, Theme.muted, Ui.REGULAR), Ui.lin(Ui.MATCH, Ui.WRAP));
        } else if (state.isSpeaker()) {
            sheet.setSubtitle(state.queueSize > 0 ? "Playing " + (state.queueIndex + 1) + " of " + state.queueSize : "");
            if (state.upNext.isEmpty()) {
                body.addView(Ui.text(c, "Nothing is queued after this song.", 14, Theme.muted, Ui.REGULAR), Ui.lin(Ui.MATCH, Ui.WRAP));
            }
            for (final SessionState.QueueItem item : state.upNext) {
                body.addView(queueRow(c, item.index + 1, item.title, item.artist, false, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        service.playQueueIndex(item.index);
                        sheet.dismiss();
                    }
                }));
            }
        } else {
            List<Track> order = service.queueSnapshot();
            int cursor = service.queueCursor();
            sheet.setSubtitle(order.isEmpty() ? "" : "Playing " + (cursor + 1) + " of " + order.size()
                    + (state.shuffle ? " \u00B7 shuffled" : ""));
            if (order.isEmpty()) {
                body.addView(Ui.text(c, "Choose a song from My Music to start a queue.", 14, Theme.muted, Ui.REGULAR), Ui.lin(Ui.MATCH, Ui.WRAP));
            }
            int from = Math.max(0, cursor - 10);
            int to = Math.min(order.size(), from + 120);
            for (int i = from; i < to; i++) {
                final int index = i;
                Track t = order.get(i);
                body.addView(queueRow(c, i + 1, t.title, t.artist, i == cursor, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        service.playQueueIndex(index);
                        sheet.dismiss();
                    }
                }));
            }
        }
        host.showSheet(sheet);
    }

    private static View queueRow(Context c, int number, String title, String artist, boolean current, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(10), Ui.dp(10), Ui.dp(10), Ui.dp(10));
        row.setBackground(Ui.ripple(current ? Ui.rect(Theme.accentSoft, 14) : null, 14));
        row.setClickable(true);
        row.setOnClickListener(click);
        if (current) {
            EqualizerView eq = new EqualizerView(c);
            eq.setPlaying(true);
            row.addView(eq, Ui.lin(Ui.dp(18), Ui.dp(16), 8, 0, 18, 0));
        } else {
            TextView n = Ui.text(c, String.valueOf(number), 13, Theme.subtle, Ui.MEDIUM);
            n.setGravity(Gravity.CENTER);
            row.addView(n, Ui.lin(Ui.dp(34), Ui.WRAP, 0, 0, 8, 0));
        }
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = Ui.text(c, title, 15, current ? Theme.accent : Theme.text, Ui.MEDIUM);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(t, Ui.lin(Ui.MATCH, Ui.WRAP));
        if (artist != null && !artist.isEmpty()) {
            TextView a = Ui.text(c, artist, 12, Theme.muted, Ui.REGULAR);
            a.setSingleLine(true);
            a.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(a, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 2, 0, 0));
        }
        row.addView(texts, Ui.lin(0, Ui.WRAP, 1f));
        return row;
    }

    // ---------------------------------------------------------------- choice / input

    public static void choice(UiHost host, String title, String subtitle, String[] labels, String[] descriptions, int selected,
                              final ChoiceListener listener) {
        final Sheet sheet = new Sheet(host.activity(), title, subtitle, host.bottomInset(), host.maxSheetHeight());
        LinearLayout body = sheet.body();
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            String description = descriptions != null && i < descriptions.length ? descriptions[i] : null;
            LinearLayout row = optionRow(sheet.context(), 0, labels[i], description, i == selected);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    sheet.dismiss();
                    listener.onChoice(index);
                }
            });
            body.addView(row, rowParams());
        }
        host.showSheet(sheet);
    }

    public static void input(UiHost host, String title, String subtitle, String hint, String initial, int inputType,
                             String actionLabel, final TextListener listener) {
        final Sheet sheet = new Sheet(host.activity(), title, subtitle, host.bottomInset(), host.maxSheetHeight());
        Context c = sheet.context();
        LinearLayout body = sheet.body();
        final EditText edit = new EditText(c);
        edit.setSingleLine(true);
        edit.setText(initial == null ? "" : initial);
        edit.setSelection(edit.getText().length());
        edit.setHint(hint);
        edit.setHintTextColor(Theme.subtle);
        edit.setTextColor(Theme.text);
        edit.setTextSize(16);
        edit.setInputType(inputType);
        edit.setImeOptions(EditorInfo.IME_ACTION_DONE);
        edit.setPadding(Ui.dp(16), Ui.dp(14), Ui.dp(16), Ui.dp(14));
        edit.setBackground(Ui.stroke(Theme.surface2, Theme.line, 1, 14));
        body.addView(edit, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 0, 0, 14));

        LinearLayout buttons = new LinearLayout(c);
        TextView cancel = Ui.secondaryButton(c, "Cancel");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sheet.dismiss();
            }
        });
        buttons.addView(cancel, Ui.linW(1f, Ui.WRAP, 0, 0, 6, 0));
        final TextView ok = Ui.primaryButton(c, actionLabel);
        final Runnable submit = new Runnable() {
            @Override
            public void run() {
                String text = edit.getText().toString().trim();
                sheet.dismiss();
                listener.onText(text);
            }
        };
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                submit.run();
            }
        });
        buttons.addView(ok, Ui.linW(1f, Ui.WRAP, 6, 0, 0, 0));
        body.addView(buttons, Ui.lin(Ui.MATCH, Ui.WRAP));
        edit.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    submit.run();
                    return true;
                }
                return false;
            }
        });
        host.showSheet(sheet);
        edit.requestFocus();
        edit.postDelayed(new Runnable() {
            @Override
            public void run() {
                android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                        edit.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(edit, 0);
            }
        }, 250);
    }

    /** Convenience for text inputs. */
    public static int textInput() {
        return InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS;
    }

    public static int urlInput() {
        return InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI;
    }
}
