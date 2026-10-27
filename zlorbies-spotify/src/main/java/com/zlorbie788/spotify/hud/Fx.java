package com.zlorbie788.spotify.hud;

import com.zlorbie788.spotify.config.SpotifyConfig.ShapeKind;
import com.zlorbie788.spotify.util.Colors;
import com.zlorbie788.spotify.util.Ease;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Small light effects that dress a panel: a comet running around its border, a streak of light sweeping over it,
 * floating specks, and corner brackets. All of them only add light, never change the layout, and are cheap when idle.
 */
public final class Fx {
    private Fx() {}

    // ------------------------------------------------------------ outline path

    /** Points of the outline of a shape, clockwise from the top-left, as {x0, y0, x1, y1, ...}; returns the length. */
    private static float[] outline(float x, float y, float w, float h, ShapeKind kind, float r, float[] outLen) {
        r = Math.max(0f, Math.min(r, Math.min(w, h) / 2f));
        boolean tl = true, tr = true, br = true, bl = true;
        if (kind == ShapeKind.SQUARE) r = 0f;
        if (kind == ShapeKind.TECH) {
            tr = false;
            bl = false;
        }
        int arc = kind == ShapeKind.ROUND ? 6 : 1;
        java.util.ArrayList<float[]> pts = new java.util.ArrayList<>();
        // top edge, then the corners in order
        corner(pts, tl, x + r, y + r, r, 180, 270, arc, x, y, kind);
        corner(pts, tr, x + w - r, y + r, r, 270, 360, arc, x + w, y, kind);
        corner(pts, br, x + w - r, y + h - r, r, 0, 90, arc, x + w, y + h, kind);
        corner(pts, bl, x + r, y + h - r, r, 90, 180, arc, x, y + h, kind);
        float[] flat = new float[pts.size() * 2 + 2];
        float len = 0f;
        for (int i = 0; i < pts.size(); i++) {
            flat[i * 2] = pts.get(i)[0];
            flat[i * 2 + 1] = pts.get(i)[1];
            if (i > 0) len += (float) Math.hypot(flat[i * 2] - flat[i * 2 - 2], flat[i * 2 + 1] - flat[i * 2 - 1]);
        }
        flat[pts.size() * 2] = flat[0];
        flat[pts.size() * 2 + 1] = flat[1];
        len += (float) Math.hypot(flat[pts.size() * 2] - flat[pts.size() * 2 - 2], flat[pts.size() * 2 + 1] - flat[pts.size() * 2 - 1]);
        outLen[0] = len;
        return flat;
    }

    private static void corner(java.util.List<float[]> pts, boolean cut, float cx, float cy, float r, int from, int to, int steps,
                               float sharpX, float sharpY, ShapeKind kind) {
        if (!cut || r <= 0.01f) {
            pts.add(new float[]{sharpX, sharpY});
            return;
        }
        if (kind != ShapeKind.ROUND) {
            // a straight cut: two points
            double a0 = Math.toRadians(from);
            double a1 = Math.toRadians(to);
            pts.add(new float[]{cx + (float) Math.cos(a0) * r, cy + (float) Math.sin(a0) * r});
            pts.add(new float[]{cx + (float) Math.cos(a1) * r, cy + (float) Math.sin(a1) * r});
            return;
        }
        for (int i = 0; i <= steps; i++) {
            double a = Math.toRadians(from + (to - from) * (i / (double) steps));
            pts.add(new float[]{cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r});
        }
    }

    private static float[] at(float[] flat, float s) {
        int n = flat.length / 2;
        float acc = 0f;
        for (int i = 1; i < n; i++) {
            float dx = flat[i * 2] - flat[i * 2 - 2];
            float dy = flat[i * 2 + 1] - flat[i * 2 - 1];
            float seg = (float) Math.hypot(dx, dy);
            if (acc + seg >= s) {
                float f = seg <= 0f ? 0f : (s - acc) / seg;
                return new float[]{flat[i * 2 - 2] + dx * f, flat[i * 2 - 1] + dy * f};
            }
            acc += seg;
        }
        return new float[]{flat[0], flat[1]};
    }

    // ------------------------------------------------------------ comet along the border

