package com.zlorbie788.spotify.hud;

import com.zlorbie788.spotify.config.LyricsConfig;
import net.minecraft.client.Minecraft;

/** Where the lyrics box sits and how big it is. The box has a fixed size for a given set of settings. */
public final class LyricsLayout {
    /** Height of one row of text. */
    public static final int ROW = 9;
    public static final int PAD_X = 8;
    public static final int PAD_Y = 7;

    public static int padX(LyricsConfig lc) {
        return lc.padX;
    }

    public static int padY(LyricsConfig lc) {
        return lc.padY;
    }

    private LyricsLayout() {}

    /** Height of one line slot: wrapped lines may take two rows, the box leaves room for one and a half. */
    public static int unit(LyricsConfig lc) {
        if (lc.overflow != LyricsConfig.Overflow.WRAP || lc.wrapRows <= 1) return ROW;
        return ROW + (lc.wrapRows - 1) * 5;
    }

    /** Unscaled height of the box. */
    public static int boxHeight(LyricsConfig lc) {
        int unit = unit(lc);
        int current = Math.round(unit * lc.currentScale);
        return 2 * padY(lc) + (lc.linesBefore + lc.linesAfter) * (unit + lc.lineGap) + current;
    }

    public static int boxWidth(LyricsConfig lc) {
        return lc.width;
    }

    public static int width(LyricsConfig lc) {
        return Math.round(boxWidth(lc) * lc.scale);
    }

    public static int height(LyricsConfig lc) {
        return Math.round(boxHeight(lc) * lc.scale);
    }

    public static int[] bounds(Minecraft mc, LyricsConfig lc) {
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        return HudLayout.resolve(lc.ax, lc.ay, lc.x, lc.y, width(lc), height(lc), sw, sh);
    }

    public static void place(LyricsConfig lc, int screenX, int screenY, int w, int h, int sw, int sh) {
        int x = Math.max(0, Math.min(screenX, Math.max(0, sw - w)));
        int y = Math.max(0, Math.min(screenY, Math.max(0, sh - h)));
        lc.ax = HudLayout.nearestAnchor(x, w, sw);
        lc.ay = HudLayout.nearestAnchor(y, h, sh);
        lc.x = HudLayout.toOffset(lc.ax, x, w, sw);
        lc.y = HudLayout.toOffset(lc.ay, y, h, sh);
    }
}
