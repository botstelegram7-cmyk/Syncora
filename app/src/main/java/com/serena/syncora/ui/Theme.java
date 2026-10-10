package com.serena.syncora.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;

import com.serena.syncora.Prefs;

/** Colour palettes. Call {@link #apply} whenever the theme preferences change. */
public final class Theme {
    public static final int[] ACCENTS = {0xFFC9F66B, 0xFF5CE1E6, 0xFFB794F6, 0xFFFFC857, 0xFFFF7A9A};
    private static final int[] ACCENTS_ON_LIGHT = {0xFF5F8F00, 0xFF008C99, 0xFF7048C4, 0xFFB86E00, 0xFFD6275E};
    public static final String[] ACCENT_NAMES = {"Lime", "Aqua", "Violet", "Amber", "Rose"};

    public static boolean dark = true;
    public static int bg = 0xFF0A0F0E;
    public static int surface = 0xFF111816;
    public static int surface2 = 0xFF18221F;
    public static int surface3 = 0xFF202C28;
    public static int line = 0x22FFFFFF;
    public static int text = 0xFFF2F6F0;
    public static int muted = 0xFFA3B0A8;
    public static int subtle = 0xFF6E7C74;
    public static int accent = ACCENTS[0];
    public static int accentHi = 0xFFE3FFA8;
    public static int accentSoft = 0x2EC9F66B;
    public static int onAccent = 0xFF0B100F;
    public static int danger = 0xFFFF6B6B;
    public static int good = 0xFF5AD58C;
    public static int warn = 0xFFFFB454;

    private Theme() {
    }

    public static void apply(Context context, Prefs prefs) {
        String mode = prefs.theme();
        boolean night = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        boolean light = Prefs.THEME_LIGHT.equals(mode) || (Prefs.THEME_SYSTEM.equals(mode) && !night);
        boolean amoled = Prefs.THEME_AMOLED.equals(mode);
        int index = Math.max(0, Math.min(ACCENTS.length - 1, prefs.accent()));
        dark = !light;
        if (light) {
            bg = 0xFFF3F6F1;
            surface = 0xFFFFFFFF;
            surface2 = 0xFFE9EEE8;
            surface3 = 0xFFDCE4DB;
            line = 0x1F000000;
            text = 0xFF101612;
            muted = 0xFF55635B;
            subtle = 0xFF8A978F;
            accent = ACCENTS_ON_LIGHT[index];
            accentHi = mix(accent, Color.WHITE, 0.25f);
            onAccent = 0xFFFFFFFF;
        } else {
            if (amoled) {
                bg = 0xFF000000;
                surface = 0xFF0B0E0D;
                surface2 = 0xFF141917;
                surface3 = 0xFF1C2320;
            } else {
                bg = 0xFF0A0F0E;
                surface = 0xFF111816;
                surface2 = 0xFF18221F;
                surface3 = 0xFF202C28;
            }
            line = 0x22FFFFFF;
            text = 0xFFF2F6F0;
            muted = 0xFFA3B0A8;
            subtle = 0xFF6E7C74;
            accent = ACCENTS[index];
            accentHi = mix(accent, Color.WHITE, 0.45f);
            onAccent = 0xFF0B100F;
        }
        accentSoft = alpha(accent, 0x2E);
    }

    public static int alpha(int color, int alpha255) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha255)) << 24);
    }

    public static int mix(int a, int b, float t) {
        float u = 1f - t;
        return Color.argb(
                Math.round(Color.alpha(a) * u + Color.alpha(b) * t),
                Math.round(Color.red(a) * u + Color.red(b) * t),
                Math.round(Color.green(a) * u + Color.green(b) * t),
                Math.round(Color.blue(a) * u + Color.blue(b) * t));
    }

    /** Pairs of colours used for generated artwork and avatars. */
    private static final int[][] ART = {
            {0xFF7F5AF0, 0xFF2CB67D}, {0xFFFF8906, 0xFFE53170}, {0xFF3DA9FC, 0xFF094067}, {0xFFEF4565, 0xFF7F5AF0},
            {0xFF2CB67D, 0xFF0F3D3E}, {0xFFF9A826, 0xFFB5179E}, {0xFF00B4D8, 0xFF7209B7}, {0xFFFF5D8F, 0xFFFFA62B}
    };

    public static int[] artColors(String key) {
        int h = key == null ? 0 : Math.abs(key.hashCode());
        return ART[h % ART.length];
    }

    public static int[] artColors(int index) {
        return ART[Math.abs(index) % ART.length];
    }
}
