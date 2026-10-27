package com.zlorbie788.spotify.gui.widgets;

import com.zlorbie788.spotify.config.SpotifyConfig.ShapeKind;
import com.zlorbie788.spotify.hud.Anim;
import com.zlorbie788.spotify.hud.Gfx;
import com.zlorbie788.spotify.hud.Icons;
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
import java.util.function.Supplier;

/**
 * A button that shows a pixel icon (play, skip, shuffle...). The icon, its "lit" state (shuffle on, repeat on)
 * and a small badge are read every frame, so the button always matches the real player. Works with Tab,
 * Enter and Space like every other widget, and announces its name to screen readers.
 */
public class IconButton extends AbstractWidget {
    private final Supplier<String[]> icon;
    private final Runnable press;
    private BooleanSupplier lit = () -> false;
    private Supplier<String> badge = () -> "";
    private int accent = Colors.MINT;
    private int pixel = 1;
    private boolean solid;
    private long clickedAt;
    private float clickX;
    private float clickY;

    public IconButton(int x, int y, int width, int height, String name, Supplier<String[]> icon, Runnable press) {
        super(x, y, width, height, Component.literal(name));
        this.icon = icon;
        this.press = press;
        setTooltip(Tooltip.create(Component.literal(name)));
    }

    public IconButton accent(int color) {
        this.accent = color;
        return this;
    }

    /** Size of one icon pixel in GUI pixels (1 or 2). */
    public IconButton pixel(int size) {
        this.pixel = Math.max(1, size);
        return this;
    }

    /** Filled with the accent colour: the one main button (play / pause). */
    public IconButton solid() {
        this.solid = true;
        return this;
    }

    public IconButton lit(BooleanSupplier state) {
        this.lit = state;
        return this;
    }

    public IconButton badge(Supplier<String> text) {
        this.badge = text;
        return this;
    }

    public IconButton tip(String text) {
        setTooltip(Tooltip.create(Component.literal(text)));
        return this;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (!active) return;
        clickedAt = Chrome.now();
        clickX = (float) event.x();
        clickY = (float) event.y();
        press.run();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!active || !visible || !isFocused()) return false;
        int key = event.key();
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_SPACE) {
            playDownSound(Minecraft.getInstance().getSoundManager());
            clickedAt = Chrome.now();
            clickX = getX() + getWidth() / 2f;
            clickY = getY() + getHeight() / 2f;
            press.run();
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
        boolean hot = active && isHoveredOrFocused();
        boolean on = lit.getAsBoolean();
        float hv = Anim.smooth(this, "hover", hot ? 1f : 0f, 16f);
        float lv = Anim.smooth(this, "lit", on ? 1f : 0f, on ? 1f : 0f, 14f);
        int acc = Theme.accent(accent);

        int top;
        int bottom;
        int edge;
        if (solid) {
            top = Colors.mix(Theme.surface(), acc, 0.70f + 0.20f * hv);
            bottom = Colors.mix(Theme.surface(), acc, 0.55f + 0.20f * hv);
            edge = Colors.lighten(acc, 0.2f + 0.3f * hv);
        } else {
            top = Colors.mix(Colors.mix(Theme.raised(), Colors.WHITE, 0.04f), acc, 0.10f * lv + 0.18f * hv);
            bottom = Colors.mix(Theme.raised(), acc, 0.05f * lv + 0.08f * hv);
            edge = Colors.mix(Colors.mix(Theme.line(), Colors.alpha(acc, 0.7f), lv), acc, hv);
        }
        if (!active) {
            top = Theme.well();
            bottom = Theme.well();
            edge = Theme.line();
        }
        if (Theme.highContrast() && active) edge = hot ? Colors.WHITE : 0xFFB0B0B0;
        float r = solid ? Math.min(h, w) / 2f : Math.min(Theme.radius() + 1f, h / 2f);
        if (solid && active && !Theme.highContrast()) {
            Shapes.glow(g, Gfx.dev(), x, y, w, h, ShapeKind.ROUND, r, acc, 0.45f + 0.5f * hv);
        }
        Gfx.panel(g, x, y, w, h, ShapeKind.ROUND, r, top, bottom, edge, 1f);
        Chrome.ripple(g, x, y, w, h, clickX, clickY, Chrome.since(clickedAt), solid ? Colors.WHITE : acc);
        if (isFocused()) {
            Gfx.panel(g, x - 1, y - 1, w + 2, h + 2, ShapeKind.ROUND, r + 1f, 0, 0, Colors.alpha(Colors.WHITE, 0.9f), 1f);
        }

        String[] pic = icon.get();
        int iw = Icons.width(pic) * pixel;
        int ih = Icons.height(pic) * pixel;
        int color;
        if (!active) color = Theme.dim();
        else if (solid) color = Theme.highContrast() ? Colors.BLACK : 0xFF06100C;
        else color = Colors.mix(Colors.mix(Theme.soft(), Colors.lighten(acc, 0.15f), lv), Colors.WHITE, hv * (1f - lv));
        Icons.draw(g, pic, x + (w - iw) / 2, y + (h - ih) / 2, pixel, Colors.opaque(color));
        if (lv > 0.05f && !solid) {
            // a small dot under the icon says "on", so it is not only a colour change
            int dotW = Math.max(2, Math.round(4 * lv));
            g.fill(x + (w - dotW) / 2, y + h - 3, x + (w + dotW) / 2, y + h - 2, Colors.alpha(Colors.lighten(acc, 0.2f), lv));
        }

        String mark = badge.get();
        if (mark != null && !mark.isEmpty()) {
            Font font = Minecraft.getInstance().font;
            Gfx.drawStyled(g, font, mark, x + w - Gfx.width(font, mark) - 2, y + h - 9, Colors.opaque(Colors.lighten(acc, 0.2f)),
                    false, false, true);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
