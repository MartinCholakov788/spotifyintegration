package com.zlorbie788.spotify.hud;

import com.mojang.blaze3d.platform.NativeImage;
import com.zlorbie788.spotify.client.SpotifyClient;
import com.zlorbie788.spotify.media.Covers;
import com.zlorbie788.spotify.util.Colors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * The pictures of the intro and the few helpers that place them. The pictures are cut by {@link IntroBake} on a background
 * thread (the first time, shortly after the game starts or the player opens), turned into textures on the render thread as
 * soon as they are ready, and then reused. While a picture is not ready yet, whatever uses it is simply left out of the frame,
 * so nothing ever waits.
 */
final class IntroArt {
    /** A texture with its size in pixels. */
    record Tex(Identifier id, int w, int h) {}

    private static final Map<String, Tex> TEX = new HashMap<>();
    private static final ConcurrentHashMap<String, NativeImage> READY = new ConcurrentHashMap<>();
    private static final Set<String> ASKED = ConcurrentHashMap.newKeySet();
    private static volatile boolean broken;
    private static ExecutorService pool;

    static final int[] RING_SIZES = {32, 64, 128, 256, 512, 1024};
    static final int[] ROCK_LODS = {256, 128, 64, 32};
    static final int STAR_LAYERS = 4;
    private static final int GLOW = 256;
    private static final int WARP = 768;

    private IntroArt() {}

    static boolean broken() {
        return broken;
    }

    // ------------------------------------------------------------ the cache

    private static synchronized ExecutorService pool() {
        if (pool == null) {
            pool = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Spotify-Intro-Art");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });
        }
        return pool;
    }

    /** The texture for {@code key}, or null while it is still being cut (the first call starts the cutting). */
    static Tex get(String key, Supplier<BufferedImage> baker) {
        Tex have = TEX.get(key);
        if (have != null) return have;
        if (broken) return null;
        NativeImage ready = READY.remove(key);
        if (ready != null) {
            try {
                Identifier id = Identifier.fromNamespaceAndPath("zlorbies-spotify", "intro/" + key);
                int w = ready.getWidth();
                int h = ready.getHeight();
                DynamicTexture texture = new DynamicTexture(() -> "zlorbie_intro", ready);
                texture.upload();
                Minecraft.getInstance().getTextureManager().register(id, texture);
                Tex t = new Tex(id, w, h);
                TEX.put(key, t);
                return t;
            } catch (Throwable e) {
                fail(key, e);
                return null;
            }
        }
        if (ASKED.add(key)) {
            pool().execute(() -> {
                try {
                    READY.put(key, Covers.toNative(baker.get()));
                } catch (Throwable e) {
                    fail(key, e);
                }
            });
        }
        return null;
    }

    private static void fail(String key, Throwable e) {
        if (!broken) SpotifyClient.LOGGER.warn("[Spotify Integration Mod] the intro pictures are off ({}): {}", key, e.toString());
        broken = true;
    }

    /** Gives a texture back (a picture for a screen size that is no longer used). */
    private static void drop(String key) {
        Tex t = TEX.remove(key);
        ASKED.remove(key);
        if (t != null) {
            try {
                Minecraft.getInstance().getTextureManager().release(t.id());
            } catch (Throwable ignored) {
                // the game releases them when it closes anyway
            }
        }
    }

    /** The few pictures the everyday player effects use (light streak, curtains, dots); cut at game start whatever the intro setting. */
    static void prewarmBasic() {
        if (broken) return;
        shineTex(false);
        shineTex(true);
        curtainTex();
        discTex();
    }

    /** Starts cutting the pictures that do not depend on the screen, so they are ready by the time the intro plays. */
    static void prewarm() {
        if (broken) return;
        glowTex();
        for (int s : RING_SIZES) ringTex(s);
        for (int v = 0; v < Rocks.VARIANTS; v++) for (int l = 0; l < ROCK_LODS.length; l++) rockTex(v, l);
        warpTex(false);
        warpTex(true);
        hband();
        vband();
        glintTex();
    }

    // ------------------------------------------------------------ the pictures

    static Tex glowTex() {
        return get("glow", () -> IntroBake.glow(GLOW));
    }

    static Tex hband() {
        return get("hband", IntroBake::hband);
    }

    static Tex vband() {
        return get("vband", IntroBake::vband);
    }

    static Tex glintTex() {
        return get("glint", IntroBake::glint);
    }

    static Tex shineTex(boolean core) {
        return get(core ? "shine_core" : "shine_soft", () -> IntroBake.shine(core));
    }

    static Tex curtainTex() {
        return get("curtain", IntroBake::curtain);
    }

    static Tex discTex() {
        return get("disc", IntroBake::disc);
    }

