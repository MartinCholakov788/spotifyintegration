package com.zlorbie788.spotify.hud;

import com.zlorbie788.spotify.config.SpotifyConfig.ShapeKind;
import com.zlorbie788.spotify.config.UiFont;
import com.zlorbie788.spotify.util.Clock;
import com.zlorbie788.spotify.util.Colors;
import com.zlorbie788.spotify.util.Ease;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Drawing helpers shared by the HUD, the player screens and the settings: smooth shapes, text in the chosen font, bars.
 *
 * <p>Two pieces of state live here, set by whoever draws a screen or the HUD: the device scale (how many real pixels one
 * drawing unit is, so shapes can be sharp) and the current font (so every text call writes in it).
 */
public final class Gfx {
    private static float dev = 0f;
    private static UiFont font = UiFont.MINECRAFT;
    private static int fontShift;

    private Gfx() {}

    // ------------------------------------------------------------ state

    /** Device pixels per drawing unit. Defaults to the GUI scale. */
    public static float dev() {
        if (dev > 0f) return dev;
        Minecraft mc = Minecraft.getInstance();
        return mc == null ? 1f : Math.max(1f, (float) mc.getWindow().getGuiScale());
    }

    /** Sets the device scale (0 = use the GUI scale again). Returns the previous value so it can be put back. */
    public static float setDev(float value) {
        float old = dev;
        dev = value;
        return old;
    }

    public static UiFont font() {
        return font;
    }

    public static int fontShift() {
        return fontShift;
    }

    /** Selects the font for the text drawn from now on. Put the old one back (see {@link #font()}) when done. */
    public static void useFont(UiFont f, int shift) {
        font = f == null || !f.works() ? UiFont.MINECRAFT : f;
        fontShift = font.isDefault() ? 0 : shift;
    }

    // ------------------------------------------------------------ shapes

    /** Corner size in pixels of the four 1.2-style corner levels (0 = square). */
    private static final float[] LEVEL_RADIUS = {0f, 3f, 6f, 10f};

    public static float levelRadius(int level) {
        return LEVEL_RADIUS[Math.max(0, Math.min(3, level))];
    }

    /** Old-style corner table (kept for the screens that have not moved to {@link #panel} yet). */
    public static int[] corner(int level) {
        int r = Math.round(levelRadius(level));
        return new int[]{r};
    }

    /** Smooth rounded box (level 0..3 picks the corner size). Pass edge = 0 for no border, fillTop = 0 for no fill. */
    public static void box(GuiGraphics g, int x, int y, int w, int h, int level, int fillTop, int fillBottom, int edge) {
        panel(g, x, y, w, h, ShapeKind.ROUND, levelRadius(level), fillTop, fillBottom, edge, 1f);
    }

    /** Any shape, any corner size, border thickness in pixels. */
    public static void panel(GuiGraphics g, float x, float y, float w, float h, ShapeKind kind, float radius, int fillTop,
                             int fillBottom, int edge, float border) {
        float r = Math.min(radius, Math.min(w, h) / 2f);
        Shapes.panel(g, dev(), x, y, w, h, kind, r, fillTop, fillBottom, edge, border);
    }

    /** Filled disc. */
    public static void disc(GuiGraphics g, int cx, int cy, int radius, int color) {
        Shapes.disc(g, dev(), cx, cy, radius, color);
    }

    /** Soft light around a box. */
    public static void halo(GuiGraphics g, int x, int y, int w, int h, int color, float strength) {
        Shapes.glow(g, dev(), x, y, w, h, ShapeKind.ROUND, 3f, color, strength);
    }

    // ------------------------------------------------------------ accent line

    /** The two pixel line along the top edge (see the longer overload). */
    public static void accentLine(GuiGraphics g, int x, int y, int w, int thickness, int accent, boolean rainbow,
                                  float phase, boolean motion, float alpha) {
        accentLine(g, x, y, w, thickness, accent, rainbow, phase, motion, alpha, 0f);
    }

