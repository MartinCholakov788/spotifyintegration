package com.zlorbie788.spotify.hud;

import com.zlorbie788.spotify.util.Colors;
import com.zlorbie788.spotify.util.Ease;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;

/**
 * The asteroid field of the intro: the camera flies straight ahead through a field of dark, cratered rocks towards a star.
 * Each rock is one of six small pictures (see {@link IntroBake#rock}) with a grainy surface, craters, patches of lighter and
 * darker stone and a bright edge on the side that faces the star. Every frame a rock is only moved, turned so its lit side
 * points at the star, scaled by its distance, tinted with the album colours and faded into the dark with distance: one draw
 * per rock. Rocks are drawn far to near, the camera sways and rolls a little, and they pass beside the flight path, never
 * through it.
 */
final class Rocks {
    static final int VARIANTS = 6;
    private static final int COUNT = 34;
    private static final float FAR = 62f;
    /** How far the camera travels in total (world units) by the burst. */
    private static final float TRAVEL = 66f;
    /** The body of a rock fills this part of the half width of its picture. */
    private static final float BODY = 0.72f;

    private static final class Rock {
        float x, y, z0, size;
        float spin, phase, aspect;
        int variant;
        boolean flip;
        float depth;
    }

    private static Rock[] rocks;
    private static Rock[] sorted;

    private Rocks() {}

    private static float rnd(int a, int b) {
        int h = a * 0x9E3779B1 + b * 0x85EBCA6B;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        return ((h >>> 8) & 0xFFFF) / 65535f;
    }

    private static void init() {
        if (rocks != null) return;
        rocks = new Rock[COUNT];
        sorted = new Rock[COUNT];
        for (int i = 0; i < COUNT; i++) {
            Rock r = new Rock();
            // every rock passes beside the flight path, never through it
            r.size = 0.55f + 1.5f * (float) Math.pow(rnd(i, 1), 1.6);
            float clear = r.size * 1.3f + 1.7f;
            float rho = clear + rnd(i, 2) * 7f;
            float ang = rnd(i, 3) * (float) (Math.PI * 2);
            r.x = (float) Math.cos(ang) * rho * 1.25f;
            r.y = (float) Math.sin(ang) * rho * 1.0f;
            r.z0 = 5f + (FAR - 8f) * ((i + rnd(i, 4)) / COUNT);
            r.spin = (rnd(i, 5) - 0.5f) * 1.5f;
            r.phase = rnd(i, 7) * 6.28f;
            r.aspect = 0.78f + 0.42f * rnd(i, 9);
            r.variant = (i * 5 + (int) (rnd(i, 10) * 2f)) % VARIANTS;
            r.flip = rnd(i, 11) < 0.5f;
            rocks[i] = r;
            sorted[i] = r;
        }
    }

    /** How far the camera has flown at time {@code t} (the burst is at {@code burst}). */
    static float camZ(float t, float burst) {
        float u = Ease.clamp01(t / burst);
        return TRAVEL * (0.14f * u + 0.86f * (float) Math.pow(u, 2.3));
    }

    /**
     * Draws the field. {@code cx, cy} is the point the camera flies towards, {@code alpha} fades the whole field in (0..1),
     * {@code accent} is the colour of the star's light.
     */
    static void draw(GuiGraphics g, float t, float burst, int sw, int sh, float cx, float cy, int accent, float alpha) {
        init();
        if (alpha <= 0.01f) return;
        float cam = camZ(t, burst);
        float f = sh * 0.95f;
        float swayX = 0.55f * (float) Math.sin(t * 1.9) + 0.25f * (float) Math.sin(t * 4.1);
        float swayY = 0.35f * (float) Math.sin(t * 1.4 + 1.0);
        float roll = 0.10f * (float) Math.sin(t * 1.3) + 0.06f * Ease.clamp01(t / burst) * (float) Math.sin(t * 3.0);
        float cr = (float) Math.cos(roll);
        float sr = (float) Math.sin(roll);
        float dev = Gfx.dev();
        int tint = Colors.mix(Colors.WHITE, accent, 0.45f);
        int deep = 0xFF080C1E;
        float baseAngle = (float) Math.atan2(IntroBake.LIGHT_Y, IntroBake.LIGHT_X);

        int n = 0;
        for (Rock r : rocks) {
            r.depth = r.z0 - cam;
            if (r.depth > 0.6f && r.depth < FAR) sorted[n++] = r;
        }
        // far to near
        for (int i = 1; i < n; i++) {
            Rock k = sorted[i];
            int j = i - 1;
            while (j >= 0 && sorted[j].depth < k.depth) {
                sorted[j + 1] = sorted[j];
                j--;
            }
            sorted[j + 1] = k;
        }
        var pose = g.pose();
        for (int ri = 0; ri < n; ri++) {
            Rock r = sorted[ri];
            float depth = r.depth;
            float fog = Ease.clamp01((depth - 8f) / (FAR - 8f));
            fog = Math.max(fog * fog, 1f - alpha);
            if (fog > 0.97f) continue;
            float px = r.x - swayX;
            float py = r.y - swayY;
            float ux = px / depth * f;
            float uy = py / depth * f;
            float sx = cx + ux * cr - uy * sr;
            float sy = cy + ux * sr + uy * cr;
            // apparent radius of the rock and half width of its picture
            float rad = r.size * 1.05f / depth * f;
            float half = rad / BODY;
            if (half < 1.6f) continue;
            if (sx + half < 0f || sx - half > sw || sy + half < 0f || sy - half > sh) continue;

            // the picture whose pixels are closest to the size on the screen
            float need = 2f * half * dev;
            int lod = 0;
            for (int l = IntroArt.ROCK_LODS.length - 1; l >= 0; l--) {
                if (IntroArt.ROCK_LODS[l] * 1.35f >= need) {
                    lod = l;
                    break;
                }
            }
            IntroArt.Tex tex = IntroArt.rockTex(r.variant, lod);
            if (tex == null) continue;

            // turn it so the lit side faces the star in the middle of the screen, with a slow wobble
            float toStar = (float) Math.atan2(cy - sy, cx - sx);
            float base = r.flip ? -baseAngle : baseAngle;
            float rot = toStar - base + 0.32f * (float) Math.sin(t * r.spin + r.phase);
            int col = Colors.mix(tint, deep, fog * 0.75f);
            col = Colors.alpha(col, Math.max(0.02f, 1f - fog * fog));
            int hi = Math.max(2, Math.round(half));
            pose.pushMatrix();
            pose.translate(sx, sy);
            pose.rotate(rot);
            pose.scale(1f, r.flip ? -r.aspect : r.aspect);
            g.blit(RenderPipelines.GUI_TEXTURED, tex.id(), -hi, -hi, 0f, 0f, hi * 2, hi * 2, tex.w(), tex.h(), tex.w(), tex.h(), col);
            pose.popMatrix();
        }
    }
}
