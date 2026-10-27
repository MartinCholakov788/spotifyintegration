package com.zlorbie788.spotify.hud;

import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.config.SpotifyConfig;
import com.zlorbie788.spotify.config.UiFont;
import com.zlorbie788.spotify.media.Player;
import com.zlorbie788.spotify.media.Snapshot;
import com.zlorbie788.spotify.util.Colors;
import com.zlorbie788.spotify.util.Ease;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * A tiny bar at the top of the screen that appears for a moment when you press a music key in the world ("Next song",
 * "Volume 60%") and disappears again by itself. It replaces the text above the hotbar when it is switched on, and it
 * never covers the full player (it stays away while the player is shown).
 */
public final class MiniBar {
    public static final int W = 188;
    public static final int H = 28;
    private static final float IN = 0.18f;
    private static final float OUT = 0.35f;

    private static long shownAt;
    private static float stay;
    private static String message = "";
    private static boolean running;

    private MiniBar() {}

    /** True when keys should show their feedback here instead of above the hotbar. */
    public static boolean enabled() {
        return ConfigIo.get().miniBar;
    }

    /** Shows the bar for the configured time with this short message (may be empty). */
    public static void show(String text) {
        message = text == null ? "" : text;
        shownAt = System.nanoTime();
        stay = Math.max(1, ConfigIo.get().miniBarSeconds);
        running = true;
    }

    public static boolean showing() {
        return running;
    }

    /** HudElementRegistry callback. */
    public static void render(GuiGraphics g, DeltaTracker tracker) {
        Minecraft mc = Minecraft.getInstance();
        SpotifyConfig cfg = ConfigIo.get();
        if (!running || mc.player == null || mc.options.hideGui) return;
        float t = (System.nanoTime() - shownAt) / 1_000_000_000f;
        float total = stay + IN + OUT;
        if (t >= total) {
            running = false;
            return;
        }
        float a;
        if (t < IN) a = Ease.outCubic(t / IN);
        else if (t > total - OUT) a = 1f - Ease.inOutCubic((t - (total - OUT)) / OUT);
        else a = 1f;
        if (!cfg.motion()) a = a > 0.5f ? 1f : 0f;
        if (a <= 0.01f) return;
        draw(g, mc, cfg, message, a);
    }

    /** Draws the bar (also used by the screenshot tests). */
    public static void draw(GuiGraphics g, Minecraft mc, SpotifyConfig cfg, String text, float a) {
        int sw = mc.getWindow().getGuiScaledWidth();
        int w = Math.min(W, sw - 16);
        int x = (sw - w) / 2;
        int y = 6 - Math.round((1f - a) * 14f);
        UiFont oldFont = Gfx.font();
        int oldShift = Gfx.fontShift();
        Gfx.useFont(cfg.font, cfg.fontShift);
        try {
            Font f = mc.font;
            int accent = PlayerHud.smoothAccent();
            boolean hc = cfg.highContrast;
            int top = hc ? Colors.alpha(Colors.BLACK, 1f) : Colors.alpha(Colors.mix(Colors.CARD, accent, 0.16f), 0.93f * a);
            int bottom = hc ? top : Colors.alpha(Colors.CARD_DARK, 0.93f * a);
            int edge = hc ? Colors.alpha(Colors.WHITE, a) : Colors.alpha(Colors.mix(Colors.LINE, accent, 0.45f), a);
            Gfx.box(g, x, y, w, H, Math.max(1, cfg.corner), top, bottom, edge);

            Snapshot s = Player.snap();
            boolean playing = Player.playing();
            String[] icon = playing ? Icons.PAUSE : Icons.PLAY;
            Icons.draw(g, icon, x + 7, y + (H - Icons.height(icon)) / 2, 1, Colors.alpha(accent, a));
            int tx = x + 7 + Icons.width(icon) + 7;
            int tw = x + w - 7 - tx;
            String head = text.isEmpty() ? (playing ? "NOW PLAYING" : s.active() ? "PAUSED" : "NOTHING PLAYING") : text;
            Gfx.text(g, f, Gfx.fit(f, head, tw), tx, y + 4, accent, a, cfg.textShadow);
            String line = !s.active() ? "" : s.artist().isEmpty() ? s.title() : s.title() + " - " + s.artist();
            Gfx.text(g, f, Gfx.fit(f, line, tw), tx, y + 15, hc ? Colors.WHITE : Colors.TEXT, a, cfg.textShadow);
            float p = Player.progress();
            int bw = w - 14;
            g.fill(x + 7, y + H - 4, x + 7 + bw, y + H - 3, Colors.alpha(Colors.LINE, 0.8f * a));
            int fw = Math.round(bw * p);
            if (fw > 0) g.fill(x + 7, y + H - 4, x + 7 + fw, y + H - 3, Colors.alpha(accent, a));
        } finally {
            Gfx.useFont(oldFont, oldShift);
        }
    }
}
