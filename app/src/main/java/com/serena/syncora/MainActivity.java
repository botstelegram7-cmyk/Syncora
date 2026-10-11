package com.serena.syncora;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.serena.syncora.ui.ArtView;
import com.serena.syncora.ui.DevicesPage;
import com.serena.syncora.ui.PlayerPage;
import com.serena.syncora.ui.SettingsPage;
import com.serena.syncora.ui.Sheet;
import com.serena.syncora.ui.Sheets;
import com.serena.syncora.ui.SourcePage;
import com.serena.syncora.ui.StateListener;
import com.serena.syncora.ui.Theme;
import com.serena.syncora.ui.Ui;
import com.serena.syncora.ui.UiHost;
import com.serena.syncora.radio.RadioCatalog;
import com.serena.syncora.radio.Station;

import java.util.ArrayList;
import java.util.List;

/** The single screen of Syncora: four tabs, a mini player and sheets. */
public final class MainActivity extends Activity implements UiHost {
    private static final int REQUEST_NOTIFICATIONS = 4100;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<StateListener> listeners = new ArrayList<>();
    private Prefs prefs;
    private SyncoraService service;
    private boolean bound;
    private SessionState state = new SessionState();

    private FrameLayout rootFrame;
    private LinearLayout content;
    private FrameLayout pagesFrame;
    private PlayerPage playerPage;
    private SourcePage sourcePage;
    private DevicesPage devicesPage;
    private SettingsPage settingsPage;
    private View[] pageViews;
    private LinearLayout mini;
    private ArtView miniArt;
    private TextView miniTitle;
    private TextView miniSubtitle;
    private ImageView miniPlay;
    private View miniProgress;
    private LinearLayout nav;
    private NavItem[] navItems;
    private TextView toastView;
    private int tab = TAB_PLAYER;
    private int insetTop;
    private int insetBottom;
    private int insetLeft;
    private int insetRight;
    private boolean keyboardVisible;
    private Sheet openSheet;
    private Source lastNavSource;

    private final Runnable miniTicker = new Runnable() {
        @Override
        public void run() {
            if (mini != null && mini.getVisibility() == View.VISIBLE) {
                updateMiniProgress();
                main.postDelayed(this, 500);
            }
        }
    };

