package com.zlorbie788.spotify.hud;

import com.zlorbie788.spotify.util.Colors;
import com.zlorbie788.spotify.util.Ease;
import net.minecraft.client.gui.GuiGraphics;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * The signature of Zlorbie788: the name "ZLORBIE788" written as one continuous light-pen path in a slanted stroke
 * alphabet of its own, ending in a long flourish underneath. Pure geometry plus a few drawing helpers; the intro decides
 * when and how much of it to draw. The path is cut once into two pictures (the letters, and the flourish below them), each as
 * a hard core and a soft halo; while it is being written the pictures are revealed with a clip and only the stroke the pen is
 * on right now is drawn as a few straight pieces. There are no image files, and a frame costs a handful of draws.
 */
final class Signature {
    /** One straight piece of the pen path. {@code start} is the path length before it; {@code taper} scales its thickness. */
    record Seg(float x1, float y1, float x2, float y2, float start, float len, float taper, boolean flourish) {}

    static final String NAME = "ZLORBIE788";

    private static final float GAP = 0.36f;
    private static final float SLANT = 0.16f;

    final List<Seg> segs = new ArrayList<>();
    /** Length of the path (letters first, then the flourish). */
    float total;
    /** Where the flourish starts along the path. */
    float flourishAt;
    /** Pixel size of the whole thing (letters only, flourish included in {@code height}). */
    float width;
    float height;
    float letterH;
    /** Stroke thickness in GUI pixels the pictures are cut for. */
    float thick;
    /** For each letter: first and last segment, left edge, end of the path length. */
    final int[] letterFirst = new int[NAME.length()];
    final int[] letterLast = new int[NAME.length()];
    final float[] letterLeft = new float[NAME.length()];
    final float[] letterEnd = new float[NAME.length()];
    /** Index of the first flourish segment, and of the first one that has come back left of where the flourish started. */
    int flourishFirst;
    int hookEnd;
    float hookEndLen;
    /** Bounds (x0, y0, x1, y1) of the letters and of the flourish, with room for the halo. */
    final float[] boundsLetters = new float[4];
    final float[] boundsFlourish = new float[4];

    // ------------------------------------------------------------ the alphabet

    private static final float[][][] Z = {{{0, 0}, {1, 0}, {0, 1}, {1, 1}}};
    private static final float[][][] L = {{{0, 0}, {0, 1}, {1, 1}}};
    private static final float[][][] O = {{{0.28f, 0}, {0.72f, 0}, {1, 0.26f}, {1, 0.74f}, {0.72f, 1}, {0.28f, 1}, {0, 0.74f},
            {0, 0.26f}, {0.28f, 0}}};
    private static final float[][][] R = {{{0, 1}, {0, 0}, {0.74f, 0}, {1, 0.18f}, {1, 0.38f}, {0.74f, 0.54f}, {0, 0.54f}},
            {{0.46f, 0.54f}, {1, 1}}};
    private static final float[][][] B = {{{0, 0}, {0.72f, 0}, {1, 0.14f}, {1, 0.36f}, {0.72f, 0.5f}, {1, 0.64f}, {1, 0.86f},
            {0.72f, 1}, {0, 1}, {0, 0}}, {{0, 0.5f}, {0.72f, 0.5f}}};
    private static final float[][][] I = {{{0.5f, 0}, {0.5f, 1}}};
    private static final float[][][] E = {{{1, 0}, {0, 0}, {0, 1}, {1, 1}}, {{0, 0.5f}, {0.8f, 0.5f}}};
    private static final float[][][] SEVEN = {{{0, 0}, {1, 0}, {0.34f, 1}}};
    /** The 8 starts and ends at its bottom left, so the flourish can leave from there and stay under the letters. */
    private static final float[][][] EIGHT = {{{0.26f, 1}, {0, 0.88f}, {0, 0.62f}, {0.26f, 0.5f}, {0.06f, 0.38f}, {0.06f, 0.12f},
            {0.26f, 0}, {0.74f, 0}, {0.94f, 0.12f}, {0.94f, 0.38f}, {0.74f, 0.5f}, {1, 0.62f}, {1, 0.88f}, {0.74f, 1}, {0.26f, 1}}};

    private static float[][][] glyph(char c) {
        return switch (c) {
            case 'Z' -> Z;
            case 'L' -> L;
            case 'O' -> O;
            case 'R' -> R;
            case 'B' -> B;
            case 'I' -> I;
            case 'E' -> E;
            case '7' -> SEVEN;
            default -> EIGHT;
        };
    }

    private static float widthOf(char c) {
        return switch (c) {
            case 'Z' -> 0.82f;
            case 'L' -> 0.70f;
            case 'O' -> 0.88f;
            case 'R' -> 0.82f;
            case 'B' -> 0.82f;
            case 'I' -> 0.10f;
            case 'E' -> 0.72f;
            case '7' -> 0.78f;
            default -> 0.88f;
        };
    }

