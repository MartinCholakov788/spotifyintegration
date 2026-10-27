package com.zlorbie788.spotify.gui.widgets;

import com.zlorbie788.spotify.config.SpotifyConfig.ShapeKind;
import com.zlorbie788.spotify.hud.Anim;
import com.zlorbie788.spotify.hud.Gfx;
import com.zlorbie788.spotify.hud.Shapes;
import com.zlorbie788.spotify.hud.Theme;
import com.zlorbie788.spotify.util.Colors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * An on/off switch with its label. The state is shown three ways - the knob position, the words ON / OFF and
 * the colour - so it never relies on colour alone. The knob glides and the track fades between the two states.
 * Reads and writes the value through the given callbacks.
 */
public class StyledToggle extends AbstractWidget {
    /** Below this width the switch graphic would crowd out the label, so a tick box is drawn instead. */
    private static final int COMPACT_BELOW = 104;

    private final String label;
    private final BooleanSupplier getter;
    private final Consumer<Boolean> setter;
    private int accent = Colors.MINT;
    private long clickedAt;
    private float clickX;
    private float clickY;

    public StyledToggle(int x, int y, int width, int height, String label, BooleanSupplier getter,
                        Consumer<Boolean> setter) {
        super(x, y, width, height, Component.literal(label));
        this.label = label;
        this.getter = getter;
        this.setter = setter;
    }

    public StyledToggle accent(int color) {
        this.accent = color;
        return this;
    }

    public StyledToggle tip(String text) {
        setTooltip(Tooltip.create(Component.literal(text)));
        return this;
    }

    private void flip() {
        setter.accept(!getter.getAsBoolean());
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (!active) return;
        clickedAt = Chrome.now();
        clickX = (float) event.x();
        clickY = (float) event.y();
        flip();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!active || !visible || !isFocused()) return false;
        int key = event.key();
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_SPACE) {
            playDownSound(Minecraft.getInstance().getSoundManager());
            flip();
            return true;
        }
        return false;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();
        boolean on = getter.getAsBoolean();
        boolean hot = active && isHoveredOrFocused();
        Font font = Minecraft.getInstance().font;
        float hv = Anim.smooth(this, "hover", hot ? 1f : 0f, 16f);
        float st = Anim.smooth(this, "on", on ? 1f : 0f, on ? 1f : 0f, 18f);
        int acc = Theme.accent(accent);

        int body = Colors.mix(Theme.raised(), acc, 0.14f * hv);
        int edge = Colors.mix(Theme.line(), acc, hv);
        if (Theme.highContrast()) edge = hot ? Colors.WHITE : 0xFFB0B0B0;
        Chrome.body(g, x, y, w, h, Colors.mix(body, Colors.WHITE, 0.03f), body, edge);
        Chrome.ripple(g, x, y, w, h, clickX, clickY, Chrome.since(clickedAt), Theme.good());
        if (isFocused()) Chrome.focusRing(g, x, y, w, h);

        if (w < COMPACT_BELOW) {
            renderCompact(g, font, x, y, h, st);
            return;
        }

        // Switch on the right: a pill track and a knob that glides, plus the words ON / OFF.
        String word = on ? "ON" : "OFF";
        boolean showWord = w >= 132;
        int wordW = showWord ? Gfx.width(font, "OFF") : 0;
        int trackW = 22;
        int trackH = 11;
        int trackX = x + w - 6 - (showWord ? wordW + 5 : 0) - trackW;
        int trackY = y + (h - trackH) / 2;
        int off = Theme.well();
        int onC = Colors.mix(Theme.well(), Theme.good(), 0.55f);
        Chrome.pill(g, trackX, trackY, trackW, trackH, Colors.mix(off, onC, st), Colors.mix(off, onC, st),
                Colors.mix(Theme.line(), Theme.good(), st));
        float kr = 3.6f;
        float kx = trackX + 1.5f + kr + st * (trackW - 3f - 2 * kr);
        float ky = trackY + trackH / 2f;
        if (st > 0.05f) Shapes.glow(g, Gfx.dev(), kx - kr, ky - kr, kr * 2, kr * 2, ShapeKind.ROUND, kr, Theme.good(), 0.7f * st);
        Shapes.disc(g, Gfx.dev(), kx, ky, kr, Colors.mix(Theme.dim(), Colors.WHITE, st));
        int stateColor = on ? Theme.good() : Theme.dim();
        if (showWord) {
            Gfx.drawStyled(g, font, word, x + w - 6 - Gfx.width(font, word), y + (h - 8) / 2, Colors.opaque(stateColor), false, false,
                    false);
        }

        // Label on the left, cut short rather than running into the switch.
        int room = trackX - (x + 6) - 4;
        Gfx.drawStyled(g, font, Gfx.fit(font, label, Math.max(8, room)), x + 6, y + (h - 8) / 2,
                Colors.opaque(active ? Theme.text() : Theme.dim()), false, false, active);
    }

    /** Narrow variant: a tick box followed by the label (box filled and ticked = on). */
    private void renderCompact(GuiGraphics g, Font font, int x, int y, int h, float st) {
        int boxX = x + 6;
        int boxY = y + (h - 10) / 2;
        int off = Theme.well();
        int onC = Colors.mix(Theme.well(), Theme.good(), 0.55f);
        float r = Math.min(Theme.radius(), 3f);
        Gfx.panel(g, boxX, boxY, 10, 10, ShapeKind.ROUND, r, Colors.mix(off, onC, st), Colors.mix(off, onC, st),
                Colors.mix(Theme.line(), Theme.good(), st), 1f);
        if (st > 0.15f) {
            int tick = Colors.alpha(Colors.WHITE, Math.min(1f, st * 1.4f));
            int[][] pixels = {{2, 4}, {3, 5}, {4, 6}, {5, 5}, {6, 4}, {7, 3}};
            for (int[] p : pixels) g.fill(boxX + p[0], boxY + p[1], boxX + p[0] + 1, boxY + p[1] + 1, tick);
        }
        int textX = boxX + 10 + 5;
        int room = getX() + getWidth() - 4 - textX;
        Gfx.drawStyled(g, font, Gfx.fit(font, label, Math.max(8, room)), textX, y + (h - 8) / 2,
                Colors.opaque(active ? Theme.text() : Theme.dim()), false, false, active);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        // "Payment sound, button" plus the current state, so a screen reader announces ON or OFF.
        output.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE,
                Component.literal(label + ": " + (getter.getAsBoolean() ? "on" : "off")));
    }
}
