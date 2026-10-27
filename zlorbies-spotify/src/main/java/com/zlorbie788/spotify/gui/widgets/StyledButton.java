package com.zlorbie788.spotify.gui.widgets;

import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.hud.Anim;
import com.zlorbie788.spotify.hud.Gfx;
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

/**
 * The push button: a smooth rounded body that fades to its highlight colour on hover, a ripple where it is clicked, a visible
 * focus ring for keyboard users (Tab / arrows to move, Enter or Space to press) and an optional tooltip. Narration and focus
 * handling come from the vanilla widget base class, so screen readers and Tab navigation keep working.
 */
public class StyledButton extends AbstractWidget {
    /** What happens on click. The button is passed in so handlers can change their own label. */
    public interface Press {
        void press(StyledButton button);
    }

    private final Press onPress;
    private int accent = Colors.CYAN;
    private boolean primary = false;
    private long clickedAt;
    private float clickX;
    private float clickY;

    public StyledButton(int x, int y, int width, int height, Component label, Press onPress) {
        super(x, y, width, height, label);
        this.onPress = onPress;
    }

    /** Border / highlight colour. */
    public StyledButton accent(int color) {
        this.accent = color;
        return this;
    }

    /** Filled with the accent colour: use for the one main action of a group. */
    public StyledButton primary() {
        this.primary = true;
        return this;
    }

    public StyledButton tip(String text) {
        setTooltip(Tooltip.create(Component.literal(text)));
        return this;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (!active) return;
        clickedAt = Chrome.now();
        clickX = (float) event.x();
        clickY = (float) event.y();
        onPress.press(this);
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
            onPress.press(this);
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
        Font font = Minecraft.getInstance().font;
        float hv = Anim.smooth(this, "hover", hot ? 1f : 0f, 16f);
        int acc = Theme.accent(accent);

        int top;
        int bottom;
        if (!active) {
            top = Theme.well();
            bottom = Theme.well();
        } else if (primary) {
            top = Colors.mix(Theme.surface(), acc, 0.34f + 0.20f * hv);
            bottom = Colors.mix(Theme.surface(), acc, 0.20f + 0.16f * hv);
        } else {
            top = Colors.mix(Colors.mix(Theme.raised(), Colors.WHITE, 0.04f), acc, 0.20f * hv);
            bottom = Colors.mix(Theme.raised(), acc, 0.08f * hv);
        }
        int edge = !active ? Theme.line() : Colors.mix(Theme.line(), acc, primary ? 1f : hv);
        if (Theme.highContrast() && active) edge = hot ? Colors.WHITE : 0xFFB0B0B0;

        Chrome.body(g, x, y, w, h, top, bottom, edge);
        if (hv > 0.02f && !Theme.highContrast()) {
            // a thin light along the top edge
            g.fill(x + 3, y + 1, x + w - 3, y + 2, Colors.alpha(Colors.WHITE, 0.07f * hv));
        }
        Chrome.ripple(g, x, y, w, h, clickX, clickY, Chrome.since(clickedAt), acc);
        if (isFocused()) Chrome.focusRing(g, x, y, w, h);

        int textColor = !active ? Theme.dim() : Colors.mix(Theme.soft(), Theme.text(), hv);
        String label = getMessage().getString();
        int room = w - 8;
        int ty = y + (h - 8) / 2;
        boolean plain = Gfx.font().isDefault();
        if (plain && font.width(getMessage()) <= room) {
            // Keep any colour the caller put on the label (e.g. a coloured key name).
            g.drawString(font, getMessage(), x + (w - font.width(getMessage())) / 2, ty, Colors.opaque(textColor), active);
        } else {
            String cut = Gfx.fit(font, label, room);
            Gfx.drawStyled(g, font, cut, x + (w - Gfx.width(font, cut)) / 2, ty, Colors.opaque(textColor), false, false, active);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
