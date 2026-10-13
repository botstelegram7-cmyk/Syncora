package com.serena.syncora.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

/** A slim, rounded seek / volume bar with a glowing thumb. Progress is a fraction between 0 and 1. */
public final class PremiumSeekBar extends View {
    public interface Listener {
        void onStart();

        void onChange(float fraction);

        void onStop(float fraction);
    }

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float progress;
    private boolean dragging;
    private float press;
    private ValueAnimator pressAnimator;
    private Listener listener;
    private Shader shader;
    private int shaderWidth;
    private int shaderAccent;

    public PremiumSeekBar(Context context) {
        super(context);
        setClickable(true);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public float getProgress() {
        return progress;
    }

    /** Ignored while the user is dragging. */
    public void setProgress(float fraction) {
        if (dragging) return;
        float p = Math.max(0f, Math.min(1f, fraction));
        if (p != progress) {
            progress = p;
            invalidate();
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setAlpha(enabled ? 1f : 0.45f);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = Ui.dp(36);
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) h = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float cy = getHeight() / 2f;
        float inset = Ui.dpf(10);
        float left = inset;
        float right = w - inset;
        float trackH = Ui.dpf(4) + press * Ui.dpf(2);
        float radius = trackH / 2f;

        trackPaint.setColor(Theme.alpha(Theme.text, 46));
        rect.set(left, cy - radius, right, cy + radius);
        canvas.drawRoundRect(rect, radius, radius, trackPaint);

        float x = left + (right - left) * progress;
        if (shader == null || shaderWidth != (int) w || shaderAccent != Theme.accent) {
            shader = new LinearGradient(left, 0, right, 0, Theme.accentHi, Theme.accent, Shader.TileMode.CLAMP);
            shaderWidth = (int) w;
            shaderAccent = Theme.accent;
        }
        fillPaint.setShader(shader);
        rect.set(left, cy - radius, Math.max(left + trackH, x), cy + radius);
        canvas.drawRoundRect(rect, radius, radius, fillPaint);

        if (press > 0f) {
            haloPaint.setColor(Theme.alpha(Theme.accent, (int) (60 * press)));
            canvas.drawCircle(x, cy, Ui.dpf(16) * press, haloPaint);
        }
        thumbPaint.setColor(Theme.accentHi);
        canvas.drawCircle(x, cy, Ui.dpf(6) + Ui.dpf(2.5f) * press, thumbPaint);
    }

    private float fractionAt(float x) {
        float inset = Ui.dpf(10);
        float span = getWidth() - 2 * inset;
        if (span <= 0) return 0f;
        float fraction = (x - inset) / span;
        if (fraction <= 0.005f) return 0f;
        if (fraction >= 0.995f) return 1f;
        return Math.max(0f, Math.min(1f, fraction));
    }

    private void animatePress(float to) {
        if (pressAnimator != null) pressAnimator.cancel();
        if (!Ui.animations) {
            press = to;
            invalidate();
            return;
        }
        pressAnimator = ValueAnimator.ofFloat(press, to);
        pressAnimator.setDuration(140);
        pressAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                press = (Float) animation.getAnimatedValue();
                invalidate();
            }
        });
        pressAnimator.start();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                dragging = true;
                progress = fractionAt(event.getX());
                animatePress(1f);
                if (listener != null) {
                    listener.onStart();
                    listener.onChange(progress);
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    float newProgress = fractionAt(event.getX());
                    if (Math.abs(newProgress - progress) > 0.0001f) {
                        progress = newProgress;
                        if (listener != null) listener.onChange(progress);
                        invalidate();
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    dragging = false;
                    progress = fractionAt(event.getX());
                    animatePress(0f);
                    if (listener != null) {
                        listener.onChange(progress);
                        listener.onStop(progress);
                    }
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    dragging = false;
                    animatePress(0f);
                    if (listener != null) listener.onStop(progress);
                    invalidate();
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }
}
