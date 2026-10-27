package com.zlorbie788.spotify.hud;

import com.zlorbie788.spotify.config.SafeMode;
import com.zlorbie788.spotify.util.Colors;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** A small red strip that says plainly what failed and where the one-click fix is. Drawn with plain Minecraft text on purpose. */
public final class SafeBanner {
    private SafeBanner() {}

    public static void render(GuiGraphics g, DeltaTracker dt) {
        String problem = SafeMode.problem();
        if (problem.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.font == null) return;
        // keep it for a while, then let it go quietly (the reason stays in the settings)
        if (!SafeMode.fresh() && mc.screen == null) return;
        Font f = mc.font;
        String a = "Spotify Integration Mod: " + problem;
        String b = "Open the settings (Keys & setup) and press \"Reset to safe look\".";
        int w = Math.min(mc.getWindow().getGuiScaledWidth() - 16, Math.max(f.width(a), f.width(b)) + 12);
        int x = (mc.getWindow().getGuiScaledWidth() - w) / 2;
        int y = 4;
        g.fill(x, y, x + w, y + 25, Colors.alpha(0xFF2A0A10, 0.92f));
        g.fill(x, y, x + 2, y + 25, 0xFFFF5566);
        g.drawString(f, f.plainSubstrByWidth(a, w - 10), x + 6, y + 4, 0xFFFFB0B8, false);
        g.drawString(f, f.plainSubstrByWidth(b, w - 10), x + 6, y + 14, 0xFFE0E0E8, false);
    }
}