    private final Runnable hideToast = new Runnable() {
        @Override
        public void run() {
            if (toastView == null) return;
            if (Ui.animations) {
                toastView.animate().alpha(0f).translationY(Ui.dpf(12)).setDuration(220).withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        toastView.setVisibility(View.GONE);
                    }
                }).start();
            } else {
                toastView.setVisibility(View.GONE);
            }
        }
    };

    private final SyncoraService.Listener serviceListener = new SyncoraService.Listener() {
        @Override
        public void onState(SessionState s) {
            onServiceState(s);
        }

        @Override
        public void onMessage(String message) {
            toast(message);
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((SyncoraService.LocalBinder) binder).getService();
            service.addListener(serviceListener);
            handleDebugIntent(getIntent());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    // ================================================================= lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        Ui.init(this);
        Theme.apply(this, prefs);
        Ui.animations = prefs.animations();
        buildUi();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !getSharedPreferences("perm", MODE_PRIVATE).getBoolean("notifications", false)) {
            getSharedPreferences("perm", MODE_PRIVATE).edit().putBoolean("notifications", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        bound = bindService(new Intent(this, SyncoraService.class), connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (openSheet != null) openSheet.dismiss();
        if (service != null) service.removeListener(serviceListener);
        if (bound) {
            unbindService(connection);
            bound = false;
        }
        service = null;
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (sourcePage != null) sourcePage.release();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleDebugIntent(intent);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Ui.init(this);
        if (Prefs.THEME_SYSTEM.equals(prefs.theme())) themeChanged();
    }

    @Override
    public void onBackPressed() {
        if (tab != TAB_PLAYER) {
            navigate(TAB_PLAYER);
        } else {
            moveTaskToBack(true);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (sourcePage != null) sourcePage.onPermissionResult(requestCode, granted);
    }

    // ================================================================= UI construction

    private void buildUi() {
        getWindow().setBackgroundDrawable(new ColorDrawable(Theme.bg));
        rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(Theme.bg);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        rootFrame.addView(content, Ui.frame(Ui.MATCH, Ui.MATCH));

        pagesFrame = new FrameLayout(this);
        content.addView(pagesFrame, Ui.lin(Ui.MATCH, 0, 1f));

        playerPage = new PlayerPage(this);
        sourcePage = new SourcePage(this);
        devicesPage = new DevicesPage(this);
        settingsPage = new SettingsPage(this);
        pageViews = new View[]{playerPage, sourcePage, devicesPage, settingsPage};
        for (View page : pageViews) {
            pagesFrame.addView(page, Ui.frame(Ui.MATCH, Ui.MATCH));
            page.setVisibility(View.GONE);
        }

        buildMini();
        content.addView(mini, Ui.lin(Ui.MATCH, Ui.WRAP));
        buildNav();
        content.addView(nav, Ui.lin(Ui.MATCH, Ui.WRAP));

        toastView = Ui.text(this, "", 14, Theme.text, Ui.MEDIUM);
        toastView.setGravity(Gravity.CENTER);
        toastView.setPadding(Ui.dp(20), Ui.dp(13), Ui.dp(20), Ui.dp(13));
        toastView.setBackground(Ui.stroke(Theme.surface3, Theme.line, 1, 22));
        toastView.setMaxLines(3);
        toastView.setVisibility(View.GONE);
        toastView.setElevation(Ui.dpf(8));
        FrameLayout.LayoutParams toastParams = Ui.frame(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        toastParams.leftMargin = Ui.dp(24);
        toastParams.rightMargin = Ui.dp(24);
        toastParams.bottomMargin = Ui.dp(96);
        rootFrame.addView(toastView, toastParams);

        setContentView(rootFrame);
        setupWindow();
        showTab(tab, false);
        onServiceState(state);
    }

    private void setupWindow() {
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        boolean lightBars = !Theme.dark;
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            w.setNavigationBarColor(Color.TRANSPARENT);
            w.setNavigationBarContrastEnforced(false);
            WindowInsetsController controller = w.getInsetsController();
            if (controller != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(lightBars ? mask : 0, mask);
            }
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            if (Build.VERSION.SDK_INT >= 29) {
                flags |= View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
                w.setNavigationBarColor(Color.TRANSPARENT);
                w.setNavigationBarContrastEnforced(false);
            } else {
                w.setNavigationBarColor(Theme.surface);
            }
            if (lightBars) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            rootFrame.setSystemUiVisibility(flags);
        }
        rootFrame.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int left;
                int top;
                int right;
                int bottom;
                boolean ime = false;
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    android.graphics.Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
                    left = bars.left;
                    top = bars.top;
                    right = bars.right;
                    ime = insets.isVisible(WindowInsets.Type.ime());
                    bottom = ime ? keyboard.bottom : bars.bottom;
                } else {
                    left = insets.getSystemWindowInsetLeft();
                    top = insets.getSystemWindowInsetTop();
                    right = insets.getSystemWindowInsetRight();
                    bottom = insets.getSystemWindowInsetBottom();
                    ime = bottom > rootFrame.getHeight() * 0.18f && rootFrame.getHeight() > 0;
                }
                applyInsets(left, top, right, bottom, ime);
                return insets;
            }
        });
        rootFrame.requestApplyInsets();
    }

    private void applyInsets(int left, int top, int right, int bottom, boolean ime) {
        insetLeft = left;
        insetTop = top;
        insetRight = right;
        insetBottom = bottom;
        keyboardVisible = ime;
        content.setPadding(left, 0, right, ime ? bottom : 0);
        nav.setPadding(Ui.dp(8), Ui.dp(6), Ui.dp(8), Ui.dp(8) + (ime ? 0 : bottom));
        nav.setVisibility(ime ? View.GONE : View.VISIBLE);
        playerPage.setTopInset(top);
        sourcePage.setTopInset(top);
        devicesPage.setTopInset(top);
        settingsPage.setTopInset(top);
        updateMiniVisibility();
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) toastView.getLayoutParams();
        p.bottomMargin = Ui.dp(96) + (ime ? bottom : bottom);
        toastView.setLayoutParams(p);
    }

    private void buildMini() {
        mini = new LinearLayout(this);
        mini.setOrientation(LinearLayout.VERTICAL);
        mini.setBackground(Ui.stroke(Theme.surface2, Theme.line, 1, 0));

        FrameLayout bar = new FrameLayout(this);
        bar.setBackgroundColor(Theme.alpha(Theme.text, 24));
        miniProgress = new View(this);
        miniProgress.setBackgroundColor(Theme.accent);
        miniProgress.setPivotX(0);
        miniProgress.setScaleX(0f);
        bar.addView(miniProgress, Ui.frame(Ui.MATCH, Ui.MATCH));
        mini.addView(bar, Ui.lin(Ui.MATCH, Ui.dp(2)));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(12), Ui.dp(8), Ui.dp(8), Ui.dp(8));
        miniArt = new ArtView(this);
        miniArt.setGlow(false);
        miniArt.setCornerFraction(0.16f);
        row.addView(miniArt, Ui.lin(Ui.dp(46), Ui.dp(46), 0, 0, 12, 0));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        miniTitle = Ui.text(this, "", 14, Theme.text, Ui.BOLD);
        miniTitle.setSingleLine(true);
        miniTitle.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(miniTitle, Ui.lin(Ui.MATCH, Ui.WRAP));
        miniSubtitle = Ui.text(this, "", 12, Theme.muted, Ui.REGULAR);
        miniSubtitle.setSingleLine(true);
        miniSubtitle.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(miniSubtitle, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 2, 0, 0));
        row.addView(texts, Ui.lin(0, Ui.WRAP, 1f));
        miniPlay = Ui.iconButton(this, R.drawable.ic_play, 48, 28, Theme.text, "Play or pause");
        miniPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (service != null) service.togglePlay();
            }
        });
        row.addView(miniPlay, Ui.lin(Ui.dp(48), Ui.dp(48)));
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                navigate(TAB_PLAYER);
            }
        });
        mini.addView(row, Ui.lin(Ui.MATCH, Ui.WRAP));
        mini.setVisibility(View.GONE);
    }

    private final class NavItem {
        final LinearLayout root;
        final FrameLayout pill;
        final ImageView icon;
        final TextView label;

        NavItem(int iconRes, String text, final int index) {
            root = new LinearLayout(MainActivity.this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER_HORIZONTAL);
            root.setPadding(0, Ui.dp(4), 0, Ui.dp(2));
            pill = new FrameLayout(MainActivity.this);
            icon = Ui.icon(MainActivity.this, iconRes, 24, Theme.subtle);
            pill.addView(icon, Ui.frame(Ui.dp(24), Ui.dp(24), Gravity.CENTER));
            root.addView(pill, Ui.lin(Ui.dp(60), Ui.dp(32)));
            label = Ui.text(MainActivity.this, text, 11, Theme.subtle, Ui.MEDIUM);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            root.addView(label, Ui.lin(Ui.WRAP, Ui.WRAP, 0, 3, 0, 0));
            root.setClickable(true);
            root.setFocusable(true);
            root.setContentDescription(text);
            Ui.pressable(root);
            root.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    navigate(index);
                }
            });
        }

        void setSelected(boolean on) {
            icon.setColorFilter(on ? Theme.accent : Theme.subtle);
            label.setTextColor(on ? Theme.text : Theme.subtle);
            label.setTypeface(on ? Ui.BOLD : Ui.MEDIUM);
            pill.setBackground(on ? Ui.rect(Theme.accentSoft, 16) : null);
        }

        void set(int iconRes, String text) {
            icon.setImageResource(iconRes);
            label.setText(text);
            root.setContentDescription(text);
        }
    }

    private void buildNav() {
        nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setPadding(Ui.dp(8), Ui.dp(6), Ui.dp(8), Ui.dp(8));
        nav.setBackground(Ui.stroke(Theme.surface, Theme.line, 1, 0));
        navItems = new NavItem[4];
        navItems[0] = new NavItem(R.drawable.ic_play_circle, "Player", TAB_PLAYER);
        navItems[1] = new NavItem(R.drawable.ic_library, "Library", TAB_SOURCE);
        navItems[2] = new NavItem(R.drawable.ic_devices, "Devices", TAB_DEVICES);
        navItems[3] = new NavItem(R.drawable.ic_settings, "Settings", TAB_SETTINGS);
        for (NavItem item : navItems) nav.addView(item.root, Ui.lin(0, Ui.WRAP, 1f));
        lastNavSource = null;
    }

    private void updateNav(SessionState s) {
        if (s.source != lastNavSource) {
            lastNavSource = s.source;
            String label;
            switch (s.source) {
                case RADIO:
                    label = "Radio";
                    break;
                case DLNA:
                    label = "DLNA";
                    break;
                case MIC:
                    label = "Mic";
                    break;
                case YOUTUBE:
                    label = "YouTube";
                    break;
                case SPEAKER:
                    label = "Speaker";
                    break;
                default:
                    label = "Library";
                    break;
            }
            navItems[1].set(Sheets.sourceIcon(s.source), label);
        }
        for (int i = 0; i < navItems.length; i++) navItems[i].setSelected(i == tab);
    }

    // ================================================================= state

    private void onServiceState(SessionState s) {
        state = s;
        playerPage.onState(s);
        sourcePage.onState(s);
        devicesPage.onState(s);
        settingsPage.onState(s);
        updateNav(s);
        updateMini(s);
        for (StateListener l : new ArrayList<>(listeners)) l.onState(s);
    }

    private void updateMini(SessionState s) {
        miniTitle.setText(s.hasMedia ? s.title : "");
        miniSubtitle.setText(s.subtitle);
        miniArt.setArt(s.art, s.artKey.isEmpty() ? s.title : s.artKey, Sheets.sourceIcon(s.source));
        miniPlay.setImageResource(s.playing ? R.drawable.ic_pause : R.drawable.ic_play);
        updateMiniVisibility();
        updateMiniProgress();
    }

    private void updateMiniVisibility() {
        boolean show = state.hasMedia && tab != TAB_PLAYER && !keyboardVisible;
        int visibility = show ? View.VISIBLE : View.GONE;
        if (mini.getVisibility() != visibility) {
            mini.setVisibility(visibility);
            if (show) {
                Ui.fadeIn(mini);
                main.removeCallbacks(miniTicker);
                main.postDelayed(miniTicker, 500);
            }
        }
    }

    private void updateMiniProgress() {
        if (state.durationMs <= 0 || state.live) {
            miniProgress.setScaleX(state.live && state.playing ? 1f : 0f);
            return;
        }
        miniProgress.setScaleX(Math.max(0f, Math.min(1f, state.positionNow() / (float) state.durationMs)));
    }

    // ================================================================= navigation

    private void showTab(int index, boolean animate) {
        for (int i = 0; i < pageViews.length; i++) {
            pageViews[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
        }
        if (animate) Ui.fadeIn(pageViews[index]);
        tab = index;
        if (index == TAB_SOURCE) sourcePage.onShown();
        updateNav(state);
        updateMiniVisibility();
    }

    @Override
    public void navigate(int target) {
        if (target < 0 || target >= pageViews.length) return;
        if (target == tab) {
            if (target == TAB_SOURCE) sourcePage.onShown();
            return;
        }
        showTab(target, true);
    }

    // ================================================================= UiHost

    @Override
    public Activity activity() {
        return this;
    }

    @Override
    public SyncoraService service() {
        return service;
    }

    @Override
    public Prefs prefs() {
        return prefs;
    }

    @Override
    public SessionState state() {
        return state;
    }

    @Override
    public void addStateListener(StateListener listener) {
        if (!listeners.contains(listener)) listeners.add(listener);
        listener.onState(state);
    }

    @Override
    public void removeStateListener(StateListener listener) {
        listeners.remove(listener);
    }

    @Override
    public void toast(String message) {
        if (toastView == null || message == null || message.isEmpty()) return;
        main.removeCallbacks(hideToast);
        toastView.setText(message);
        toastView.setVisibility(View.VISIBLE);
        if (Ui.animations) {
            toastView.setAlpha(0f);
            toastView.setTranslationY(Ui.dpf(12));
            toastView.animate().alpha(1f).translationY(0f).setDuration(220).start();
        } else {
            toastView.setAlpha(1f);
            toastView.setTranslationY(0f);
        }
        main.postDelayed(hideToast, 2800);
    }

    @Override
    public void showSheet(Sheet sheet) {
        if (openSheet != null && openSheet != sheet) openSheet.dismiss();
        openSheet = sheet;
        sheet.show();
    }

    @Override
    public boolean hasPermission(String permission) {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void requestPermission(String permission, int requestCode) {
        if (Build.VERSION.SDK_INT < 23) return;
        boolean askedBefore = getSharedPreferences("perm", MODE_PRIVATE).getBoolean(permission, false);
        if (askedBefore && !shouldShowRequestPermissionRationale(permission)) {
            // the system will not show the dialog again: send the user to the app settings
            toast("Allow the permission in Settings to continue.");
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            } catch (RuntimeException ignored) {
                // no settings app
            }
            return;
        }
        getSharedPreferences("perm", MODE_PRIVATE).edit().putBoolean(permission, true).apply();
        requestPermissions(new String[]{permission}, requestCode);
    }

    @Override
    public void themeChanged() {
        Theme.apply(this, prefs);
        Ui.animations = prefs.animations();
        int keepTab = tab;
        if (sourcePage != null) sourcePage.release();
        buildUi();
        showTab(keepTab, false);
        rootFrame.requestApplyInsets();
    }

    @Override
    public int bottomInset() {
        return keyboardVisible ? 0 : insetBottom;
    }

    @Override
    public int maxSheetHeight() {
        return (int) (getResources().getDisplayMetrics().heightPixels * 0.72f);
    }

    // ================================================================= debug deep links (debuggable builds only)

    private void debugPlay(String what) {
        final SyncoraService s = service;
        if (s == null) return;
        switch (what) {
            case "first": {
                final Context app = getApplicationContext();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final List<com.serena.syncora.core.Track> all = com.serena.syncora.audio.MusicLibrary.loadAll(app);
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                if (!all.isEmpty() && service != null) service.playTracks(all, 0);
                            }
                        });
                    }
                }, "syncora-debug-play").start();
                break;
            }
            case "radio":
                s.playStation(com.serena.syncora.radio.RadioDirectory.featured().get(0));
                break;
            case "akashvani": {
                List<Station> channels = RadioCatalog.akashvani(getApplicationContext());
                Station liveNews = RadioCatalog.findById(channels, "air-5");
                if (liveNews == null && !channels.isEmpty()) liveNews = channels.get(0);
                if (liveNews != null) s.playStation(liveNews);
                else android.util.Log.e("Syncora", "Bundled Akashvani catalog is empty");
                break;
            }
            case "india-mp3": {
                Station indian = RadioCatalog.firstByCodec(RadioCatalog.india(getApplicationContext()), "MP3");
                if (indian != null) s.playStation(indian);
                else android.util.Log.e("Syncora", "Bundled India MP3 catalog is empty");
                break;
            }
            case "mic":
                s.startMic(null, false, "Automatic input");
                break;
            default:
                break;
        }
    }

    private void debugCommand(String command) {
        SyncoraService s = service;
        if (s == null) return;
        switch (command) {
            case "toggle":
                s.togglePlay();
                break;
            case "next":
                s.next();
                break;
            case "seek":
                s.seekTo(60_000);
                break;
            case "volume":
                s.setDeviceVolume(prefs.deviceId(), 0.35f);
                break;
            case "offset":
                s.setDeviceOffset(prefs.deviceId(), 40);
                break;
            case "left":
                s.setDeviceChannel(prefs.deviceId(), com.serena.syncora.core.ChannelMode.LEFT);
                break;
            case "focus_loss":
                s.debugFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
                break;
            case "focus_gain":
                s.debugFocusChange(android.media.AudioManager.AUDIOFOCUS_GAIN);
                break;
            default:
                break;
        }
    }

    private boolean isDebuggable() {
        return (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    /**
     * {@code adb shell am start -n com.serena.syncora/.MainActivity --es tab devices --es source radio --es sheet volume}
     * is used by the automated UI checks. Ignored in release builds.
     */
    private void handleDebugIntent(Intent intent) {
        if (intent == null || !isDebuggable()) return;
        final String sourceId = intent.getStringExtra("source");
        final String tabName = intent.getStringExtra("tab");
        final String sheet = intent.getStringExtra("sheet");
        final String theme = intent.getStringExtra("theme");
        final String play = intent.getStringExtra("play");
        final String connect = intent.getStringExtra("connect");
        final String cmd = intent.getStringExtra("cmd");
        if (theme != null && !theme.equals(prefs.theme())) {
            prefs.setTheme(theme);
            themeChanged();
        }
        if (sourceId != null && service != null) service.selectSource(Source.fromId(sourceId));
        if (play != null && service != null) debugPlay(play);
        if (connect != null && service != null) service.connectByAddress(connect);
        if (cmd != null && service != null) debugCommand(cmd);
        if (intent.getBooleanExtra("monitor", false) && service != null) service.setMicMonitor(true);
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (tabName != null) {
                    switch (tabName) {
                        case "source":
                            navigate(TAB_SOURCE);
                            break;
                        case "devices":
                            navigate(TAB_DEVICES);
                            break;
                        case "settings":
                            navigate(TAB_SETTINGS);
                            break;
                        default:
                            navigate(TAB_PLAYER);
                            break;
                    }
                }
                if (sheet != null) {
                    switch (sheet) {
                        case "volume":
                            Sheets.volume(MainActivity.this);
                            break;
                        case "audio":
                            Sheets.audio(MainActivity.this, null);
                            break;
                        case "source":
                            Sheets.source(MainActivity.this);
                            break;
                        case "queue":
                            Sheets.queue(MainActivity.this);
                            break;
                        default:
                            break;
                    }
                }
            }
        }, 400);
    }
}
