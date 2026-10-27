package com.zlorbie788.spotify.gui;

import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.config.LyricsConfig;
import com.zlorbie788.spotify.config.SpotifyConfig;
import com.zlorbie788.spotify.gui.widgets.Chrome;
import com.zlorbie788.spotify.hud.Gfx;
import com.zlorbie788.spotify.hud.LyricsLayout;
import com.zlorbie788.spotify.hud.LyricsView;
import com.zlorbie788.spotify.hud.Theme;
import com.zlorbie788.spotify.lyrics.Lyrics;
import com.zlorbie788.spotify.media.Player;
import com.zlorbie788.spotify.media.Snapshot;
import com.zlorbie788.spotify.util.Colors;
import com.zlorbie788.spotify.util.TextWrap;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/**
 * The lyrics inside the player screen: the same look as the on-screen lyrics (all the lyrics settings apply),
 * but as big as the room allows. Click a line to jump to it, use the chips at the bottom to shift the timing.
 */
public final class LyricsPanel {
    private static final int STRIP = 15;

    private final LyricsView view = new LyricsView();
    private Lyrics.Doc lastDoc;
    private int x;
    private int y;
    private int w;
    private int h;
    private int plainScroll;
    private Lyrics.Doc plainRowsFor;
    private int plainRowsW = -1;
    private List<String> plainRows = new java.util.ArrayList<>();
    private int retryX, retryY, retryW;

