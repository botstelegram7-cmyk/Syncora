package com.serena.syncora.ui;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.serena.syncora.Prefs;
import com.serena.syncora.R;
import com.serena.syncora.SessionState;
import com.serena.syncora.SyncoraService;
import com.serena.syncora.core.AudioProfile;

/** Fourth tab: all settings. */
public final class SettingsPage extends FrameLayout implements StateListener {
    private final UiHost host;
    private final LinearLayout column;
    private TextView nameValue;
    private TextView ipValue;
    private TextView themeValue;
    private TextView audioValue;
    private TextView resyncValue;
    private ToggleView animations;
    private ToggleView autoConnect;
    private LinearLayout accentRow;

    public SettingsPage(final UiHost host) {
        super(host.activity());
        this.host = host;
        Context c = host.activity();
        setBackgroundColor(Theme.bg);
        ScrollView scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        addView(scroll, Ui.frame(Ui.MATCH, Ui.MATCH));
        column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(Ui.dp(20), Ui.dp(16), Ui.dp(20), Ui.dp(32));
        scroll.addView(column, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        build(c);
    }

    public void setTopInset(int px) {
        column.setPadding(Ui.dp(20), Ui.dp(16) + px, Ui.dp(20), Ui.dp(32));
    }

    private LinearLayout group(Context c, String label) {
        column.addView(Ui.label(c, label), Ui.lin(Ui.MATCH, Ui.WRAP, 6, 18, 0, 10));
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.card(22));
        column.addView(card, Ui.lin(Ui.MATCH, Ui.WRAP));
        return card;
    }

