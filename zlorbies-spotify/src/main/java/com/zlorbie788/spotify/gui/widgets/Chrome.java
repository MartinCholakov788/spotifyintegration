package com.zlorbie788.spotify.gui.widgets;

import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.config.SpotifyConfig.ShapeKind;
import com.zlorbie788.spotify.hud.Anim;
import com.zlorbie788.spotify.hud.Fx;
import com.zlorbie788.spotify.hud.Gfx;
import com.zlorbie788.spotify.hud.Theme;
import com.zlorbie788.spotify.util.Clock;
import com.zlorbie788.spotify.util.Colors;
import com.zlorbie788.spotify.util.Ease;
import net.minecraft.client.gui.GuiGraphics;

/** Shapes shared by the widgets: smooth rounded boxes, the keyboard focus ring and the click ripple. */
public final class Chrome {
    private Chrome() {}

    /** A smooth rounded box with a one-pixel border (corner size from the "Interface" settings). */
    public static void body(GuiGraphics g, int x, int y, int w, int h, int fill, int edge) {
        body(g, x, y, w, h, fill, fill, edge);
    }

    /** The same with a vertical gradient fill. */
    public static void body(GuiGraphics g, int x, int y, int w, int h, int top, int bottom, int edge) {
        float r = Math.min(Theme.radius(), Math.min(w, h) / 2f);
        Gfx.panel(g, x, y, w, h, ShapeKind.ROUND, r, top, bottom, edge, 1f);
    }

    /** Pill-shaped (fully rounded) box. */
    public static void pill(GuiGraphics g, float x, float y, float w, float h, int top, int bottom, int edge) {
        Gfx.panel(g, x, y, w, h, ShapeKind.ROUND, Math.min(w, h) / 2f, top, bottom, edge, edge == 0 ? 0f : 1f);
    }

    /** White ring one pixel outside the widget: always visible, on every theme. */
    public static void focusRing(GuiGraphics g, int x, int y, int w, int h) {
        int ring = Theme.highContrast() ? Colors.WHITE : Colors.alpha(Colors.WHITE, 0.9f);
        float r = Math.min(Theme.radius() + 1f, Math.min(w + 2, h + 2) / 2f);
        Gfx.panel(g, x - 1, y - 1, w + 2, h + 2, ShapeKind.ROUND, r, 0, 0, ring, 1f);
    }

    /** A ring of light spreading from where the widget was clicked, clipped to the widget. {@code since} is in milliseconds. */
    public static void ripple(GuiGraphics g, int x, int y, int w, int h, float cx, float cy, long since, int color) {
        if (since < 0 || since > 520 || !Anim.enabled() || !ConfigIo.get().ripples) return;
        float u = Ease.outCubic(since / 520f);
        float r = u * (Math.max(w, h) * 1.1f + 6f);
        float a = (1f - u) * 0.30f;
        g.enableScissor(x, y, x + w, y + h);
        // a soft disc made of two circles, so it reads as light rather than a hard ring
        Fx.circle(g, cx, cy, r, 2f, Colors.alpha(color, a));
        Fx.circle(g, cx, cy, Math.max(0f, r - 3f), 2f, Colors.alpha(color, a * 0.5f));
        g.disableScissor();
    }

    /** Milliseconds since a time taken with {@link #now()}; -1 when none was taken. */
    public static long since(long at) {
        return at == 0L ? -1L : Math.max(0L, (Clock.nanos() - at) / 1_000_000L);
    }

    public static long now() {
        return Clock.nanos();
    }
}