    public void layout(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    public boolean contains(double mx, double my) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ------------------------------------------------------------ drawing

    public void render(GuiGraphics g, Font font, int accent, int light, boolean playing) {
        SpotifyConfig cfg = ConfigIo.get();
        LyricsConfig lc = cfg.lyrics;
        Lyrics.need();
        Chrome.body(g, x, y, w, h, Colors.alpha(Theme.well(), cfg.highContrast ? 1f : 0.55f),
                Colors.alpha(Colors.mix(Theme.line(), accent, 0.25f), 1f));
        int bodyH = h - STRIP;
        Snapshot s = Player.snap();
        retryW = 0;

        if (!s.active() || s.isAd()) {
            centered(g, font, s.isAd() ? "No lyrics during ads" : "Nothing is playing", bodyH, Theme.dim());
        } else {
            switch (Lyrics.status()) {
                case READY -> drawLines(g, font, cfg, lc, accent, light, playing, bodyH);
                case INSTRUMENTAL -> centered(g, font, "Instrumental", bodyH, Theme.soft());
                case LOADING -> centered(g, font, "Looking for the lyrics...", bodyH, Theme.soft());
                case PLAIN -> drawPlain(g, font, bodyH);
                default -> {
                    String msg = Lyrics.status() == Lyrics.Status.ERROR ? "The lyrics service is not reachable"
                            : "No lyrics found for this song";
                    centered(g, font, msg, bodyH - 12, Theme.soft());
                    String again = "Try again";
                    retryW = Gfx.width(font, again) + 12;
                    retryX = x + (w - retryW) / 2;
                    retryY = y + bodyH / 2 + 4;
                    Chrome.body(g, retryX, retryY, retryW, 13, Colors.opaque(Theme.surface()), Colors.opaque(accent));
                    Gfx.drawStyled(g, font, again, retryX + 6, retryY + 3, Colors.opaque(accent), false, false, false);
                }
            }
        }
        drawStrip(g, font, lc, accent);
    }

    private void centered(GuiGraphics g, Font font, String text, int bodyH, int color) {
        String t = Gfx.fit(font, text, w - 16);
        Gfx.drawStyled(g, font, t, x + (w - Gfx.width(font, t)) / 2, y + (bodyH - 8) / 2, Colors.opaque(color), false, false, false);
    }

    private void drawLines(GuiGraphics g, Font font, SpotifyConfig cfg, LyricsConfig lc, int accent, int light,
                           boolean playing, int bodyH) {
        Lyrics.Doc doc = Lyrics.doc();
        if (doc != lastDoc) {
            view.reset();
            lastDoc = doc;
        }
        // Fit as many lines above and below as the room allows (restored right after drawing).
        int unit = LyricsLayout.unit(lc);
        int slot = unit + lc.lineGap;
        int current = Math.round(unit * lc.currentScale);
        int each = Math.max(1, Math.min(9, (bodyH - 2 * LyricsLayout.padY(lc) - current) / (2 * slot)));
        int oldBefore = lc.linesBefore;
        int oldAfter = lc.linesAfter;
        lc.linesBefore = each;
        lc.linesAfter = each;
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate((float) x, (float) y);
        try {
            view.draw(g, font, lc, doc, Lyrics.position(), Player.duration(), accent, light, playing, 1f, w, bodyH,
                    cfg.motion());
        } finally {
            pose.popMatrix();
            lc.linesBefore = oldBefore;
            lc.linesAfter = oldAfter;
        }
    }

    private void drawPlain(GuiGraphics g, Font font, int bodyH) {
        // every line is wrapped onto as many rows as it needs: nothing is cut off with "..."
        int textW = w - 20;
        if (plainRowsFor != Lyrics.doc() || plainRowsW != textW) {
            plainRowsFor = Lyrics.doc();
            plainRowsW = textW;
            plainRows = new java.util.ArrayList<>();
            for (String line : Lyrics.doc().plain()) {
                if (line == null || line.isBlank()) {
                    plainRows.add("");
                    continue;
                }
                plainRows.addAll(TextWrap.wrapAll(line, textW, t -> Gfx.width(font, t)));
            }
            plainScroll = 0;
        }
        List<String> lines = plainRows;
        int rowH = 10;
        int visible = Math.max(1, (bodyH - 8) / rowH);
        int max = Math.max(0, lines.size() - visible);
        plainScroll = Math.max(0, Math.min(max, plainScroll));
        g.enableScissor(x, y, x + w, y + bodyH);
        for (int i = 0; i < visible && plainScroll + i < lines.size(); i++) {
            Gfx.drawStyled(g, font, lines.get(plainScroll + i), x + 8, y + 5 + i * rowH, Colors.opaque(Theme.soft()), false, false, false);
        }
        g.disableScissor();
        if (max > 0) {
            int trackH = bodyH - 8;
            int thumb = Math.max(8, trackH * visible / lines.size());
            int ty = y + 4 + (trackH - thumb) * plainScroll / max;
            g.fill(x + w - 4, y + 4, x + w - 2, y + 4 + trackH, Colors.alpha(Theme.line(), 0.6f));
            g.fill(x + w - 4, ty, x + w - 2, ty + thumb, Colors.opaque(Theme.soft()));
        }
        String note = "Not timed";
        int nw = Gfx.width(font, note) + 6;
        g.fill(x + w - 8 - nw, y + bodyH - 11, x + w - 6, y + bodyH - 1, Colors.alpha(Theme.well(), 0.85f));
        Gfx.drawStyled(g, font, note, x + w - 8 - Gfx.width(font, note), y + bodyH - 10, Colors.opaque(Theme.dim()), false, false, false);
    }

    // The bottom strip: "-  Timing +0.3 s  +  reset"
    private int chipMinusX() { return x + 6; }
    private int chipPlusX() { return x + 6 + 14 + 70; }
    private int chipsY() { return y + h - STRIP + 1; }

    private void drawStrip(GuiGraphics g, Font font, LyricsConfig lc, int accent) {
        int cy = chipsY();
        g.fill(x + 4, y + h - STRIP, x + w - 4, y + h - STRIP + 1, Colors.alpha(Theme.line(), 0.8f));
        chip(g, font, chipMinusX(), cy, "-", accent);
        String t = "Timing " + String.format(java.util.Locale.ROOT, "%+.1f", lc.offsetMs / 1000f) + " s";
        Gfx.drawStyled(g, font, t, chipMinusX() + 18 + (66 - Gfx.width(font, t)) / 2, cy + 3, Colors.opaque(Theme.soft()), false, false, false);
        chip(g, font, chipPlusX(), cy, "+", accent);
        if (lc.offsetMs != 0) {
            String r = "reset";
            int rx = chipPlusX() + 18;
            Gfx.drawStyled(g, font, r, rx, cy + 3, Colors.opaque(Theme.dim()), false, false, false);
        }
        String src = Lyrics.status() == Lyrics.Status.READY ? "synced · lrclib.net" : "lrclib.net";
        String fitted = Gfx.fit(font, src, Math.max(0, w - (chipPlusX() - x) - 70));
        if (!fitted.isEmpty()) {
            Gfx.drawStyled(g, font, fitted, x + w - 6 - Gfx.width(font, fitted), cy + 3, Colors.opaque(Theme.dim()), false, false, false);
        }
    }

    private void chip(GuiGraphics g, Font font, int cx, int cy, String label, int accent) {
        Chrome.body(g, cx, cy, 13, 13, Colors.opaque(Theme.surface()), Colors.opaque(Colors.mix(Theme.line(), accent, 0.4f)));
        Gfx.drawStyled(g, font, label, cx + (13 - Gfx.width(font, label)) / 2 + 1, cy + 3, Colors.opaque(Theme.text()), false, false, false);
    }

    // ------------------------------------------------------------ input

    /** Returns true when the click was used. */
    public boolean click(double mx, double my) {
        if (!contains(mx, my)) return false;
        LyricsConfig lc = ConfigIo.get().lyrics;
        int cy = chipsY();
        if (my >= cy && my < cy + 13) {
            if (mx >= chipMinusX() && mx < chipMinusX() + 13) {
                lc.offsetMs = Math.max(-5000, lc.offsetMs - 100);
                return true;
            }
            if (mx >= chipPlusX() && mx < chipPlusX() + 13) {
                lc.offsetMs = Math.min(5000, lc.offsetMs + 100);
                return true;
            }
            if (lc.offsetMs != 0 && mx >= chipPlusX() + 18 && mx < chipPlusX() + 18 + 34) {
                lc.offsetMs = 0;
                return true;
            }
        }
        if (retryW > 0 && mx >= retryX && mx < retryX + retryW && my >= retryY && my < retryY + 13) {
            Lyrics.retry();
            return true;
        }
        if (Lyrics.status() == Lyrics.Status.READY && Player.canSeek()) {
            long ms = view.startAt((float) (my - y));
            if (ms >= 0) {
                Player.seekTo(Math.max(0, ms - lc.offsetMs));
                return true;
            }
        }
        return true;
    }

    public boolean scroll(double mx, double my, double dy) {
        if (!contains(mx, my) || Lyrics.status() != Lyrics.Status.PLAIN) return false;
        plainScroll -= (int) Math.signum(dy) * 3;
        return true;
    }
}
