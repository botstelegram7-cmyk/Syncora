package com.serena.syncora.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.serena.syncora.Prefs;
import com.serena.syncora.R;
import com.serena.syncora.SessionState;
import com.serena.syncora.lyrics.LrcParser;
import com.serena.syncora.lyrics.Lyrics;
import com.serena.syncora.lyrics.LyricsAppearance;

import java.util.ArrayList;
import java.util.List;

/** A native live editor; the preview is the same LyricsView used by the real player and fullscreen lyrics. */
final class LyricsEditorSheet {
    private static final String SAMPLE = "[00:00.00]<00:00.00>Make the words <00:00.65>follow the <00:01.05>music\n"
            + "[00:02.10]Line timing stays honest\n[00:04.20]<00:04.20>Tap a <00:04.55>line to seek";

    private LyricsEditorSheet() {
    }

    static void show(final UiHost host) {
        final Prefs prefs = host.prefs();
        final LyricsAppearance[] current = {prefs.lyricsAppearance()};
        final List<LyricsAppearance> history = new ArrayList<>();
        history.add(current[0].copy());
        final int[] historyIndex = {0};

        final Sheet sheet = new Sheet(host.activity(), "Lyrics & Appearance",
                "Your changes preview instantly and are saved on this device.", host.bottomInset(), host.maxSheetHeight());
        final Context context = sheet.context();
        LinearLayout body = sheet.body();

        final LyricsView preview = new LyricsView(context);
        final Lyrics sample = LrcParser.parse(SAMPLE, "Live preview · sample timestamps");
        FrameLayout previewFrame = new FrameLayout(context);
        previewFrame.setBackground(Ui.stroke(Theme.surface2, Theme.line, 1, 18));
        previewFrame.setClipToOutline(true);
        BackdropView previewBackdrop = new BackdropView(context);
        SessionState previewState = host.state();
        previewBackdrop.setArt(previewState.art, previewState.artKey.isEmpty() ? previewState.title : previewState.artKey);
        previewFrame.addView(previewBackdrop, Ui.frame(Ui.MATCH, Ui.MATCH));
        previewFrame.addView(preview, Ui.frame(Ui.MATCH, Ui.MATCH));
        body.addView(previewFrame, Ui.lin(Ui.MATCH, Ui.dp(200), 0, 0, 0, 12));

        final TextView[] modeValueHolder = {null};
        modeValueHolder[0] = actionRow(context, body, R.drawable.ic_note, "Display mode", "Karaoke",
                "Word highlight is used only when the source includes word times", new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        String[] modes = {LyricsAppearance.KARAOKE, LyricsAppearance.CLASSIC, LyricsAppearance.CINEMATIC,
                                LyricsAppearance.MINIMAL, LyricsAppearance.DUAL, LyricsAppearance.COMPACT};
                        current[0].mode = next(modes, current[0].mode);
                        commit(host, prefs, current, history, historyIndex, preview, sample, modeValueHolder[0], null, null, null);
                    }
                });

        final TextView[] themeValueHolder = {null};
        themeValueHolder[0] = actionRow(context, body, R.drawable.ic_palette, "Theme", "Dynamic Album Art",
                "12 built-in palettes with readable foreground colours", new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        String[] themes = new String[LyricsAppearance.PRESETS.size()];
                        for (int i = 0; i < themes.length; i++) themes[i] = LyricsAppearance.PRESETS.get(i).id;
                        if ("custom".equals(current[0].themeId)) current[0].themeId = themes[0];
                        else current[0].themeId = next(themes, current[0].themeId);
                        current[0].themeName = "";
                        current[0].activeColor = 0;
                        current[0].inactiveColor = 0;
                        current[0].backgroundColor = 0;
                        current[0].background = "";
                        commit(host, prefs, current, history, historyIndex, preview, sample, modeValueHolder[0], themeValueHolder[0], null, null);
                    }
                });

        final TextView[] fontValueHolder = {null};
        fontValueHolder[0] = actionRow(context, body, R.drawable.ic_tune, "Font family", "Sans-serif",
                "Responsive system fonts; no external font download", new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        String[] fonts = {LyricsAppearance.SANS, LyricsAppearance.SERIF,
                                LyricsAppearance.CONDENSED, LyricsAppearance.MONO};
                        current[0].fontFamily = next(fonts, current[0].fontFamily);
                        commit(host, prefs, current, history, historyIndex, preview, sample, modeValueHolder[0], themeValueHolder[0], fontValueHolder[0], null);
                    }
                });

        final TextView[] backgroundValueHolder = {null};
        backgroundValueHolder[0] = actionRow(context, body, R.drawable.ic_palette, "Background", "Theme default",
                "Album art uses the current player artwork behind a contrast veil", new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        String[] backgrounds = {"", LyricsAppearance.BACKGROUND_ALBUM, LyricsAppearance.BACKGROUND_GRADIENT,
                                LyricsAppearance.BACKGROUND_SOLID, LyricsAppearance.BACKGROUND_CLEAR};
                        current[0].background = nextIncludingEmpty(backgrounds, current[0].background);
                        commit(host, prefs, current, history, historyIndex, preview, sample, modeValueHolder[0], themeValueHolder[0], fontValueHolder[0], backgroundValueHolder[0]);
                    }
                });

        LinearLayout typography = section(context, body, "Typography & timing");
        addStepper(context, typography, "Font size", "sp", new StepListener() {
            @Override
            public void step(int amount) {
                current[0].fontSizeSp = Math.max(14, Math.min(44, current[0].fontSizeSp + amount));
                commit(host, prefs, current, history, historyIndex, preview, sample, modeValueHolder[0], themeValueHolder[0], fontValueHolder[0], backgroundValueHolder[0]);
            }

            @Override
            public String value() {
                return current[0].fontSizeSp + " sp";
            }
        }, -1, 1);
        addStepper(context, typography, "Line spacing", "%", new StepListener() {
            @Override
            public void step(int amount) {
                current[0].lineSpacingPercent = Math.max(90, Math.min(220, current[0].lineSpacingPercent + amount));
                commit(host, prefs, current, history, historyIndex, preview, sample, modeValueHolder[0], themeValueHolder[0], fontValueHolder[0], backgroundValueHolder[0]);
            }

            @Override
            public String value() {
                return current[0].lineSpacingPercent + " %";
            }
        }, -10, 10);
        addStepper(context, typography, "Letter spacing", "%", new StepListener() {
            @Override
            public void step(int amount) {
                current[0].letterSpacingPercent = Math.max(-5, Math.min(20, current[0].letterSpacingPercent + amount));
                commit(host, prefs, current, history, historyIndex, preview, sample, modeValueHolder[0], themeValueHolder[0], fontValueHolder[0], backgroundValueHolder[0]);
            }

            @Override
            public String value() {
                return current[0].letterSpacingPercent + " %";
            }
        }, -1, 1);

        LinearLayout colors = section(context, body, "Karaoke active colour");
        LinearLayout swatches = new LinearLayout(context);
        swatches.setGravity(Gravity.CENTER_VERTICAL);
        swatches.setPadding(Ui.dp(8), Ui.dp(8), Ui.dp(8), Ui.dp(8));
        for (int i = 0; i < Theme.ACCENTS.length; i++) {
            final int color = Theme.ACCENTS[i];
            View swatch = new View(context);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(color);
            dot.setStroke(Ui.dp(2), Theme.alpha(Theme.text, 100));
            swatch.setBackground(dot);
            swatch.setContentDescription("Active lyric colour " + Theme.ACCENT_NAMES[i]);
            swatch.setFocusable(true);
            swatch.setClickable(true);
            swatch.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    current[0].activeColor = color;
                    commit(host, prefs, current, history, historyIndex, preview, sample, modeValueHolder[0], themeValueHolder[0], fontValueHolder[0], backgroundValueHolder[0]);
                }
            });
            swatches.addView(swatch, Ui.lin(Ui.dp(36), Ui.dp(36), 0, 0, Ui.dp(8), 0));
        }
        colors.addView(swatches, Ui.lin(Ui.MATCH, Ui.WRAP));

        LinearLayout toggles = section(context, body, "Accessibility & layers");
        addToggle(context, toggles, "Animations", "Smooth line transitions; never affects audio", current[0].animations,
                new ToggleListener() {
                    @Override
                    public void changed(boolean checked) {
                        current[0].animations = checked;
                        commit(host, prefs, current, history, historyIndex, preview, sample, modeValueHolder[0], themeValueHolder[0], fontValueHolder[0], backgroundValueHolder[0]);
                    }
                });
        addToggle(context, toggles, "Reduced motion", "Turn off lyric movement and emphasis animations", current[0].reducedMotion,
                new ToggleListener() {
                    @Override
                    public void changed(boolean checked) {
                        current[0].reducedMotion = checked;
                        commit(host, prefs, current, history, historyIndex, preview, sample, modeValueHolder[0], themeValueHolder[0], fontValueHolder[0], backgroundValueHolder[0]);
                    }
                });
        addToggle(context, toggles, "High contrast", "Strengthen the current line without relying on colour alone", current[0].highContrast,
                new ToggleListener() {
                    @Override
                    public void changed(boolean checked) {
                        current[0].highContrast = checked;
                        commit(host, prefs, current, history, historyIndex, preview, sample, modeValueHolder[0], themeValueHolder[0], fontValueHolder[0], backgroundValueHolder[0]);
                    }
                });
        addToggle(context, toggles, "Bold active line", "Use weight and a marker in addition to lyric colour", current[0].boldActive,
                new ToggleListener() {
                    @Override
                    public void changed(boolean checked) {
                        current[0].boldActive = checked;
                        commit(host, prefs, current, history, historyIndex, preview, sample, modeValueHolder[0], themeValueHolder[0], fontValueHolder[0], backgroundValueHolder[0]);
                    }
                });
        addToggle(context, toggles, "Uppercase lyrics", "Transform displayed text only", current[0].uppercase,
                new ToggleListener() {
                    @Override
                    public void changed(boolean checked) {
                        current[0].uppercase = checked;
                        commit(host, prefs, current, history, historyIndex, preview, sample, modeValueHolder[0], themeValueHolder[0], fontValueHolder[0], backgroundValueHolder[0]);
                    }
                });
        addToggle(context, toggles, "Show translation", "Shown only when a real translation is available", current[0].showTranslation,
                new ToggleListener() {
                    @Override
                    public void changed(boolean checked) {
                        current[0].showTranslation = checked;
                        commit(host, prefs, current, history, historyIndex, preview, sample, modeValueHolder[0], themeValueHolder[0], fontValueHolder[0], backgroundValueHolder[0]);
                    }
                });
        addToggle(context, toggles, "Show romanization", "Shown only when a real romanized layer is available", current[0].showRomanization,
                new ToggleListener() {
                    @Override
                    public void changed(boolean checked) {
                        current[0].showRomanization = checked;
                        commit(host, prefs, current, history, historyIndex, preview, sample, modeValueHolder[0], themeValueHolder[0], fontValueHolder[0], backgroundValueHolder[0]);
                    }
                });

        LinearLayout historyButtons = new LinearLayout(context);
        TextView undo = Ui.secondaryButton(context, "Undo");
        TextView redo = Ui.secondaryButton(context, "Redo");
        TextView reset = Ui.secondaryButton(context, "Reset lyrics");
        historyButtons.addView(undo, Ui.linW(1f, Ui.WRAP, 0, 0, 4, 0));
        historyButtons.addView(redo, Ui.linW(1f, Ui.WRAP, 4, 0, 4, 0));
        historyButtons.addView(reset, Ui.linW(1f, Ui.WRAP, 4, 0, 0, 0));
        body.addView(historyButtons, Ui.lin(Ui.MATCH, Ui.WRAP, 0, Ui.dp(16), 0, Ui.dp(8)));
        undo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (historyIndex[0] <= 0) return;
                historyIndex[0]--;
                current[0] = history.get(historyIndex[0]).copy();
                persistAndPreview(host, prefs, current[0], preview, sample, modeValueHolder[0], themeValueHolder[0], fontValueHolder[0], backgroundValueHolder[0]);
            }
        });
        redo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (historyIndex[0] >= history.size() - 1) return;
                historyIndex[0]++;
                current[0] = history.get(historyIndex[0]).copy();
                persistAndPreview(host, prefs, current[0], preview, sample, modeValueHolder[0], themeValueHolder[0], fontValueHolder[0], backgroundValueHolder[0]);
            }
        });
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                prefs.resetLyricsAppearance();
                current[0] = prefs.lyricsAppearance();
                history.clear();
                history.add(current[0].copy());
                historyIndex[0] = 0;
                persistAndPreview(host, prefs, current[0], preview, sample, modeValueHolder[0], themeValueHolder[0], fontValueHolder[0], backgroundValueHolder[0]);
                host.toast("Lyrics appearance restored. Other settings were not changed.");
            }
        });

        TextView saveCustom = Ui.primaryButton(context, "Save current style as a custom theme");
        body.addView(saveCustom, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 0, 0, 0));
        saveCustom.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                final LyricsAppearance copy = current[0].copy();
                String selectedBackground = copy.effectiveBackground();
                int selectedActive = copy.effectiveActiveColor();
                int selectedInactive = copy.effectiveInactiveColor();
                int selectedBackgroundColor = copy.backgroundColor == 0 ? copy.preset().backgroundStart : copy.backgroundColor;
                copy.themeId = "custom";
                copy.themeName = copy.themeName == null || copy.themeName.isEmpty() ? "My lyrics theme" : copy.themeName;
                copy.background = selectedBackground;
                copy.activeColor = selectedActive;
                copy.inactiveColor = selectedInactive;
                copy.backgroundColor = selectedBackgroundColor;
                sheet.dismiss();
                Sheets.input(host, "Save custom theme", "Name this reusable lyrics style", "My theme", copy.themeName,
                        Sheets.textInput(), "Save", new Sheets.TextListener() {
                            @Override
                            public void onText(String text) {
                                if (text.trim().isEmpty()) return;
                                copy.themeName = text.trim();
                                current[0] = copy;
                                prefs.setLyricsAppearance(copy);
                                host.toast("Custom lyrics theme saved.");
                                LyricsEditorSheet.show(host);
                            }
                        });
            }
        });

        // A real shared renderer and source timestamp data make the preview representative; its position is fixed
        // only in this editor and is never connected to (or allowed to seek) actual playback.
        persistAndPreview(host, prefs, current[0], preview, sample, modeValueHolder[0], themeValueHolder[0], fontValueHolder[0], backgroundValueHolder[0]);
        host.showSheet(sheet);
    }

    private interface StepListener {
        void step(int amount);

        String value();
    }

    private interface ToggleListener {
        void changed(boolean checked);
    }

    private static void commit(UiHost host, Prefs prefs, LyricsAppearance[] current,
                               List<LyricsAppearance> history, int[] historyIndex, LyricsView preview, Lyrics sample,
                               TextView modeValue, TextView themeValue, TextView fontValue, TextView backgroundValue) {
        if (historyIndex[0] < history.size() - 1) history.subList(historyIndex[0] + 1, history.size()).clear();
        history.add(current[0].copy());
        if (history.size() > 24) history.remove(0);
        historyIndex[0] = history.size() - 1;
        persistAndPreview(host, prefs, current[0], preview, sample, modeValue, themeValue, fontValue, backgroundValue);
    }

    private static void persistAndPreview(UiHost host, Prefs prefs, LyricsAppearance appearance, LyricsView preview,
                                          Lyrics sample, TextView mode, TextView theme, TextView font, TextView background) {
        prefs.setLyricsAppearance(appearance);
        if (mode != null) mode.setText(LyricsAppearance.modeLabel(appearance.mode));
        if (theme != null) theme.setText(appearance.themeLabel());
        if (font != null) font.setText(LyricsAppearance.fontLabel(appearance.fontFamily));
        if (background != null) background.setText(backgroundLabel(appearance));
        preview.setAppearance(appearance);
        preview.show("lyrics-editor-preview", sample, "");
        preview.setPosition(750);
        if (host.service() != null) host.service().lyricsAppearanceChanged();
    }

    private static String next(String[] values, String current) {
        int index = -1;
        for (int i = 0; i < values.length; i++) if (values[i].equals(current)) index = i;
        return values[(index + 1) % values.length];
    }

    private static String nextIncludingEmpty(String[] values, String current) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(current)) return values[(i + 1) % values.length];
        return values[0];
    }

    private static String backgroundLabel(LyricsAppearance appearance) {
        String style = appearance.effectiveBackground();
        if (LyricsAppearance.BACKGROUND_ALBUM.equals(style)) return "Album art";
        if (LyricsAppearance.BACKGROUND_GRADIENT.equals(style)) return "Gradient";
        if (LyricsAppearance.BACKGROUND_SOLID.equals(style)) return "Solid";
        if (LyricsAppearance.BACKGROUND_CLEAR.equals(style)) return "Transparent";
        return "Theme default";
    }

    private static TextView actionRow(Context context, LinearLayout body, int icon, String title, String value,
                                      String subtitle, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(12), Ui.dp(12), Ui.dp(12), Ui.dp(12));
        row.setBackground(Ui.ripple(Ui.rect(Theme.surface2, 14), 14));
        row.setClickable(true);
        row.setFocusable(true);
        row.addView(Ui.icon(context, icon, 20, Theme.accent), Ui.lin(Ui.dp(22), Ui.dp(22), 0, 0, 10, 0));
        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(Ui.text(context, title, 14, Theme.text, Ui.MEDIUM), Ui.lin(Ui.MATCH, Ui.WRAP));
        if (subtitle != null && !subtitle.isEmpty()) labels.addView(Ui.text(context, subtitle, 11, Theme.subtle, Ui.REGULAR),
                Ui.lin(Ui.MATCH, Ui.WRAP, 0, 2, 0, 0));
        row.addView(labels, Ui.lin(0, Ui.WRAP, 1f));
        TextView current = Ui.text(context, value, 12, Theme.accent, Ui.MEDIUM);
        current.setGravity(Gravity.END);
        current.setContentDescription(title + ": " + value + ". Tap to change.");
        row.addView(current, Ui.lin(Ui.WRAP, Ui.WRAP, 6, 0, 6, 0));
        row.addView(Ui.icon(context, R.drawable.ic_chevron, 18, Theme.subtle), Ui.lin(Ui.dp(18), Ui.dp(18)));
        row.setOnClickListener(click);
        body.addView(row, Ui.lin(Ui.MATCH, Ui.WRAP, 0, 0, 0, 6));
        return current;
    }

    private static LinearLayout section(Context context, LinearLayout body, String title) {
        TextView label = Ui.label(context, title);
        body.addView(label, Ui.lin(Ui.MATCH, Ui.WRAP, 2, 14, 0, 7));
        LinearLayout block = new LinearLayout(context);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setBackground(Ui.card(16));
        body.addView(block, Ui.lin(Ui.MATCH, Ui.WRAP));
        return block;
    }

    private static void addStepper(Context context, LinearLayout parent, String label, String unit,
                                   final StepListener listener, int decrement, int increment) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(12), Ui.dp(8), Ui.dp(12), Ui.dp(8));
        TextView title = Ui.text(context, label, 13, Theme.text, Ui.MEDIUM);
        row.addView(title, Ui.lin(0, Ui.WRAP, 1f));
        TextView minus = Ui.secondaryButton(context, "−");
        row.addView(minus, Ui.lin(Ui.dp(40), Ui.dp(38), 0, 0, 5, 0));
        TextView value = Ui.text(context, "", 13, Theme.accent, Ui.MEDIUM);
        value.setGravity(Gravity.CENTER);
        row.addView(value, Ui.lin(Ui.dp(52), Ui.WRAP));
        TextView plus = Ui.secondaryButton(context, "+");
        row.addView(plus, Ui.lin(Ui.dp(40), Ui.dp(38), 5, 0, 0, 0));
        value.setText(listener.value());
        parent.addView(row, Ui.lin(Ui.MATCH, Ui.WRAP));
        minus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                listener.step(decrement);
                value.setText(listener.value());
            }
        });
        plus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                listener.step(increment);
                value.setText(listener.value());
            }
        });
        row.setContentDescription(label + " controls");
    }

    private static void addToggle(Context context, LinearLayout parent, String title, String subtitle,
                                  boolean checked, final ToggleListener listener) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(12), Ui.dp(10));
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.text(context, title, 13, Theme.text, Ui.MEDIUM), Ui.lin(Ui.MATCH, Ui.WRAP));
        texts.addView(Ui.text(context, subtitle, 11, Theme.subtle, Ui.REGULAR), Ui.lin(Ui.MATCH, Ui.WRAP, 0, 2, 0, 0));
        row.addView(texts, Ui.lin(0, Ui.WRAP, 1f));
        final ToggleView toggle = new ToggleView(context);
        toggle.setChecked(checked, false);
        toggle.setListener(new ToggleView.Listener() {
            @Override
            public void onChanged(boolean value) {
                listener.changed(value);
            }
        });
        row.addView(toggle, Ui.lin(Ui.WRAP, Ui.WRAP, 8, 0, 0, 0));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toggle.performClick();
            }
        });
        row.setContentDescription(title + ". " + subtitle);
        parent.addView(row, Ui.lin(Ui.MATCH, Ui.WRAP));
    }
}
