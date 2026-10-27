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
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

/**
 * A labelled slider over [min, max] that snaps to a step and reports the real value. Dragging, the arrow keys
 * and narration come from the vanilla slider; only the look is custom: a dark track, an accent fill that
 * follows the value, a bright handle and the value written on top.
 */
public class ConfigSlider extends AbstractSliderButton {
    private final String label;
    private final double min;
    private final double max;
    private final double step;
    private final DoubleFunction<String> format;
    private final DoubleConsumer onChange;
    private int accent = Colors.CYAN;

    public ConfigSlider(int x, int y, int width, int height, String label, double min, double max, double step,
                        double current, DoubleFunction<String> format, DoubleConsumer onChange) {
        super(x, y, width, height, Component.empty(), (clamp(current, min, max) - min) / (max - min));
        this.label = label;
        this.min = min;
        this.max = max;
        this.step = step;
        this.format = format;
        this.onChange = onChange;
        updateMessage();
    }

    public ConfigSlider accent(int color) {
        this.accent = color;
        return this;
    }

    public ConfigSlider tip(String text) {
        setTooltip(Tooltip.create(Component.literal(text)));
        return this;
    }

    /** Moves the handle to a value set elsewhere (for example the real volume) without calling the callback. */
    public void setActual(double v) {
        double target = (clamp(v, min, max) - min) / (max - min);
        if (Math.abs(target - this.value) > 1e-6) {
            this.value = target;
            updateMessage();
        }
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private double actual() {
        double raw = min + (max - min) * this.value;
        double snapped = Math.round(raw / step) * step;
        return clamp(snapped, min, max);
    }

    @Override
    protected void updateMessage() {
        setMessage(Component.literal(label + format.apply(actual())));
    }

    @Override
    protected void applyValue() {
        onChange.accept(actual());
    }

    @Override
    public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();
        boolean hot = active && isHoveredOrFocused();
        Font font = Minecraft.getInstance().font;
        float hv = Anim.smooth(this, "hover", hot ? 1f : 0f, 16f);
        int acc = Theme.accent(accent);
        float shown = Anim.smooth(this, "value", (float) this.value, (float) this.value, 34f);

        int edge = Colors.mix(Theme.line(), acc, hv);
        if (Theme.highContrast()) edge = hot ? Colors.WHITE : 0xFFB0B0B0;
        Chrome.body(g, x, y, w, h, Theme.well(), edge);

        // Accent fill up to the handle, then the handle itself.
        float r = Math.min(Theme.radius(), h / 2f);
        float inner = Math.max(2f, w - 2f);
        float handleW = 4f;
        float fillW = shown * (inner - handleW) + handleW / 2f;
        if (fillW > 1f) {
            float fw = Math.min(inner, Math.max(2f * r - 2f, fillW));
            int c1 = Colors.alpha(acc, Theme.highContrast() ? 0.6f : 0.30f + 0.14f * hv);
            int c2 = Colors.alpha(Colors.darken(acc, 0.25f), Theme.highContrast() ? 0.6f : 0.50f + 0.12f * hv);
            Gfx.panel(g, x + 1, y + 1, fw, h - 2, ShapeKind.ROUND, Math.max(0f, r - 1f), c1, c2, 0, 0f);
        }
        float handleX = x + 1 + shown * (inner - handleW);
        // The handle only shows while you point at the slider; otherwise the fill alone says where it is,
        // which keeps the text clean.
        float ha = Math.max(hv, isFocused() ? 1f : 0f);
        if (ha > 0.02f) {
            int hc = Colors.opaque(Colors.mix(Colors.lighten(acc, 0.35f), Colors.WHITE, hv));
            Shapes.glow(g, Gfx.dev(), handleX, y + 2, handleW, h - 4, ShapeKind.ROUND, handleW / 2f, acc, 0.5f * ha);
            Gfx.panel(g, handleX, y + 2, handleW, h - 4, ShapeKind.ROUND, handleW / 2f, Colors.alpha(hc, ha), Colors.alpha(hc, ha), 0, 0f);
        } else if (fillW > 4f && inner - fillW > 26f) {
            int cap = Colors.alpha(Colors.lighten(acc, 0.35f), 0.9f);
            Gfx.panel(g, handleX + handleW - 2f, y + 3, 1.6f, h - 6, ShapeKind.ROUND, 0.8f, cap, cap, 0, 0f);
        }
        if (isFocused()) Chrome.focusRing(g, x, y, w, h);

        // label on the left, value on the right
        int color = Colors.opaque(active ? Theme.text() : Theme.dim());
        String value = format.apply(actual());
        int vw = Gfx.width(font, value);
        String name = Gfx.fit(font, label.endsWith(": ") ? label.substring(0, label.length() - 2) : label.trim(), Math.max(10, w - vw - 20));
        Gfx.drawStyled(g, font, name, x + 6, y + (h - 8) / 2, color, false, false, true);
        Gfx.drawStyled(g, font, value, x + w - 6 - vw, y + (h - 8) / 2, Colors.opaque(active ? Theme.soft() : Theme.dim()), true, false, true);
    }
}
