package com.serena.syncora.ui;

import android.animation.TimeInterpolator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** View factories and small helpers shared by every screen. */
public final class Ui {
    public static float density = 1f;
    public static boolean animations = true;

    public static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    public static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    public static final Typeface BOLD = Typeface.create("sans-serif", Typeface.BOLD);
    public static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    public static final Typeface CONDENSED = Typeface.create("sans-serif-condensed", Typeface.NORMAL);
    public static final Typeface MONO = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);

    public static final TimeInterpolator DECELERATE = new DecelerateInterpolator(1.6f);

    private Ui() {
    }

    public static void init(Context context) {
        density = context.getResources().getDisplayMetrics().density;
    }

    public static int dp(float v) {
        return Math.round(v * density);
    }

    public static float dpf(float v) {
        return v * density;
    }

    // ---------------------------------------------------------------- layout params

    public static LinearLayout.LayoutParams lin(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lin(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    public static LinearLayout.LayoutParams lin(int w, int h, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return p;
    }

    public static LinearLayout.LayoutParams linW(float weight, int h, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, h, weight);
        p.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return p;
    }

    public static FrameLayout.LayoutParams frame(int w, int h) {
        return new FrameLayout.LayoutParams(w, h);
    }

    public static FrameLayout.LayoutParams frame(int w, int h, int gravity) {
        return new FrameLayout.LayoutParams(w, h, gravity);
    }

    public static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    public static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    // ---------------------------------------------------------------- drawables

    public static GradientDrawable rect(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dpf(radiusDp));
        return d;
    }

    public static GradientDrawable oval(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    public static GradientDrawable stroke(int fill, int strokeColor, float strokeDp, float radiusDp) {
        GradientDrawable d = rect(fill, radiusDp);
        d.setStroke(Math.max(1, dp(strokeDp)), strokeColor);
        return d;
    }

    public static GradientDrawable gradient(int from, int to, GradientDrawable.Orientation orientation, float radiusDp) {
        GradientDrawable d = new GradientDrawable(orientation, new int[]{from, to});
        d.setCornerRadius(dpf(radiusDp));
        return d;
    }

    public static GradientDrawable topRounded(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        float r = dpf(radiusDp);
        d.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        return d;
    }

    /** Wraps {@code content} in a touch ripple clipped to a rounded rectangle. */
    public static Drawable ripple(Drawable content, float radiusDp) {
        GradientDrawable mask = rect(Color.WHITE, radiusDp);
        return new RippleDrawable(ColorStateList.valueOf(Theme.alpha(Theme.text, 38)), content, mask);
    }

    public static Drawable rippleOval(Drawable content) {
        GradientDrawable mask = oval(Color.WHITE);
        return new RippleDrawable(ColorStateList.valueOf(Theme.alpha(Theme.text, 38)), content, mask);
    }

    /** A rounded card background with a hairline border. */
    public static Drawable card(float radiusDp) {
        return stroke(Theme.surface, Theme.line, 1, radiusDp);
    }

    public static void setBackground(View v, Drawable d) {
        v.setBackground(d);
    }

    // ---------------------------------------------------------------- text & icons

    public static TextView text(Context c, CharSequence value, float sp, int color, Typeface face) {
        TextView t = new TextView(c);
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setIncludeFontPadding(false);
        return t;
    }

    public static TextView title(Context c, CharSequence value, float sp) {
        TextView t = text(c, value, sp, Theme.text, BOLD);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    public static TextView label(Context c, CharSequence value) {
        TextView t = text(c, value.toString().toUpperCase(Locale.getDefault()), 11, Theme.subtle, BOLD);
        t.setLetterSpacing(0.14f);
        return t;
    }

    public static ImageView icon(Context c, int resId, float sizeDp, int tint) {
        ImageView v = new ImageView(c);
        v.setImageResource(resId);
        v.setColorFilter(tint);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setLayoutParams(new ViewGroup.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    /** A square tappable icon with a circular ripple. */
    public static ImageView iconButton(Context c, int resId, float buttonDp, float iconDp, int tint, String description) {
        ImageView v = new ImageView(c);
        v.setImageResource(resId);
        v.setColorFilter(tint);
        v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = dp((buttonDp - iconDp) / 2f);
        v.setPadding(pad, pad, pad, pad);
        v.setBackground(rippleOval(null));
        v.setContentDescription(description);
        v.setClickable(true);
        v.setFocusable(true);
        pressable(v);
        return v;
    }

    public static View space(Context c, int wDp, int hDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(wDp), dp(hDp)));
        return v;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(Theme.line);
        v.setLayoutParams(new LinearLayout.LayoutParams(MATCH, Math.max(1, dp(0.5f))));
        return v;
    }

    /** A pill with an icon and text (source chip, device chip...). */
    public static LinearLayout pill(Context c, int iconRes, CharSequence label, int fill, int tint) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(8), dp(14), dp(8));
        row.setBackground(ripple(stroke(fill, Theme.line, 1, 20), 20));
        if (iconRes != 0) {
            ImageView icon = icon(c, iconRes, 18, tint);
            row.addView(icon, lin(dp(18), dp(18), 0, 0, 8, 0));
        }
        TextView text = text(c, label, 13, tint, MEDIUM);
        text.setSingleLine(true);
        row.addView(text, lin(WRAP, WRAP));
        row.setClickable(true);
        row.setFocusable(true);
        pressable(row);
        return row;
    }

    /** Filled accent button with rounded corners. */
    public static TextView primaryButton(Context c, CharSequence label) {
        TextView t = text(c, label, 14, Theme.onAccent, BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(20), dp(13), dp(20), dp(13));
        t.setBackground(ripple(gradient(Theme.accentHi, Theme.accent, GradientDrawable.Orientation.TL_BR, 14), 14));
        t.setClickable(true);
        t.setFocusable(true);
        pressable(t);
        return t;
    }

    public static TextView secondaryButton(Context c, CharSequence label) {
        TextView t = text(c, label, 14, Theme.text, MEDIUM);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(18), dp(12), dp(18), dp(12));
        t.setBackground(ripple(stroke(Theme.surface2, Theme.line, 1, 14), 14));
        t.setClickable(true);
        t.setFocusable(true);
        pressable(t);
        return t;
    }

    /** Subtle scale feedback when pressed. */
    public static void pressable(final View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                if (!animations) return false;
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).start();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        view.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(DECELERATE).start();
                        break;
                    default:
                        break;
                }
                return false;
            }
        });
    }

    public static void fadeIn(View v) {
        if (!animations) {
            v.setAlpha(1f);
            return;
        }
        v.setAlpha(0f);
        v.setTranslationY(dpf(10));
        v.animate().alpha(1f).translationY(0).setDuration(260).setInterpolator(DECELERATE).start();
    }

    public static String formatTime(long ms) {
        long total = Math.max(0, ms) / 1000;
        long h = total / 3600;
        long m = (total / 60) % 60;
        long s = total % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%d:%02d", m, s);
    }

    public static ColorDrawable color(int c) {
        return new ColorDrawable(c);
    }
}
