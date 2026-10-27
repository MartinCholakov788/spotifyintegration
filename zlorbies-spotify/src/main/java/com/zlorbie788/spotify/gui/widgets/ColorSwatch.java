package com.zlorbie788.spotify.gui.widgets;

import com.zlorbie788.spotify.hud.Anim;
import com.zlorbie788.spotify.hud.Theme;
import com.zlorbie788.spotify.util.Colors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.function.BooleanSupplier;

/** A small square of one colour: click to pick it. The picked one gets a white frame and a tick. */
public class ColorSwatch extends AbstractWidget {
    private final int color;
    private final BooleanSupplier selected;
    private final Runnable pick;

    public ColorSwatch(int x, int y, int width, int height, String name, int color, BooleanSupplier selected, Runnable pick) {
        super(x, y, width, height, Component.literal(name));
        this.color = Colors.opaque(color);
        this.selected = selected;
        this.pick = pick;
        setTooltip(Tooltip.create(Component.literal(name)));
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (active) pick.run();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!active || !visible || !isFocused()) return false;
        int key = event.key();
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_SPACE) {
            playDownSound(Minecraft.getInstance().getSoundManager());
            pick.run();
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
        boolean on = selected.getAsBoolean();
        boolean hot = active && isHoveredOrFocused();
        float hv = Anim.smooth(this, "hover", hot ? 1f : 0f, 16f);
        float sv = Anim.smooth(this, "sel", on ? 1f : 0f, on ? 1f : 0f, 16f);
        int shade = active ? color : Colors.mix(color, Theme.well(), 0.65f);
        // a picked swatch grows a little and gets a white frame
        int grow = Math.round(sv * 1.2f + hv * 0.6f);
        Chrome.body(g, x - grow, y - grow, w + 2 * grow, h + 2 * grow, Colors.lighten(shade, 0.12f * hv), shade,
                Colors.mix(Colors.mix(Theme.line(), Colors.lighten(color, 0.5f), hv), Colors.WHITE, sv));
        if (on) {
            // a tick in a contrasting colour (dark on light swatches, white on dark ones)
            float[] hsv = Colors.toHsv(color);
            int tick = hsv[2] > 0.75f && hsv[1] < 0.6f || hsv[2] > 0.9f ? 0xFF0A0F1C : Colors.WHITE;
            int cx = x + w / 2 - 3;
            int cy = y + h / 2 - 2;
            int[][] pixels = {{0, 2}, {1, 3}, {2, 4}, {3, 3}, {4, 2}, {5, 1}, {6, 0}};
            for (int[] p : pixels) g.fill(cx + p[0], cy + p[1], cx + p[0] + 1, cy + p[1] + 1, tick);
        }
        if (isFocused()) Chrome.focusRing(g, x, y, w, h);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
