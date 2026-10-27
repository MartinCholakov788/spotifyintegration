package com.zlorbie788.spotify.hud;

import com.zlorbie788.spotify.config.SpotifyConfig;
import com.zlorbie788.spotify.media.Diagnosis;
import com.zlorbie788.spotify.util.Colors;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/** Draws the "Spotify not found" checklist (see {@link Diagnosis}). */
public final class HelpCard {
    private HelpCard() {}

    /** Height the checklist needs for these checks at full detail. */
    public static int height(List<Diagnosis.Check> checks) {
        int h = 0;
        for (Diagnosis.Check c : checks) h += rowHeight(c);
        return h;
    }

    private static int rowHeight(Diagnosis.Check c) {
        return c.level() == Diagnosis.Level.OK || c.hint().isEmpty() ? 12 : 23;
    }

    /**
     * Draws the checklist inside a box of {@code w} x {@code maxH} at (x, y). Rows that do not fit are left out
     * (the problems come first, the good news last). Returns the height used.
     */
    public static int draw(GuiGraphics g, Font f, SpotifyConfig cfg, List<Diagnosis.Check> checks, int x, int y, int w,
                           int maxH, int accent, float alpha) {
        int used = 0;
        // problems first, then what is still to do, then what is fine
        for (Diagnosis.Level level : new Diagnosis.Level[]{Diagnosis.Level.BAD, Diagnosis.Level.INFO, Diagnosis.Level.OK}) {
            for (Diagnosis.Check c : checks) {
                if (c.level() != level) continue;
                int rh = rowHeight(c);
                if (used + rh > maxH) {
                    // not enough room for the hint: still show the label alone
                    if (rh > 12 && used + 12 <= maxH) {
                        row(g, f, cfg, c, x, y + used, w, accent, alpha, false);
                        used += 12;
                    }
                    continue;
                }
                row(g, f, cfg, c, x, y + used, w, accent, alpha, true);
                used += rh;
            }
        }
        return used;
    }

    private static void row(GuiGraphics g, Font f, SpotifyConfig cfg, Diagnosis.Check c, int x, int y, int w, int accent,
                            float alpha, boolean hint) {
        int color = switch (c.level()) {
            case OK -> Colors.MINT;
            case BAD -> Colors.RED;
            default -> Colors.GOLD;
        };
        // a small badge: tick, cross or dot
        Gfx.disc(g, x + 4, y + 4, 4, Colors.alpha(color, 0.28f * alpha));
        int ink = Colors.alpha(color, alpha);
        int cx = x + 4;
        int cy = y + 4;
        switch (c.level()) {
            case OK -> {
                int[][] px = {{-2, 0}, {-1, 1}, {0, 2}, {1, 1}, {2, 0}, {3, -1}};
                for (int[] p : px) g.fill(cx + p[0], cy + p[1] - 1, cx + p[0] + 1, cy + p[1], ink);
            }
            case BAD -> {
                for (int i = -2; i <= 2; i++) {
                    g.fill(cx + i, cy + i, cx + i + 1, cy + i + 1, ink);
                    g.fill(cx + i, cy - i, cx + i + 1, cy - i + 1, ink);
                }
            }
            default -> g.fill(cx - 1, cy - 1, cx + 1, cy + 1, ink);
        }
        int tx = x + 13;
        int tw = w - 13;
        Gfx.text(g, f, Gfx.fit(f, c.label(), tw), tx, y, c.level() == Diagnosis.Level.OK ? Theme.soft() : Theme.text(),
                alpha, cfg.textShadow);
        if (hint && !c.hint().isEmpty() && c.level() != Diagnosis.Level.OK) {
            Gfx.text(g, f, Gfx.fit(f, c.hint(), tw), tx, y + 11, Theme.dim(), alpha, false);
        }
    }
}
