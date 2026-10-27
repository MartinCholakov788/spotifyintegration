package com.zlorbie788.spotify.gui;

import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.config.LyricsConfig;
import com.zlorbie788.spotify.config.SpotifyConfig;
import com.zlorbie788.spotify.gui.widgets.StyledButton;
import com.zlorbie788.spotify.hud.Gfx;
import com.zlorbie788.spotify.hud.HudLayout;
import com.zlorbie788.spotify.hud.LyricsHud;
import com.zlorbie788.spotify.hud.LyricsLayout;
import com.zlorbie788.spotify.hud.PlayerHud;
import com.zlorbie788.spotify.hud.Theme;
import com.zlorbie788.spotify.util.Colors;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/**
 * Drag the player to where you want it. Scroll = size, Shift+scroll = opacity, arrow keys nudge (Shift = 10 px),
 * R resets the place, Ctrl while dragging turns snapping off. The position is stored relative to the nearest
 * screen edge, so it stays put when the window size or GUI scale changes.
 */
public class LayoutEditorScreen extends Screen implements PlayerHud.HidesHud {
    private static final int SNAP = 4;

    private final Screen parent;
    /** False = moving the player, true = moving the lyrics. */
    private boolean editLyrics;
    private StyledButton targetButton;
    private boolean dragging;
    private int dragOffsetX;
    private int dragOffsetY;

    public LayoutEditorScreen(Screen parent) {
        super(Component.literal("Move the player"));
        this.parent = parent;
    }

    private static SpotifyConfig cfg() {
        return ConfigIo.get();
    }

    private LyricsConfig lc() {
        return cfg().lyrics;
    }

    private void resetActive() {
        SpotifyConfig d = SpotifyConfig.defaults();
        if (editLyrics) {
            LyricsConfig l = lc();
            l.ax = d.lyrics.ax;
            l.ay = d.lyrics.ay;
            l.x = d.lyrics.x;
            l.y = d.lyrics.y;
        } else {
            cfg().ax = d.ax;
            cfg().ay = d.ay;
            cfg().x = d.x;
            cfg().y = d.y;
        }
    }

    private float scaleNow() {
        return editLyrics ? lc().scale : cfg().scale;
    }

    private float opacityNow() {
        return editLyrics ? lc().opacity : cfg().opacity;
    }

    private int[] boundsOf(boolean lyrics) {
        return lyrics ? LyricsLayout.bounds(minecraft, lc()) : HudLayout.bounds(minecraft, cfg());
    }

    @Override
    protected void init() {
        targetButton = addRenderableWidget(new StyledButton(width / 2 - 118, height - 60, 106, 16,
                Component.literal(editLyrics ? "Moving: Lyrics" : "Moving: Player"), b -> {
                    editLyrics = !editLyrics;
                    b.setMessage(Component.literal(editLyrics ? "Moving: Lyrics" : "Moving: Player"));
                }).accent(Colors.CYAN).tip("Switch between the player and the lyrics. You can also click the other one."));
        doneButton = addRenderableWidget(new StyledButton(width / 2 - 8, height - 60, 62, 16, Component.literal("Done"), b -> onClose())
                .accent(Colors.MINT).primary().tip("Save the place and go back (Esc)"));
        resetButton = addRenderableWidget(new StyledButton(width / 2 + 58, height - 60, 62, 16, Component.literal("Reset"), b -> {
            resetActive();
            if (editLyrics) {
                lc().scale = 1f;
                lc().opacity = 1f;
            } else {
                cfg().scale = 1f;
                cfg().opacity = 1f;
            }
        }).accent(Colors.RED).tip("Back to the default place, size and opacity"));
    }

    private StyledButton doneButton;
    private StyledButton resetButton;

    /** Keeps the two buttons out of the player's way: bottom row normally, top row if the player sits there. */
    private void placeButtons() {
        if (doneButton == null || resetButton == null || targetButton == null) return;
        int[] b = bounds();
        int rowX = width / 2 - 122;
        int rowW = 244;
        int[] rows = {height - 60, 5};
        int chosen = rows[0];
        for (int y : rows) {
            boolean hit = b[0] < rowX + rowW && b[0] + b[2] > rowX && b[1] < y + 20 && b[1] + b[3] > y - 4;
            if (!hit) {
                chosen = y;
                break;
            }
        }
        doneButton.setY(chosen);
        resetButton.setY(chosen);
        targetButton.setY(chosen);
    }

    private int[] bounds() {
        return boundsOf(editLyrics);
    }

    private void placeAt(int x, int y) {
        int[] b = bounds();
        if (editLyrics) LyricsLayout.place(lc(), x, y, b[2], b[3], width, height);
        else HudLayout.place(cfg(), x, y, b[2], b[3], width, height);
    }

    private int snap(int v) {
        if (minecraft.hasControlDown()) return v;
        return Math.round(v / (float) SNAP) * SNAP;
    }

    private boolean inside(double mx, double my) {
        return insideBox(bounds(), mx, my);
    }

