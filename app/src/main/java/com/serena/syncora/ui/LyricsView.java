package com.serena.syncora.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.serena.syncora.lyrics.LyricLine;
import com.serena.syncora.lyrics.LyricWord;
import com.serena.syncora.lyrics.Lyrics;
import com.serena.syncora.lyrics.LyricsAppearance;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Native lyrics renderer shared by the player page and fullscreen view. Playback position is supplied by the
 * existing player timeline; this view never starts a competing timer or changes audio state by itself.
 */
final class LyricsView extends ScrollView {

    /** Called when the user taps a timed line or word. */
    interface SeekListener {
        void onSeek(long positionMs);
    }

    private final LinearLayout column;
    private final List<TextView> lineViews = new ArrayList<>();
    private Lyrics shown;
    private String shownKey;
    private String shownStatus;
    private LyricsAppearance appearance = new LyricsAppearance();
    private int active = -1;
    private int activeWord = -1;
    private long positionMs;
    private SeekListener seekListener;

    LyricsView(Context context) {
        super(context);
        setVerticalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setFillViewport(true);
        setClipToPadding(false);
        column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(Ui.dp(26), Ui.dp(68), Ui.dp(26), Ui.dp(48));
        addView(column, new ScrollView.LayoutParams(Ui.MATCH, Ui.MATCH));
        applyBackground();
    }

    void setSeekListener(SeekListener listener) {
        seekListener = listener;
    }

    void setAppearance(LyricsAppearance newAppearance) {
        if (newAppearance == null || sameAppearance(appearance, newAppearance)) return;
        appearance = newAppearance.copy();
        applyBackground();
        rebuild();
    }

    private static boolean sameAppearance(LyricsAppearance a, LyricsAppearance b) {
        return a.mode.equals(b.mode) && a.themeId.equals(b.themeId) && a.themeName.equals(b.themeName)
                && a.fontFamily.equals(b.fontFamily)
                && a.background.equals(b.background) && a.fontSizeSp == b.fontSizeSp
                && a.lineSpacingPercent == b.lineSpacingPercent && a.letterSpacingPercent == b.letterSpacingPercent
                && a.activeColor == b.activeColor && a.inactiveColor == b.inactiveColor
                && a.backgroundColor == b.backgroundColor && a.animations == b.animations
                && a.reducedMotion == b.reducedMotion && a.boldActive == b.boldActive
                && a.uppercase == b.uppercase && a.showTranslation == b.showTranslation
                && a.showRomanization == b.showRomanization && a.highContrast == b.highContrast;
    }