    private LinearLayout row(Context c, LinearLayout card, int icon, String title, String subtitle, boolean divider) {
        if (divider) {
            View line = new View(c);
            line.setBackgroundColor(Theme.line);
            card.addView(line, Ui.lin(Ui.MATCH, Math.max(1, Ui.dp(0.5f)), 62, 0, 0, 0));
        }
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(16), Ui.dp(14), Ui.dp(16), Ui.dp(14));
        row.setBackground(Ui.ripple(null, 22));
        LinearLayout badge = new LinearLayout(c);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(Ui.rect(Theme.accentSoft, 12));
        badge.addView(Ui.icon(c, icon, 20, Theme.accent), Ui.lin(Ui.dp(20), Ui.dp(20)));
        row.addView(badge, Ui.lin(Ui.dp(38), Ui.dp(38), 0, 0, 14, 0));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.text(c, title, 15, Theme.text, Ui.MEDIUM), Ui.lin(Ui.MATCH, Ui.WRAP));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = Ui.text(c, subtitle, 12, Theme.muted, Ui.REGULAR);
            texts.addView(sub, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 3, 0, 0));
        }
        row.addView(texts, Ui.lin(0, Ui.WRAP, 1f));
        card.addView(row, Ui.lin(Ui.MATCH, Ui.WRAP));
        return row;
    }

    /** A row that shows its current value on the right and a chevron. */
    private TextView valueRow(Context c, LinearLayout card, int icon, String title, String subtitle, boolean divider, View.OnClickListener click) {
        LinearLayout row = row(c, card, icon, title, subtitle, divider);
        TextView value = Ui.text(c, "", 13, Theme.accent, Ui.MEDIUM);
        value.setGravity(Gravity.END);
        value.setSingleLine(true);
        value.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(value, Ui.lin(Ui.WRAP, Ui.WRAP, 8, 0, 4, 0));
        row.addView(Ui.icon(c, R.drawable.ic_chevron, 20, Theme.subtle), Ui.lin(Ui.dp(20), Ui.dp(20)));
        row.setClickable(true);
        row.setOnClickListener(click);
        return value;
    }

    private ToggleView toggleRow(Context c, LinearLayout card, int icon, String title, String subtitle, boolean divider, boolean checked,
                                 ToggleView.Listener listener) {
        LinearLayout row = row(c, card, icon, title, subtitle, divider);
        ToggleView toggle = new ToggleView(c);
        toggle.setChecked(checked, false);
        toggle.setListener(listener);
        row.addView(toggle, Ui.lin(Ui.WRAP, Ui.WRAP, 8, 0, 0, 0));
        return toggle;
    }

    private void build(final Context c) {
        final Prefs prefs = host.prefs();
        column.addView(Ui.text(c, "Settings", 28, Theme.text, Ui.BOLD), Ui.lin(Ui.MATCH, Ui.WRAP));
        column.addView(Ui.text(c, "Make Syncora yours", 13, Theme.muted, Ui.REGULAR), Ui.lin(Ui.MATCH, Ui.WRAP, 0, 4, 0, 0));

        // ---- device & connection
        LinearLayout device = group(c, "Device");
        nameValue = valueRow(c, device, R.drawable.ic_phone, "Device name", "Shown to other devices", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Sheets.input(host, "Device name", "How this device appears in rooms and DLNA apps", prefs.defaultDeviceName(), prefs.deviceName(),
                        Sheets.textInput(), "Save", new Sheets.TextListener() {
                            @Override
                            public void onText(String text) {
                                prefs.setDeviceName(text);
                                SyncoraService s = host.service();
                                if (s != null) s.settingsChanged();
                                refresh();
                            }
                        });
            }
        });
        ipValue = valueRow(c, device, R.drawable.ic_link, "IP address for connect", "Where Speaker mode looks for a player", true,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Sheets.input(host, "IP address for connect", "Type the player's IP address (shown in its Devices tab). Leave empty to search automatically.",
                                "192.168.1.20", prefs.connectIp(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, "Save",
                                new Sheets.TextListener() {
                                    @Override
                                    public void onText(String text) {
                                        prefs.setConnectIp(text);
                                        refresh();
                                    }
                                });
                    }
                });

        // ---- appearance
        LinearLayout look = group(c, "Appearance");
        themeValue = valueRow(c, look, R.drawable.ic_palette, "Theme", "Dark, AMOLED black, light or follow the system", false,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String[] ids = {Prefs.THEME_DARK, Prefs.THEME_AMOLED, Prefs.THEME_LIGHT, Prefs.THEME_SYSTEM};
                        String[] labels = {"Dark", "AMOLED black", "Light", "Follow system"};
                        int selected = 0;
                        for (int i = 0; i < ids.length; i++) if (ids[i].equals(prefs.theme())) selected = i;
                        final String[] chosen = ids;
                        Sheets.choice(host, "Theme", null, labels, null, selected, new Sheets.ChoiceListener() {
                            @Override
                            public void onChoice(int index) {
                                prefs.setTheme(chosen[index]);
                                host.themeChanged();
                            }
                        });
                    }
                });
        LinearLayout accent = row(c, look, R.drawable.ic_palette, "Accent colour", null, true);
        accentRow = new LinearLayout(c);
        accentRow.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < Theme.ACCENTS.length; i++) {
            final int index = i;
            View swatch = new View(c);
            boolean on = prefs.accent() == i;
            android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable();
            dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            dot.setColor(Theme.ACCENTS[i]);
            if (on) dot.setStroke(Ui.dp(3), Theme.text);
            swatch.setBackground(dot);
            swatch.setContentDescription(Theme.ACCENT_NAMES[i]);
            swatch.setClickable(true);
            swatch.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.setAccent(index);
                    host.themeChanged();
                }
            });
            accentRow.addView(swatch, Ui.lin(Ui.dp(28), Ui.dp(28), 0, 0, i == Theme.ACCENTS.length - 1 ? 0 : 10, 0));
        }
        accent.addView(accentRow, Ui.lin(Ui.WRAP, Ui.WRAP));
        animations = toggleRow(c, look, R.drawable.ic_equalizer, "Animation", "Moving visuals and smooth transitions", true, prefs.animations(),
                new ToggleView.Listener() {
                    @Override
                    public void onChanged(boolean checked) {
                        prefs.setAnimations(checked);
                        Ui.animations = checked;
                    }
                });

        // ---- audio
        LinearLayout audio = group(c, "Audio");
        audioValue = valueRow(c, audio, R.drawable.ic_tune, "Audio mode", "Trade a little delay for steadier sound", false,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        final AudioProfile[] profiles = AudioProfile.all();
                        String[] labels = new String[profiles.length];
                        String[] descriptions = new String[profiles.length];
                        int selected = 1;
                        for (int i = 0; i < profiles.length; i++) {
                            labels[i] = profiles[i].label;
                            descriptions[i] = profiles[i].description;
                            if (profiles[i].id.equals(prefs.audioMode())) selected = i;
                        }
                        Sheets.choice(host, "Audio mode", "Applies to the whole room when this device is the player", labels, descriptions, selected,
                                new Sheets.ChoiceListener() {
                                    @Override
                                    public void onChoice(int index) {
                                        SyncoraService s = host.service();
                                        if (s != null) s.setAudioMode(profiles[index].id);
                                        else prefs.setAudioMode(profiles[index].id);
                                        refresh();
                                    }
                                });
                    }
                });

        // ---- speaker mode
        LinearLayout speaker = group(c, "Speaker mode");
        autoConnect = toggleRow(c, speaker, R.drawable.ic_speaker, "Auto connect", "Join the last player automatically", false, prefs.autoConnect(),
                new ToggleView.Listener() {
                    @Override
                    public void onChanged(boolean checked) {
                        prefs.setAutoConnect(checked);
                        SyncoraService s = host.service();
                        if (s != null) s.settingsChanged();
                    }
                });
        resyncValue = valueRow(c, speaker, R.drawable.ic_sync, "Re-sync frequency", "How often a speaker re-checks its timing", true,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        final int[] values = {0, 5, 15, 30, 60};
                        String[] labels = {"Off (only when it drifts a lot)", "Every 5 seconds", "Every 15 seconds", "Every 30 seconds", "Every minute"};
                        int selected = 2;
                        for (int i = 0; i < values.length; i++) if (values[i] == prefs.resyncSeconds()) selected = i;
                        Sheets.choice(host, "Re-sync frequency", "Shorter intervals keep devices tighter but may cause tiny audio nudges", labels, null,
                                selected, new Sheets.ChoiceListener() {
                                    @Override
                                    public void onChoice(int index) {
                                        prefs.setResyncSeconds(values[index]);
                                        SyncoraService s = host.service();
                                        if (s != null) s.settingsChanged();
                                        refresh();
                                    }
                                });
                    }
                });

        // ---- about
        LinearLayout about = group(c, "About");
        String version = "0.2.0";
        try {
            PackageInfo info = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            if (info.versionName != null) version = info.versionName;
        } catch (Exception ignored) {
            // keep the default
        }
        row(c, about, R.drawable.ic_info, "Syncora " + version, "One Beat. Every Device.", false);
        row(c, about, R.drawable.ic_wifi, "Works offline", "Rooms run over your own Wi-Fi or hotspot. Radio needs internet on the player only.", true);
        refresh();
    }

    private void refresh() {
        Prefs prefs = host.prefs();
        nameValue.setText(prefs.deviceName());
        ipValue.setText(prefs.connectIp().isEmpty() ? "Automatic" : prefs.connectIp());
        switch (prefs.theme()) {
            case Prefs.THEME_AMOLED:
                themeValue.setText("AMOLED black");
                break;
            case Prefs.THEME_LIGHT:
                themeValue.setText("Light");
                break;
            case Prefs.THEME_SYSTEM:
                themeValue.setText("Follow system");
                break;
            default:
                themeValue.setText("Dark");
                break;
        }
        audioValue.setText(AudioProfile.of(prefs.audioMode()).label);
        int resync = prefs.resyncSeconds();
        resyncValue.setText(resync == 0 ? "Off" : resync + " s");
    }

    @Override
    public void onState(SessionState s) {
        refresh();
        if (autoConnect.isChecked() != s.autoConnect) autoConnect.setChecked(s.autoConnect, false);
    }
}