    // ------------------------------------------------------------ building

    /** {@code unit} is the pixel width of one alphabet unit; the letters are 1.35 times taller than a unit is wide. */
    static Signature build(float unit) {
        Signature s = new Signature();
        s.letterH = unit * 1.35f;
        float h = s.letterH;
        float x = 0f;
        float len = 0f;
        float lastX = 0f;
        float lastY = 0f;
        boolean have = false;
        s.thick = Math.max(1.6f, unit * 0.15f);
        for (int i = 0; i < NAME.length(); i++) {
            char c = NAME.charAt(i);
            float gw = widthOf(c) * unit;
            s.letterFirst[i] = s.segs.size();
            float left = Float.MAX_VALUE;
            for (float[][] stroke : glyph(c)) {
                for (int k = 1; k < stroke.length; k++) {
                    float ax = x + stroke[k - 1][0] * gw;
                    float ay = stroke[k - 1][1] * h;
                    float bx = x + stroke[k][0] * gw;
                    float by = stroke[k][1] * h;
                    ax += (h - ay) * SLANT;
                    bx += (h - by) * SLANT;
                    float l = (float) Math.hypot(bx - ax, by - ay);
                    if (l < 0.01f) continue;
                    s.segs.add(new Seg(ax, ay, bx, by, len, l, 1f, false));
                    len += l;
                    lastX = bx;
                    lastY = by;
                    have = true;
                    left = Math.min(left, Math.min(ax, bx));
                }
            }
            s.letterLast[i] = s.segs.size() - 1;
            s.letterLeft[i] = left;
            s.letterEnd[i] = len;
            x += gw + GAP * unit;
        }
        s.width = x - GAP * unit + h * SLANT;
        s.flourishAt = len;
        s.flourishFirst = s.segs.size();

        // the flourish: leaves the bottom of the last 8 and sweeps back under the whole name, always below the letters
        if (have) {
            float w = s.width;
            float base = h * 1.22f;
            float[] p0 = {lastX, lastY};
            float[] p1 = {lastX + unit * 1.6f, base + h * 0.55f};
            float[] p2 = {w * 0.50f, base + h * 0.30f};
            float[] p3 = {-w * 0.04f, base + h * 0.10f};
            int n = 44;
            float px = p0[0];
            float py = p0[1];
            for (int i = 1; i <= n; i++) {
                float u = i / (float) n;
                float v = 1f - u;
                float qx = v * v * v * p0[0] + 3 * v * v * u * p1[0] + 3 * v * u * u * p2[0] + u * u * u * p3[0];
                float qy = v * v * v * p0[1] + 3 * v * v * u * p1[1] + 3 * v * u * u * p2[1] + u * u * u * p3[1];
                float l = (float) Math.hypot(qx - px, qy - py);
                float taper = 0.35f + 0.95f * (float) Math.pow(Math.sin(Math.PI * Math.min(1f, u * 0.92f + 0.04f)), 0.8);
                s.segs.add(new Seg(px, py, qx, qy, len, l, taper, true));
                len += l;
                px = qx;
                py = qy;
            }
            s.height = base + h * 0.45f;
        } else {
            s.height = h;
        }
        s.total = len;
        // the hook: the first part of the flourish, which goes out to the right before sweeping back to the left
        s.hookEnd = s.segs.size();
        for (int i = s.flourishFirst; i < s.segs.size(); i++) {
            if (s.segs.get(i).x2 < lastX - 0.5f) {
                s.hookEnd = i;
                break;
            }
        }
        s.hookEndLen = s.hookEnd < s.segs.size() ? s.segs.get(s.hookEnd).start : s.total;
        s.bounds(s.boundsLetters, 0, s.flourishFirst, s.thick * 3.6f + 2f);
        s.bounds(s.boundsFlourish, s.flourishFirst, s.segs.size(), s.thick * 3.6f + 2f);
        return s;
    }

    // ------------------------------------------------------------ queries

    /** The pen position after this much of the path. */
    float[] at(float pen) {
        if (segs.isEmpty()) return new float[] {0f, 0f};
        for (Seg g : segs) {
            if (pen <= g.start + g.len) {
                float f = Ease.clamp01((pen - g.start) / g.len);
                return new float[] {g.x1 + (g.x2 - g.x1) * f, g.y1 + (g.y2 - g.y1) * f};
            }
        }
        Seg last = segs.get(segs.size() - 1);
        return new float[] {last.x2, last.y2};
    }

