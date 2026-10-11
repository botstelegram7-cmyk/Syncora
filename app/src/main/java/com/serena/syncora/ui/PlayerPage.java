package com.serena.syncora.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.serena.syncora.R;
import com.serena.syncora.SessionState;
import com.serena.syncora.Source;
import com.serena.syncora.SyncoraService;
import com.serena.syncora.core.PlayQueue;

import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

/**
 * The music player. The same screen is shown on the player and on every speaker, with the same cover art.
 */
public final class PlayerPage extends FrameLayout implements StateListener {
    private final UiHost host;
    private final BackdropView backdrop;
    private final LinearLayout column;
    private final LinearLayout header;
    private final LinearLayout sourceChip;
    private final TextView sourceText;
    private final ImageView sourceIcon;
    private final LinearLayout devicesChip;
    private final TextView devicesText;
    private final View devicesDot;
    private final ArtView art;
    private final LyricsView lyricsView;
    private final ViewPager pager;
    private final View[] dots = new View[2];
    private final TextView titleView;
    private final TextView subtitleView;
    private final TextView statusView;
    private final PremiumSeekBar seek;
    private final TextView timeLeft;
    private final TextView timeRight;
    private final TextView liveBadge;
    private final ImageView shuffle;
    private final ImageView prev;
    private final ImageView next;
    private final ImageView repeat;
    private final FrameLayout playButton;
    private final ImageView playIcon;

