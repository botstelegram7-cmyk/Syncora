package com.serena.syncora.lyrics;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Persistable lyric display options and the built-in, native-rendered appearance presets. */
public final class LyricsAppearance {
    public static final String CLASSIC = "classic";
    public static final String KARAOKE = "karaoke";
    public static final String CINEMATIC = "cinematic";
    public static final String MINIMAL = "minimal";
    public static final String DUAL = "dual";
    public static final String COMPACT = "compact";

    public static final String SANS = "sans";
    public static final String SERIF = "serif";
    public static final String CONDENSED = "condensed";
    public static final String MONO = "mono";

    public static final String BACKGROUND_ALBUM = "album";
    public static final String BACKGROUND_GRADIENT = "gradient";
    public static final String BACKGROUND_SOLID = "solid";
    public static final String BACKGROUND_CLEAR = "clear";

    /** Immutable preset. The renderer consumes the same values for the player page and fullscreen view. */
    public static final class Preset {
        public final String id;
        public final String name;
        public final String background;
        public final int backgroundStart;
        public final int backgroundEnd;
        public final int active;
        public final int inactive;
        public final int previous;
        public final int upcoming;
        public final boolean highContrast;

        private Preset(String id, String name, String background, int backgroundStart, int backgroundEnd,
                       int active, int inactive, int previous, int upcoming, boolean highContrast) {
            this.id = id;
            this.name = name;
            this.background = background;
            this.backgroundStart = backgroundStart;
            this.backgroundEnd = backgroundEnd;
            this.active = active;
            this.inactive = inactive;
            this.previous = previous;
            this.upcoming = upcoming;
            this.highContrast = highContrast;
        }
    }

    private static Preset preset(String id, String name, String background, int start, int end, int active,
                                 int inactive, int previous, int upcoming, boolean contrast) {
        return new Preset(id, name, background, start, end, active, inactive, previous, upcoming, contrast);
    }

    private static final Preset[] BUILT_INS = {
            preset("dark", "Dark", BACKGROUND_GRADIENT, 0xF2101514, 0xE00A0F0E, 0xFFE3FFA8, 0xFFD8DFDA,
                    0xFF86918B, 0xFFADB7B1, false),
            preset("amoled", "AMOLED Black", BACKGROUND_SOLID, 0xFF000000, 0xFF000000, 0xFFF5F7F5, 0xFFD3D8D5,
                    0xFF737A76, 0xFFB5BBB8, true),
            preset("neon", "Neon Pulse", BACKGROUND_GRADIENT, 0xF00C071B, 0xE0180B32, 0xFF72FFF2, 0xFFD6C9F3,
                    0xFF8A80A8, 0xFFB9A8D4, false),
            preset("midnight", "Midnight Purple", BACKGROUND_GRADIENT, 0xF018102D, 0xE00A0915, 0xFFC6A5FF, 0xFFE0D8EC,
                    0xFF887B98, 0xFFC0B3D0, false),
            preset("aurora", "Aurora Gradient", BACKGROUND_GRADIENT, 0xE0092730, 0xE014102D, 0xFF9DFFCE, 0xFFE3F2ED,
                    0xFF879B98, 0xFFBBD9D0, false),
            preset("minimal-white", "Minimal White", BACKGROUND_SOLID, 0xF7FFFFFF, 0xF7FFFFFF, 0xFF202622,
                    0xFF333B36, 0xFF909A93, 0xFF626C66, true),
            preset("sunset", "Sunset Glow", BACKGROUND_GRADIENT, 0xF02C111E, 0xE0141024, 0xFFFFB58C, 0xFFFFE5D8,
                    0xFFA4867B, 0xFFE4BFB0, false),
            preset("ocean", "Ocean Blue", BACKGROUND_GRADIENT, 0xF00A2039, 0xE0091121, 0xFF83D9FF, 0xFFE3F4FC,
                    0xFF829CAE, 0xFFBCD7E8, false),
            preset("cinematic", "Cinematic Blur", BACKGROUND_ALBUM, 0xBA000000, 0xE5000000, 0xFFF4F6F4,
                    0xFFD2D8D3, 0xFF808782, 0xFFB2BAB5, true),
            preset("retro", "Retro Music Player", BACKGROUND_GRADIENT, 0xF0221825, 0xE00F1116, 0xFFFFC857,
                    0xFFF4EADA, 0xFF9E9176, 0xFFCCB98D, false),
            preset("karaoke", "Classic Karaoke", BACKGROUND_CLEAR, 0x00000000, 0x00000000, 0xFFC9F66B,
                    0xFFE5EAE6, 0xFF7D8981, 0xFFB4BDB7, false),
            preset("dynamic", "Dynamic Album Art", BACKGROUND_ALBUM, 0xB8000000, 0xD9000000, 0xFFFFFFFF,
                    0xFFE4E8E5, 0xFF848B86, 0xFFC1C7C2, true)
    };