    private void applyBackground() {
        String style = appearance.effectiveBackground();
        LyricsAppearance.Preset preset = appearance.preset();
        if (LyricsAppearance.BACKGROUND_CLEAR.equals(style)) {
            setBackgroundColor(android.graphics.Color.TRANSPARENT);
        } else if (LyricsAppearance.BACKGROUND_SOLID.equals(style)) {
            int color = appearance.backgroundColor != 0 ? appearance.backgroundColor : preset.backgroundStart;
            setBackgroundColor(color);
        } else if (LyricsAppearance.BACKGROUND_ALBUM.equals(style)) {
            // Artwork is supplied by the player's own BackdropView; this subtle veil preserves contrast without
            // decoding or blurring a second copy of the cover.
            setBackgroundColor(Theme.alpha(Theme.bg, preset.highContrast ? 175 : 100));
        } else {
            int start = appearance.backgroundColor != 0 ? appearance.backgroundColor : preset.backgroundStart;
            GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{start, preset.backgroundEnd});
            setBackground(gradient);
        }
        int top = Ui.dp(LyricsAppearance.COMPACT.equals(appearance.mode) ? 60 : 68);
        int bottom = Ui.dp(LyricsAppearance.COMPACT.equals(appearance.mode) ? 28 : 48);
        column.setPadding(Ui.dp(LyricsAppearance.COMPACT.equals(appearance.mode) ? 18 : 26), top,
                Ui.dp(LyricsAppearance.COMPACT.equals(appearance.mode) ? 18 : 26), bottom);
    }

    /**
     * Shows the lyrics of one song. Without lyrics, shows an honest loading, unavailable or error state instead.
     * Calling it again with the same song and lyrics does nothing.
     */
    void show(String key, Lyrics lyrics, String status) {
        String safeKey = key == null ? "" : key;
        String safeStatus = status == null ? "" : status;
        if (lyrics == shown && safeKey.equals(shownKey) && safeStatus.equals(shownStatus)) return;
        shown = lyrics;
        shownKey = safeKey;
        shownStatus = safeStatus;
        active = -1;
        activeWord = -1;
        rebuild();
    }

    private void rebuild() {
        column.removeAllViews();
        lineViews.clear();
        active = -1;
        activeWord = -1;
        scrollTo(0, 0);
        Context context = getContext();
        if (shown == null || shown.isEmpty()) {
            String text = shownStatus.isEmpty() ? "Play a song to see its lyrics" : shownStatus;
            TextView status = Ui.text(context, text, 16, Theme.muted, Ui.MEDIUM);
            status.setGravity(Gravity.CENTER);
            status.setMinHeight(Ui.dp(100));
            status.setContentDescription(text);
            column.addView(status, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 12, 0, 0));
            TextView hint = Ui.text(context, "Lyrics are independent of audio playback. Use Refresh in Lyrics & Appearance to try again.",
                    12, Theme.subtle, Ui.REGULAR);
            hint.setGravity(Gravity.CENTER);
            column.addView(hint, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 8, 0, 0));
            return;
        }

        if (!shownStatus.isEmpty()) {
            TextView update = Ui.text(context, shownStatus, 12, Theme.warn, Ui.MEDIUM);
            update.setContentDescription("Lyrics status: " + shownStatus);
            column.addView(update, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 0, 0, Ui.dp(10)));
        }

        boolean synced = shown.synced();
        int index = 0;
        for (final LyricLine line : shown.lines) {
            final int lineIndex = index++;
            String text = appearance.uppercase ? line.text.toUpperCase(Locale.getDefault()) : line.text;
            TextView lineView = Ui.text(context, text.isEmpty() ? " " : text, textSize(), Theme.text, font(false));
            lineView.setLineSpacing(Ui.dpf(3), appearance.lineSpacingPercent / 100f);
            lineView.setLetterSpacing(appearance.letterSpacingPercent / 100f);
            lineView.setGravity(textGravity());
            lineView.setPadding(Ui.dp(10), Ui.dp(linePadding()), Ui.dp(10), Ui.dp(linePadding()));
            lineView.setMinHeight(Ui.dp(44));
            lineView.setFocusable(line.timed());
            lineView.setContentDescription(line.timed() ? line.text + ", seek to " + Ui.formatTime(line.timeMs)
                    : line.text + ", unsynchronized lyrics");
            styleLine(lineView, lineIndex, false, -1);
            if (synced && line.timed()) {
                lineView.setClickable(true);
                lineView.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        if (seekListener != null) seekListener.onSeek(line.timeMs);
                    }
                });
            }
            column.addView(lineView, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 0, 0, 0));
            lineViews.add(lineView);
        }

        if ((appearance.mode.equals(LyricsAppearance.DUAL) || appearance.showTranslation || appearance.showRomanization)) {
            String unavailable = "Translation and romanization are not available from this track's lyrics source.";
            TextView note = Ui.text(context, unavailable, 12, Theme.subtle, Ui.REGULAR);
            note.setGravity(Gravity.CENTER);
            note.setContentDescription(unavailable);
            column.addView(note, Ui.lin(Ui.MATCH, Ui.WRAP, 0, Ui.dp(18), 0, 0));
        }

        TextView source = Ui.text(context, "Lyrics from " + shown.source, 11, Theme.subtle, Ui.MEDIUM);
        source.setGravity(Gravity.CENTER);
        source.setContentDescription("Lyrics source: " + shown.source);
        column.addView(source, Ui.lin(Ui.MATCH, Ui.WRAP, 0, Ui.dp(20), 0, 0));
        setPosition(positionMs);
    }

    /** Updates only the previous/current line and, when available, the currently timed word. */
    void setPosition(long positionMs) {
        this.positionMs = Math.max(0, positionMs);
        if (shown == null || !shown.synced()) return;
        int index = shown.indexAt(this.positionMs);
        int word = index >= 0 && index < shown.lines.size()
                && LyricsAppearance.KARAOKE.equals(appearance.mode)
                ? shown.lines.get(index).wordIndexAt(this.positionMs) : -1;
        if (index == active && word == activeWord) return;
        int previous = active;
        active = index;
        activeWord = word;
        if (previous >= 0 && previous < lineViews.size() && previous != index) {
            styleLine(lineViews.get(previous), previous, false, -1);
        }
        if (index >= 0 && index < lineViews.size()) {
            styleLine(lineViews.get(index), index, true, word);
            if (previous != index && getHeight() > 0) {
                View line = lineViews.get(index);
                int target = line.getTop() - (getHeight() - line.getHeight()) / 2;
                smoothScrollTo(0, Math.max(0, target));
                if (appearance.animations && !appearance.reducedMotion && !Ui.animations) {
                    line.setAlpha(1f);
                } else if (appearance.animations && !appearance.reducedMotion) {
                    line.setAlpha(0.75f);
                    line.animate().alpha(1f).setDuration(180).start();
                }
            }
        }
    }

    private int textSize() {
        if (LyricsAppearance.CINEMATIC.equals(appearance.mode)) return Math.min(44, appearance.fontSizeSp + 8);
        if (LyricsAppearance.COMPACT.equals(appearance.mode)) return Math.max(14, appearance.fontSizeSp - 4);
        if (LyricsAppearance.MINIMAL.equals(appearance.mode)) return Math.max(15, appearance.fontSizeSp - 2);
        return appearance.fontSizeSp;
    }

    private int linePadding() {
        if (LyricsAppearance.COMPACT.equals(appearance.mode)) return 4;
        if (LyricsAppearance.CINEMATIC.equals(appearance.mode)) return 12;
        return 8;
    }

    private int textGravity() {
        return LyricsAppearance.CINEMATIC.equals(appearance.mode) ? Gravity.CENTER : Gravity.START;
    }

    private Typeface font(boolean active) {
        int style = active && appearance.boldActive ? Typeface.BOLD : Typeface.NORMAL;
        if (LyricsAppearance.SERIF.equals(appearance.fontFamily)) return Typeface.create("serif", style);
        if (LyricsAppearance.CONDENSED.equals(appearance.fontFamily)) return Typeface.create("sans-serif-condensed", style);
        if (LyricsAppearance.MONO.equals(appearance.fontFamily)) return Typeface.create(Typeface.MONOSPACE, style);
        return Typeface.create("sans-serif", style);
    }

    private void styleLine(TextView view, int index, boolean isActive, int wordIndex) {
        if (shown == null || index < 0 || index >= shown.lines.size()) return;
        LyricLine line = shown.lines.get(index);
        LyricsAppearance.Preset preset = appearance.preset();
        int activeColor = appearance.effectiveActiveColor();
        int inactiveColor = appearance.effectiveInactiveColor();
        int color;
        if (!shown.synced()) {
            color = Theme.alpha(Theme.text, 235);
        } else if (isActive) {
            color = activeColor;
        } else if (index < active) {
            color = preset.previous;
        } else {
            color = appearance.highContrast || preset.highContrast ? inactiveColor : preset.upcoming;
        }
        view.setTextColor(color);
        view.setTypeface(font(isActive));
        view.setAlpha(isActive ? 1f : (LyricsAppearance.MINIMAL.equals(appearance.mode) ? 0.72f : 0.84f));
        view.setBackground(isActive ? Ui.rect(Theme.alpha(activeColor, appearance.highContrast ? 30 : 18), 12) : null);

        if (!isActive || wordIndex < 0 || wordIndex >= line.words.size()
                || !LyricsAppearance.KARAOKE.equals(appearance.mode)) {
            view.setText(appearance.uppercase ? line.text.toUpperCase(Locale.getDefault()) : line.text);
            return;
        }
        String text = appearance.uppercase ? line.text.toUpperCase(Locale.getDefault()) : line.text;
        SpannableString styled = new SpannableString(text);
        int searchFrom = 0;
        for (int i = 0; i < line.words.size(); i++) {
            LyricWord lyricWord = line.words.get(i);
            String wordText = appearance.uppercase ? lyricWord.text.toUpperCase(Locale.getDefault()) : lyricWord.text;
            if (wordText.isEmpty()) continue;
            int start = text.indexOf(wordText, searchFrom);
            if (start < 0) start = text.indexOf(wordText);
            if (start < 0) continue;
            int end = Math.min(text.length(), start + wordText.length());
            searchFrom = end;
            if (lyricWord.timed() && i <= wordIndex) {
                styled.setSpan(new ForegroundColorSpan(activeColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (i == wordIndex) {
                styled.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                styled.setSpan(new UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                styled.setSpan(new BackgroundColorSpan(Theme.alpha(activeColor, 42)), start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        view.setText(styled);
    }
}
