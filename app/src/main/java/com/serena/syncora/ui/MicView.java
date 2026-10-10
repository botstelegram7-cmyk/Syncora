package com.serena.syncora.ui;

import android.Manifest;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
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

import java.util.ArrayList;
import java.util.List;

/** Microphone / aux input: capture a live source and play it on every speaker. */
final class MicView extends SourceView {
    static final int REQUEST_MIC = 4102;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final FrameLayout bigButton;
    private final ImageView bigIcon;
    private final TextView bigLabel;
    private final TextView hint;
    private final LevelView level;
    private final LinearLayout inputList;
    private final PremiumSeekBar gain;
    private final ToggleView monitor;
    private final List<AudioDeviceInfo> inputs = new ArrayList<>();
    private int selectedInput; // 0 = automatic
    private boolean micOn;
    private boolean attached;
    private String inputSignature = "";

    private final Runnable levelTicker = new Runnable() {
        @Override
        public void run() {
            if (!attached) return;
            SyncoraService service = host.service();
            level.setLevel(micOn && service != null ? service.micLevel() : 0f);
            main.postDelayed(this, 60);
        }
    };

    MicView(final Context c, final UiHost host) {
        super(c, host);
        ScrollView scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        addView(scroll, Ui.frame(Ui.MATCH, Ui.MATCH));
        LinearLayout column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(Ui.dp(20), Ui.dp(8), Ui.dp(20), Ui.dp(28));
        scroll.addView(column, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

        bigButton = new FrameLayout(c);
        bigButton.setClickable(true);
        bigButton.setFocusable(true);
        bigButton.setContentDescription("Start or stop the microphone");
        Ui.pressable(bigButton);
        bigIcon = Ui.icon(c, R.drawable.ic_mic, 54, Theme.onAccent);
        bigButton.addView(bigIcon, Ui.frame(Ui.dp(54), Ui.dp(54), Gravity.CENTER));
        bigButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                toggle();
            }
        });
        column.addView(bigButton, Ui.lin(Ui.dp(140), Ui.dp(140), 0, 8, 0, 0));
        bigLabel = Ui.text(c, "Tap to start sharing", 18, Theme.text, Ui.BOLD);
        bigLabel.setGravity(Gravity.CENTER);
        column.addView(bigLabel, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 16, 0, 0));
        hint = Ui.text(c, "", 13, Theme.muted, Ui.REGULAR);
        hint.setGravity(Gravity.CENTER);
        hint.setLineSpacing(0, 1.15f);
        column.addView(hint, Ui.lin(Ui.MATCH, Ui.WRAP, 20, 6, 20, 0));

        level = new LevelView(c);
        column.addView(level, Ui.lin(Ui.MATCH, Ui.dp(14), 6, 20, 6, 0));

        // input chooser
        LinearLayout inputCard = new LinearLayout(c);
        inputCard.setOrientation(LinearLayout.VERTICAL);
        inputCard.setPadding(Ui.dp(14), Ui.dp(14), Ui.dp(14), Ui.dp(8));
        inputCard.setBackground(Ui.card(18));
        inputCard.addView(Ui.label(c, "Input"), Ui.lin(Ui.MATCH, Ui.WRAP, 4, 0, 0, 8));
        inputList = new LinearLayout(c);
        inputList.setOrientation(LinearLayout.VERTICAL);
        inputCard.addView(inputList, Ui.lin(Ui.MATCH, Ui.WRAP));
        column.addView(inputCard, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 22, 0, 0));

        // gain + monitor
        LinearLayout options = new LinearLayout(c);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setPadding(Ui.dp(18), Ui.dp(14), Ui.dp(18), Ui.dp(10));
        options.setBackground(Ui.card(18));
        options.addView(Ui.label(c, "Input gain"), Ui.lin(Ui.MATCH, Ui.WRAP));
        gain = new PremiumSeekBar(c);
        gain.setProgress((host.prefs().micGain() - 0.5f) / 2.5f);
        gain.setListener(new PremiumSeekBar.Listener() {
            @Override
            public void onStart() {
            }

            @Override
            public void onChange(float fraction) {
                SyncoraService service = host.service();
                if (service != null) service.setMicGain(0.5f + fraction * 2.5f);
            }

            @Override
            public void onStop(float fraction) {
            }
        });
        options.addView(gain, Ui.lin(Ui.MATCH, Ui.dp(36), 0, 4, 0, 6));
        LinearLayout monitorRow = new LinearLayout(c);
        monitorRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.text(c, "Monitor on this device", 15, Theme.text, Ui.MEDIUM), Ui.lin(Ui.MATCH, Ui.WRAP));
        texts.addView(Ui.text(c, "Off avoids a feedback squeal. Use headphones when on.", 12, Theme.muted, Ui.REGULAR),
                Ui.lin(Ui.MATCH, Ui.WRAP, 0, 3, 0, 0));
        monitorRow.addView(texts, Ui.lin(0, Ui.WRAP, 1f));
        monitor = new ToggleView(c);
        monitor.setChecked(host.prefs().micMonitor(), false);
        monitor.setListener(new ToggleView.Listener() {
            @Override
            public void onChanged(boolean checked) {
                SyncoraService service = host.service();
                if (service != null) service.setMicMonitor(checked);
            }
        });
        monitorRow.addView(monitor, Ui.lin(Ui.WRAP, Ui.WRAP, 12, 0, 0, 0));
        options.addView(monitorRow, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 4, 0, 6));
        column.addView(options, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 14, 0, 0));
        refreshButton();
    }

    private void refreshButton() {
        int fill = micOn ? Theme.danger : Theme.accent;
        bigButton.setBackground(Ui.rippleOval(Ui.oval(fill)));
        bigIcon.setImageResource(micOn ? R.drawable.ic_stop : R.drawable.ic_mic);
        bigIcon.setColorFilter(Theme.dark && !micOn ? Theme.onAccent : 0xFFFFFFFF);
        if (!micOn) bigIcon.setColorFilter(Theme.onAccent);
    }

    private void toggle() {
        SyncoraService service = host.service();
        if (service == null) return;
        if (micOn) {
            service.stopMic();
            return;
        }
        if (!host.hasPermission(Manifest.permission.RECORD_AUDIO)) {
            host.requestPermission(Manifest.permission.RECORD_AUDIO, REQUEST_MIC);
            return;
        }
        start(service);
    }

    private void start(SyncoraService service) {
        AudioDeviceInfo device = selectedInput > 0 && selectedInput <= inputs.size() ? inputs.get(selectedInput - 1) : null;
        boolean aux = device != null && isAux(device);
        String label = device == null ? "Automatic input" : label(device);
        service.startMic(device, aux, label);
        host.prefs().setMicInputId(device == null ? 0 : device.getId());
    }

    @Override
    void onPermissionResult(int requestCode, boolean granted) {
        if (requestCode != REQUEST_MIC) return;
        SyncoraService service = host.service();
        if (granted && service != null) start(service);
        else if (!granted) host.toast("The microphone permission is needed to share a live input.");
    }

    @Override
    void onShown() {
        refreshInputs();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        main.post(levelTicker);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        attached = false;
        main.removeCallbacks(levelTicker);
    }

    private void refreshInputs() {
        inputs.clear();
        AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                int type = d.getType();
                if (type == AudioDeviceInfo.TYPE_TELEPHONY || type == AudioDeviceInfo.TYPE_REMOTE_SUBMIX
                        || type == AudioDeviceInfo.TYPE_FM_TUNER || type == AudioDeviceInfo.TYPE_BUS) continue;
                inputs.add(d);
            }
        }
        // restore the last selection
        int saved = host.prefs().micInputId();
        selectedInput = 0;
        for (int i = 0; i < inputs.size(); i++) if (inputs.get(i).getId() == saved) selectedInput = i + 1;
        renderInputs();
    }

    private void renderInputs() {
        Context c = getContext();
        StringBuilder sig = new StringBuilder();
        for (AudioDeviceInfo d : inputs) sig.append(d.getId()).append(',');
        sig.append(selectedInput);
        if (sig.toString().equals(inputSignature)) return;
        inputSignature = sig.toString();
        inputList.removeAllViews();
        inputList.addView(inputRow(c, 0, "Automatic", "Let Android choose the best input"));
        for (int i = 0; i < inputs.size(); i++) {
            AudioDeviceInfo d = inputs.get(i);
            inputList.addView(inputRow(c, i + 1, label(d), isAux(d) ? "Line-level aux input" : "Built-in or connected input"));
        }
    }

    private View inputRow(Context c, final int index, String title, String subtitle) {
        boolean on = index == selectedInput;
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(6), Ui.dp(10), Ui.dp(6), Ui.dp(10));
        row.setBackground(Ui.ripple(null, 12));
        row.setClickable(true);
        View dot = new View(c);
        dot.setBackground(on ? Ui.oval(Theme.accent) : Ui.stroke(0, Theme.subtle, 2, 20));
        row.addView(dot, Ui.lin(Ui.dp(18), Ui.dp(18), 0, 0, 14, 0));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.text(c, title, 14, on ? Theme.text : Theme.muted, Ui.MEDIUM), Ui.lin(Ui.MATCH, Ui.WRAP));
        texts.addView(Ui.text(c, subtitle, 11, Theme.subtle, Ui.REGULAR), Ui.lin(Ui.MATCH, Ui.WRAP, 0, 2, 0, 0));
        row.addView(texts, Ui.lin(0, Ui.WRAP, 1f));
        row.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (micOn) {
                    host.toast("Stop the input before switching.");
                    return;
                }
                selectedInput = index;
                renderInputs();
            }
        });
        return row;
    }

    private static boolean isAux(AudioDeviceInfo d) {
        int t = d.getType();
        return t == AudioDeviceInfo.TYPE_LINE_ANALOG || t == AudioDeviceInfo.TYPE_LINE_DIGITAL || t == AudioDeviceInfo.TYPE_USB_DEVICE
                || t == AudioDeviceInfo.TYPE_USB_ACCESSORY || t == AudioDeviceInfo.TYPE_USB_HEADSET || t == AudioDeviceInfo.TYPE_AUX_LINE
                || t == AudioDeviceInfo.TYPE_HDMI;
    }

    private static String label(AudioDeviceInfo d) {
        switch (d.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC:
                return "Phone microphone";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                return "Wired headset mic";
            case AudioDeviceInfo.TYPE_LINE_ANALOG:
            case AudioDeviceInfo.TYPE_LINE_DIGITAL:
            case AudioDeviceInfo.TYPE_AUX_LINE:
                return "Line-in (AUX)";
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_ACCESSORY:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                return "USB audio input";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                return "Bluetooth headset mic";
            case AudioDeviceInfo.TYPE_HDMI:
                return "HDMI input";
            default:
                CharSequence name = d.getProductName();
                return name == null || name.length() == 0 ? "Audio input" : name.toString();
        }
    }

    @Override
    String subtitle(SessionState s) {
        return s.micOn ? "Live \u2014 shared with every speaker" : "Share a live input with every speaker";
    }

    @Override
    public void onState(SessionState s) {
        boolean changed = s.micOn != micOn;
        micOn = s.micOn;
        if (changed) refreshButton();
        bigLabel.setText(micOn ? "Sharing live" : "Tap to start sharing");
        int speakers = s.connectedSpeakers();
        if (micOn) {
            hint.setText((s.micInput.isEmpty() ? "Capturing audio" : s.micInput) + (speakers > 0 ? " \u00B7 " + speakers
                    + (speakers == 1 ? " speaker" : " speakers") + " listening" : " \u00B7 no speakers connected yet"));
        } else {
            hint.setText("Turn this phone into a wireless microphone or aux receiver for all connected speakers.");
        }
        if (monitor.isChecked() != s.micMonitor) monitor.setChecked(s.micMonitor, false);
        if (inputs.isEmpty() && inputSignature.isEmpty()) refreshInputs();
    }
}
