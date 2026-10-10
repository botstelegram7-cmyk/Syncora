package com.serena.syncora.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Small animated equaliser bars that show what is playing. */
public final class EqualizerView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private boolean playing;
    private int color = Theme.accent;

    public EqualizerView(Context context) {
        super(context);
    }

    public void setColor(int c) {
        color = c;
        invalidate();
    }

    public void setPlaying(boolean p) {
        if (playing == p) return;
        playing = p;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int bars = 4;
        float w = getWidth();
        float h = getHeight();
        float gap = w * 0.12f;
        float barW = (w - gap * (bars - 1)) / bars;
        long t = System.currentTimeMillis();
        paint.setColor(color);
        for (int i = 0; i < bars; i++) {
            float level;
            if (playing && Ui.animations) {
                level = 0.3f + 0.7f * (0.5f + 0.5f * (float) Math.sin(t / 170.0 + i * 1.7 + (i % 2) * 0.8));
            } else {
                level = playing ? 0.6f : 0.25f + 0.1f * (i % 3);
            }
            float bh = Math.max(barW, h * level);
            float x = i * (barW + gap);
            rect.set(x, h - bh, x + barW, h);
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint);
        }
        if (playing && Ui.animations) postInvalidateOnAnimation();
    }
}