    /**
     * A bright head with a fading tail running around the outline. {@code progress} 0..1 is how far round it is; the tail
     * is {@code tailFraction} of the outline long.
     */
    public static void comet(GuiGraphics g, float dev, float x, float y, float w, float h, ShapeKind kind, float radius,
                             int color, float progress, float tailPx, float thickness, float alpha) {
        if (alpha <= 0.01f || w < 8 || h < 8) return;
        float[] len = new float[1];
        float[] path = outline(x, y, w, h, kind, radius, len);
        float L = len[0];
        if (L <= 1f) return;
        float head = ((progress % 1f) + 1f) % 1f * L;
        float step = Math.max(0.8f, 1f / Math.max(1f, dev / 2f));
        int n = (int) (tailPx / step);
        for (int i = 0; i <= n; i++) {
            float s = head - i * step;
            if (s < 0) s += L;
            float[] p = at(path, s);
            float f = 1f - i / (float) n;
            float a = alpha * f * f;
            int col = i < 2 ? Colors.lighten(color, 0.7f) : color;
            Shapes.fill(g, dev, p[0] - thickness / 2f, p[1] - thickness / 2f, thickness, thickness, ShapeKind.SQUARE, 0,
                    Colors.alpha(col, a), Colors.alpha(col, a));
        }
        float[] hp = at(path, head);
        Shapes.glow(g, dev, hp[0] - 1f, hp[1] - 1f, 2f, 2f, ShapeKind.ROUND, 1f, color, 0.9f * alpha);
    }

    // ------------------------------------------------------------ shine

    /**
     * A diagonal streak of light crossing the box once, {@code t} 0..1 (0 and 1 are outside). Clipped to the box.
     */
    public static void shine(GuiGraphics g, float x, float y, float w, float h, int color, float t, float alpha) {
        if (t <= 0f || t >= 1f || alpha <= 0.01f || w < 12 || h < 8) return;
        if (IntroArt.shine(g, x, y, w, h, color, t, alpha)) return;
        float env = (float) Math.sin(Math.PI * t);
        float slope = 0.55f;
        float travel = w + h * slope + 40f;
        float cx = x - 20f + travel * Ease.inOutCubic(t);
        g.enableScissor((int) x + 1, (int) y + 1, (int) (x + w) - 1, (int) (y + h) - 1);
        for (int row = 0; row < (int) h; row += 2) {
            float bx = cx - row * slope;
            int bi = (int) bx;
            g.fill(bi - 10, (int) y + row, bi + 10, (int) y + row + 2, Colors.alpha(color, 0.035f * env * alpha));
            g.fill(bi - 5, (int) y + row, bi + 5, (int) y + row + 2, Colors.alpha(color, 0.06f * env * alpha));
            g.fill(bi - 1, (int) y + row, bi + 2, (int) y + row + 2, Colors.alpha(Colors.WHITE, 0.10f * env * alpha));
        }
        g.disableScissor();
    }

    // ------------------------------------------------------------ specks

    /** Floating light specks drifting upwards inside the box (positions come from time, nothing is stored). */
    public static void specks(GuiGraphics g, float x, float y, float w, float h, int color, double seconds, int count,
                              float alpha, int seed) {
        if (alpha <= 0.01f || w < 12 || h < 12) return;
        for (int i = 0; i < count; i++) {
            double k = i * 12.9898 + seed * 0.37;
            double fx = frac(Math.sin(k) * 43758.5453);
            double speed = 0.035 + 0.05 * frac(Math.sin(k * 1.7) * 9731.1);
            double life = frac(seconds * speed + frac(Math.sin(k * 2.3) * 3571.7));
            float px = x + (float) (fx * w + Math.sin(seconds * 0.6 + k) * 4.0);
            float py = y + h - (float) (life * (h + 6)) + 3;
            float fade = (float) Math.sin(Math.PI * life);
            int size = i % 3 == 0 ? 2 : 1;
            g.fill((int) px, (int) py, (int) px + size, (int) py + size, Colors.alpha(color, 0.5f * fade * alpha));
        }
    }

    private static double frac(double v) {
        return v - Math.floor(v);
    }

    // ------------------------------------------------------------ brackets

