package com.zlorbie788.spotify.hud;

import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.config.LyricsConfig;
import com.zlorbie788.spotify.config.SpotifyConfig;
import com.zlorbie788.spotify.lyrics.Lyrics;
import com.zlorbie788.spotify.media.Covers;
import com.zlorbie788.spotify.media.Player;
import com.zlorbie788.spotify.media.Snapshot;
import com.zlorbie788.spotify.util.Colors;
import com.zlorbie788.spotify.util.Ease;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The lyrics on the game screen: a second movable element next to the player. It shows only when there are
 * time-synced lyrics for the song (or, if you choose, a small note), fades in and out, and follows every
 * setting of {@link LyricsConfig}.
 */
public final class LyricsHud {
    private static final LyricsView VIEW = new LyricsView();
    private static float shown;
    private static long lastNanos = System.nanoTime();
    private static Lyrics.Doc lastDoc;

    private LyricsHud() {}

    /** How the lyrics are doing right now, decided once per frame. */
    private enum Mode { LINES, INSTRUMENTAL, NOTE, HIDDEN }

    private static Mode mode(LyricsConfig lc) {
        Snapshot s = Player.snap();
        if (!s.active() || s.isAd()) return Mode.HIDDEN;
        if (!Player.playing() && !lc.showWhenPaused) return Mode.HIDDEN;
        switch (Lyrics.status()) {
            case READY:
                return Mode.LINES;
            case INSTRUMENTAL:
                return lc.showInstrumental ? Mode.INSTRUMENTAL : Mode.HIDDEN;
            case LOADING:
            case NONE:
            case PLAIN:
            case ERROR:
                return lc.hideWhenNone ? Mode.HIDDEN : Mode.NOTE;
            default:
                return Mode.HIDDEN;
        }
    }

    /** HudElementRegistry callback. */
    public static void render(GuiGraphics g, DeltaTracker tracker) {
        Minecraft mc = Minecraft.getInstance();
        SpotifyConfig cfg = ConfigIo.get();
        LyricsConfig lc = cfg.lyrics;
        if (!lc.enabled || mc.player == null || mc.options.hideGui || mc.screen instanceof PlayerHud.HidesHud) {
            shown = 0f;
            return;
        }
        long nanos = System.nanoTime();
        float dt = Math.min(0.1f, (nanos - lastNanos) / 1_000_000_000f);
        lastNanos = nanos;
        Mode mode = mode(lc);
        float target = mode == Mode.HIDDEN ? 0f : 1f;
        shown = cfg.motion() ? Ease.approach(shown, target, 6f * lc.speed, dt) : target;
        if (Math.abs(shown - target) < 0.01f) shown = target;
        if (shown <= 0.01f) return;

        int[] b = LyricsLayout.bounds(mc, lc);
        int lift = cfg.motion() ? Math.round((1f - shown) * 6f) : 0;
        drawBox(g, mc, cfg, b[0], b[1] + lift, lc.scale, lc.opacity * shown, mode, false);
    }

    /**
     * Draws the lyrics at a screen position whatever the song is doing (settings preview, layout editor). When there are
     * no timed lyrics a made-up sample is shown so every setting can be seen at work.
     */
    public static void preview(GuiGraphics g, Minecraft mc, SpotifyConfig cfg, int x, int y, float scale, float alpha) {
        drawBox(g, mc, cfg, x, y, scale, alpha, Mode.LINES, true);
    }

