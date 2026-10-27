package com.zlorbie788.spotify.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * One option of the settings: its control(s), a short explanation under them and the words it can be found by.
 * The pages (see {@link SettingsPages}) create rows once; the screen places and draws them.
 */
final class SettingsRow {
    /** A control inside the row. The position is {@code x = fx * W + px}, {@code width = fw * W + pw}, {@code y = ry}. */
    record Cell(AbstractWidget widget, float fx, float fw, int px, int pw, int ry) {}

    /** Drawn by a row that is not made of ordinary controls (a font list, the equaliser, a status block...). */
    interface Draw {
        void draw(GuiGraphics g, Font f, int x, int y, int w, int mouseX, int mouseY);
    }

    /** Click on a custom row; coordinates are relative to the row. Returns true when used. */
    interface Click {
        boolean click(int rx, int ry, int w);
    }

    /** Height of a custom row that depends on the width. */
    interface Height {
        int of(int w);
    }

    final int group;
    final String section;
    final String label;
    final String help;
    /** Longer text shown as a tooltip on the controls (may be empty). */
    final String more;
    final List<Cell> cells = new ArrayList<>();
    final String keywords;

    int controlH = 15;
    Height heightOf;
    BooleanSupplier when = () -> true;
    /** The controls are greyed out (and ignore clicks) while this is false. */
    BooleanSupplier enabled = () -> true;
    /** Called while the mouse button is held after a click that this custom row accepted. */
    Click drag;
    Draw draw;
    Click click;

    // filled in by the screen
    List<String> helpLines = List.of();
    int cacheKey = Integer.MIN_VALUE;
    int y;
    int h;
    boolean shown;

    SettingsRow(int group, String section, String label, String help, String more, String groupName) {
        this.group = group;
        this.section = section;
        this.label = label;
        this.help = help == null ? "" : help;
        this.more = more == null ? "" : more;
        this.keywords = (label + " " + this.help + " " + this.more + " " + section + " " + groupName).toLowerCase(Locale.ROOT);
    }

    SettingsRow add(AbstractWidget w, float fx, float fw, int px, int pw, int ry) {
        cells.add(new Cell(w, fx, fw, px, pw, ry));
        return this;
    }

    SettingsRow when(BooleanSupplier b) {
        this.when = b;
        return this;
    }

    SettingsRow enabled(BooleanSupplier b) {
        this.enabled = b;
        return this;
    }

    boolean matches(String[] words) {
        for (String w : words) if (!keywords.contains(w)) return false;
        return true;
    }
}