    private static boolean insideBox(int[] b, double mx, double my) {
        return mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[3];
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0x66000000);
        for (int x = 0; x < width; x += 16) g.fill(x, 0, x + 1, height, 0x14FFFFFF);
        for (int y = 0; y < height; y += 16) g.fill(0, y, width, y + 1, 0x14FFFFFF);
        // centre guides
        g.fill(width / 2, 0, width / 2 + 1, height, 0x22FFFFFF);
        g.fill(0, height / 2, width, height / 2 + 1, 0x22FFFFFF);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        com.zlorbie788.spotify.config.UiFont oldFont = Gfx.font();
        int oldShift = Gfx.fontShift();
        Gfx.useFont(ConfigIo.get().uiFont, ConfigIo.get().fontShift);
        try {
            renderScreen(g, mouseX, mouseY, partialTick);
        } finally {
            Gfx.useFont(oldFont, oldShift);
        }
    }

    private void renderScreen(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        placeButtons();
        super.render(g, mouseX, mouseY, partialTick);
        SpotifyConfig cfg = cfg();
        int[] b = bounds();
        int[] pb = boundsOf(false);
        int[] lb = boundsOf(true);
        PlayerHud.preview(g, minecraft, cfg, pb[0], pb[1], cfg.scale, editLyrics ? cfg.opacity * 0.6f : cfg.opacity);
        LyricsHud.preview(g, minecraft, cfg, lb[0], lb[1], lc().scale, editLyrics ? lc().opacity : lc().opacity * 0.6f);
        int[] other = editLyrics ? pb : lb;
        g.renderOutline(other[0] - 1, other[1] - 1, other[2] + 2, other[3] + 2, 0x33FFFFFF);
        int outline = dragging ? Colors.CYAN : (inside(mouseX, mouseY) ? 0xCCFFFFFF : 0x88FFFFFF);
        g.renderOutline(b[0] - 1, b[1] - 1, b[2] + 2, b[3] + 2, outline);

        String anchor = (cfg.ay == SpotifyConfig.ANCHOR_END ? "bottom" : cfg.ay == SpotifyConfig.ANCHOR_CENTER ? "middle" : "top")
                + "-" + (cfg.ax == SpotifyConfig.ANCHOR_END ? "right" : cfg.ax == SpotifyConfig.ANCHOR_CENTER ? "centre" : "left");
        int ay = editLyrics ? lc().ay : cfg.ay;
        int ax = editLyrics ? lc().ax : cfg.ax;
        anchor = (ay == SpotifyConfig.ANCHOR_END ? "bottom" : ay == SpotifyConfig.ANCHOR_CENTER ? "middle" : "top")
                + "-" + (ax == SpotifyConfig.ANCHOR_END ? "right" : ax == SpotifyConfig.ANCHOR_CENTER ? "centre" : "left");
        center(g, String.format(Locale.ROOT, "%s  ·  x %d  y %d  ·  size %.1fx  ·  opacity %d%%  ·  stays near the %s",
                editLyrics ? "Lyrics" : "Player", b[0], b[1], scaleNow(), Math.round(opacityNow() * 100), anchor), height - 36, Colors.CYAN);
        center(g, "Drag = move  ·  scroll = size  ·  Shift+scroll = opacity  ·  Ctrl = no snapping", height - 24, Theme.dim());
        center(g, "Arrows = nudge (Shift = 10)  ·  R = reset  ·  L = player / lyrics  ·  Esc = save and go back", height - 12, Theme.dim());
    }

    private void center(GuiGraphics g, String text, int y, int color) {
        String cut = Gfx.fit(font, text, width - 12);
        Gfx.drawStyled(g, font, cut, (width - Gfx.width(font, cut)) / 2, y, Colors.opaque(color), false, false, true);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() == 0 && !inside(event.x(), event.y()) && insideBox(boundsOf(!editLyrics), event.x(), event.y())) {
            editLyrics = !editLyrics;
            targetButton.setMessage(Component.literal(editLyrics ? "Moving: Lyrics" : "Moving: Player"));
        }
        if (event.button() == 0 && inside(event.x(), event.y())) {
            int[] b = bounds();
            dragging = true;
            dragOffsetX = (int) event.x() - b[0];
            dragOffsetY = (int) event.y() - b[1];
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (dragging && event.button() == 0) {
            placeAt(snap((int) event.x() - dragOffsetX), snap((int) event.y() - dragOffsetY));
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        dragging = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        SpotifyConfig cfg = cfg();
        float dir = scrollY > 0 ? 1f : -1f;
        if (minecraft.hasShiftDown()) {
            float o = Math.max(0.1f, Math.min(1f, Math.round((opacityNow() + 0.1f * dir) * 10f) / 10f));
            if (editLyrics) lc().opacity = o;
            else cfg.opacity = o;
        } else {
            int[] before = bounds();
            float sc = Math.max(0.5f, Math.min(3f, Math.round((scaleNow() + 0.1f * dir) * 10f) / 10f));
            if (editLyrics) lc().scale = sc;
            else cfg.scale = sc;
            placeAt(before[0], before[1]);
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        int step = minecraft.hasShiftDown() ? 10 : 1;
        int[] b = bounds();
        switch (key) {
            case GLFW.GLFW_KEY_LEFT -> {
                placeAt(b[0] - step, b[1]);
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                placeAt(b[0] + step, b[1]);
                return true;
            }
            case GLFW.GLFW_KEY_UP -> {
                placeAt(b[0], b[1] - step);
                return true;
            }
            case GLFW.GLFW_KEY_DOWN -> {
                placeAt(b[0], b[1] + step);
                return true;
            }
            case GLFW.GLFW_KEY_R -> {
                resetActive();
                return true;
            }
            case GLFW.GLFW_KEY_L -> {
                editLyrics = !editLyrics;
                targetButton.setMessage(Component.literal(editLyrics ? "Moving: Lyrics" : "Moving: Player"));
                return true;
            }
            default -> { }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        ConfigIo.save();
    }

    @Override
    public void onClose() {
        ConfigIo.save();
        minecraft.setScreen(parent);
    }
}