    private static void drawBox(GuiGraphics g, Minecraft mc, SpotifyConfig cfg, int x, int y, float scale, float alpha,
                                Mode mode, boolean preview) {
        LyricsConfig lc = cfg.lyrics;
        Font font = mc.font;
        int boxW = LyricsLayout.boxWidth(lc);
        int boxH = LyricsLayout.boxHeight(lc);
        int accent = PlayerHud.smoothAccent();
        Covers.Set cover = Covers.current();
        int light = cover != null ? cover.palette.light() : Colors.lighten(accent, 0.55f);

        Lyrics.Doc doc;
        long pos;
        long dur = Player.duration();
        boolean sample = preview && !Lyrics.ready();
        if (sample) {
            doc = Lyrics.sample();
            pos = (System.currentTimeMillis() / 1) % Lyrics.sampleLength();
            dur = Lyrics.sampleLength();
        } else {
            doc = Lyrics.doc();
            pos = Lyrics.position();
        }
        if (doc != lastDoc) {
            VIEW.reset();
            lastDoc = doc;
        }

        var pose = g.pose();
        pose.pushMatrix();
        pose.translate((float) x, (float) y);
        pose.scale(scale, scale);
        background(g, lc, cover, accent, boxW, boxH, alpha);
        switch (mode) {
            case LINES -> VIEW.draw(g, font, lc, doc, pos, dur, accent, light, Player.playing() || sample, alpha, boxW, boxH,
                    cfg.motion());
            case INSTRUMENTAL -> note(g, font, lc, "Instrumental", true, accent, boxW, boxH, alpha);
            case NOTE -> note(g, font, lc, noteText(), false, accent, boxW, boxH, alpha);
            default -> { }
        }
        pose.popMatrix();
    }

    private static String noteText() {
        switch (Lyrics.status()) {
            case LOADING:
                return "Looking for the lyrics...";
            case PLAIN:
                return "Lyrics exist but are not timed";
            case ERROR:
                return "Lyrics service not reachable";
            default:
                return "No lyrics for this song";
        }
    }

    private static void note(GuiGraphics g, Font font, LyricsConfig lc, String text, boolean icon, int accent, int boxW,
                             int boxH, float alpha) {
        int color = LyricsView.tone(lc.currentTone, lc.currentColor, accent, accent, 0);
        int iw = icon ? Icons.width(Icons.NOTE) + 5 : 0;
        String fit = Gfx.fit(font, text, boxW - 2 * LyricsLayout.padX(lc) - iw);
        int w = Gfx.width(font, fit) + iw;
        int x = switch (lc.align) {
            case LEFT -> LyricsLayout.padX(lc);
            case RIGHT -> boxW - LyricsLayout.padX(lc) - w;
            default -> (boxW - w) / 2;
        };
        int y = (boxH - 9) / 2;
        if (icon) Icons.draw(g, Icons.NOTE, x, y - 1, 1, Colors.alpha(color, alpha));
        Gfx.drawStyled(g, font, fit, x + iw, y, Colors.alpha(color, alpha), false, false, lc.shadow);
    }

    private static void background(GuiGraphics g, LyricsConfig lc, Covers.Set cover, int accent, int w, int h, float alpha) {
        panel(g, lc, accent, w, h, alpha);
        if (lc.accentBar) {
            int inset = Math.min(h / 3, 4 + lc.corner * 2);
            g.fill(0, inset, 2, h - inset, Colors.alpha(accent, alpha));
        }
    }

    private static void panel(GuiGraphics g, LyricsConfig lc, int accent, int w, int h, float alpha) {
        if (lc.back == LyricsConfig.Back.NONE) return;
        float a = lc.backOpacity * alpha;
        int top;
        int bottom;
        switch (lc.back) {
            case SOLID -> {
                top = Colors.alpha(Colors.CARD, a);
                bottom = Colors.alpha(Colors.CARD_DARK, a);
            }
            case ALBUM -> {
                float[] hsv = Colors.toHsv(accent);
                top = Colors.alpha(Colors.hsv(hsv[0], 0.55f, 0.26f), a);
                bottom = Colors.alpha(Colors.hsv(hsv[0], 0.5f, 0.10f), a);
            }
            default -> {
                top = Colors.alpha(Colors.CARD, 0.7f * a);
                bottom = Colors.alpha(Colors.CARD_DARK, 0.7f * a);
            }
        }
        int edge = lc.border ? Colors.alpha(Colors.mix(Colors.LINE, accent, 0.25f), a) : 0;
        Gfx.box(g, 0, 0, w, h, lc.corner, top, bottom, edge);
    }
}
