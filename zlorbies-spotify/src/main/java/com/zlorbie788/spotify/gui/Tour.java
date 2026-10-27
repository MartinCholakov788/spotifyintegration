package com.zlorbie788.spotify.gui;

import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.config.SpotifyConfig;
import com.zlorbie788.spotify.gui.widgets.Chrome;
import com.zlorbie788.spotify.hud.Anim;
import com.zlorbie788.spotify.hud.Gfx;
import com.zlorbie788.spotify.hud.Intro;
import com.zlorbie788.spotify.hud.Theme;
import com.zlorbie788.spotify.util.Colors;
import com.zlorbie788.spotify.util.Ease;
import com.zlorbie788.spotify.util.TextWrap;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

/**
 * The first-time tour: five short cards laid over the settings screen (never over the player). Next / Back / Skip with the
 * mouse or the keys (Enter, Right, Left, Esc). It shows by itself the first time the settings open, and again from
 * Settings > Keys & setup > Quick tour or {@code /music tutorial}.
 */
public final class Tour {
    private record Step(String title, String[] lines, int color) {}

    private static final Step[] STEPS = {
            new Step("Welcome to Spotify Integration Mod", new String[]{
                    "Your music, right inside Minecraft.",
                    "It follows Spotify and it also plays your own songs. No log-in is ever needed."}, Colors.MINT),
            new Step("Two ways to play", new String[]{
                    "If Spotify is open, the player shows it. If not, it plays the songs in your music folder.",
                    "Press \"Open music folder\", drop in MP3, OGG, WAV or FLAC files, and press play."}, Colors.PINK),
            new Step("Control it while you play", new String[]{
                    "Right Alt opens the player. On the number pad: 5 play / pause, 4 and 6 previous and next, 8 and 2 volume, + like.",
                    "A tiny bar at the top shows what happened, then hides again."}, Colors.CYAN),
            new Step("Make it yours", new String[]{
                    "You are in the settings: seven groups on the left, a search box, and a short explanation under every option.",
                    "Try a Quick look, tune the equaliser, and save your whole setup as a profile such as \"Survival\" or \"Build\"."}, Colors.PURPLE),
            new Step("Good to know", new String[]{
                    "Type /music help in chat to see every command. Something looks wrong? Settings, Keys & setup, \"Reset to safe look\".",
                    "Replay this tour any time: Settings, Keys & setup, \"Quick tour\", or /music tutorial."}, Colors.GOLD),
    };

    private static boolean active;
    private static int step;
    private static long stepAt;
    private static long startedAt;
    private static boolean pendingAuto = true;

    private static int nextX, nextY, nextW, nextH;
    private static int backX, backY, backW, backH;
    private static int skipX, skipY, skipW, skipH;

    private Tour() {}

    public static boolean active() {
        return active;
    }

    /** Starts the tour from the first card. */
    public static void start() {
        active = true;
        step = 0;
        startedAt = stepAt = System.nanoTime();
    }

    /** Called when a player screen opens: starts the tour once for a new player. */
    public static void maybeStart(SpotifyConfig cfg) {
        if (!cfg.tourDone && pendingAuto) {
            pendingAuto = false;
            start();
        }
    }

    public static void finish() {
        active = false;
        SpotifyConfig cfg = ConfigIo.get();
        if (!cfg.tourDone) {
            cfg.tourDone = true;
            ConfigIo.save();
        }
    }

    private static void go(int delta) {
        int next = step + delta;
        if (next >= STEPS.length) {
            finish();
            return;
        }
        if (next < 0) return;
        step = next;
        stepAt = System.nanoTime();
    }

    // ------------------------------------------------------------ drawing

