package com.serena.syncora.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Radar-style rings around an icon: "searching / waiting for connection". */
public final class RingsView extends View {
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint discPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int iconRes;
    private float phase;
    private boolean active = true;
    private ValueAnimator animator;
    private int color = Theme.accent;

    public RingsView(Context context, int iconRes) {
        super(context);
        this.iconRes = iconRes;
        ringPaint.setStyle(Paint.Style.STROKE);
    }

    public void setIcon(int res) {
        iconRes = res;
        invalidate();
    }

    public void setColor(int c) {
        color = c;
        invalidate();
    }

    public void setActive(boolean on) {
        active = on;
        updateAnimator();
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateAnimator();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (animator != null) animator.cancel();
        animator = null;
    }

    private void updateAnimator() {
        boolean run = active && Ui.animations && isAttachedToWindow();
        if (run && animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(2800);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setInterpolator(new LinearInterpolator());
            animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    phase = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            animator.start();
        } else if (!run && animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = Math.min(MeasureSpec.getSize(widthMeasureSpec), Ui.dp(220));
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float max = Math.min(cx, cy);
        ringPaint.setStrokeWidth(Ui.dpf(1.5f));
        for (int i = 0; i < 3; i++) {
            float p = (phase + i / 3f) % 1f;
            if (!active || !Ui.animations) p = 0.25f + i * 0.25f;
            float radius = max * (0.36f + 0.64f * p);
            int alpha = (int) ((1f - p) * (active ? 110 : 50));
            ringPaint.setColor(Theme.alpha(color, alpha));
            canvas.drawCircle(cx, cy, radius, ringPaint);
        }
        discPaint.setColor(Theme.surface2);
        canvas.drawCircle(cx, cy, max * 0.34f, discPaint);
        discPaint.setColor(Theme.alpha(color, 40));
        canvas.drawCircle(cx, cy, max * 0.34f, discPaint);
        Drawable icon = getContext().getDrawable(iconRes);
        if (icon != null) {
            icon = icon.mutate();
            icon.setTint(color);
            int s = (int) (max * 0.34f);
            icon.setBounds((int) cx - s / 2, (int) cy - s / 2, (int) cx + s / 2, (int) cy + s / 2);
            icon.draw(canvas);
        }
    }
}