    /** Four small L-shaped brackets in the corners of a box, drawn on the outside edge. */
    public static void brackets(GuiGraphics g, int x, int y, int w, int h, int len, int color, float alpha) {
        int c = Colors.alpha(color, alpha);
        int t = 1;
        // top-left
        g.fill(x - 1, y - 1, x - 1 + len, y - 1 + t, c);
        g.fill(x - 1, y - 1, x - 1 + t, y - 1 + len, c);
        // top-right
        g.fill(x + w + 1 - len, y - 1, x + w + 1, y - 1 + t, c);
        g.fill(x + w + 1 - t, y - 1, x + w + 1, y - 1 + len, c);
        // bottom-left
        g.fill(x - 1, y + h + 1 - t, x - 1 + len, y + h + 1, c);
        g.fill(x - 1, y + h + 1 - len, x - 1 + t, y + h + 1, c);
        // bottom-right
        g.fill(x + w + 1 - len, y + h + 1 - t, x + w + 1, y + h + 1, c);
        g.fill(x + w + 1 - t, y + h + 1 - len, x + w + 1, y + h + 1, c);
    }

    // ------------------------------------------------------------ circle

    /** A thin circle drawn from short segments (any size; the shape renderer is limited to small corners). */
    public static void circle(GuiGraphics g, float cx, float cy, float r, float thickness, int color) {
        if (r < 1f) return;
        int n = (int) Math.max(24, Math.min(240, r * 1.6f));
        float t = Math.max(1f, thickness);
        for (int i = 0; i < n; i++) {
            double a = i * (Math.PI * 2.0 / n);
            int x = Math.round(cx + (float) Math.cos(a) * r);
            int y = Math.round(cy + (float) Math.sin(a) * r);
            g.fill(x, y, x + (int) Math.ceil(t), y + (int) Math.ceil(t), color);
        }
    }

    // ------------------------------------------------------------ aurora

    /**
     * Slow curtains of coloured light drifting behind the content of a panel, brightening on every beat. Stays inside the
     * box (inset by {@code inset}) so it never reaches the rounded corners. {@code parallaxX} moves it against the mouse.
     */
    public static void aurora(GuiGraphics g, float x, float y, float w, float h, float inset, int c1, int c2, double seconds,
                              float beat, float alpha, float parallaxX) {
        if (alpha <= 0.01f || w < 40 || h < 30) return;
        int x0 = (int) (x + inset);
        int y0 = (int) (y + 1);
        int x1 = (int) (x + w - inset);
        int y1 = (int) (y + h - 1);
        int span = x1 - x0;
        if (span < 20) return;
        int[] from3 = new int[3];
        int[] to3 = new int[3];
        int[] cols = new int[3];
        float[] alphas = new float[3];
        boolean baked = IntroArt.curtainTex() != null;
        for (int k = 0; k < 3; k++) {
            int col = k == 1 ? c2 : c1;
            double centre = x0 + span * (0.18 + 0.32 * k + 0.13 * Math.sin(seconds * (0.21 + 0.07 * k) + k * 2.1)) + parallaxX * (0.6 + k * 0.4);
            double half = span * (0.20 + 0.04 * Math.sin(seconds * 0.17 + k));
            float base = (0.075f + 0.075f * beat) * alpha;
            int from = (int) Math.max(x0, centre - half);
            int to = (int) Math.min(x1, centre + half);
            if (baked) {
                // one picture per curtain, stretched over the whole width it covers (clipped to the box)
                from3[k] = (int) (centre - half);
                to3[k] = (int) (centre + half);
                cols[k] = col;
                alphas[k] = base;
                continue;
            }
            for (int sx = from; sx < to; sx += 4) {
                double d = Math.abs(sx + 2 - centre) / half;
                float a = (float) Math.pow(Math.max(0.0, 1.0 - d * d), 2.0) * base;
                if (a < 0.004f) continue;
                g.fillGradient(sx, y0, Math.min(x1, sx + 4), y1, Colors.alpha(col, a), Colors.alpha(col, 0f));
            }
        }
        if (baked) IntroArt.curtain(g, x0, y0, x1, y1, from3, to3, cols, alphas, 3);
    }
}