    /**
     * A line that fades out at both ends, with a bright highlight sweeping along it (rainbow mode: the colour wheel
     * scrolling). {@code boost} (0..1) widens and brightens the highlight and adds a faint glow under the line: it flashes
     * on every beat.
     */
    public static void accentLine(GuiGraphics g, int x, int y, int w, int thickness, int accent, boolean rainbow,
                                  float phase, boolean motion, float alpha, float boost) {
        if (w <= 0) return;
        // about 64 pieces however wide the line is (the colour change along it is slow)
        int step = Math.max(2, Math.round(w / 64f));
        float light = Ease.clamp01(phase);
        boost = Ease.clamp01(boost);
        for (int px = 0; px < w; px += step) {
            int end = Math.min(w, px + step);
            float t = (px + 1f) / w;
            float edge = Math.min(1f, Math.min(t, 1f - t) / 0.16f);
            edge = edge * edge * (3f - 2f * edge);
            int color;
            if (rainbow) {
                color = Colors.rainbow(t - phase);
                if (boost > 0f) color = Colors.lighten(color, 0.4f * boost);
            } else if (motion) {
                float d = Math.abs(t - (light * 1.5f - 0.25f));
                float hi = Math.max(0f, 1f - d * (4f - 1.6f * boost));
                color = Colors.lighten(accent, Math.min(0.9f, 0.55f * hi + 0.3f * boost));
            } else {
                color = accent;
            }
            g.fill(x + px, y, x + end, y + thickness, Colors.alpha(color, alpha * (0.25f + 0.75f * edge)));
        }
        if (boost > 0.03f) {
            g.fill(x + w / 8, y + thickness, x + w - w / 8, y + thickness + 1, Colors.alpha(accent, 0.34f * boost * alpha));
        }
    }

    // ------------------------------------------------------------ text

    /** Text in the current font, with optional bold / italic. */
    public static MutableComponent make(String s, boolean bold, boolean italic) {
        MutableComponent c = Component.literal(s == null ? "" : s);
        if (!font.isDefault()) c = c.withStyle(font.style());
        if (bold) c = c.withStyle(ChatFormatting.BOLD);
        if (italic) c = c.withStyle(ChatFormatting.ITALIC);
        return c;
    }

    public static Component bold(String s) {
        return make(s, true, false);
    }

    /** Text that may be bold and / or italic (the lyrics use it). */
    public static Component styled(String s, boolean bold, boolean italic) {
        return make(s, bold, italic);
    }

    private static boolean plain(boolean bold, boolean italic) {
        return font.isDefault() && !bold && !italic;
    }

    public static int width(Font f, String s) {
        return width(f, s, false, false);
    }

    public static int width(Font f, String s, boolean bold) {
        return width(f, s, bold, false);
    }

    public static int width(Font f, String s, boolean bold, boolean italic) {
        if (s == null || s.isEmpty()) return 0;
        return plain(bold, italic) ? f.width(s) : f.width(make(s, bold, italic));
    }

