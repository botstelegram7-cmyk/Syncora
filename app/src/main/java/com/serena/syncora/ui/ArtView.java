package com.serena.syncora.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

import com.serena.syncora.R;

/**
 * Album art with rounded corners and a soft glow. Without a bitmap a colourful generated cover with an icon is shown,
 * so every song, station and source still has a thumbnail.
 */
public final class ArtView extends View {
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Rect src = new Rect();
    private final Path clip = new Path();
    private Bitmap bitmap;
    private Bitmap previous;
    private String key = "";
    private int iconRes = R.drawable.ic_note;
    private float fade = 1f;
    private ValueAnimator fadeAnimator;
    private float cornerFraction = 0.075f;
    private boolean glow = true;
    private int maxSizePx = Integer.MAX_VALUE;

    public ArtView(Context context) {
        super(context);
    }

    public void setCornerFraction(float fraction) {
        cornerFraction = fraction;
        invalidate();
    }

    public void setGlow(boolean enabled) {
        glow = enabled;
        invalidate();
    }

    public void setMaxSize(int px) {
        maxSizePx = px;
        requestLayout();
    }

    /** @param art may be null; @param key identifies the cover for the generated fallback (e.g. artKey) */
    public void setArt(Bitmap art, String key, int iconRes) {
        String newKey = key == null ? "" : key;
        boolean changed = art != bitmap || !newKey.equals(this.key) || iconRes != this.iconRes;
        if (!changed) return;
        previous = bitmap;
        bitmap = art;
        this.key = newKey;
        this.iconRes = iconRes;
        if (fadeAnimator != null) fadeAnimator.cancel();
        if (Ui.animations && isAttachedToWindow()) {
            fade = 0f;
            fadeAnimator = ValueAnimator.ofFloat(0f, 1f);
            fadeAnimator.setDuration(320);
            fadeAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    fade = (Float) animation.getAnimatedValue();
                    if (fade >= 1f) previous = null;
                    invalidate();
                }
            });
            fadeAnimator.start();
        } else {
            fade = 1f;
            previous = null;
            invalidate();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) w = Ui.dp(120);
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) h = w;
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float pad = glow ? Ui.dpf(14) : 0;
        float size = Math.min(Math.min(getWidth(), getHeight()) - 2 * pad, maxSizePx);
        if (size <= 0) return;
        float left = (getWidth() - size) / 2f;
        float top = (getHeight() - size) / 2f;
        box.set(left, top, left + size, top + size);
        float radius = size * cornerFraction;

        if (glow) {
            int[] colors = Theme.artColors(key);
            for (int i = 6; i >= 1; i--) {
                float grow = Ui.dpf(3.2f) * i;
                glowPaint.setColor(Theme.alpha(colors[0], Theme.dark ? 16 : 12));
                canvas.drawRoundRect(box.left - grow, box.top - grow + Ui.dpf(6), box.right + grow, box.bottom + grow + Ui.dpf(6),
                        radius + grow, radius + grow, glowPaint);
            }
        }

        clip.reset();
        clip.addRoundRect(box, radius, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        if (previous != null && fade < 1f) drawCover(canvas, previous, 1f);
        drawCover(canvas, bitmap, fade);
        canvas.restore();
    }

    private void drawCover(Canvas canvas, Bitmap b, float alpha) {
        if (b != null) {
            int bw = b.getWidth();
            int bh = b.getHeight();
            int side = Math.min(bw, bh);
            src.set((bw - side) / 2, (bh - side) / 2, (bw + side) / 2, (bh + side) / 2);
            bitmapPaint.setAlpha((int) (255 * alpha));
            canvas.drawBitmap(b, src, box, bitmapPaint);
            return;
        }
        int[] colors = Theme.artColors(key);
        fillPaint.setShader(new LinearGradient(box.left, box.top, box.right, box.bottom, colors[0], colors[1], Shader.TileMode.CLAMP));
        fillPaint.setAlpha((int) (255 * alpha));
        canvas.drawRect(box, fillPaint);
        fillPaint.setShader(null);
        Drawable icon = getContext().getDrawable(iconRes);
        if (icon != null) {
            icon = icon.mutate();
            icon.setTint(0xE6FFFFFF);
            icon.setAlpha((int) (255 * alpha));
            int iconSize = (int) (box.width() * 0.42f);
            int cx = (int) box.centerX();
            int cy = (int) box.centerY();
            icon.setBounds(cx - iconSize / 2, cy - iconSize / 2, cx + iconSize / 2, cy + iconSize / 2);
            icon.draw(canvas);
        }
    }
}
