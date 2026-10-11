package com.serena.syncora.ui;

import android.graphics.Bitmap;
import android.graphics.Color;

/** Lightweight dominant-colour sampler. It only examines the 20x20 bitmap already used by the backdrop. */
final class ArtworkPalette {
    private ArtworkPalette() {
    }

    static int[] extract(Bitmap sample) {
        if (sample == null || sample.getWidth() <= 0 || sample.getHeight() <= 0) return null;
        int width = sample.getWidth();
        int height = sample.getHeight();
        int[] bins = new int[512];
        int[] samples = new int[512];
        long[] red = new long[512];
        long[] green = new long[512];
        long[] blue = new long[512];
        float[] hsv = new float[3];
        long grayR = 0;
        long grayG = 0;
        long grayB = 0;
        int grayCount = 0;
        for (int y = 0; y < height; y += Math.max(1, height / 16)) {
            for (int x = 0; x < width; x += Math.max(1, width / 16)) {
                int color = sample.getPixel(x, y);
                if (Color.alpha(color) < 24) continue;
                int r = Color.red(color);
                int g = Color.green(color);
                int b = Color.blue(color);
                Color.colorToHSV(color, hsv);
                grayR += r;
                grayG += g;
                grayB += b;
                grayCount++;
                // Neutral and near-black pixels are useful for the base but poor accent colours.
                if (hsv[1] < 0.14f || hsv[2] < 0.10f || hsv[2] > 0.96f) continue;
                int bucket = ((r >> 5) << 6) | ((g >> 5) << 3) | (b >> 5);
                bins[bucket] += 1 + Math.round(hsv[1] * 2f);
                samples[bucket]++;
                red[bucket] += r;
                green[bucket] += g;
                blue[bucket] += b;
            }
        }
        int first = -1;
        for (int i = 0; i < bins.length; i++) if (first < 0 || bins[i] > bins[first]) first = i;
        int second = -1;
        for (int i = 0; i < bins.length; i++) {
            if (i == first || bins[i] == 0) continue;
            if (second < 0 || bins[i] > bins[second]) second = i;
        }
        if (first < 0 || bins[first] == 0) {
            if (grayCount == 0) return null;
            int neutral = Color.rgb((int) (grayR / grayCount), (int) (grayG / grayCount), (int) (grayB / grayCount));
            return new int[]{Theme.mix(neutral, Theme.accent, 0.35f), Theme.mix(neutral, Theme.bg, 0.20f)};
        }
        int primary = average(first, samples, red, green, blue);
        int secondary = second < 0 ? Theme.mix(primary, Color.WHITE, 0.28f) : average(second, samples, red, green, blue);
        if (colorDistance(primary, secondary) < 52) secondary = Theme.mix(primary, Color.WHITE, 0.34f);
        return new int[]{primary, secondary};
    }

    private static int average(int index, int[] samples, long[] r, long[] g, long[] b) {
        int denominator = Math.max(1, samples[index]);
        int red = (int) Math.max(0, Math.min(255, r[index] / denominator));
        int green = (int) Math.max(0, Math.min(255, g[index] / denominator));
        int blue = (int) Math.max(0, Math.min(255, b[index] / denominator));
        return Color.rgb(red, green, blue);
    }

    private static int colorDistance(int a, int b) {
        int dr = Color.red(a) - Color.red(b);
        int dg = Color.green(a) - Color.green(b);
        int db = Color.blue(a) - Color.blue(b);
        return (int) Math.sqrt(dr * dr + dg * dg + db * db);
    }
}