    /** Cuts styled text to fit, ending in "...". */
    public static String fit(Font f, String s, int maxWidth, boolean bold, boolean italic) {
        if (s == null) return "";
        if (width(f, s, bold, italic) <= maxWidth) return s;
        int limit = Math.max(0, maxWidth - width(f, "…", bold, italic));
        int lo = 0;
        int hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (width(f, s.substring(0, mid), bold, italic) <= limit) lo = mid;
            else hi = mid - 1;
        }
        return s.substring(0, lo) + "…";
    }

    public static String fit(Font f, String s, int maxWidth) {
        return fit(f, s, maxWidth, false, false);
    }

    public static String fitBold(Font f, String s, int maxWidth) {
        return fit(f, s, maxWidth, true, false);
    }

    /** Draws styled text at (x, y) in the current font. */
    public static void drawStyled(GuiGraphics g, Font f, String s, int x, int y, int color, boolean bold, boolean italic,
                                  boolean shadow) {
        if (s == null || s.isEmpty()) return;
        if (plain(bold, italic)) g.drawString(f, s, x, y, color, shadow);
        else g.drawString(f, make(s, bold, italic), x, y + fontShift, color, shadow);
    }

    public static void text(GuiGraphics g, Font f, String s, int x, int y, int color, float alpha, boolean shadow) {
        drawStyled(g, f, s, x, y, Colors.alpha(color, alpha), false, false, shadow);
    }

    public static void textBold(GuiGraphics g, Font f, String s, int x, int y, int color, float alpha, boolean shadow) {
        drawStyled(g, f, s, x, y, Colors.alpha(color, alpha), true, false, shadow);
    }

    public static void textRight(GuiGraphics g, Font f, String s, int right, int y, int color, float alpha, boolean shadow) {
        drawStyled(g, f, s, right - width(f, s), y, Colors.alpha(color, alpha), false, false, shadow);
    }

    public static void textCentre(GuiGraphics g, Font f, String s, int centreX, int y, int color, float alpha, boolean bold,
                                  boolean shadow) {
        drawStyled(g, f, s, centreX - width(f, s, bold) / 2, y, Colors.alpha(color, alpha), bold, false, shadow);
    }

    /**
     * One line of text inside a fixed-width box. Too long for the box: it scrolls (wait, glide, wait, repeat)
     * when {@code scroll} is on, otherwise it is cut with an ellipsis. Nothing ever leaves the box.
     */
    public static void line(GuiGraphics g, Font f, String s, int x, int y, int boxWidth, int color, float alpha,
                            boolean bold, boolean shadow, boolean scroll, long elapsedMs) {
        if (s == null || s.isEmpty() || boxWidth <= 0) return;
        int tw = width(f, s, bold);
        if (tw <= boxWidth) {
            drawStyled(g, f, s, x, y, Colors.alpha(color, alpha), bold, false, shadow);
            return;
        }
        if (!scroll) {
            drawStyled(g, f, fit(f, s, boxWidth, bold, false), x, y, Colors.alpha(color, alpha), bold, false, shadow);
            return;
        }
        float off = Ease.marquee(tw, boxWidth, elapsedMs, 26f);
        g.enableScissor(x, y - 2, x + boxWidth, y + 11);
        drawStyled(g, f, s, x - Math.round(off), y, Colors.alpha(color, alpha), bold, false, shadow);
        g.disableScissor();
    }

    // ------------------------------------------------------------ bars

    /**
     * Progress bar: a rounded dark track, an accent fill with a lighter top, a sweep of light moving through it (with motion),
     * a bright head that flares on a beat. {@code knob} adds a round handle, used by the interactive player.
     */
    public static void progress(GuiGraphics g, int x, int y, int w, int h, float value, int accent, int accent2,
                                boolean motion, float sweep, boolean knob, boolean hot, float alpha) {
        value = Ease.clamp01(value);
        float dv = dev();
        if (knob && h >= 3 && com.zlorbie788.spotify.config.ConfigIo.get().progressStyle == com.zlorbie788.spotify.config.SpotifyConfig.ProgressStyle.WAVE) {
            waveProgress(g, x, y, w, h, value, accent, accent2, motion, knob, hot, alpha);
            return;
        }
        float r = h / 2f;
        int track = Colors.alpha(0xFF05070D, 0.75f * alpha);
        Shapes.fill(g, dv, x, y, w, h, ShapeKind.ROUND, r, track, track);
        float fill = w * value;
        float pulse = motion ? Visual.pulse() : 0f;
        if (fill > 0.5f) {
            float fw = Math.max(h, fill);
            fw = Math.min(fw, w);
            Shapes.fill(g, dv, x, y, fw, h, ShapeKind.ROUND, r, Colors.alpha(accent2, alpha), Colors.alpha(accent, alpha));
            if (motion && fw > 10) {
                int span = (int) Math.max(10, Math.min(30, fw / 2));
                float pos = (fw + span) * Ease.clamp01(sweep) - span;
                int x0 = (int) Math.max(0, pos);
                int x1 = (int) Math.min(fw, pos + span);
                if (x1 > x0) {
                    g.enableScissor(x + x0, y, x + x1, y + h);
                    int third = Math.max(1, (x1 - x0) / 3);
                    float a = (0.16f + 0.26f * pulse) * alpha;
                    Shapes.fill(g, dv, x + x0, y, x1 - x0, h, ShapeKind.SQUARE, 0, Colors.alpha(Colors.WHITE, a * 0.5f),
                            Colors.alpha(Colors.WHITE, a * 0.5f));
                    Shapes.fill(g, dv, x + x0 + third, y, third, h, ShapeKind.SQUARE, 0, Colors.alpha(Colors.WHITE, a),
                            Colors.alpha(Colors.WHITE, a));
                    g.disableScissor();
                }
            }
            if (pulse > 0.05f) {
                Shapes.glow(g, dv, x + fw - h, y, h, h, ShapeKind.ROUND, r, accent2, 0.9f * pulse * alpha);
            }
        }
        if (knob) {
            float kr = hot ? r + 3.2f : r + 2.2f;
            float cx = x + Math.max(r, Math.min(w - r, fill));
            float cy = y + r;
            Shapes.disc(g, dv, cx, cy, kr + 1f, Colors.alpha(accent, 0.35f * alpha));
            Shapes.disc(g, dv, cx, cy, kr, Colors.alpha(Colors.WHITE, alpha));
        }
    }

    /**
     * The "Wave" seek bar: the part already played is a gently moving sine wave, the part still to come is a calm straight line.
     * Drawn in device pixels so the curve is smooth at every GUI scale.
     */
    private static void waveProgress(GuiGraphics g, int x, int y, int w, int h, float value, int accent, int accent2,
                                     boolean motion, boolean knob, boolean hot, float alpha) {
        float dv = dev();
        float cy = y + h / 2f;
        float thick = Math.max(1.7f, h * 0.55f);
        float pulse = motion ? Visual.pulse() : 0f;
        float amp = (hot ? 2.3f : 1.7f) * (1f + 0.35f * pulse);
        double phase = motion ? (Clock.nanos() / 1e9) * 3.4 : 0.0;
        float k = (float) (Math.PI * 2 / 15.0);
        float fillW = w * value;
        var pose = g.pose();
        pose.pushMatrix();
        pose.scale(1f / dv, 1f / dv);
        int step = Math.max(1, Math.round(dv * 0.5f));
        int x0 = Math.round(x * dv);
        int cols = Math.round(w * dv);
        int fillCols = Math.round(fillW * dv);
        int half = Math.max(1, Math.round(thick * dv / 2f));
        int rest = Colors.alpha(Colors.mix(accent, 0xFF8A94AD, 0.55f), 0.45f * alpha);
        // the flat part still to come
        int restTop = Math.round(cy * dv) - Math.max(1, Math.round(thick * dv * 0.28f));
        int restBot = Math.round(cy * dv) + Math.max(1, Math.round(thick * dv * 0.28f));
        if (fillCols < cols) g.fill(x0 + fillCols, restTop, x0 + cols, restBot, rest);
        // the played part
        int prevTop = Integer.MIN_VALUE;
        int prevBot = Integer.MIN_VALUE;
        for (int i = 0; i < fillCols; i += step) {
            float gx = x + i / dv;
            // the wave grows out of the start and settles into a straight line right at the knob
            float taper = Math.min(1f, (gx - x) / 10f) * Math.min(1f, Math.max(0f, (fillW - (gx - x)) / 6f + 0.15f));
            float yy = cy + amp * taper * (float) Math.sin(k * gx - phase);
            int top = Math.round(yy * dv) - half;
            int bot = Math.round(yy * dv) + half;
            int t0 = prevTop == Integer.MIN_VALUE ? top : Math.min(top, prevTop + 0);
            int b0 = prevBot == Integer.MIN_VALUE ? bot : Math.max(bot, prevBot);
            float u = fillCols <= 0 ? 0f : i / (float) fillCols;
            int col = Colors.alpha(Colors.mix(accent2, accent, u), alpha);
            g.fill(x0 + i, Math.min(t0, top), x0 + i + step, Math.max(b0, bot), col);
            prevTop = top;
            prevBot = bot;
        }
        pose.popMatrix();
        if (knob) {
            float kr = hot ? 5.2f : 4.2f;
            float kx = x + Math.max(0f, Math.min(w, fillW));
            Shapes.disc(g, dv, kx, cy, kr + 1f, Colors.alpha(accent, 0.35f * alpha));
            Shapes.disc(g, dv, kx, cy, kr, Colors.alpha(Colors.WHITE, alpha));
        }
    }

    /** The small beat display of the player (see {@link Viz}). */
    public static void equalizer(GuiGraphics g, int x, int y, int w, int h, int color, float amp, long timeMs,
                                 int seed, boolean motion, float alpha) {
        Viz.draw(g, dev(), com.zlorbie788.spotify.config.ConfigIo.get().viz, x, y, w, h, color, amp, motion, alpha, seed);
    }

    /** The wide beat display of the full player. */
    public static void spectrum(GuiGraphics g, int x, int y, int w, int h, int color, boolean motion, float alpha) {
        Viz.drawWide(g, dev(), com.zlorbie788.spotify.config.ConfigIo.get().viz, x, y, w, h, color, motion, alpha);
    }
}
