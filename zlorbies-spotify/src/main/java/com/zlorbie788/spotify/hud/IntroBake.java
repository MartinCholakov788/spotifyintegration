package com.zlorbie788.spotify.hud;

import java.awt.image.BufferedImage;
import java.util.Random;

/**
 * Cuts the little pictures the intro is made of, once, in plain Java (no image files): soft glows, light bands, rings, the
 * glint, the asteroids with their rocky surface, the star layers and the streaks of the flight. The intro then only places
 * and tints these pictures, which is what keeps it light: a few dozen draws per frame instead of thousands of tiny rectangles.
 * Everything here is pure maths on arrays, so it runs on a background thread.
 */
final class IntroBake {
    private IntroBake() {}

    // ------------------------------------------------------------ helpers

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(1f, v);
    }

    private static float smooth(float e0, float e1, float v) {
        float t = clamp01((v - e0) / (e1 - e0));
        return t * t * (3f - 2f * t);
    }

    private static int hash(int a, int b, int c) {
        int h = a * 0x9E3779B1 + b * 0x85EBCA6B + c * 0xC2B2AE35;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        h *= 0x297A2D39;
        h ^= h >>> 15;
        return h;
    }

    private static float rnd(int a, int b, int c) {
        return (hash(a, b, c) & 0xFFFFFF) / (float) 0x1000000;
    }

    /** Smooth value noise, 0..1. */
    private static float noise(float x, float y, int seed) {
        int ix = (int) Math.floor(x);
        int iy = (int) Math.floor(y);
        float fx = x - ix;
        float fy = y - iy;
        fx = fx * fx * (3f - 2f * fx);
        fy = fy * fy * (3f - 2f * fy);
        float a = rnd(ix, iy, seed);
        float b = rnd(ix + 1, iy, seed);
        float c = rnd(ix, iy + 1, seed);
        float d = rnd(ix + 1, iy + 1, seed);
        return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fy;
    }

    private static int argb(float a, float r, float g, float b) {
        int ia = Math.round(clamp01(a) * 255f);
        int ir = Math.round(clamp01(r) * 255f);
        int ig = Math.round(clamp01(g) * 255f);
        int ib = Math.round(clamp01(b) * 255f);
        return (ia << 24) | (ir << 16) | (ig << 8) | ib;
    }

    private static int white(float a) {
        return (Math.round(clamp01(a) * 255f) << 24) | 0xFFFFFF;
    }

    // ------------------------------------------------------------ glows, bands, rings, glint

    /** A round soft glow, white, bright in the middle and gone at the edge. */
    static BufferedImage glow(int n) {
        BufferedImage im = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        float c = n / 2f;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                float d = (float) Math.hypot(x + 0.5f - c, y + 0.5f - c) / c;
                float k = 1f - d;
                im.setRGB(x, y, d >= 1f ? 0 : white(Math.min(1f, k * k * 1.3f)));
            }
        }
        return im;
    }

    /** Light that is strongest in the middle of the width and fades to both sides (the same all the way down). */
    static BufferedImage hband() {
        BufferedImage im = new BufferedImage(64, 8, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 64; x++) {
            float k = 1f - Math.abs(x + 0.5f - 32f) / 32f;
            int c = white(k * k);
            for (int y = 0; y < 8; y++) im.setRGB(x, y, c);
        }
        return im;
    }

    /** Light that is strongest in the middle of the height and fades up and down (the same all the way across). */
    static BufferedImage vband() {
        BufferedImage im = new BufferedImage(8, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) {
            float k = 1f - Math.abs(y + 0.5f - 32f) / 32f;
            int c = white(k * k);
            for (int x = 0; x < 8; x++) im.setRGB(x, y, c);
        }
        return im;
    }

    /** A thin, soft circle that nearly fills the picture. */
    static BufferedImage ring(int size) {
        BufferedImage im = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        float c = size / 2f;
        float rho = c - 2.5f;
        float th = 2.2f;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float d = (float) Math.hypot(x + 0.5f - c, y + 0.5f - c);
                float cov = clamp01(th / 2f + 0.5f - Math.abs(d - rho));
                if (cov > 0f) im.setRGB(x, y, white(cov));
            }
        }
        return im;
    }

    /** A four-pointed glint: a long horizontal arm, a shorter vertical one, two short diagonals and a hot centre. */
    static BufferedImage glint() {
        int n = 96;
        BufferedImage im = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        float c = n / 2f;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                float dx = x + 0.5f - c;
                float dy = y + 0.5f - c;
                float a = 0f;
                float ax = Math.abs(dx) / c;
                float ay = Math.abs(dy) / c;
                a = Math.max(a, (float) Math.exp(-(dy * dy) / 2.2f) * (float) Math.pow(Math.max(0f, 1f - ax), 1.6));
                a = Math.max(a, (float) Math.exp(-(dx * dx) / 2.2f) * (float) Math.pow(Math.max(0f, 1f - ay / 0.8f), 1.6));
                float u = (dx + dy) * 0.7071f;
                float v = (dx - dy) * 0.7071f;
                a = Math.max(a, 0.55f * (float) Math.exp(-(v * v) / 1.6f) * (float) Math.pow(Math.max(0f, 1f - Math.abs(u) / (c * 0.36f)), 1.4));
                a = Math.max(a, 0.55f * (float) Math.exp(-(u * u) / 1.6f) * (float) Math.pow(Math.max(0f, 1f - Math.abs(v) / (c * 0.36f)), 1.4));
                a = Math.max(a, (float) Math.exp(-(dx * dx + dy * dy) / 14f));
                if (a > 0.004f) im.setRGB(x, y, white(a));
            }
        }
        return im;
    }

    /**
     * A slanted streak of light, 256 by 256: the line runs from the top right to the bottom left, falling 128 pixels over the
     * height. {@code core} is a hair-thin bright line, otherwise a soft wide band around it.
     */
    static BufferedImage shine(boolean core) {
        int n = 256;
        BufferedImage im = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        float sigma = core ? 1.25f : 9f;
        for (int y = 0; y < n; y++) {
            float cx = 192f - 0.5f * (y + 0.5f);
            for (int x = 0; x < n; x++) {
                float d = (x + 0.5f - cx) / sigma;
                float a = (float) Math.exp(-d * d);
                if (a > 0.004f) im.setRGB(x, y, white(a));
            }
        }
        return im;
    }

    /** A curtain of light: strongest in the middle of the width and fading out to the sides, and fading out towards the bottom. */
    static BufferedImage curtain() {
        int w = 128;
        int h = 32;
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < w; x++) {
            float d = Math.abs(x + 0.5f - w / 2f) / (w / 2f);
            float prof = (float) Math.pow(Math.max(0f, 1f - d * d), 2.0);
            for (int y = 0; y < h; y++) {
                float a = prof * (1f - (y + 0.5f) / h);
                if (a > 0.003f) im.setRGB(x, y, white(a));
            }
        }
        return im;
    }

    /** A small round dot with a soft edge, 16 pixels square. */
    static BufferedImage disc() {
        int n = 16;
        BufferedImage im = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                float d = (float) Math.hypot(x + 0.5f - n / 2f, y + 0.5f - n / 2f);
                float a = clamp01(n / 2f - d + 0.5f);
                if (a > 0f) im.setRGB(x, y, white(a));
            }
        }
        return im;
    }

    // ------------------------------------------------------------ asteroids

    /** Direction the light comes from in the asteroid pictures (to the right and a little up); the intro turns them to face the star. */
    static final float LIGHT_X = 0.80f;
    static final float LIGHT_Y = -0.25f;
    static final float LIGHT_Z = 0.42f;

    private static float fbm(float x, float y, int seed, int octaves) {
        float sum = 0f;
        float amp = 1f;
        float norm = 0f;
        for (int o = 0; o < octaves; o++) {
            sum += (noise(x, y, seed + o * 31) - 0.5f) * 2f * amp;
            norm += amp;
            x *= 2.03f;
            y *= 2.03f;
            amp *= 0.5f;
        }
        return sum / norm;
    }

    /**
     * One asteroid, {@code n} pixels square, as an unevenly shaped lump with a grainy surface, craters with raised rims, patches
     * of lighter and darker rock and pits; lit from the right, with a bright edge on the lit side, a faint cool fill light on
     * the dark side and a soft glow around the lit edge. Grey-warm so the intro can tint it with the colours of the album.
     */
    static BufferedImage rock(int variant, int n) {
        int seed = 101 + variant * 977;
        BufferedImage im = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        float c = n / 2f;
        float r0 = n * 0.36f;
        Random rng = new Random(seed);

        // outline: a few slow bumps plus a little roughness
        float[] amp = new float[7];
        float[] ph = new float[7];
        for (int k = 2; k < 7; k++) {
            amp[k] = (0.10f / k) * (0.5f + rng.nextFloat());
            ph[k] = rng.nextFloat() * 6.2832f;
        }

        // height field of the surface in pixels: grain, patches and craters
        int pad = 1;
        int hn = n + 2 * pad;
        float[] hgt = new float[hn * hn];
        float[] albedo = new float[hn * hn];
        float[] cx = new float[14];
        float[] cy = new float[14];
        float[] cr = new float[14];
        int craters = 8 + (variant % 3) * 2;
        for (int k = 0; k < craters; k++) {
            float a = rng.nextFloat() * 6.2832f;
            float d = (float) Math.sqrt(rng.nextFloat()) * r0 * 0.8f;
            cx[k] = c + (float) Math.cos(a) * d;
            cy[k] = c + (float) Math.sin(a) * d;
            float big = rng.nextFloat();
            cr[k] = r0 * (0.07f + 0.20f * big * big);
        }
        for (int y = 0; y < hn; y++) {
            for (int x = 0; x < hn; x++) {
                float px = x - pad + 0.5f;
                float py = y - pad + 0.5f;
                float s = n / 256f;
                float h = 0f;
                h += fbm(px / (46f * s), py / (46f * s), seed, 2) * 7.5f * s;
                h += fbm(px / (16f * s), py / (16f * s), seed + 7, 3) * 3.4f * s;
                h += fbm(px / (5.5f * s), py / (5.5f * s), seed + 13, 2) * 1.2f * s;
                for (int k = 0; k < craters; k++) {
                    float d = (float) Math.hypot(px - cx[k], py - cy[k]) / cr[k];
                    if (d < 1f) h -= 0.42f * cr[k] * (1f - d * d);
                    float rim = (d - 1f) * 3.2f;
                    h += 0.07f * cr[k] * (float) Math.exp(-rim * rim);
                }
                hgt[y * hn + x] = h;
                float al = 0.52f + 0.34f * fbm(px / (60f * s), py / (60f * s), seed + 21, 2)
                        + 0.16f * fbm(px / (7f * s), py / (7f * s), seed + 25, 2);
                // pits and specks
                float sp = rnd((int) (px / 2.5f), (int) (py / 2.5f), seed);
                if (sp > 0.985f) al += 0.35f;
                else if (sp < 0.012f) al -= 0.25f;
                albedo[y * hn + x] = al;
            }
        }

        float llen = (float) Math.sqrt(LIGHT_X * LIGHT_X + LIGHT_Y * LIGHT_Y + LIGHT_Z * LIGHT_Z);
        float lx = LIGHT_X / llen;
        float ly = LIGHT_Y / llen;
        float lz = LIGHT_Z / llen;
        float lightAngle = (float) Math.atan2(ly, lx);
        float haloSpan = n * 0.045f;

        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                float dx = x + 0.5f - c;
                float dy = y + 0.5f - c;
                float r = (float) Math.hypot(dx, dy);
                float th = (float) Math.atan2(dy, dx);
                float edge = 1f;
                for (int k = 2; k < 7; k++) edge += amp[k] * (float) Math.cos(k * th + ph[k]);
                edge += 0.025f * (noise(th * 5.2f + 40f, seed * 0.01f, seed) - 0.5f);
                float rr = r0 * edge;
                float cover = clamp01(rr - r + 0.5f);
                if (cover <= 0f) {
                    // the soft glow around the lit edge, outside the rock
                    float out = r - rr;
                    if (out < haloSpan * 4f) {
                        float side = (float) Math.cos(th - lightAngle);
                        side = side > 0f ? side * side : 0f;
                        float g = 0.26f * (float) Math.exp(-out / haloSpan) * side;
                        if (g > 0.004f) im.setRGB(x, y, argb(g, 1f, 0.97f, 0.92f));
                    }
                    continue;
                }
                // base normal of a lumpy ball, then bent by the surface
                float nx = dx / rr;
                float ny = dy / rr;
                float rho2 = nx * nx + ny * ny;
                float nz = (float) Math.sqrt(Math.max(0f, 1f - rho2));
                int hx = x + pad;
                int hy = y + pad;
                float gx = (hgt[hy * hn + hx + 1] - hgt[hy * hn + hx - 1]) * 0.5f;
                float gy = (hgt[(hy + 1) * hn + hx] - hgt[(hy - 1) * hn + hx]) * 0.5f;
                float bump = 0.85f;
                float bx = nx - gx * bump;
                float by = ny - gy * bump;
                float bz = Math.max(0.12f, nz);
                float bl = (float) Math.sqrt(bx * bx + by * by + bz * bz);
                bx /= bl;
                by /= bl;
                bz /= bl;

                float diff = Math.max(0f, bx * lx + by * ly + bz * lz);
                float soft = smooth(0f, 0.55f, diff);
                diff = (float) Math.pow(diff, 0.85) * (0.55f + 0.45f * soft);
                // crater floors lie in shadow
                float hh = hgt[hy * hn + hx];
                float ao = 1f - 0.5f * clamp01(-hh / (r0 * 0.12f));
                float alb = clamp01(albedo[hy * hn + hx]);
                // the bright edge on the lit side
                float edgeK = (float) Math.pow(1f - nz, 2.4);
                float facing = clamp01(nx * lx + ny * ly + 0.35f);
                float rim = edgeK * facing * 1.15f;

                float amb = 0.075f + 0.07f * nz;
                float lit = diff * alb * ao * 1.15f;
                float rr2 = amb * 0.55f * (0.6f + alb) + lit * 1.00f + rim * 0.95f;
                float gg2 = amb * 0.72f * (0.6f + alb) + lit * 0.93f + rim * 0.95f;
                float bb2 = amb * 1.15f * (0.6f + alb) + lit * 0.84f + rim * 1.00f;
                // a little darkening toward the unlit edge so the lump reads as round
                float dark = 1f - 0.25f * (1f - facing) * edgeK;
                im.setRGB(x, y, argb(cover, rr2 * dark, gg2 * dark, bb2 * dark));
            }
        }
        return im;
    }

    private static final java.util.Map<Integer, BufferedImage> ROCK_BASE = new java.util.HashMap<>();

    /** The full-size picture of an asteroid, cut once and shared by its smaller versions. */
    static synchronized BufferedImage rockBase(int variant) {
        return ROCK_BASE.computeIfAbsent(variant, v -> rock(v, 256));
    }

    /** Halves the picture until it is {@code size} pixels square, averaging with the transparency taken into account. */
    static BufferedImage shrink(BufferedImage src, int size) {
        BufferedImage cur = src;
        while (cur.getWidth() > size) {
            int w = cur.getWidth() / 2;
            BufferedImage next = new BufferedImage(w, w, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < w; y++) {
                for (int x = 0; x < w; x++) {
                    float a = 0f;
                    float r = 0f;
                    float g = 0f;
                    float b = 0f;
                    for (int j = 0; j < 2; j++) {
                        for (int i = 0; i < 2; i++) {
                            int p = cur.getRGB(x * 2 + i, y * 2 + j);
                            float pa = (p >>> 24) / 255f;
                            a += pa;
                            r += pa * ((p >> 16) & 255);
                            g += pa * ((p >> 8) & 255);
                            b += pa * (p & 255);
                        }
                    }
                    if (a <= 0f) continue;
                    next.setRGB(x, y, (Math.round(a / 4f * 255f) << 24) | (Math.round(r / a) << 16) | (Math.round(g / a) << 8) | Math.round(b / a));
                }
            }
            cur = next;
        }
        return cur;
    }

    // ------------------------------------------------------------ the stars and the streaks

    /**
     * One layer of stars, {@code w} by {@code h} pixels (one pixel is one GUI pixel, or {@code scale} of them on a huge screen).
     * Layer 0 is many faint points, the last layer a few bright ones with a small cross.
     */
    static BufferedImage stars(int layer, int w, int h, int layers) {
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Random rng = new Random(7000 + layer * 131L);
        float area = w * (float) h;
        float last = layers - 1f;
        float k = layer / Math.max(1f, last);
        int count = Math.max(8, Math.round(area / (1300f + 1700f * k * k * 2.2f) * (layer == 0 ? 1.3f : 1f)));
        for (int i = 0; i < count; i++) {
            int x = 1 + rng.nextInt(Math.max(1, w - 2));
            int y = 1 + rng.nextInt(Math.max(1, h - 2));
            float t = rng.nextFloat();
            float cr = 1f;
            float cg = 1f;
            float cb = 1f;
            if (t < 0.22f) {
                cr = 0.72f;
                cg = 0.84f;
                cb = 1f;
            } else if (t < 0.34f) {
                cr = 1f;
                cg = 0.89f;
                cb = 0.74f;
            }
            float a = 0.45f + 0.5f * k * (0.6f + 0.4f * rng.nextFloat());
            im.setRGB(x, y, argb(a, cr, cg, cb));
            if (layer == layers - 1 || (layer == layers - 2 && rng.nextFloat() < 0.35f)) {
                int arm = layer == layers - 1 ? 2 : 1;
                for (int j = 1; j <= arm; j++) {
                    float fa = a * (0.5f / j);
                    if (x - j >= 0) im.setRGB(x - j, y, argb(fa, cr, cg, cb));
                    if (x + j < w) im.setRGB(x + j, y, argb(fa, cr, cg, cb));
                    if (y - j >= 0) im.setRGB(x, y - j, argb(fa, cr, cg, cb));
                    if (y + j < h) im.setRGB(x, y + j, argb(fa, cr, cg, cb));
                }
            }
        }
        return im;
    }

    /**
     * The streaks of the flight: thin wedges that point away from the middle, cut into dashes along their length (dashes are
     * longer further out). When the picture is enlarged around its middle the dashes slide outwards like stars rushing past.
     * {@code streaks} false gives many short dots, true gives fewer long streaks.
     */
    static BufferedImage warp(int size, boolean streaks) {
        BufferedImage im = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        float c = size / 2f;
        int rays = streaks ? 84 : 150;
        float[] angle = new float[rays];
        float[] phase = new float[rays];
        float[] period = new float[rays];
        float[] len = new float[rays];
        float[] bright = new float[rays];
        float[] widthK = new float[rays];
        float dTheta = (float) (Math.PI * 2.0 / rays);
        int salt = streaks ? 5 : 9;
        for (int i = 0; i < rays; i++) {
            angle[i] = (i + (rnd(i, salt, 1) - 0.5f) * 0.8f) * dTheta;
            phase[i] = rnd(i, salt, 2);
            period[i] = streaks ? 0.50f + 0.55f * rnd(i, salt, 3) : 0.40f + 0.50f * rnd(i, salt, 3);
            len[i] = streaks ? 0.20f + 0.30f * rnd(i, salt, 4) : 0.035f + 0.04f * rnd(i, salt, 4);
            bright[i] = 0.40f + 0.60f * rnd(i, salt, 5);
            widthK[i] = 0.7f + 0.8f * rnd(i, salt, 6);
        }
        float rMin = size * 0.025f;
        float rMax = c - 2f;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float dx = x + 0.5f - c;
                float dy = y + 0.5f - c;
                float r = (float) Math.hypot(dx, dy);
                if (r < rMin || r > rMax) continue;
                float th = (float) Math.atan2(dy, dx);
                if (th < 0f) th += (float) (Math.PI * 2.0);
                int i0 = (int) Math.floor(th / dTheta);
                float lr = (float) Math.log(r / rMin);
                float best = 0f;
                for (int di = -1; di <= 2; di++) {
                    int i = ((i0 + di) % rays + rays) % rays;
                    float da = th - angle[i];
                    if (da > Math.PI) da -= (float) (Math.PI * 2.0);
                    if (da < -Math.PI) da += (float) (Math.PI * 2.0);
                    float perp = r * (float) Math.abs(Math.sin(da));
                    if (Math.abs(da) > 1.2f) continue;
                    float hw = Math.max(streaks ? 0.55f : 0.65f, r * (streaks ? 0.0034f : 0.0030f) * widthK[i]);
                    float cov = clamp01(1f - perp / (hw + 0.9f));
                    cov = cov * cov * (3f - 2f * cov);
                    if (cov <= 0f) continue;
                    // position inside the dash pattern, in log radius
                    float pos = (lr / period[i] + phase[i]) % 1f;
                    float ld = len[i] / period[i];
                    if (pos > ld) continue;
                    float along = pos / ld;
                    float shape = (float) Math.sin(Math.PI * along);
                    shape = streaks ? (float) Math.pow(shape, 0.6) * (0.35f + 0.65f * along) : shape;
                    float v = cov * shape * bright[i];
                    if (v > best) best = v;
                }
                if (best <= 0.01f) continue;
                float fadeOut = 1f - smooth(0.72f, 1f, r / rMax);
                float fadeIn = smooth(0f, 0.12f, r / rMax);
                float a = best * fadeOut * (0.35f + 0.65f * fadeIn);
                if (a > 0.01f) im.setRGB(x, y, argb(a, 0.90f, 0.95f, 1f));
            }
        }
        return im;
    }
}
