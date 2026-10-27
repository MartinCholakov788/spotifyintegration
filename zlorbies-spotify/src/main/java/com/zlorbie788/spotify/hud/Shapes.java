package com.zlorbie788.spotify.hud;

import com.mojang.blaze3d.platform.NativeImage;
import com.zlorbie788.spotify.client.SpotifyClient;
import com.zlorbie788.spotify.config.SpotifyConfig.ShapeKind;
import com.zlorbie788.spotify.media.Covers;
import com.zlorbie788.spotify.util.Colors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.awt.image.BufferedImage;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Smooth shapes. Everything is drawn in device pixels (one GUI pixel is {@code dev} device pixels), so a corner is as sharp as
 * the screen allows instead of a staircase of GUI pixels. Straight parts are plain rectangles; each corner is a small
 * anti-aliased picture (cut once, reused). Shapes: rounded, cut corners, "tech" (two cut corners), square.
 *
 * <p>All positions and sizes are in the units of the current pose; {@code dev} is how many device pixels one of those
 * units is (GUI scale, times the pose scale of the player).
 */
public final class Shapes {
    private static final int LIMIT = 260;
    private static final int MAX_R = 120;
    private static final Map<Long, Identifier> CACHE = new LinkedHashMap<>(256, 0.75f, true);
    private static int nextId;
    private static boolean broken;

    private Shapes() {}

    // ------------------------------------------------------------ the corner pictures

    /** Coverage 0..1 of one pixel of a top-left corner of size {@code r}; {@code t} > 0 = only the ring of that thickness. */
    public static float coverage(ShapeKind kind, int r, int t, int px, int py) {
        double fx = px + 0.5;
        double fy = py + 0.5;
        double outer;
        double inner = 0;
        if (kind == ShapeKind.ROUND) {
            double d = Math.hypot(r - fx, r - fy);
            outer = clamp(r - d + 0.5);
            if (t > 0) inner = clamp((r - t) - d + 0.5);
        } else {
            double s = Math.sqrt(2.0);
            double a = fx + fy;
            outer = clamp((a - r) / s + 0.5);
            if (t > 0) inner = clamp((a - r - t * s) / s + 0.5);
        }
        return (float) Math.max(0.0, outer - inner);
    }

    private static double clamp(double v) {
        return v < 0 ? 0 : Math.min(1, v);
    }

    private static BufferedImage bake(ShapeKind kind, int r, int t, int quadrant) {
        BufferedImage image = new BufferedImage(r, r, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < r; y++) {
            for (int x = 0; x < r; x++) {
                float a = coverage(kind, r, t, x, y);
                int ax = (quadrant == 1 || quadrant == 2) ? r - 1 - x : x;
                int ay = (quadrant == 2 || quadrant == 3) ? r - 1 - y : y;
                int alpha = Math.round(a * 255f);
                image.setRGB(ax, ay, (alpha << 24) | 0x00FFFFFF);
            }
        }
        return image;
    }

    private static Identifier corner(ShapeKind kind, int r, int t, int quadrant) {
        if (broken) return null;
        long key = (((long) kind.ordinal() * 512L + r) * 64L + t) * 4L + quadrant;
        Identifier id = CACHE.get(key);
        if (id != null) return id;
        try {
            NativeImage image = Covers.toNative(bake(kind, r, t, quadrant));
            id = Identifier.fromNamespaceAndPath("zlorbies-spotify", "shape/c" + (++nextId));
            DynamicTexture texture = new DynamicTexture(() -> "zlorbie_shape", image);
            texture.upload();
            Minecraft mc = Minecraft.getInstance();
            mc.getTextureManager().register(id, texture);
            CACHE.put(key, id);
            if (CACHE.size() > LIMIT) {
                Iterator<Map.Entry<Long, Identifier>> it = CACHE.entrySet().iterator();
                int drop = CACHE.size() - LIMIT + 20;
                while (it.hasNext() && drop-- > 0) {
                    Map.Entry<Long, Identifier> e = it.next();
                    mc.getTextureManager().release(e.getValue());
                    it.remove();
                }
            }
            return id;
        } catch (Throwable e) {
            broken = true;
            SpotifyClient.LOGGER.warn("[Spotify Integration Mod] smooth corners are off: {}", e.toString());
            return null;
        }
    }

