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
 * The song-change toast: a small card that slides in from the screen edge ("Now playing: title, artist, cover") when a
 * different song starts, stays for a few seconds and slides out again. By default it only appears while the player
 * itself is hidden. Where it is, how long it stays and its size are in Settings > Alerts, and it can be switched off.
 */
public final class Toast {
    public static final int W = 196;
    public static final int H = 46;
    private static final float IN = 0.42f;
    private static final float OUT = 0.38f;

    private static int lastSerial = -1;
    private static long startedAt;
    private static boolean running;
    private static String title = "";
    private static String artist = "";
    private static boolean test;

    private Toast() {}

    /** Shows the toast now for the current song (or a sample song when nothing plays): the "Test" button. */
    public static void test() {
        Snapshot s = Player.snap();
        if (s.active()) {
            title = s.title().isBlank() ? "Unknown title" : s.title();
            artist = s.artist();
        } else {
            title = "Midnight City";
            artist = "M83";
        }
        test = true;
        start();
    }

    private static void start() {
        startedAt = System.nanoTime();
        running = true;
    }

    /** True while a toast is on screen. */
    public static boolean showing() {
        return running;
    }

    /** Looks for a song change (call every frame). */
    static void watch(SpotifyConfig cfg) {
        int serial = Player.trackSerial();
        if (serial == lastSerial) return;
        boolean first = lastSerial == -1;
        lastSerial = serial;
        if (first || !cfg.toast) return; // the song that was already playing when the world opened is not news
        Snapshot s = Player.snap();
        if (!s.active() || s.isAd()) return;
        if (cfg.toastWhen == SpotifyConfig.ToastWhen.HIDDEN && !PlayerHud.hiddenNow()) return;
        title = s.title().isBlank() ? "Unknown title" : s.title();
        artist = s.artist();
        test = false;
        start();
    }

    /** HudElementRegistry callback. */
    public static void render(GuiGraphics g, DeltaTracker tracker) {
        Minecraft mc = Minecraft.getInstance();
        SpotifyConfig cfg = ConfigIo.get();
        if (mc.player == null || mc.options.hideGui) return;
        watch(cfg);
        if (!running) return;
        if (!cfg.toast && !test) {
            running = false;
            return;
        }
        float total = Math.max(1, cfg.toastSeconds) + IN + OUT;
        float t = (System.nanoTime() - startedAt) / 1_000_000_000f;
        if (t >= total) {
            running = false;
            test = false;
            return;
        }
        float amount;
        if (t < IN) amount = Ease.outCubic(t / IN);
        else if (t > total - OUT) amount = 1f - Ease.inOutCubic((t - (total - OUT)) / OUT);
        else amount = 1f;
        if (!cfg.motion()) amount = amount > 0.5f ? 1f : 0f;
        draw(g, mc, cfg, title, artist, amount, t - IN, Math.max(1, cfg.toastSeconds), true);
    }

    /** Where the toast sits at rest and how it is shifted by {@code amount} (1 = in place, 0 = off screen). */
    static int[] place(SpotifyConfig cfg, int sw, int sh, float scale, float amount) {
        int w = Math.round(W * scale);
        int h = Math.round(H * scale);
        int margin = 8;
        SpotifyConfig.Corner corner = cfg.toastCorner;
        int x = corner.side() < 0 ? margin : corner.side() > 0 ? sw - w - margin : (sw - w) / 2;
        int y = corner.top() ? margin : sh - h - margin - (corner == SpotifyConfig.Corner.BOTTOM_CENTER ? 28 : 0);
        float away = 1f - amount;
        if (corner.side() < 0) x -= Math.round((w + margin + 4) * away);
        else if (corner.side() > 0) x += Math.round((w + margin + 4) * away);
        else y += Math.round((corner.top() ? -(h + margin + 4) : (h + margin + 32)) * away);
        return new int[]{x, y, w, h};
    }

    /** Draws the toast. {@code amount} is its slide (0..1), {@code age} the seconds it has been in place. */
    public static void draw(GuiGraphics g, Minecraft mc, SpotifyConfig cfg, String name, String by, float amount, float age,
                            float stay, boolean live) {
        if (amount <= 0.01f) return;
        float scale = cfg.toastScale;
        int[] b = place(cfg, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight(), scale, amount);
        drawAt(g, mc, cfg, name, by, b[0], b[1], scale, Ease.clamp01(amount * 1.4f), age, stay);
    }

    /** Draws the toast at a fixed place (the settings preview uses this). */
    public static void drawAt(GuiGraphics g, Minecraft mc, SpotifyConfig cfg, String name, String by, int x, int y,
                              float scale, float alpha, float age, float stay) {
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate((float) x, (float) y);
        pose.scale(scale, scale);
        float oldDev = Gfx.setDev((float) mc.getWindow().getGuiScale() * scale);
        UiFont oldFont = Gfx.font();
        int oldShift = Gfx.fontShift();
        Gfx.useFont(cfg.font, cfg.fontShift);
        try {
            body(g, mc.font, mc, cfg, name, by, alpha, age, stay);
        } finally {
            Gfx.useFont(oldFont, oldShift);
            Gfx.setDev(oldDev);
            pose.popMatrix();
        }
    }

    private static void body(GuiGraphics g, Font font, Minecraft mc, SpotifyConfig cfg, String name, String by, float a,
                             float age, float stay) {
        int accent = PlayerHud.smoothAccent();
        boolean hc = cfg.highContrast;
        float r = Math.min(cfg.radius, H / 2f);
        SpotifyConfig.ShapeKind kind = cfg.shape;
        if (cfg.showGlow && !hc) Shapes.glow(g, Gfx.dev(), 0, 0, W, H, kind, r, accent, 0.9f * a);
        int top = hc ? Colors.alpha(Colors.BLACK, Math.max(0.92f, a)) : Colors.alpha(Colors.mix(Colors.CARD, accent, 0.16f), 0.95f * a);
        int bottom = hc ? top : Colors.alpha(Colors.CARD_DARK, 0.95f * a);
        int edge = hc ? Colors.alpha(Colors.WHITE, a) : Colors.alpha(Colors.mix(Colors.LINE, accent, 0.45f), a);
        Gfx.panel(g, 0, 0, W, H, kind, r, top, bottom, edge, Math.max(1f, cfg.borderWidth));

        int pad = 7;
        int cover = H - 2 * pad;
        int tx = pad;
        if (cfg.toastCover) {
            PlayerHud.cover(g, mc, cfg, pad, pad, cover, cfg.coverShape == SpotifyConfig.CoverShape.CIRCLE, a, 1f, false);
            tx = pad + cover + 8;
        }
        int tw = W - tx - pad;
        int text = hc ? Colors.WHITE : Colors.TEXT;
        int soft = hc ? Colors.WHITE : Colors.TEXT_SOFT;
        Gfx.text(g, font, "NOW PLAYING", tx, pad, accent, a, cfg.textShadow);
        Gfx.line(g, font, name, tx, pad + 11, tw, text, a, true, cfg.textShadow, false, 0);
        Gfx.line(g, font, by == null ? "" : by, tx, pad + 23, tw, soft, a, false, cfg.textShadow, false, 0);

        // a thin line along the bottom runs down while the toast waits
        float left = Ease.clamp01(1f - age / Math.max(1f, stay));
        int lw = Math.round((W - 2 * pad) * left);
        if (lw > 0) {
            g.fill(pad, H - 4, pad + lw, H - 3, Colors.alpha(accent, 0.85f * a));
        }
    }
}
