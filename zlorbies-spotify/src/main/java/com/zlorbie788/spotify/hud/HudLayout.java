package com.zlorbie788.spotify.hud;

import com.zlorbie788.spotify.config.SpotifyConfig;
import net.minecraft.client.Minecraft;

/**
 * Where the player sits on the screen. The position is stored as an offset from the nearest screen edge
 * (left / right / centre, top / bottom / middle), so it stays put when the window size or GUI scale changes.
 */
public final class HudLayout {
    private HudLayout() {}

    public static int width(SpotifyConfig cfg) {
        return Math.round(cfg.style.width * cfg.scale);
    }

    public static int height(SpotifyConfig cfg) {
        return Math.round(cfg.style.height * cfg.scale);
    }

    /** Screen-space {x, y, w, h}, clamped so the player is always fully visible. */
    public static int[] bounds(Minecraft mc, SpotifyConfig cfg) {
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        return resolve(cfg.ax, cfg.ay, cfg.x, cfg.y, width(cfg), height(cfg), sw, sh);
    }

    public static int[] resolve(int ax, int ay, int ox, int oy, int w, int h, int sw, int sh) {
        int x = toScreen(ax, ox, w, sw);
        int y = toScreen(ay, oy, h, sh);
        x = Math.max(0, Math.min(x, Math.max(0, sw - w)));
        y = Math.max(0, Math.min(y, Math.max(0, sh - h)));
        return new int[]{x, y, w, h};
    }

    public static int toScreen(int anchor, int offset, int size, int screen) {
        return switch (anchor) {
            case SpotifyConfig.ANCHOR_END -> screen - size - offset;
            case SpotifyConfig.ANCHOR_CENTER -> (screen - size) / 2 + offset;
            default -> offset;
        };
    }

    public static int toOffset(int anchor, int position, int size, int screen) {
        return switch (anchor) {
            case SpotifyConfig.ANCHOR_END -> screen - size - position;
            case SpotifyConfig.ANCHOR_CENTER -> position - (screen - size) / 2;
            default -> position;
        };
    }

    /** Which edge a box at this position belongs to: the middle sixth is "centre", otherwise the nearer side. */
    public static int nearestAnchor(int position, int size, int screen) {
        int centre = position + size / 2;
        if (Math.abs(centre - screen / 2) <= screen / 12) return SpotifyConfig.ANCHOR_CENTER;
        return centre < screen / 2 ? SpotifyConfig.ANCHOR_START : SpotifyConfig.ANCHOR_END;
    }

    /** Stores a screen position (top-left corner) in the config, clamped and re-anchored to the nearest edge. */
    public static void place(SpotifyConfig cfg, int screenX, int screenY, int w, int h, int sw, int sh) {
        int x = Math.max(0, Math.min(screenX, Math.max(0, sw - w)));
        int y = Math.max(0, Math.min(screenY, Math.max(0, sh - h)));
        cfg.ax = nearestAnchor(x, w, sw);
        cfg.ay = nearestAnchor(y, h, sh);
        cfg.x = toOffset(cfg.ax, x, w, sw);
        cfg.y = toOffset(cfg.ay, y, h, sh);
    }
}