    /** Forgets every corner picture (the game releases them anyway when it closes). */
    public static void clear() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) for (Identifier id : CACHE.values()) mc.getTextureManager().release(id);
        CACHE.clear();
    }

    // ------------------------------------------------------------ geometry

    /** Corner sizes in device pixels: top-left, top-right, bottom-right, bottom-left. */
    static int[] corners(ShapeKind kind, float radiusPx, int w, int h) {
        int r = Math.round(radiusPx);
        r = Math.max(0, Math.min(r, Math.min(Math.min(w, h) / 2, MAX_R)));
        switch (kind) {
            case SQUARE:
                return new int[]{0, 0, 0, 0};
            case TECH:
                return new int[]{r, 0, r, 0};
            default:
                return new int[]{r, r, r, r};
        }
    }

    private static void begin(GuiGraphics g, float dev) {
        g.pose().pushMatrix();
        g.pose().scale(1f / dev, 1f / dev);
    }

    private static void end(GuiGraphics g) {
        g.pose().popMatrix();
    }

    private static int px(float v, float dev) {
        return Math.round(v * dev);
    }

    private static int colorAt(int top, int bottom, float t) {
        return top == bottom ? top : Colors.mix(top, bottom, Math.max(0f, Math.min(1f, t)));
    }

    private static void blit(GuiGraphics g, Identifier id, int x, int y, int size, int color) {
        g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0f, 0f, size, size, size, size, size, size, color);
    }

    private static void rect(GuiGraphics g, int x1, int y1, int x2, int y2, int top, int bottom, int h) {
        if (x2 <= x1 || y2 <= y1) return;
        if (top == bottom) g.fill(x1, y1, x2, y2, top);
        else g.fillGradient(x1, y1, x2, y2, colorAt(top, bottom, y1 / (float) h), colorAt(top, bottom, y2 / (float) h));
    }

    // ------------------------------------------------------------ public drawing

    /** A filled shape with a vertical colour gradient (pass the same colour twice for a flat fill). */
    public static void fill(GuiGraphics g, float dev, float x, float y, float w, float h, ShapeKind kind, float radius,
                            int top, int bottom) {
        if (top == 0 && bottom == 0) return;
        int x1 = px(x, dev);
        int y1 = px(y, dev);
        int W = px(x + w, dev) - x1;
        int H = px(y + h, dev) - y1;
        if (W <= 0 || H <= 0) return;
        int[] c = corners(kind, radius * dev, W, H);
        int tl = c[0], tr = c[1], br = c[2], bl = c[3];
        int topBand = Math.max(tl, tr);
        int botBand = Math.max(bl, br);
        begin(g, dev);
        try {
            g.pose().translate(x1, y1);
            rect(g, 0, topBand, W, H - botBand, top, bottom, H);
            rect(g, tl, 0, W - tr, topBand, top, bottom, H);
            rect(g, bl, H - botBand, W - br, H, top, bottom, H);
            ShapeKind k = kind == ShapeKind.TECH ? ShapeKind.CHAMFER : kind;
            cornerFill(g, k, tl, 0, 0, 0, topBand, colorAt(top, bottom, tl / 2f / H), top, bottom, H);
            cornerFill(g, k, tr, 1, W - tr, 0, topBand, colorAt(top, bottom, tr / 2f / H), top, bottom, H);
            cornerFill(g, k, br, 2, W - br, H - br, botBand, colorAt(top, bottom, 1f - br / 2f / H), top, bottom, H);
            cornerFill(g, k, bl, 3, 0, H - bl, botBand, colorAt(top, bottom, 1f - bl / 2f / H), top, bottom, H);
        } finally {
            end(g);
        }
    }

    private static void cornerFill(GuiGraphics g, ShapeKind kind, int r, int quadrant, int x, int y, int band, int color,
                                   int top, int bottom, int H) {
        if (r <= 0) return;
        Identifier id = corner(kind, r, 0, quadrant);
        if (id != null) blit(g, id, x, y, r, color);
        else g.fill(x, y, x + r, y + r, color);
        // the strip beside the corner when the neighbouring corner is taller
        if (r < band) {
            boolean topSide = quadrant == 0 || quadrant == 1;
            if (topSide) rect(g, x, r, x + r, band, top, bottom, H);
            else rect(g, x, H - band, x + r, y, top, bottom, H);
        }
    }

    /** A border of the given thickness (GUI pixels, at least one device pixel) that follows the shape. */
    public static void ring(GuiGraphics g, float dev, float x, float y, float w, float h, ShapeKind kind, float radius,
                            float thickness, int color) {
        if (color == 0 || thickness <= 0f) return;
        int x1 = px(x, dev);
        int y1 = px(y, dev);
        int W = px(x + w, dev) - x1;
        int H = px(y + h, dev) - y1;
        if (W <= 0 || H <= 0) return;
        int T = Math.max(1, Math.min(Math.round(thickness * dev), Math.min(W, H) / 2));
        int[] c = corners(kind, radius * dev, W, H);
        for (int i = 0; i < 4; i++) if (c[i] > 0 && c[i] < T) c[i] = Math.min(T, Math.min(W, H) / 2);
        int tl = c[0], tr = c[1], br = c[2], bl = c[3];
        begin(g, dev);
        try {
            g.pose().translate(x1, y1);
            fillRect(g, tl > 0 ? tl : 0, 0, W - (tr > 0 ? tr : 0), T, color);
            fillRect(g, bl > 0 ? bl : 0, H - T, W - (br > 0 ? br : 0), H, color);
            fillRect(g, 0, tl > 0 ? tl : T, T, H - (bl > 0 ? bl : T), color);
            fillRect(g, W - T, tr > 0 ? tr : T, W, H - (br > 0 ? br : T), color);
            ShapeKind k = kind == ShapeKind.TECH ? ShapeKind.CHAMFER : kind;
            cornerRing(g, k, tl, T, 0, 0, 0, color);
            cornerRing(g, k, tr, T, 1, W - tr, 0, color);
            cornerRing(g, k, br, T, 2, W - br, H - br, color);
            cornerRing(g, k, bl, T, 3, 0, H - bl, color);
        } finally {
            end(g);
        }
    }

    private static void fillRect(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        if (x2 > x1 && y2 > y1) g.fill(x1, y1, x2, y2, color);
    }

    private static void cornerRing(GuiGraphics g, ShapeKind kind, int r, int t, int quadrant, int x, int y, int color) {
        if (r <= 0) return;
        Identifier id = corner(kind, r, Math.min(t, 63), quadrant);
        if (id != null) blit(g, id, x, y, r, color);
        else g.renderOutline(x, y, r, r, color);
    }

    /** Fill and border in one call. {@code edge} = 0 or {@code borderWidth} = 0 for no border. */
    public static void panel(GuiGraphics g, float dev, float x, float y, float w, float h, ShapeKind kind, float radius,
                             int top, int bottom, int edge, float borderWidth) {
        fill(g, dev, x, y, w, h, kind, radius, top, bottom);
        if (edge != 0 && borderWidth > 0f) ring(g, dev, x, y, w, h, kind, radius, borderWidth, edge);
    }

    /** A disc (or a dot) of the given radius. */
    public static void disc(GuiGraphics g, float dev, float cx, float cy, float radius, int color) {
        fill(g, dev, cx - radius, cy - radius, radius * 2f, radius * 2f, ShapeKind.ROUND, radius, color, color);
    }

    /** A soft light around a shape: rings that fade outwards. {@code strength} 1 = normal. */
    public static void glow(GuiGraphics g, float dev, float x, float y, float w, float h, ShapeKind kind, float radius,
                            int color, float strength) {
        if (strength <= 0.01f) return;
        int layers = 6;
        for (int i = layers; i >= 1; i--) {
            float fade = 1f - (i - 1f) / layers;
            float a = 0.20f * strength * fade * fade;
            ring(g, dev, x - i, y - i, w + 2 * i, h + 2 * i, kind, radius + i, 1f, Colors.alpha(color, Math.min(1f, a)));
        }
    }

    /** A soft dark shadow under a shape. */
    public static void shadow(GuiGraphics g, float dev, float x, float y, float w, float h, ShapeKind kind, float radius,
                              float strength) {
        if (strength <= 0.01f) return;
        for (int i = 5; i >= 1; i--) {
            float e = i * 1.6f;
            fill(g, dev, x - e, y - e + 3f, w + 2 * e, h + 2 * e, kind, radius + e, Colors.alpha(0xFF000000, 0.06f * strength),
                    Colors.alpha(0xFF000000, 0.06f * strength));
        }
    }
}