    static Tex ringTex(int size) {
        return get("ring" + size, () -> IntroBake.ring(size));
    }

    static Tex warpTex(boolean streaks) {
        return get(streaks ? "warp_streaks" : "warp_dots", () -> IntroBake.warp(WARP, streaks));
    }

    static Tex rockTex(int variant, int lod) {
        int size = ROCK_LODS[lod];
        return get("rock" + variant + "_" + size, () -> IntroBake.shrink(IntroBake.rockBase(variant), size));
    }

    private static String starKey;

    /** The star layers for a screen of this size (in GUI pixels). Returns the scale that was used or 0 while they are being cut. */
    static int starSize(int sw, int sh, int[] out) {
        int scale = 1;
        while ((long) (sw / scale + 24) * (sh / scale + 1) > 1_100_000L) scale++;
        out[0] = sw / scale + 24;
        out[1] = sh / scale + 1;
        return scale;
    }

    static Tex starsTex(int layer, int sw, int sh) {
        int[] size = new int[2];
        starSize(sw, sh, size);
        String suffix = "_" + size[0] + "x" + size[1];
        String key = "stars" + layer + suffix;
        if (starKey != null && !starKey.equals(suffix)) {
            for (int l = 0; l < STAR_LAYERS; l++) drop("stars" + l + starKey);
        }
        starKey = suffix;
        return get(key, () -> IntroBake.stars(layer, size[0], size[1], STAR_LAYERS));
    }

    // ------------------------------------------------------------ placing them

    private static void blit(GuiGraphics g, Tex t, int x, int y, int w, int h, int color) {
        g.blit(RenderPipelines.GUI_TEXTURED, t.id(), x, y, 0f, 0f, w, h, t.w(), t.h(), t.w(), t.h(), color);
    }

    /** A round soft glow of radius {@code r} around a point; {@code color} carries the strength in its alpha. */
    static void glow(GuiGraphics g, float cx, float cy, float r, int color, float alpha) {
        if (r < 0.5f || alpha <= 0.004f) return;
        Tex t = glowTex();
        if (t == null) return;
        int ri = Math.max(1, Math.round(r));
        var p = g.pose();
        p.pushMatrix();
        p.translate(cx, cy);
        blit(g, t, -ri, -ri, ri * 2, ri * 2, Colors.alpha(color, Math.min(1f, alpha)));
        p.popMatrix();
    }

    /** Light fading to both left and right, filling a rectangle (the same all the way down). */
    static void hlight(GuiGraphics g, int x0, int y0, int x1, int y1, int color, float alpha) {
        if (x1 <= x0 || y1 <= y0 || alpha <= 0.004f) return;
        Tex t = hband();
        if (t != null) blit(g, t, x0, y0, x1 - x0, y1 - y0, Colors.alpha(color, Math.min(1f, alpha)));
    }

    /** Light fading up and down, filling a rectangle (the same all the way across). */
    static void vlight(GuiGraphics g, int x0, int y0, int x1, int y1, int color, float alpha) {
        if (x1 <= x0 || y1 <= y0 || alpha <= 0.004f) return;
        Tex t = vband();
        if (t != null) blit(g, t, x0, y0, x1 - x0, y1 - y0, Colors.alpha(color, Math.min(1f, alpha)));
    }

    /** A thin circle of radius {@code r} around a point (the picture closest in size is used). */
    static void ring(GuiGraphics g, float cx, float cy, float r, int color, float alpha) {
        if (r < 2f || alpha <= 0.004f) return;
        float dev = Gfx.dev();
        float diam = 2f * r * dev;
        int size = RING_SIZES[RING_SIZES.length - 1];
        for (int s : RING_SIZES) {
            if (s * 1.5f >= diam) {
                size = s;
                break;
            }
        }
        Tex t = ringTex(size);
        if (t == null) return;
        float rho = 1f - 5f / size;
        int half = Math.max(2, Math.round(r / rho));
        var p = g.pose();
        p.pushMatrix();
        p.translate(cx, cy);
        blit(g, t, -half, -half, half * 2, half * 2, Colors.alpha(color, Math.min(1f, alpha)));
        p.popMatrix();
    }

