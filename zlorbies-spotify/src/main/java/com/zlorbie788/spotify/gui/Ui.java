package com.zlorbie788.spotify.gui;

import com.zlorbie788.spotify.gui.widgets.Chrome;
import com.zlorbie788.spotify.gui.widgets.StyledButton;
import com.zlorbie788.spotify.gui.widgets.StyledToggle;
import com.zlorbie788.spotify.hud.Gfx;
import com.zlorbie788.spotify.hud.Theme;
import com.zlorbie788.spotify.util.Colors;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Small factories and drawing helpers shared by the player's screens. */
public final class Ui {
    private Ui() {}

    public static StyledButton button(int x, int y, int w, int h, String label, StyledButton.Press press) {
        return new StyledButton(x, y, w, h, Component.literal(label), press);
    }

    public static StyledToggle toggle(int x, int y, int w, int h, String label, BooleanSupplier get,
                                      Consumer<Boolean> set) {
        return new StyledToggle(x, y, w, h, label, get, set);
    }

    /** Framed group with a coloured title strip. Draw it before the widgets so they sit on top. */
    public static void card(GuiGraphics g, Font font, int x, int y, int w, int h, String title, String note, int accent) {
        int acc = Theme.accent(accent);
        Chrome.body(g, x, y, w, h, Colors.alpha(Colors.mix(Theme.well(), Theme.surface(), 0.3f), 0.70f),
                Colors.alpha(Theme.well(), 0.62f), Theme.line());
        Gfx.panel(g, x + 5, y + 4, 2.5f, 9, com.zlorbie788.spotify.config.SpotifyConfig.ShapeKind.ROUND, 1.2f,
                Colors.opaque(Colors.lighten(acc, 0.2f)), Colors.opaque(acc), 0, 0f);
        Gfx.drawStyled(g, font, title, x + 12, y + 4, Colors.opaque(acc), true, false, true);
        g.fillGradient(x + 6, y + 15, x + w / 2, y + 16, Colors.alpha(acc, 0.55f), Colors.alpha(acc, 0.0f));
        g.fillGradient(x + w / 2, y + 15, x + w - 6, y + 16, Colors.alpha(Theme.line(), 0.0f), Colors.alpha(Theme.line(), 0.8f));
        if (note != null && !note.isEmpty()) {
            int room = w - 16 - Gfx.width(font, title, true) - 10;
            if (room > 24) {
                String cut = Gfx.fit(font, note, room);
                Gfx.drawStyled(g, font, cut, x + w - 8 - Gfx.width(font, cut), y + 4, Colors.opaque(Theme.dim()), false, false, false);
            }
        }
    }

    public static void label(GuiGraphics g, Font font, String text, int x, int y) {
        Gfx.drawStyled(g, font, text, x, y, Colors.opaque(Theme.soft()), false, false, false);
    }

    public static void hint(GuiGraphics g, Font font, String text, int x, int y, int maxWidth) {
        Gfx.drawStyled(g, font, Gfx.fit(font, text, maxWidth), x, y, Colors.opaque(Theme.dim()), false, false, false);
    }

    /**
     * The round play / pause button that fades in over a cover when the mouse is on it. {@code amount} is 0..1 (the fade).
     * Drawn in the cover's own centre; the caller says where the cover is.
     */
    public static void coverButton(GuiGraphics g, int x, int y, int size, float amount, boolean playing, int accent) {
        if (amount <= 0.01f) return;
        float r = Math.max(12f, Math.min(22f, size * 0.17f)) * (0.85f + 0.15f * amount);
        float cx = x + size / 2f;
        float cy = y + size / 2f;
        com.zlorbie788.spotify.hud.Shapes.disc(g, Gfx.dev(), cx, cy, r + 3f, Colors.alpha(accent, 0.22f * amount));
        com.zlorbie788.spotify.hud.Shapes.disc(g, Gfx.dev(), cx, cy, r, Colors.alpha(0xFF05070D, 0.62f * amount));
        String[] icon = playing ? com.zlorbie788.spotify.hud.Icons.PAUSE : com.zlorbie788.spotify.hud.Icons.PLAY;
        int scale = r >= 16f ? 2 : 1;
        int iw = com.zlorbie788.spotify.hud.Icons.width(icon) * scale;
        int ih = com.zlorbie788.spotify.hud.Icons.height(icon) * scale;
        com.zlorbie788.spotify.hud.Icons.draw(g, icon, Math.round(cx - iw / 2f), Math.round(cy - ih / 2f), scale,
                Colors.alpha(Colors.WHITE, amount));
    }
}