    public static final List<Preset> PRESETS = Collections.unmodifiableList(Arrays.asList(BUILT_INS));

    public String mode = KARAOKE;
    public String themeId = "dynamic";
    public String themeName = "";
    public String fontFamily = SANS;
    public String background = ""; // empty = use the selected preset
    public int fontSizeSp = 21;
    public int lineSpacingPercent = 140;
    public int letterSpacingPercent = 0;
    public int activeColor = 0; // 0 = use preset
    public int inactiveColor = 0;
    public int backgroundColor = 0;
    public boolean animations = true;
    public boolean reducedMotion;
    public boolean boldActive = true;
    public boolean uppercase;
    public boolean showTranslation;
    public boolean showRomanization;
    public boolean highContrast;

    public LyricsAppearance copy() {
        LyricsAppearance copy = new LyricsAppearance();
        copy.mode = mode;
        copy.themeId = themeId;
        copy.themeName = themeName;
        copy.fontFamily = fontFamily;
        copy.background = background;
        copy.fontSizeSp = fontSizeSp;
        copy.lineSpacingPercent = lineSpacingPercent;
        copy.letterSpacingPercent = letterSpacingPercent;
        copy.activeColor = activeColor;
        copy.inactiveColor = inactiveColor;
        copy.backgroundColor = backgroundColor;
        copy.animations = animations;
        copy.reducedMotion = reducedMotion;
        copy.boldActive = boldActive;
        copy.uppercase = uppercase;
        copy.showTranslation = showTranslation;
        copy.showRomanization = showRomanization;
        copy.highContrast = highContrast;
        return copy;
    }

    public Preset preset() {
        for (Preset preset : BUILT_INS) if (preset.id.equals(themeId)) return preset;
        return BUILT_INS[BUILT_INS.length - 1];
    }

    public String themeLabel() {
        if ("custom".equals(themeId) && themeName != null && !themeName.trim().isEmpty()) return themeName.trim();
        return preset().name;
    }

    public String effectiveBackground() {
        return background == null || background.isEmpty() ? preset().background : background;
    }

    public int effectiveActiveColor() {
        return activeColor == 0 ? preset().active : activeColor;
    }

    public int effectiveInactiveColor() {
        return inactiveColor == 0 ? preset().inactive : inactiveColor;
    }

    public static String modeLabel(String mode) {
        if (CINEMATIC.equals(mode)) return "Cinematic";
        if (MINIMAL.equals(mode)) return "Minimal";
        if (DUAL.equals(mode)) return "Dual lyrics";
        if (COMPACT.equals(mode)) return "Compact";
        if (KARAOKE.equals(mode)) return "Karaoke";
        return "Classic";
    }

    public static String fontLabel(String family) {
        if (SERIF.equals(family)) return "Serif";
        if (CONDENSED.equals(family)) return "Condensed";
        if (MONO.equals(family)) return "Monospace";
        return "Sans-serif";
    }
}