    /**
     * A diagonal streak of light crossing a box once ({@code t} 0..1, 0 and 1 are outside), clipped to the box, from two
     * pictures: a soft band and a thin bright line. Returns false when the pictures are not ready.
     */
    static boolean shine(GuiGraphics g, float x, float y, float w, float h, int color, float t, float alpha) {
        Tex soft = shineTex(false);
        Tex core = shineTex(true);
        if (soft == null || core == null) return false;
        float env = (float) Math.sin(Math.PI * t);
        float slope = 0.55f;
        float travel = w + h * slope + 40f;
        float cx = x - 20f + travel * com.zlorbie788.spotify.util.Ease.inOutCubic(t);
        float kx = 1.1f * h / 256f;
        int x0 = Math.round(cx - 192f * kx);
        int sw = Math.round(256f * kx);
        int yy = (int) y;
        int hh = (int) h;
        g.enableScissor((int) x + 1, (int) y + 1, (int) (x + w) - 1, (int) (y + h) - 1);
        blit(g, soft, x0, yy, sw, hh, Colors.alpha(color, Math.min(1f, 0.10f * env * alpha)));
        blit(g, core, x0, yy, sw, hh, Colors.alpha(Colors.WHITE, Math.min(1f, 0.11f * env * alpha)));
        g.disableScissor();
        return true;
    }

    /** Three curtains of light (see Fx.aurora), each one picture stretched over the width it covers. */
    static boolean curtain(GuiGraphics g, int x0, int y0, int x1, int y1, int[] from, int[] to, int[] colors, float[] alphas, int n) {
        Tex t = curtainTex();
        if (t == null) return false;
        g.enableScissor(x0, y0, x1, y1);
        for (int i = 0; i < n; i++) {
            if (to[i] > from[i] && alphas[i] > 0.004f) blit(g, t, from[i], y0, to[i] - from[i], y1 - y0, Colors.alpha(colors[i], Math.min(1f, alphas[i])));
        }
        g.disableScissor();
        return true;
    }

    /** A round dot of radius {@code r} at a point (sub-pixel positions allowed). Returns false when the picture is not ready. */
    static boolean disc(GuiGraphics g, float cx, float cy, float r, int color) {
        Tex t = discTex();
        if (t == null) return false;
        var p = g.pose();
        p.pushMatrix();
        p.translate(cx, cy);
        p.scale(r / 8f, r / 8f);
        blit(g, t, -8, -8, 16, 16, color);
        p.popMatrix();
        return true;
    }

    /** The four-pointed glint; {@code size} is the length of the long arm from the middle. */
    static void glint(GuiGraphics g, float cx, float cy, float size, int color) {
        Tex t = glintTex();
        if (t == null || size < 1f) return;
        int s = Math.max(1, Math.round(size));
        var p = g.pose();
        p.pushMatrix();
        p.translate(cx, cy);
        blit(g, t, -s, -s, s * 2, s * 2, color);
        p.popMatrix();
    }

    private static int sigScale(boolean halo) {
        int dev = Math.max(1, Math.round(Gfx.dev()));
        return halo ? Math.max(1, (dev + 1) / 2) : dev;
    }

    private static Tex sigTex(Signature sig, boolean flourish, boolean halo, int scale) {
        String key = "sig" + (flourish ? "f" : "l") + (halo ? "h" : "c") + Math.round(sig.thick * 20f) + "_" + Math.round(sig.width) + "_" + scale;
        return get(key, () -> sig.bake(flourish, halo, scale));
    }

    /** Starts cutting the four pictures of a signature for the current screen, so they are ready when the pen starts. */
    static void warmSignature(Signature sig) {
        for (int i = 0; i < 4; i++) sigTex(sig, (i & 1) != 0, (i & 2) != 0, sigScale((i & 2) != 0));
    }

    /**
     * Draws one picture of a signature (the letters or the flourish, core or halo) at the signature's origin, clipped to
     * the given box in signature coordinates (use +-{@link Float#MAX_VALUE} for no limit on a side).
     */
    static void signaturePart(GuiGraphics g, Signature sig, boolean flourish, boolean halo, float ox, float oy,
                              float clipX0, float clipX1, float clipY0, float clipY1, int color) {
        float[] b = flourish ? sig.boundsFlourish : sig.boundsLetters;
        int scale = sigScale(halo);
        Tex t = sigTex(sig, flourish, halo, scale);
        if (t == null) return;
        float x0 = Math.max(b[0], clipX0);
        float x1 = Math.min(b[2], clipX1);
        float y0 = Math.max(b[1], clipY0);
        float y1 = Math.min(b[3], clipY1);
        if (x1 <= x0 || y1 <= y0) return;
        var p = g.pose();
        boolean clipped = x0 > b[0] || x1 < b[2] || y0 > b[1] || y1 < b[3];
        if (clipped) {
            g.enableScissor((int) Math.floor(ox + x0), (int) Math.floor(oy + y0), (int) Math.ceil(ox + x1), (int) Math.ceil(oy + y1));
        }
        p.pushMatrix();
        p.translate(ox + b[0], oy + b[1]);
        p.scale(1f / scale, 1f / scale);
        blit(g, t, 0, 0, t.w(), t.h(), color);
        p.popMatrix();
        if (clipped) g.disableScissor();
    }
}
