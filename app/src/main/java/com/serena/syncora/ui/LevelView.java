package com.serena.syncora.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Segmented input level meter for the microphone. */
public final class LevelView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float level;
    private float shown;

    public LevelView(Context context) {
        super(context);
    }

    /** 0..1 */
    public void setLevel(float l) {
        level = Math.max(0f, Math.min(1f, l));
        // fast attack, slow release
        shown = level > shown ? level : shown * 0.85f + level * 0.15f;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), Ui.dp(14));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int segments = 28;
        float gap = Ui.dpf(3);
        float w = (getWidth() - gap * (segments - 1)) / segments;
        float lit = (float) Math.sqrt(shown) * segments;
        for (int i = 0; i < segments; i++) {
            boolean on = i < lit;
            int color;
            if (!on) color = Theme.alpha(Theme.text, 28);
            else if (i > segments * 0.85f) color = Theme.danger;
            else if (i > segments * 0.68f) color = Theme.warn;
            else color = Theme.accent;
            paint.setColor(color);
            float x = i * (w + gap);
            rect.set(x, 0, x + w, getHeight());
            canvas.drawRoundRect(rect, w / 2f, w / 2f, paint);
        }
    }
}
