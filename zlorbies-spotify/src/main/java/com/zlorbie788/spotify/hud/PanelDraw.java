package com.zlorbie788.spotify.hud;

import com.zlorbie788.spotify.config.SpotifyConfig;
import com.zlorbie788.spotify.config.SpotifyConfig.Background;
import com.zlorbie788.spotify.config.SpotifyConfig.ShapeKind;
import com.zlorbie788.spotify.util.Clock;
import com.zlorbie788.spotify.util.Colors;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Draws the panel of the full player (and the lyrics panel beside it): shape, corner size, border, background, the soft light
 * around it, the aurora behind the content, and the border effects. Everything comes from the "Panel" and "Visual" settings.
 */
public final class PanelDraw {
    private PanelDraw() {}

    /** Corner size in use for a panel of this size. */
    public static float radius(SpotifyConfig cfg, int w, int h) {
        return Math.min(cfg.panelRadius, Math.min(w, h) / 2f);
    }

    /** The background colours {top, bottom} for the chosen background type. */
    public static int[] colors(SpotifyConfig cfg, int accent) {
        float op = cfg.panelOpacity;
        if (cfg.highContrast) return new int[]{Colors.alpha(Colors.BLACK, Math.max(0.95f, op)), Colors.alpha(Colors.BLACK, Math.max(0.95f, op))};
        Background b = cfg.panelBack;
        switch (b) {
            case NONE:
                return new int[]{0, 0};
            case GLASS:
                return new int[]{Colors.alpha(Colors.mix(Colors.CARD, accent, 0.14f), 0.74f * op),
                        Colors.alpha(Colors.CARD_DARK, 0.80f * op)};
            case TINTED:
                return new int[]{Colors.alpha(Colors.mix(Colors.CARD, accent, 0.30f), 0.97f * op),
                        Colors.alpha(Colors.mix(Colors.CARD_DARK, accent, 0.08f), 0.97f * op)};
            case FADE:
                return new int[]{Colors.alpha(Colors.mix(Colors.CARD, accent, 0.14f), 0.94f * op),
                        Colors.alpha(Colors.CARD_DARK, 0.26f * op)};
            default:
                return new int[]{Colors.alpha(Colors.mix(Colors.CARD, accent, 0.10f), op),
                        Colors.alpha(Colors.CARD_DARK, op)};
        }
    }

    /**
     * @param effects the aurora, specks, shine and comet (the lyrics panel next to the player draws a calmer version)
     * @param parallaxX how far the mouse has pulled the light, in pixels (0 when the setting is off)
     */
    public static void draw(GuiGraphics g, SpotifyConfig cfg, int x, int y, int w, int h, int accent, boolean playing,
                            boolean effects, float parallaxX, int seed) {
        boolean hc = cfg.highContrast;
        ShapeKind kind = cfg.panelShape;
        float r = radius(cfg, w, h);
        float dev = Gfx.dev();
        boolean motion = cfg.motion();
        boolean none = cfg.panelBack == Background.NONE;
        float beat = motion && cfg.beatPulse ? Visual.pulse() : 0f;

        if (cfg.showGlow && !hc && !none) {
            float breath = 0.7f;
            if (motion && cfg.breathingGlow && playing) {
                breath = 0.55f + 0.45f * (float) (0.5 + 0.5 * Math.sin(System.nanoTime() / 1.6e9 * Math.PI * 2 * cfg.animSpeed));
            }
            Shapes.glow(g, dev, x, y, w, h, kind, r, accent, Math.min(2.2f, breath + 1.2f * beat));
        }
        int[] fill = colors(cfg, accent);
        int edge = hc ? Colors.WHITE : Colors.alpha(Colors.mix(Colors.LINE, accent, 0.30f), Math.max(0.6f, cfg.panelOpacity));
        float bw = hc ? Math.max(1.5f, cfg.panelBorder) : cfg.panelBorder;
        Gfx.panel(g, x, y, w, h, kind, r, fill[0], fill[1], edge, bw);

        boolean alive = motion && playing && effects && !hc && !none;
        if (cfg.ambient && motion && effects && !hc && !none) {
            int c2 = Colors.mix(accent, Colors.hsvShift(accent, 0.12f), 0.8f);
            Fx.aurora(g, x, y, w, h, Math.max(4f, r * 0.7f), accent, c2, Clock.nanos() / 1e9, beat, playing ? 1f : 0.55f, parallaxX);
        }
        if (cfg.particles && alive) Fx.specks(g, x + 3, y + 3, w - 6, h - 6, accent, Clock.nanos() / 1e9, 10, 1f, seed);
        if (cfg.shine && alive) {
            float t = (float) ((Clock.nanos() / 1e9) % 10.0) / 1.4f;
            Fx.shine(g, x, y, w, h, accent, t, 1f);
        }
        if (cfg.borderTrace && alive) {
            float lap = (float) ((Clock.nanos() / 1e9 * 0.06 * cfg.animSpeed) % 1.0);
            Fx.comet(g, dev, x, y, w, h, kind, r, Colors.lighten(accent, 0.25f), lap, 44f, 1.3f, 0.95f);
        }
        if (cfg.cornerBrackets && !none) Fx.brackets(g, x, y, w, h, 6, accent, 0.9f);
        if (cfg.showAccentLine && !none) {
            int inset = kind == ShapeKind.SQUARE ? 0 : Math.max(1, Math.round(r * 0.8f));
            boolean rainbow = cfg.accent == SpotifyConfig.Accent.RAINBOW;
            Gfx.accentLine(g, x + inset, y, w - 2 * inset, hc ? 3 : 2, accent, rainbow, PlayerHud.linePhase(cfg, rainbow),
                    motion, 1f, beat);
        }
    }
}
