package com.serena.syncora.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** An iOS-style switch drawn in the accent colour. */
public final class ToggleView extends View {
    public interface Listener {
        void onChanged(boolean checked);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private boolean checked;
    private float position;
    private Listener listener;
    private ValueAnimator animator;

    public ToggleView(Context context) {
        super(context);
        setClickable(true);
        setFocusable(true);
        setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                setChecked(!checked, true);
                if (listener != null) listener.onChanged(checked);
            }
        });
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public boolean isChecked() {
        return checked;
    }

    public void setChecked(boolean value, boolean animate) {
        if (value == checked && !animate) {
            position = value ? 1f : 0f;
            invalidate();
            return;
        }
        checked = value;
        if (animator != null) animator.cancel();
        if (animate && Ui.animations && isAttachedToWindow()) {
            animator = ValueAnimator.ofFloat(position, value ? 1f : 0f);
            animator.setDuration(180);
            animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    position = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            animator.start();
        } else {
            position = value ? 1f : 0f;
            invalidate();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(Ui.dp(48), Ui.dp(30));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        paint.setColor(Theme.mix(Theme.surface3, Theme.accent, position));
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, h / 2f, h / 2f, paint);
        float pad = Ui.dpf(3);
        float r = h / 2f - pad;
        float cx = pad + r + (w - 2 * pad - 2 * r) * position;
        paint.setColor(Theme.mix(Theme.muted, Theme.onAccent, position));
        canvas.drawCircle(cx, h / 2f, r, paint);
    }
}