    private void bounds(float[] out, int from, int to, float pad) {
        float x0 = Float.MAX_VALUE;
        float y0 = Float.MAX_VALUE;
        float x1 = -Float.MAX_VALUE;
        float y1 = -Float.MAX_VALUE;
        for (int i = from; i < to; i++) {
            Seg s = segs.get(i);
            x0 = Math.min(x0, Math.min(s.x1, s.x2));
            y0 = Math.min(y0, Math.min(s.y1, s.y2));
            x1 = Math.max(x1, Math.max(s.x1, s.x2));
            y1 = Math.max(y1, Math.max(s.y1, s.y2));
        }
        if (x0 > x1) {
            x0 = y0 = x1 = y1 = 0f;
        }
        out[0] = (float) Math.floor(x0 - pad);
        out[1] = (float) Math.floor(y0 - pad);
        out[2] = (float) Math.ceil(x1 + pad);
        out[3] = (float) Math.ceil(y1 + pad);
    }

    // ------------------------------------------------------------ baking

    /**
     * Cuts one picture of the signature: the letters or the flourish, as a hard core (white, anti-aliased) or a soft halo.
     * {@code scale} is how many picture pixels one GUI pixel gets.
     */
    BufferedImage bake(boolean flourish, boolean halo, int scale) {
        float[] b = flourish ? boundsFlourish : boundsLetters;
        int from = flourish ? flourishFirst : 0;
        int to = flourish ? segs.size() : flourishFirst;
        int w = Math.max(1, Math.round((b[2] - b[0]) * scale));
        int h = Math.max(1, Math.round((b[3] - b[1]) * scale));
        float[] acc = new float[w * h];
        for (int i = from; i < to; i++) {
            Seg s = segs.get(i);
            float th = thick * s.taper * scale;
            float ax = (s.x1 - b[0]) * scale;
            float ay = (s.y1 - b[1]) * scale;
            float bx = (s.x2 - b[0]) * scale;
            float by = (s.y2 - b[1]) * scale;
            float rad = halo ? th * 3.6f : th * 0.5f + 1.5f;
            int xa = Math.max(0, (int) Math.floor(Math.min(ax, bx) - rad));
            int xb = Math.min(w - 1, (int) Math.ceil(Math.max(ax, bx) + rad));
            int ya = Math.max(0, (int) Math.floor(Math.min(ay, by) - rad));
            int yb = Math.min(h - 1, (int) Math.ceil(Math.max(ay, by) + rad));
            float vx = bx - ax;
            float vy = by - ay;
            float vv = Math.max(1e-6f, vx * vx + vy * vy);
            for (int y = ya; y <= yb; y++) {
                for (int x = xa; x <= xb; x++) {
                    float px = x + 0.5f - ax;
                    float py = y + 0.5f - ay;
                    float t = Math.max(0f, Math.min(1f, (px * vx + py * vy) / vv));
                    float dx = px - vx * t;
                    float dy = py - vy * t;
                    float d = (float) Math.sqrt(dx * dx + dy * dy);
                    float v;
                    if (halo) {
                        if (d > rad) continue;
                        float k = d / (th * 1.25f);
                        v = (float) Math.exp(-k * k);
                    } else {
                        v = Math.max(0f, Math.min(1f, th * 0.5f + 0.5f - d));
                    }
                    int idx = y * w + x;
                    if (v > acc[idx]) acc[idx] = v;
                }
            }
        }
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float v = acc[y * w + x];
                if (v > 0.004f) im.setRGB(x, y, (Math.round(Math.min(1f, v) * 255f) << 24) | 0xFFFFFF);
            }
        }
        return im;
    }

    // ------------------------------------------------------------ drawing

    /** A straight line of the given thickness, drawn as one rotated rectangle. */
    static void line(GuiGraphics g, float x1, float y1, float x2, float y2, float thick, int color) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float l = (float) Math.sqrt(dx * dx + dy * dy);
        if (l < 0.01f) return;
        var p = g.pose();
        p.pushMatrix();
        p.translate(x1, y1);
        p.rotate((float) Math.atan2(dy, dx));
        p.translate(-thick * 0.5f, -thick * 0.5f);
        p.scale(l + thick, thick);
        g.fill(0, 0, 1, 1, color);
        p.popMatrix();
    }

    /** How many letters the pen has finished. */
    int lettersDone(float pen) {
        int done = 0;
        while (done < letterEnd.length && pen >= letterEnd[done] - 0.001f) done++;
        return done;
    }

    /**
     * One pass over the part of the path written so far: the pictures revealed up to the pen, plus the stroke the pen is on right
     * now as straight pieces. {@code halo} picks the soft glow (wide pieces) or the hard core (thin pieces); {@code color} has
     * the alpha. {@code (dx, dy)} moves the whole pass (the colour fringes).
     */
    void pass(GuiGraphics g, float ox, float oy, float pen, boolean halo, int color, float dx, float dy) {
        if (pen <= 0f) return;
        if (IntroArt.broken()) {
            // the pictures could not be made (should never happen): write the whole path from straight pieces so the signature still shows
            if (!halo) {
                for (Seg sg : segs) {
                    float f = Ease.clamp01((pen - sg.start) / sg.len);
                    if (f <= 0f) break;
                    drawPiece(g, sg, f, ox + dx, oy + dy, thick * sg.taper, color, false);
                }
            }
            return;
        }
        int letters = letterEnd.length;
        int done = lettersDone(pen);
        float ax = ox + dx;
        float ay = oy + dy;
        // the letters: finished ones from the picture, the one being written as straight pieces
        if (done > 0) {
            float right = done >= letters ? Float.MAX_VALUE : letterLeft[done] - thick * 0.5f - 0.3f;
            IntroArt.signaturePart(g, this, false, halo, ax, ay, -Float.MAX_VALUE, right, -Float.MAX_VALUE, Float.MAX_VALUE, color);
        }
        float th = thick;
        if (done < letters && pen > 0f) {
            int first = letterFirst[done];
            int last = letterLast[done];
            for (int i = first; i <= last; i++) {
                Seg s = segs.get(i);
                float f = Ease.clamp01((pen - s.start) / s.len);
                if (f <= 0f) break;
                drawPiece(g, s, f, ax, ay, halo ? th * 3.2f : th, color, halo);
            }
        }
        // the flourish
        if (pen > flourishAt) {
            int to = segs.size();
            if (pen < hookEndLen) {
                for (int i = flourishFirst; i < to; i++) {
                    Seg s = segs.get(i);
                    float f = Ease.clamp01((pen - s.start) / s.len);
                    if (f <= 0f) break;
                    drawPiece(g, s, f, ax, ay, (halo ? 3.2f : 1f) * th * s.taper, color, halo);
                }
            } else {
                float penX = at(pen)[0];
                IntroArt.signaturePart(g, this, true, halo, ax, ay, penX - 0.6f, Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, color);
            }
        }
    }

    private static void drawPiece(GuiGraphics g, Seg s, float f, float ox, float oy, float width, int color, boolean halo) {
        float x1 = ox + s.x1;
        float y1 = oy + s.y1;
        float x2 = x1 + (s.x2 - s.x1) * f;
        float y2 = y1 + (s.y2 - s.y1) * f;
        line(g, x1, y1, x2, y2, width, halo ? Colors.alpha(color, 0.5f) : color);
    }

    /**
     * The metallic shine: white light running over the finished parts of the signature, as a few slanted strips cut out of the
     * hard core picture. {@code shine} is where it is across the width (0..1).
     */
    void shine(GuiGraphics g, float ox, float oy, float pen, float shine, float alpha) {
        if (shine < -0.2f || alpha <= 0.01f) return;
        int done = lettersDone(pen);
        float c = shine * width;
        float lean = letterH * 0.22f;
        float mid = letterH * 0.5f;
        float[] halfW = {width * 0.060f, width * 0.026f};
        float[] level = {0.30f, 0.42f};
        float rightLetters = done >= letterEnd.length ? Float.MAX_VALUE : letterLeft[Math.max(0, done)] - thick * 0.5f - 0.3f;
        float rightFlourish = Float.MAX_VALUE;
        for (int k = 0; k < 2; k++) {
            int col = Colors.alpha(Colors.WHITE, level[k] * alpha);
            if (done > 0) {
                // upper half leans right, lower half left, like the letters themselves
                IntroArt.signaturePart(g, this, false, false, ox, oy, c + lean - halfW[k], Math.min(rightLetters, c + lean + halfW[k]),
                        -Float.MAX_VALUE, mid, col);
                IntroArt.signaturePart(g, this, false, false, ox, oy, c - lean - halfW[k], Math.min(rightLetters, c - lean + halfW[k]),
                        mid, Float.MAX_VALUE, col);
            }
            if (pen > flourishAt) {
                float penX = pen >= total ? -Float.MAX_VALUE : at(pen)[0];
                IntroArt.signaturePart(g, this, true, false, ox, oy, Math.max(penX, c - halfW[k]), Math.min(rightFlourish, c + halfW[k]),
                        -Float.MAX_VALUE, Float.MAX_VALUE, col);
            }
        }
    }

    /** A four-pointed glint at a point, {@code size} pixels from the middle to the end of the long arm. */
    static void glint(GuiGraphics g, float cx, float cy, float size, int color, float alpha) {
        if (alpha <= 0.01f || size < 1f) return;
        IntroArt.glint(g, cx, cy, size, Colors.alpha(color, alpha));
        IntroArt.glow(g, cx, cy, size * 0.45f, Colors.WHITE, alpha * 0.8f);
    }
}