    private SessionState state = new SessionState();
    private boolean dragging;
    private boolean attached;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!attached) return;
            refreshPosition();
            postDelayed(this, 250);
        }
    };

    public PlayerPage(final UiHost host) {
        super(host.activity());
        this.host = host;
        final Context c = host.activity();

        backdrop = new BackdropView(c);
        addView(backdrop, Ui.frame(Ui.MATCH, Ui.MATCH));

        column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        addView(column, Ui.frame(Ui.MATCH, Ui.MATCH));

        // ---- header: source chip + devices chip
        header = new LinearLayout(c);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(16), Ui.dp(10), Ui.dp(16), Ui.dp(6));
        column.addView(header, Ui.lin(Ui.MATCH, Ui.WRAP));

        sourceChip = Ui.pill(c, R.drawable.ic_library, "My Music", Theme.alpha(Theme.surface, 190), Theme.text);
        sourceIcon = (ImageView) sourceChip.getChildAt(0);
        sourceText = (TextView) sourceChip.getChildAt(1);
        ImageView chevron = Ui.icon(c, R.drawable.ic_expand, 18, Theme.muted);
        sourceChip.addView(chevron, Ui.lin(Ui.dp(18), Ui.dp(18), 6, 0, 0, 0));
        sourceChip.setContentDescription("Choose the current source");
        sourceChip.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Sheets.source(host);
            }
        });
        header.addView(sourceChip, Ui.lin(Ui.WRAP, Ui.WRAP));
        View grow = new View(c);
        header.addView(grow, Ui.lin(0, 1, 1f));

        devicesChip = new LinearLayout(c);
        devicesChip.setOrientation(LinearLayout.HORIZONTAL);
        devicesChip.setGravity(Gravity.CENTER_VERTICAL);
        devicesChip.setPadding(Ui.dp(12), Ui.dp(8), Ui.dp(14), Ui.dp(8));
        devicesChip.setBackground(Ui.ripple(Ui.stroke(Theme.alpha(Theme.surface, 190), Theme.line, 1, 20), 20));
        devicesChip.setClickable(true);
        devicesChip.setContentDescription("Connected devices");
        Ui.pressable(devicesChip);
        devicesDot = new View(c);
        devicesDot.setBackground(Ui.oval(Theme.good));
        devicesChip.addView(devicesDot, Ui.lin(Ui.dp(8), Ui.dp(8), 0, 0, 8, 0));
        devicesText = Ui.text(c, "Solo", 13, Theme.text, Ui.MEDIUM);
        devicesChip.addView(devicesText, Ui.lin(Ui.WRAP, Ui.WRAP));
        devicesChip.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                host.navigate(UiHost.TAB_DEVICES);
            }
        });
        header.addView(devicesChip, Ui.lin(Ui.WRAP, Ui.WRAP));

        // ---- artwork (takes whatever height is left). Swipe left on the art to read the lyrics.
        art = new ArtView(c);
        art.setMaxSize(Ui.dp(380));
        lyricsView = new LyricsView(c);
        lyricsView.setSeekListener(new LyricsView.SeekListener() {
            @Override
            public void onSeek(long positionMs) {
                SyncoraService s = host.service();
                if (s != null && state.durationMs > 0 && !state.live) s.seekTo(positionMs);
            }
        });
        final View[] pages = {art, lyricsView};
        pager = new ViewPager(c);
        pager.setAdapter(new PagerAdapter() {
            @Override
            public int getCount() {
                return pages.length;
            }

            @Override
            public boolean isViewFromObject(View view, Object object) {
                return view == object;
            }

            @Override
            public Object instantiateItem(ViewGroup container, int position) {
                View page = pages[position];
                container.addView(page);
                return page;
            }

            @Override
            public void destroyItem(ViewGroup container, int position, Object object) {
                container.removeView((View) object);
            }
        });
        pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                updateDots(position);
            }
        });
        column.addView(pager, Ui.lin(Ui.MATCH, 0, 1f));

        LinearLayout dotRow = new LinearLayout(c);
        dotRow.setOrientation(LinearLayout.HORIZONTAL);
        dotRow.setGravity(Gravity.CENTER);
        for (int i = 0; i < dots.length; i++) {
            dots[i] = new View(c);
            dotRow.addView(dots[i], Ui.lin(Ui.dp(6), Ui.dp(6), 4, 0, 4, 0));
        }
        updateDots(0);
        column.addView(dotRow, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 0, 0, Ui.dp(2)));

        // ---- title block
        LinearLayout info = new LinearLayout(c);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(Ui.dp(28), Ui.dp(4), Ui.dp(28), 0);
        titleView = Ui.text(c, "", 24, Theme.text, Ui.BOLD);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        titleView.setMarqueeRepeatLimit(-1);
        titleView.setSelected(true);
        info.addView(titleView, Ui.lin(Ui.MATCH, Ui.WRAP));
        subtitleView = Ui.text(c, "", 15, Theme.muted, Ui.REGULAR);
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(subtitleView, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 6, 0, 0));
        statusView = Ui.text(c, "", 12, Theme.subtle, Ui.MEDIUM);
        statusView.setSingleLine(true);
        statusView.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(statusView, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 8, 0, 0));
        column.addView(info, Ui.lin(Ui.MATCH, Ui.WRAP));

        // ---- seek
        LinearLayout seekBlock = new LinearLayout(c);
        seekBlock.setOrientation(LinearLayout.VERTICAL);
        seekBlock.setPadding(Ui.dp(18), Ui.dp(10), Ui.dp(18), 0);
        seek = new PremiumSeekBar(c);
        seek.setContentDescription("Seek");
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
                if (service != null && state.durationMs > 0) service.seekTo((long) (fraction * state.durationMs));
            }
        });
        seekBlock.addView(seek, Ui.lin(Ui.MATCH, Ui.dp(36)));
        FrameLayout times = new FrameLayout(c);
        timeLeft = Ui.text(c, "0:00", 12, Theme.muted, Ui.MEDIUM);
        times.addView(timeLeft, Ui.frame(Ui.WRAP, Ui.WRAP, Gravity.START));
        timeRight = Ui.text(c, "0:00", 12, Theme.muted, Ui.MEDIUM);
        times.addView(timeRight, Ui.frame(Ui.WRAP, Ui.WRAP, Gravity.END));
        liveBadge = Ui.text(c, "\u25CF  LIVE", 11, Theme.danger, Ui.BOLD);
        liveBadge.setLetterSpacing(0.12f);
        liveBadge.setVisibility(GONE);
        times.addView(liveBadge, Ui.frame(Ui.WRAP, Ui.WRAP, Gravity.CENTER_HORIZONTAL));
        seekBlock.addView(times, Ui.lin(Ui.MATCH, Ui.WRAP, 10, 0, 10, 0));
        column.addView(seekBlock, Ui.lin(Ui.MATCH, Ui.WRAP));

        // ---- transport controls
        LinearLayout controls = new LinearLayout(c);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(Ui.dp(12), Ui.dp(6), Ui.dp(12), Ui.dp(2));
        shuffle = Ui.iconButton(c, R.drawable.ic_shuffle, 48, 24, Theme.muted, "Shuffle");
        shuffle.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                SyncoraService s = host.service();
                if (s != null) s.setShuffle(!state.shuffle);
            }
        });
        controls.addView(shuffle, Ui.lin(Ui.dp(48), Ui.dp(48), 0, 0, 10, 0));
        prev = Ui.iconButton(c, R.drawable.ic_prev, 56, 32, Theme.text, "Previous");
        prev.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                SyncoraService s = host.service();
                if (s != null) s.previous();
            }
        });
        controls.addView(prev, Ui.lin(Ui.dp(56), Ui.dp(56), 0, 0, 12, 0));

        playButton = new FrameLayout(c);
        playButton.setBackground(Ui.ripple(Ui.gradient(Theme.accentHi, Theme.accent, GradientDrawable.Orientation.TL_BR, 38), 38));
        playButton.setClickable(true);
        playButton.setFocusable(true);
        playButton.setContentDescription("Play or pause");
        Ui.pressable(playButton);
        playIcon = Ui.icon(c, R.drawable.ic_play, 34, Theme.onAccent);
        playButton.addView(playIcon, Ui.frame(Ui.dp(34), Ui.dp(34), Gravity.CENTER));
        playButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                onPlayPressed();
            }
        });
        controls.addView(playButton, Ui.lin(Ui.dp(76), Ui.dp(76)));

        next = Ui.iconButton(c, R.drawable.ic_next, 56, 32, Theme.text, "Next");
        next.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                SyncoraService s = host.service();
                if (s != null) s.next();
            }
        });
        controls.addView(next, Ui.lin(Ui.dp(56), Ui.dp(56), 12, 0, 0, 0));
        repeat = Ui.iconButton(c, R.drawable.ic_repeat, 48, 24, Theme.muted, "Repeat");
        repeat.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                SyncoraService s = host.service();
                if (s == null) return;
                s.cycleRepeat();
                PlayQueue.Repeat nextMode = state.repeat.next();
                host.toast(nextMode == PlayQueue.Repeat.OFF ? "Repeat off" : nextMode == PlayQueue.Repeat.ALL ? "Loop: repeat the whole queue"
                        : "Repeat: this song");
            }
        });
        controls.addView(repeat, Ui.lin(Ui.dp(48), Ui.dp(48), 10, 0, 0, 0));
        column.addView(controls, Ui.lin(Ui.MATCH, Ui.WRAP));

        // ---- quick actions
        LinearLayout actions = new LinearLayout(c);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        actions.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(12));
        actions.addView(actionChip(c, R.drawable.ic_volume, "Volume", new OnClickListener() {
            @Override
            public void onClick(View v) {
                Sheets.volume(host);
            }
        }), Ui.linW(1f, Ui.WRAP, 4, 0, 4, 0));
        actions.addView(actionChip(c, R.drawable.ic_sync, "Sync", new OnClickListener() {
            @Override
            public void onClick(View v) {
                SyncoraService s = host.service();
                if (s != null) s.syncNow();
            }
        }), Ui.linW(1f, Ui.WRAP, 4, 0, 4, 0));
        actions.addView(actionChip(c, R.drawable.ic_tune, "Audio", new OnClickListener() {
            @Override
            public void onClick(View v) {
                Sheets.audio(host, null);
            }
        }), Ui.linW(1f, Ui.WRAP, 4, 0, 4, 0));
        actions.addView(actionChip(c, R.drawable.ic_queue, "Queue", new OnClickListener() {
            @Override
            public void onClick(View v) {
                Sheets.queue(host);
            }
        }), Ui.linW(1f, Ui.WRAP, 4, 0, 4, 0));
        column.addView(actions, Ui.lin(Ui.MATCH, Ui.WRAP));
    }

    private static LinearLayout actionChip(Context c, int iconRes, String label, OnClickListener click) {
        LinearLayout chip = new LinearLayout(c);
        chip.setOrientation(LinearLayout.VERTICAL);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(Ui.dp(4), Ui.dp(10), Ui.dp(4), Ui.dp(10));
        chip.setBackground(Ui.ripple(Ui.stroke(Theme.alpha(Theme.surface, 200), Theme.line, 1, 18), 18));
        chip.setClickable(true);
        chip.setFocusable(true);
        chip.setContentDescription(label);
        Ui.pressable(chip);
        chip.addView(Ui.icon(c, iconRes, 22, Theme.text), Ui.lin(Ui.dp(22), Ui.dp(22)));
        TextView t = Ui.text(c, label, 11, Theme.muted, Ui.MEDIUM);
        chip.addView(t, Ui.lin(Ui.WRAP, Ui.WRAP, 0, 5, 0, 0));
        chip.setOnClickListener(click);
        return chip;
    }

    private void updateDots(int selected) {
        for (int i = 0; i < dots.length; i++) {
            dots[i].setBackground(Ui.oval(i == selected ? Theme.accent : Theme.alpha(Theme.text, 70)));
        }
    }

    public void setTopInset(int px) {
        header.setPadding(Ui.dp(16), Ui.dp(10) + px, Ui.dp(16), Ui.dp(6));
    }

    private void onPlayPressed() {
        SyncoraService s = host.service();
        if (s == null) return;
        if (!state.hasMedia && !state.playing && !state.isSpeaker()) {
            host.navigate(UiHost.TAB_SOURCE);
            return;
        }
        if (state.isSpeaker() && state.connection != SessionState.Connection.CONNECTED) {
            host.navigate(UiHost.TAB_SOURCE);
            return;
        }
        if (state.interrupted) {
            s.reclaimAudio();
            return;
        }
        s.togglePlay();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        post(ticker);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        attached = false;
        removeCallbacks(ticker);
    }

    private void refreshPosition() {
        if (dragging) return;
        long duration = state.durationMs;
        long position = state.positionNow();
        lyricsView.setPosition(position);
        if (state.live || duration <= 0) {
            seek.setProgress(0f);
            timeLeft.setText(state.live ? "" : Ui.formatTime(position));
            return;
        }
        seek.setProgress(position / (float) duration);
        timeLeft.setText(Ui.formatTime(position));
    }

    private static void setText(TextView v, CharSequence text) {
        if (!TextUtils.equals(v.getText(), text)) v.setText(text);
    }

    @Override
    public void onState(SessionState s) {
        boolean sourceChanged = s.source != state.source;
        state = s;
        Source src = s.source;

        // header chips
        setText(sourceText, src.label);
        sourceIcon.setImageResource(Sheets.sourceIcon(src));
        int others = Math.max(0, s.devices.size() - 1);
        boolean speakerConnected = s.isSpeaker() && s.connection == SessionState.Connection.CONNECTED;
        if (s.isSpeaker()) {
            setText(devicesText, speakerConnected ? "Connected" : (s.connection == SessionState.Connection.CONNECTING ? "Connecting" : "Waiting"));
            ((GradientDrawable) devicesDot.getBackground()).setColor(speakerConnected ? Theme.good : Theme.warn);
        } else {
            setText(devicesText, others == 0 ? "Solo" : (others + 1) + " devices");
            ((GradientDrawable) devicesDot.getBackground()).setColor(others > 0 ? Theme.good : Theme.subtle);
        }

        // title block
        String title;
        String subtitle;
        if (s.hasMedia && !s.title.isEmpty()) {
            title = s.title;
            subtitle = s.subtitle;
        } else {
            switch (src) {
                case RADIO:
                    title = "Pick a station";
                    subtitle = "Open Radio to browse stations";
                    break;
                case DLNA:
                    title = "Ready to cast";
                    subtitle = "Choose \u201C" + s.dlnaName + "\u201D in a DLNA app";
                    break;
                case MIC:
                    title = "Microphone / Aux";
                    subtitle = "Start the input in the Mic tab";
                    break;
                case YOUTUBE:
                    title = "YouTube";
                    subtitle = "Find a video on the YouTube page";
                    break;
                case SPEAKER:
                    title = speakerConnected ? (s.hostName.isEmpty() ? "Syncora Player" : s.hostName) : "Syncora Player";
                    subtitle = speakerConnected ? "Nothing is playing yet" : "Waiting for connection";
                    break;
                default:
                    title = "Your music";
                    subtitle = "Open Library and pick a song";
                    break;
            }
        }
        setText(titleView, title);
        setText(subtitleView, subtitle);

        // status line
        String status;
        if (s.isSpeaker()) {
            if (speakerConnected) {
                status = "SPEAKER \u00B7 " + (s.hostName.isEmpty() ? "PLAYER" : s.hostName.toUpperCase()) + (s.playing ? syncSuffix(s) : "");
            } else {
                status = s.connection == SessionState.Connection.CONNECTING ? s.connectionText.toUpperCase() : "LOOKING FOR PLAYERS ON THIS WI-FI";
            }
        } else if (src == Source.RADIO && !s.stationStatus.isEmpty() && s.hasMedia) {
            status = s.stationStatus.toUpperCase();
        } else if (s.hasMedia && others > 0) {
            status = (others + 1) + " DEVICES" + syncSuffix(s) + (s.lowLatency() ? " \u00B7 LOW LATENCY" : "");
        } else if (s.hasMedia) {
            status = s.playing ? "PLAYING ON THIS DEVICE" : "PAUSED";
        } else {
            status = "";
        }
        if (s.interrupted) {
            // the app takes the audio focus back on its own for a few seconds before it asks for a tap
            status = s.interruptRetrying
                    ? "INTERRUPTED \u00B7 RECONNECTING AUDIO\u2026"
                    : "INTERRUPTED \u00B7 TAP PLAY TO RESUME";
        }
        setText(statusView, status);
        statusView.setVisibility(status.isEmpty() ? GONE : VISIBLE);
        statusView.setTextColor(s.interrupted ? Theme.danger : Theme.subtle);

        // artwork + backdrop
        int fallbackIcon = Sheets.sourceIcon(src);
        if (s.hasMedia && src == Source.MUSIC) fallbackIcon = R.drawable.ic_note;
        String key = s.artKey.isEmpty() ? (s.hasMedia ? s.title : src.id) : s.artKey;
        art.setArt(s.art, key, fallbackIcon);
        backdrop.setArt(s.art, key);

        // lyrics (swipe page)
        lyricsView.show(s.lyricsKey, s.lyrics, s.hasMedia || s.isSpeaker() ? s.lyricsStatus : "");

        // controls
        playIcon.setImageResource(s.playing ? R.drawable.ic_pause : R.drawable.ic_play);
        playButton.setAlpha(1f);
        boolean skip = s.canSkip;
        prev.setAlpha(skip ? 1f : 0.3f);
        next.setAlpha(skip ? 1f : 0.3f);
        prev.setEnabled(skip);
        next.setEnabled(skip);
        boolean modes = skip || (s.isSpeaker() && s.queueSize > 0);
        shuffle.setAlpha(modes ? 1f : 0.3f);
        repeat.setAlpha(modes ? 1f : 0.3f);
        shuffle.setEnabled(modes);
        repeat.setEnabled(modes);
        shuffle.setColorFilter(s.shuffle ? Theme.accent : Theme.muted);
        repeat.setImageResource(s.repeat == PlayQueue.Repeat.ONE ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
        repeat.setColorFilter(s.repeat == PlayQueue.Repeat.OFF ? Theme.muted : Theme.accent);

        // seek area
        boolean live = s.live && s.hasMedia;
        seek.setVisibility(live ? INVISIBLE : VISIBLE);
        seek.setEnabled(s.canSeek);
        liveBadge.setVisibility(live ? VISIBLE : GONE);
        timeRight.setVisibility(live ? INVISIBLE : VISIBLE);
        setText(timeRight, s.durationMs > 0 ? Ui.formatTime(s.durationMs) : "");
        if (sourceChanged) seek.setProgress(0f);
        refreshPosition();

    }

    private static String syncSuffix(SessionState s) {
        if (!s.aligned) return "";
        return " \u00B7 IN SYNC";
    }
}