    public static void render(GuiGraphics g, Font f, int sw, int sh, int mouseX, int mouseY) {
        if (!active) return;
        float t = Anim.enabled() ? Ease.outCubic(Math.min(1f, (System.nanoTime() - startedAt) / 280_000_000f)) : 1f;
        float s = Anim.enabled() ? Ease.outCubic(Math.min(1f, (System.nanoTime() - stepAt) / 220_000_000f)) : 1f;
        g.fill(0, 0, sw, sh, Colors.alpha(0xFF04060C, 0.62f * t));

        Step st = STEPS[step];
        int accent = Theme.accent(st.color());
        int w = Math.min(sw - 16, 330);
        int textW = w - 28;
        java.util.List<String> body = new java.util.ArrayList<>();
        for (String line : st.lines()) {
            body.addAll(TextWrap.wrapAll(line, textW, x -> Gfx.width(f, x)));
            body.add("");
        }
        if (!body.isEmpty()) body.remove(body.size() - 1);
        int h = 62 + body.size() * 10 + 26;
        int x = (sw - w) / 2;
        int y = (int) ((sh - h) / 2f + (1f - t) * 10f);

        Chrome.body(g, x, y, w, h, Colors.alpha(Colors.mix(Theme.surface(), accent, 0.08f), 0.98f), Colors.alpha(Theme.surface(), 0.98f),
                Colors.alpha(accent, 0.7f));
        Gfx.halo(g, x, y, w, h, accent, 0.35f * t);
        g.fillGradient(x + 1, y, x + w - 1, y + 3, Colors.alpha(accent, 0.9f), Colors.alpha(accent, 0.1f));

        // the step badge: a disc with the number, glowing in the step colour
        int bx = x + 24;
        int by = y + 30;
        Gfx.disc(g, bx, by, 13, Colors.alpha(accent, 0.20f));
        Gfx.disc(g, bx, by, 10, Colors.opaque(accent));
        Gfx.textCentre(g, f, String.valueOf(step + 1), bx, by - 4, 0xFF05070D, 1f, true, false);

        float slide = (1f - s) * 8f;
        Gfx.drawStyled(g, f, Gfx.fit(f, st.title(), w - 80, true, false), x + 44 + Math.round(slide), y + 22,
                Colors.alpha(Theme.text(), s), true, false, false);
        Gfx.drawStyled(g, f, (step + 1) + " of " + STEPS.length, x + 44 + Math.round(slide), y + 34, Colors.alpha(Theme.dim(), s), false,
                false, false);
        int ty = y + 54;
        for (String row : body) {
            Gfx.drawStyled(g, f, row, x + 14 + Math.round(slide), ty, Colors.alpha(Theme.soft(), s), false, false, false);
            ty += 10;
        }

        // dots
        int dotsX = x + 14;
        int dotsY = y + h - 16;
        for (int i = 0; i < STEPS.length; i++) {
            int c = i == step ? accent : Colors.alpha(Theme.line(), 0.9f);
            Gfx.disc(g, dotsX + i * 10 + 3, dotsY + 4, i == step ? 3 : 2, c);
        }

        // buttons
        nextW = step == STEPS.length - 1 ? 72 : 52;
        nextH = 15;
        nextX = x + w - nextW - 10;
        nextY = y + h - 22;
        button(g, f, nextX, nextY, nextW, nextH, step == STEPS.length - 1 ? "Let's go" : "Next", accent, true, mouseX, mouseY);
        if (step > 0) {
            backW = 46;
            backH = 15;
            backX = nextX - backW - 6;
            backY = nextY;
            button(g, f, backX, backY, backW, backH, "Back", accent, false, mouseX, mouseY);
        } else {
            backW = 0;
        }
        skipW = Gfx.width(f, "Skip") + 12;
        skipH = 13;
        skipX = x + w - skipW - 6;
        skipY = y + 8;
        boolean hs = in(mouseX, mouseY, skipX, skipY, skipW, skipH);
        Gfx.drawStyled(g, f, "Skip", skipX + 6, skipY + 3, hs ? Theme.text() : Theme.dim(), false, false, false);
    }

    private static void button(GuiGraphics g, Font f, int x, int y, int w, int h, String label, int accent, boolean primary,
                               int mx, int my) {
        boolean hover = in(mx, my, x, y, w, h);
        int fill = primary ? Colors.alpha(accent, hover ? 0.95f : 0.8f) : Colors.alpha(Theme.raised(), hover ? 1f : 0.8f);
        Chrome.body(g, x, y, w, h, fill, Colors.alpha(accent, hover ? 0.9f : 0.5f));
        Gfx.textCentre(g, f, label, x + w / 2, y + 4, primary ? 0xFF05070D : Theme.text(), 1f, primary, false);
    }

    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ------------------------------------------------------------ input

    /** Returns true when the click belonged to the tour (it swallows every click while it is open). */
    public static boolean mouseClicked(double mx, double my) {
        if (!active || Intro.active()) return false;
        if (in(mx, my, skipX, skipY, skipW, skipH)) finish();
        else if (in(mx, my, nextX, nextY, nextW, nextH)) go(1);
        else if (backW > 0 && in(mx, my, backX, backY, backW, backH)) go(-1);
        return true;
    }

    /** Returns true when the key was used by the tour. */
    public static boolean key(int key) {
        if (!active || Intro.active()) return false;
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            finish();
        } else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_RIGHT
                || key == GLFW.GLFW_KEY_SPACE) {
            go(1);
        } else if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_BACKSPACE) {
            go(-1);
        }
        return true;
    }

    /** The accent colour the tour uses for the current card (for tests). */
    static int accentOf(int index) {
        return STEPS[index].color();
    }

    static int steps() {
        return STEPS.length;
    }

}
