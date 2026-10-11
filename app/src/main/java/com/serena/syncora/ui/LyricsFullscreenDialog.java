package com.serena.syncora.ui;

import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.serena.syncora.R;
import com.serena.syncora.SessionState;
import com.serena.syncora.SyncoraService;

/** Full-screen lyrics overlay using the existing session state and playback controls. */
final class LyricsFullscreenDialog implements StateListener {
    private static final long HIDE_DELAY_MS = 4000L;

    private final UiHost host;
    private final Dialog dialog;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final FrameLayout root;
    private final BackdropView backdrop;
    private final LinearLayout topControls;
    private final LinearLayout bottomControls;
    private final LyricsView lyrics;
    private final TextView title;
    private final TextView subtitle;
    private final ImageView playIcon;
    private final ImageView previousButton;
    private final ImageView nextButton;
    private final PremiumSeekBar seek;
    private final TextView timeLeft;
    private final TextView timeRight;
    private SessionState state;
    private boolean attached;
    private boolean dragging;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!attached) return;
            refreshPosition();
            main.postDelayed(this, 200);
        }
    };
    private final Runnable hideControls = new Runnable() {
        @Override
        public void run() {
            setControlsVisible(false);
        }
    };

    static void show(UiHost host, SessionState state) {
        LyricsFullscreenDialog view = new LyricsFullscreenDialog(host, state);
        view.show();
    }

    private LyricsFullscreenDialog(final UiHost host, SessionState initial) {
        this.host = host;
        state = initial;
        dialog = new Dialog(host.activity(), android.R.style.Theme_Material_NoActionBar);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        root = new FrameLayout(host.activity());
        root.setBackgroundColor(Theme.bg);

        backdrop = new BackdropView(host.activity());
        root.addView(backdrop, Ui.frame(Ui.MATCH, Ui.MATCH));
        View shade = new View(host.activity());
        shade.setBackgroundColor(Theme.alpha(Color.BLACK, Theme.dark ? 85 : 35));
        root.addView(shade, Ui.frame(Ui.MATCH, Ui.MATCH));

        lyrics = new LyricsView(host.activity());
        lyrics.setAppearance(host.prefs().lyricsAppearance());
        lyrics.setSeekListener(new LyricsView.SeekListener() {
            @Override
            public void onSeek(long positionMs) {
                SyncoraService service = host.service();
                if (service != null && state.durationMs > 0 && !state.live) service.seekTo(positionMs);
                revealControls();
            }
        });
        lyrics.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_UP) revealControls();
                return false;
            }
        });
        root.addView(lyrics, Ui.frame(Ui.MATCH, Ui.MATCH));

        topControls = new LinearLayout(host.activity());
        topControls.setGravity(Gravity.CENTER_VERTICAL);
        topControls.setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(14), Ui.dp(10));
        topControls.setBackgroundColor(Theme.alpha(Theme.bg, 170));
        ImageView close = Ui.iconButton(host.activity(), R.drawable.ic_close, 48, 24, Theme.text, "Close fullscreen lyrics");
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                dialog.dismiss();
            }
        });
        topControls.addView(close, Ui.lin(Ui.dp(48), Ui.dp(48), 0, 0, 10, 0));
        LinearLayout labels = new LinearLayout(host.activity());
        labels.setOrientation(LinearLayout.VERTICAL);
        title = Ui.text(host.activity(), "Lyrics", 17, Theme.text, Ui.BOLD);
        title.setSingleLine(true);
        labels.addView(title, Ui.lin(Ui.MATCH, Ui.WRAP));
        subtitle = Ui.text(host.activity(), "Fullscreen", 12, Theme.muted, Ui.REGULAR);
        subtitle.setSingleLine(true);
        labels.addView(subtitle, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 2, 0, 0));
        topControls.addView(labels, Ui.lin(0, Ui.WRAP, 1f));
        ImageView style = Ui.iconButton(host.activity(), R.drawable.ic_tune, 48, 24, Theme.accent, "Lyrics styling");
        style.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                LyricsEditorSheet.show(host);
                revealControls();
            }
        });
        topControls.addView(style, Ui.lin(Ui.dp(48), Ui.dp(48)));
        root.addView(topControls, Ui.frame(Ui.MATCH, Ui.WRAP, Gravity.TOP));

        bottomControls = new LinearLayout(host.activity());
        bottomControls.setOrientation(LinearLayout.VERTICAL);
        bottomControls.setPadding(Ui.dp(18), Ui.dp(12), Ui.dp(18), Ui.dp(20) + host.bottomInset());
        bottomControls.setBackgroundColor(Theme.alpha(Theme.bg, 215));
        seek = new PremiumSeekBar(host.activity());
        seek.setContentDescription("Seek in track");
        seek.setListener(new PremiumSeekBar.Listener() {
            @Override
            public void onStart() {
                dragging = true;
            }

            @Override
            public void onChange(float fraction) {
                timeLeft.setText(Ui.formatTime((long) (fraction * state.durationMs)));
            }

            @Override
            public void onStop(float fraction) {
                dragging = false;
                SyncoraService service = host.service();
                if (service != null && state.durationMs > 0 && !state.live) service.seekTo((long) (fraction * state.durationMs));
                revealControls();
            }
        });
        bottomControls.addView(seek, Ui.lin(Ui.MATCH, Ui.dp(36)));
        FrameLayout times = new FrameLayout(host.activity());
        timeLeft = Ui.text(host.activity(), "0:00", 12, Theme.muted, Ui.MEDIUM);
        times.addView(timeLeft, Ui.frame(Ui.WRAP, Ui.WRAP, Gravity.START));
        timeRight = Ui.text(host.activity(), "0:00", 12, Theme.muted, Ui.MEDIUM);
        times.addView(timeRight, Ui.frame(Ui.WRAP, Ui.WRAP, Gravity.END));
        bottomControls.addView(times, Ui.lin(Ui.MATCH, Ui.WRAP, 10, 0, 10, 0));

        LinearLayout transport = new LinearLayout(host.activity());
        transport.setGravity(Gravity.CENTER);
        previousButton = Ui.iconButton(host.activity(), R.drawable.ic_prev, 50, 28, Theme.text, "Previous track");
        previousButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                SyncoraService service = host.service();
                if (service != null) service.previous();
                revealControls();
            }
        });
        transport.addView(previousButton, Ui.lin(Ui.dp(50), Ui.dp(50), 0, 0, 16, 0));
        FrameLayout play = new FrameLayout(host.activity());
        play.setBackground(Ui.ripple(Ui.gradient(Theme.accentHi, Theme.accent,
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR, 32), 32));
        play.setContentDescription("Play or pause");
        play.setClickable(true);
        play.setFocusable(true);
        playIcon = Ui.icon(host.activity(), R.drawable.ic_play, 30, Theme.onAccent);
        play.addView(playIcon, Ui.frame(Ui.dp(30), Ui.dp(30), Gravity.CENTER));
        play.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                SyncoraService service = host.service();
                if (service != null) {
                    if (state.interrupted) service.reclaimAudio();
                    else service.togglePlay();
                }
                revealControls();
            }
        });
        transport.addView(play, Ui.lin(Ui.dp(68), Ui.dp(68)));
        nextButton = Ui.iconButton(host.activity(), R.drawable.ic_next, 50, 28, Theme.text, "Next track");
        nextButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                SyncoraService service = host.service();
                if (service != null) service.next();
                revealControls();
            }
        });
        transport.addView(nextButton, Ui.lin(Ui.dp(50), Ui.dp(50), 16, 0, 0, 0));
        bottomControls.addView(transport, Ui.lin(Ui.MATCH, Ui.WRAP, 0, Ui.dp(10), 0, 0));
        root.addView(bottomControls, Ui.frame(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM));

        root.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (topControls.getVisibility() == View.VISIBLE) revealControls();
                else setControlsVisible(true);
            }
        });
        dialog.setContentView(root);
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                attached = true;
                applyImmersiveWindow();
                host.addStateListener(LyricsFullscreenDialog.this);
                main.post(ticker);
                revealControls();
            }
        });
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                attached = false;
                main.removeCallbacks(ticker);
                main.removeCallbacks(hideControls);
                host.removeStateListener(LyricsFullscreenDialog.this);
            }
        });
    }

    private void show() {
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
        dialog.show();
        if (window != null) window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
    }

    private void applyImmersiveWindow() {
        Window window = dialog.getWindow();
        if (window == null) return;
        View decor = window.getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams params = window.getAttributes();
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(params);
        }
    }

    private void revealControls() {
        setControlsVisible(true);
        main.removeCallbacks(hideControls);
        main.postDelayed(hideControls, HIDE_DELAY_MS);
    }

    private void setControlsVisible(boolean visible) {
        int value = visible ? View.VISIBLE : View.GONE;
        topControls.setVisibility(value);
        bottomControls.setVisibility(value);
    }

    private void refreshPosition() {
        if (state == null) return;
        long position = state.positionNow();
        lyrics.setPosition(position);
        if (!dragging && state.durationMs > 0 && !state.live) {
            seek.setProgress(position / (float) state.durationMs);
            timeLeft.setText(Ui.formatTime(position));
            timeRight.setText(Ui.formatTime(state.durationMs));
        } else if (state.live) {
            seek.setEnabled(false);
            timeRight.setText("LIVE");
        }
    }

    @Override
    public void onState(SessionState current) {
        state = current;
        String key = current.artKey.isEmpty() ? current.title : current.artKey;
        backdrop.setArt(current.art, key);
        lyrics.setAppearance(host.prefs().lyricsAppearance());
        lyrics.show(current.lyricsKey, current.lyrics,
                current.hasMedia || current.isSpeaker() ? current.lyricsStatus : "");
        title.setText(current.title.isEmpty() ? "Lyrics" : current.title);
        subtitle.setText(current.subtitle.isEmpty() ? "Fullscreen lyrics" : current.subtitle);
        playIcon.setImageResource(current.isSpeaker() && current.localPaused ? R.drawable.ic_play
                : current.playing ? R.drawable.ic_pause : R.drawable.ic_play);
        seek.setEnabled(current.canSeek && current.durationMs > 0 && !current.live);
        timeRight.setText(current.durationMs > 0 ? Ui.formatTime(current.durationMs) : (current.live ? "LIVE" : ""));
        refreshPosition();
    }
}
