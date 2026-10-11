package com.serena.syncora.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.view.View;

/** Full-bleed blurred version of the cover, tinted by it, behind the player. */
public final class BackdropView extends View {
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint gradientPaint = new Paint();
    private final Paint plainPaint = new Paint();
    private Shader plainShader;
    private String plainKey = "";
    private int plainWidth;
    private int plainHeight;
    private int plainBg;
    private final Rect dst = new Rect();
    private Bitmap tiny;
    private Bitmap sourceArt;
    private String key = "";
    private int shaderHeight;
    private int shaderBg;
    private Shader shader;

    public BackdropView(Context context) {
        super(context);
    }

    public void setArt(Bitmap art, String key) {
        String safeKey = key == null ? "" : key;
        if (art == sourceArt && safeKey.equals(this.key)) return;
        this.key = safeKey;
        sourceArt = art;
        Bitmap newTiny = null;
        if (art != null) {
            try {
                newTiny = Bitmap.createScaledBitmap(art, 20, 20, true);
            } catch (RuntimeException ignored) {
                newTiny = null;
            }
        }
        tiny = newTiny;
        Theme.setArtworkPalette(this.key, ArtworkPalette.extract(newTiny));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(Theme.bg);
        int w = getWidth();
        int h = getHeight();
        dst.set(0, 0, w, h);
        if (tiny != null) {
            imagePaint.setAlpha(Theme.dark ? 150 : 110);
            canvas.drawBitmap(tiny, null, dst, imagePaint);
        } else {
            String k = key.isEmpty() ? "syncora" : key;
            if (plainShader == null || !k.equals(plainKey) || plainWidth != w || plainHeight != h || plainBg != Theme.bg) {
                int[] colors = Theme.artColors(k);
                plainShader = new LinearGradient(0, 0, w, h * 0.7f, Theme.alpha(colors[0], Theme.dark ? 110 : 90),
                        Theme.alpha(colors[1], Theme.dark ? 60 : 50), Shader.TileMode.CLAMP);
                plainKey = k;
                plainWidth = w;
                plainHeight = h;
                plainBg = Theme.bg;
                plainPaint.setShader(plainShader);
            }
            canvas.drawRect(dst, plainPaint);
        }
        if (shader == null || shaderHeight != h || shaderBg != Theme.bg) {
            shader = new LinearGradient(0, 0, 0, h, new int[]{Theme.alpha(Theme.bg, 70), Theme.alpha(Theme.bg, 170), Theme.bg},
                    new float[]{0f, 0.45f, 0.92f}, Shader.TileMode.CLAMP);
            shaderHeight = h;
            shaderBg = Theme.bg;
        }
        gradientPaint.setShader(shader);
        canvas.drawRect(dst, gradientPaint);
    }
}
