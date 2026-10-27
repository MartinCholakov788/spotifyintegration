package com.zlorbie788.spotify.hud;

import com.zlorbie788.spotify.config.SpotifyConfig;
import com.zlorbie788.spotify.config.SpotifyConfig.IntroMode;
import com.zlorbie788.spotify.config.SpotifyConfig.ShapeKind;
import com.zlorbie788.spotify.media.Player;
import com.zlorbie788.spotify.media.Snapshot;
import com.zlorbie788.spotify.util.Clock;
import com.zlorbie788.spotify.util.Colors;
import com.zlorbie788.spotify.util.Ease;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;

/**
 * The opening sequence of the full player, about five seconds, drawn entirely in code:
 * <ol>
 *   <li>the room falls into deep space and you fly at speed through a field of dark, rim-lit asteroids towards a star, with
 *       the camera swaying and rolling and star streaks rushing past (see {@link Rocks});</li>
 *   <li>the star swells until you fly into it: white-out, shockwave with red and cyan fringes, and the panel is carved
 *       open from the middle by the burst;</li>
 *   <li>dust gathers out of the dark and a light pen writes the signature of Zlorbie788 with sparks flying off the tip,
 *       ending in a long flourish and a glint;</li>
 *   <li>a metallic shine runs over the signature while its edges split into red and cyan;</li>
 *   <li>"SPOTIFY INTEGRATION MOD" and the version appear, the beat display wakes up (the beat of the real song flashes the
 *       light, album colours tint everything) and rings leave the cover;</li>
 *   <li>a scan line sweeps down the panel and the player appears behind it.</li>
 * </ol>
 * Only the intro carries the maker's name; everything else in the mod is called "Spotify Integration Mod".
 * Any key or click skips it. Whether it plays is the "Intro" setting (first time only, every time, once per game start, never).
 */
public final class Intro {
    public static final String VERSION = "1.4.0";
    public static final String MAKER = "ZLORBIE788";
    public static final String PRODUCT = "SPOTIFY INTEGRATION MOD";

    /** Seconds (at normal speed) where the stages start. */
    private static final float BURST = 1.70f;
    private static final float OPEN_FROM = 1.70f;
    private static final float OPEN_TO = 2.20f;
    private static final float DUST_FROM = 1.55f;
    private static final float SIG_FROM = 2.05f;
    private static final float SIG_TO = 3.30f;
    private static final float SHINE_FROM = 3.15f;
    private static final float SHINE_TO = 3.85f;
    private static final float PRODUCT_FROM = 3.20f;
    private static final float TITLE_FADE = 3.95f;
    private static final float TITLE_END = 4.35f;
    private static final float SCAN_FROM = 4.35f;
    private static final float SCAN_TO = 5.05f;
    private static final float END = 5.20f;

    private static boolean sessionShown;
    /** Set when a world or server was joined and the intro has not played since (the "every time I join" setting). */
    private static boolean joinPending = true;
    private static boolean replay;
    private static boolean suppress;
    private static boolean clipped;
    private static boolean running;
    private static long startNs;
    private static float speed = 1f;

    /** Remembered from the last drawn frame so the star field can aim at the panel and take the cover colours. */
    private static float lastCx = -1f;
    private static float lastCy = -1f;
    private static int lastAccent = 0xFF34D399;

    private static Signature sig;
    private static float sigUnit = -1f;

    private Intro() {}

    // ------------------------------------------------------------ control

    /** Game start: begins cutting the pictures that do not depend on the screen (the intro ones only when it can play). */
    public static void prewarmArt(SpotifyConfig cfg) {
        IntroArt.prewarmBasic();
        if (cfg.introMode != IntroMode.NEVER) IntroArt.prewarm();
    }

    /**
     * Starts cutting the pictures of the intro on a background thread (stars for this screen size included). Cheap to call
     * again; nothing ever waits for it, a picture that is not ready yet is just left out of a frame.
     */
    public static void prewarm() {
        IntroArt.prewarm();
        try {
            var win = net.minecraft.client.Minecraft.getInstance().getWindow();
            for (int l = 0; l < IntroArt.STAR_LAYERS; l++) IntroArt.starsTex(l, win.getGuiScaledWidth(), win.getGuiScaledHeight());
        } catch (Throwable ignored) {
            // no window yet: the stars are cut when the first frame asks for them
        }
    }

    /** A world or server was joined: the next time the player opens, "every time I join" plays the intro. */
    public static void onJoin() {
        joinPending = true;
    }

    /** Next player that opens plays the intro whatever the setting says (the Replay button). */
    public static void replayNext() {
        replay = true;
    }

    /** Call when a player screen opens: starts the intro if the setting (or a replay) says so. */
    public static void maybeStart(SpotifyConfig cfg) {
        if (suppress) {
            suppress = false;
            running = false;
            return;
        }
        boolean play;
        if (replay) {
            play = true;
        } else {
            IntroMode m = cfg.introMode;
            play = m == IntroMode.ALWAYS || (m == IntroMode.FIRST && !cfg.introSeen) || (m == IntroMode.SESSION && !sessionShown)
                    || (m == IntroMode.JOIN && joinPending);
        }
        replay = false;
        if (!play || !cfg.motion()) {
            running = false;
            return;
        }
        lastCx = -1f;
        lastCy = -1f;
        prewarm();
        cfg.introSeen = true;
        sessionShown = true;
        joinPending = false;
        speed = Math.max(0.5f, Math.min(2f, cfg.introSpeed));
        startNs = Clock.nanos();
        running = true;
    }

    /** The next player screen does not play the intro (one player screen switching to another). */
    public static void suppressNext() {
        suppress = true;
    }

    public static void skip() {
        running = false;
    }

    /** Seconds into the intro at normal speed; -1 when no intro is running. */
    public static float time() {
        if (!running) return -1f;
        float t = (Clock.nanos() - startNs) / 1e9f * speed;
        if (t >= END) {
            running = false;
            return -1f;
        }
        return t;
    }

    public static boolean active() {
        return time() >= 0f;
    }

    /** Is the real content (buttons, text, cover) allowed on screen yet? */
    public static boolean showContent() {
        float t = time();
        return t < 0f || t >= SCAN_FROM;
    }

    // ------------------------------------------------------------ drawing

    /** Speed of the star field: slow at first, then faster and faster until the burst. */
    private static float warpEnergy(float t) {
        float u = Math.min(t, BURST);
        return 0.12f * u + 1.7f * u * u * u;
    }

    /** The dimming over the scene, drawn before the panel: the room falls into space, stars rush outwards. */
    public static void veil(GuiGraphics g, int screenW, int screenH) {
        float t = time();
        if (t < 0f) return;
        float in = Ease.clamp01(t / 0.35f);
        float out = Math.max(0f, 1f - Ease.outCubic((t - SCAN_FROM) / 0.65f));
        float k = in * out;
        int hint = lastAccent;
        g.fill(0, 0, screenW, screenH, Colors.alpha(0xFF02030A, 0.74f * k));
        // two faint colour clouds, one from the cover colour and one from its opposite
        int cloudA = Colors.mix(0xFF1A1E70, hint, 0.35f);
        int cloudB = Colors.mix(0xFF4A1560, Colors.hsvShift(hint, 0.45f), 0.30f);
        g.fillGradient(0, 0, screenW, screenH / 2, Colors.alpha(cloudA, 0.34f * k), Colors.alpha(cloudA, 0f));
        g.fillGradient(0, screenH / 2, screenW, screenH, Colors.alpha(cloudB, 0f), Colors.alpha(cloudB, 0.30f * k));
        int band = Math.max(24, Math.min(screenW, screenH) / 4);
        int dark = Colors.alpha(0xFF010206, 0.5f * k);
        int none = Colors.alpha(0xFF010206, 0f);
        g.fillGradient(0, 0, screenW, band, dark, none);
        g.fillGradient(0, screenH - band, screenW, screenH, none, dark);

        float cx = lastCx < 0f ? screenW / 2f : lastCx;
        float cy = lastCy < 0f ? screenH / 2f : lastCy;
        float maxR = (float) Math.hypot(Math.max(cx, screenW - cx), Math.max(cy, screenH - cy));

        // ambient stars: four layers of points (each one picture) that twinkle and drift very slowly, nearer layers faster
        float ambient = Ease.clamp01((t - 0.1f) / 0.5f) * k;
        int[] ss = new int[2];
        int starScale = IntroArt.starSize(screenW, screenH, ss);
        for (int l = 0; l < IntroArt.STAR_LAYERS; l++) {
            IntroArt.Tex st = IntroArt.starsTex(l, screenW, screenH);
            if (st == null) continue;
            float layer = 0.3f + 0.7f * (l + 1) / IntroArt.STAR_LAYERS;
            float tw = 0.62f + 0.38f * (float) Math.sin(t * (1.3f + 0.9f * l) + l * 1.9f);
            float drift = Math.min(18f, t * 2.5f * layer);
            var pose = g.pose();
            pose.pushMatrix();
            pose.translate(-drift, 0f);
            g.blit(RenderPipelines.GUI_TEXTURED, st.id(), 0, 0, 0f, 0f, st.w() * starScale, st.h() * starScale, st.w(), st.h(), st.w(), st.h(),
                    Colors.alpha(Colors.mix(Colors.WHITE, hint, 0.2f), Math.min(1f, ambient * layer * tw * 1.15f)));
            pose.popMatrix();
        }

        // the star ahead: a small point that swells as you fly towards it (behind every rock)
        if (t < BURST + 0.5f) {
            float pre = Ease.clamp01((t - 0.05f) / (BURST - 0.05f));
            float grow = pre * pre * pre * pre;
            float starA = (0.35f + 0.65f * pre) * k * (t < BURST ? 1f : 1f - Ease.clamp01((t - BURST) / 0.5f));
            int hotStar = Colors.lighten(hint, 0.55f);
            IntroArt.glow(g, cx, cy, 6f + maxR * 0.60f * grow, hotStar, starA * 0.6f);
            IntroArt.glow(g, cx, cy, 3.5f + maxR * 0.25f * grow, Colors.mix(hotStar, Colors.WHITE, 0.5f), starA);
            IntroArt.glow(g, cx, cy, 2f + 5f * pre + maxR * 0.07f * grow, Colors.WHITE, starA);
        }

        // the asteroid field
        if (t < BURST + 0.05f) {
            float fieldIn = Ease.clamp01((t - 0.05f) / 0.45f);
            Rocks.draw(g, t, BURST, screenW, screenH, cx, cy, hint, fieldIn * k);
        }

        // speed streaks rushing past: two pictures of dashes pointing away from the middle, enlarged around it again and again
        // (two copies half a cycle apart, so one is always fading in as the other fades out), dots first and streaks at full speed
        if (t < BURST + 0.35f) {
            float warpA = t < BURST ? 1f : 1f - Ease.clamp01((t - BURST) / 0.35f);
            float speedK = Ease.clamp01(t / BURST);
            speedK = speedK * speedK;
            float appear = Ease.clamp01(t / 0.3f);
            float cycle = warpEnergy(t) * 0.5f;
            int tintW = Colors.mix(Colors.mix(Colors.WHITE, 0xFF9FD4FF, 0.35f), hint, 0.18f);
            var pose = g.pose();
            for (int kind = 0; kind < 2; kind++) {
                IntroArt.Tex w = IntroArt.warpTex(kind == 1);
                if (w == null) continue;
                float kindA = kind == 1 ? speedK : 1f - 0.55f * speedK;
                for (int copy = 0; copy < 2; copy++) {
                    float phi = (cycle + copy * 0.5f) % 1f;
                    float zoom = (float) Math.pow(3.6, phi);
                    float la = (float) Math.pow(Math.sin(Math.PI * phi), 1.3);
                    float a = warpA * appear * k * la * kindA * 0.9f;
                    if (a < 0.01f) continue;
                    int half = Math.round(maxR * 1.08f * zoom);
                    pose.pushMatrix();
                    pose.translate(cx, cy);
                    g.blit(RenderPipelines.GUI_TEXTURED, w.id(), -half, -half, 0f, 0f, half * 2, half * 2, w.w(), w.h(), w.w(), w.h(),
                            Colors.alpha(tintW, Math.min(1f, a)));
                    pose.popMatrix();
                }
            }
        }

        // the white-out as the camera flies into the star
        if (t > BURST - 0.22f && t < BURST + 0.45f) {
            float w = t < BURST ? (float) Math.pow(Ease.clamp01((t - (BURST - 0.22f)) / 0.22f), 2.0)
                    : (float) Math.pow(1f - Ease.clamp01((t - BURST) / 0.26f), 2.4);
            int flash = Colors.mix(Colors.WHITE, Colors.lighten(hint, 0.5f), 0.35f);
            g.fill(0, 0, screenW, screenH, Colors.alpha(flash, 0.22f * w * k));
            IntroArt.glow(g, cx, cy, maxR * (0.50f + 0.8f * w), flash, 0.95f * w * k);
        }
    }

    /** How open the panel is, 0..1 (1 when there is no intro). */
    public static float open() {
        float t = time();
        if (t < 0f) return 1f;
        return Ease.outCubic((t - OPEN_FROM) / (OPEN_TO - OPEN_FROM));
    }

    /** Clips the panel to the part that has opened so far. Returns false when nothing of it is visible yet. */
    public static boolean beginPanel(GuiGraphics g, int x, int y, int w, int h) {
        clipped = false;
        float t = time();
        if (t < 0f) return true;
        float o = open();
        if (o <= 0.001f) return false;
        clipped = true;
        if (o >= 0.999f) {
            g.enableScissor(x - 14, y - 14, x + w + 14, y + h + 14);
            return true;
        }
        int hh = Math.max(2, Math.round(h * o));
        int cy = y + h / 2;
        g.enableScissor(x - 14, cy - hh / 2, x + w + 14, cy + (hh + 1) / 2);
        return true;
    }

    public static void endPanel(GuiGraphics g) {
        if (clipped) g.disableScissor();
        clipped = false;
    }

    private static float rand(int k, int salt) {
        int h = k * 0x9E3779B1 + salt * 0x85EBCA6B;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        return ((h >>> 8) & 0xFFFF) / 65535f;
    }

    /** Draws text letter by letter with a wide tracking, each letter rising and fading in on its own time. */
    private static void spaced(GuiGraphics g, Font f, String text, int cx, int y, float scale, int tracking, float from,
                               float step, float dur, float t, int color, int glow, float fade, float shine) {
        int total = 0;
        int[] widths = new int[text.length()];
        for (int i = 0; i < text.length(); i++) {
            widths[i] = Gfx.width(f, String.valueOf(text.charAt(i)), true);
            total += widths[i] + (i < text.length() - 1 ? tracking : 0);
        }
        float x = -total / 2f;
        var pose = g.pose();
        // one soft glow under the whole line instead of a halo around every letter; it grows as the letters arrive
        float reach = Ease.clamp01((t - from) / (step * text.length() + dur));
        int gw = Math.round(total * scale * (0.25f + 0.75f * reach)) + 24;
        IntroArt.vlight(g, cx - gw / 2, y - Math.round(7 * scale), cx + (gw + 1) / 2, y + Math.round(17 * scale), glow, 0.20f * fade);
        for (int i = 0; i < text.length(); i++) {
            float u = Ease.clamp01((t - from - i * step) / dur);
            char c = text.charAt(i);
            if (c != ' ' && u > 0f) {
                float rise = (1f - Ease.outCubic(u)) * 9f;
                float pop = 1f + (1f - Ease.outCubic(u)) * 0.45f;
                float a = Ease.clamp01(u * 1.6f) * fade;
                float centre = x + widths[i] / 2f;
                float pos = total <= 0 ? 0f : (centre + total / 2f) / total;
                float flash = Math.max(0f, 1f - Math.abs(pos - shine) * 5f) * fade;
                pose.pushMatrix();
                pose.translate(cx + centre * scale, y + rise * scale);
                pose.scale(scale * pop, scale * pop);
                String s = String.valueOf(c);
                int half = widths[i] / 2;
                Gfx.drawStyled(g, f, s, -half, 0, Colors.alpha(Colors.mix(color, Colors.WHITE, flash), a), true, false, false);
                pose.popMatrix();
            }
            x += widths[i] + tracking;
        }
    }

    /**
     * Everything that is drawn on top of the panel: the burst and shockwave, the corner brackets, the signature with its dust,
     * pen and sparks, the product title, the beat display, the rings and the scan that reveals the content.
     */
    public static void overlay(GuiGraphics g, Font f, int x, int y, int w, int h, ShapeKind kind, float radius, int accent,
                               int coverCx, int coverCy, int plateTop, int plateBottom) {
        float t = time();
        if (t < 0f) return;
        int cx = x + w / 2;
        int cy = y + h / 2;
        lastCx = cx;
        lastCy = cy;
        lastAccent = accent;
        int hot = Colors.lighten(accent, 0.55f);
        float fade = 1f - Ease.clamp01((t - TITLE_FADE) / (TITLE_END - TITLE_FADE));
        float beat = Math.min(1f, Visual.pulse());

        // 1. the burst after the flight into the star
        if (t >= BURST && t < BURST + 0.9f) {
            float post = t - BURST;
            float u = Ease.clamp01(post / 0.55f);
            float fl = (1f - u) * (1f - u);
            IntroArt.glow(g, cx, cy, 16f + 125f * Ease.outCubic(u), hot, 0.95f * fl);
            IntroArt.glow(g, cx, cy, 9f + 40f * Ease.outCubic(u), Colors.WHITE, fl);
            // the shockwave, with red and cyan fringes
            float ru = Ease.clamp01(post / 0.9f);
            float rr = Ease.outCubic(ru) * Math.max(w, h) * 0.8f;
            float ra = (1f - ru) * (1f - ru);
            IntroArt.ring(g, cx, cy, rr + 2.6f, 0xFFFF3B6B, 0.55f * ra);
            IntroArt.ring(g, cx, cy, rr - 2.6f, 0xFF3BE8FF, 0.55f * ra);
            IntroArt.ring(g, cx, cy, rr, Colors.WHITE, 0.85f * ra);
            IntroArt.ring(g, cx, cy, rr * 0.62f, hot, 0.5f * ra);
        }

        // 2. the light streak, which splits into the two edges of the opening panel
        if (t >= BURST && t < OPEN_TO + 0.5f) {
            float grow = Ease.outCubic((t - BURST) / 0.30f);
            int lw = Math.round((w + 80) * grow);
            float out = 1f - Ease.clamp01((t - OPEN_TO) / 0.5f);
            float o = open();
            int off = Math.round(h * o / 2f);
            int[] rows = o > 0.02f ? new int[] {cy - off, cy + off} : new int[] {cy};
            for (int row : rows) {
                IntroArt.vlight(g, cx - lw / 2, row - 10, cx + lw / 2, row + 11, accent, 0.26f * out);
                IntroArt.vlight(g, cx - lw / 2, row - 4, cx + lw / 2, row + 5, hot, 0.55f * out);
                g.fill(cx - lw / 2, row, cx + lw / 2, row + 1, Colors.alpha(Colors.WHITE, 0.98f * out));
                g.fill(cx - lw / 2, row - 1, cx - lw / 2 + 3, row + 2, Colors.alpha(Colors.WHITE, 0.9f * out));
                g.fill(cx + lw / 2 - 3, row - 1, cx + lw / 2, row + 2, Colors.alpha(Colors.WHITE, 0.9f * out));
            }
        }

        // 3. corner brackets snapping in (they flash with the beat)
        if (t > OPEN_FROM) {
            float a = Ease.clamp01((t - OPEN_FROM) / 0.3f) * (1f - Ease.clamp01((t - 3.85f) / 0.4f));
            int len = Math.round(4 + 7 * Ease.outBack(Ease.clamp01((t - OPEN_FROM) / 0.5f)) + 3 * beat);
            Fx.brackets(g, x, y, w, h, len, Colors.mix(hot, Colors.WHITE, beat * 0.6f), Math.min(1f, a * (0.85f + 0.4f * beat)));
        }

        // 4. a band of light passing over the opened panel
        if (t > 2.10f && t < 3.30f) {
            float u = Ease.inOutCubic((t - 2.10f) / 1.2f);
            int bandW = Math.max(30, w / 4);
            int bx = Math.round(x - bandW + (w + 2 * bandW) * u);
            float a = 0.12f * (float) Math.sin(Math.PI * u);
            g.enableScissor(x, y + 2, x + w, y + h - 2);
            IntroArt.hlight(g, bx, y + 2, bx + bandW, y + h - 2, hot, a);
            g.disableScissor();
        }

        // 5. the signature of Zlorbie788
        float unit = Math.max(5f, Math.min(17f, (w - 74) / 9.9f));
        unit = Math.round(unit * 2f) / 2f;
        if (sig == null || sigUnit != unit) {
            sig = Signature.build(unit);
            sigUnit = unit;
        }
        // ask for the pictures early; they are cut in the background and ready long before the pen starts
        IntroArt.warmSignature(sig);
        float ox = cx - sig.width / 2f;
        float oy = cy - Math.min(48f, h * 0.27f);
        float su = Ease.inOutCubic((t - SIG_FROM) / (SIG_TO - SIG_FROM));
        float pen = su * sig.total;
        if (t > DUST_FROM && t < TITLE_END) {
            // 5a. the plan of the signature, very faint, so the eye knows where the pen is going
            float guide = Ease.clamp01((t - (SIG_FROM - 0.25f)) / 0.35f) * fade;
            if (guide > 0f && su < 1f) sig.pass(g, ox, oy, sig.total, false, Colors.alpha(accent, 0.11f * guide), 0f, 0f);

            // 5b. dust gathering out of the dark and settling on the path
            int dust = 48;
            for (int i = 0; i < dust; i++) {
                float t0 = DUST_FROM + rand(i, 21) * 0.55f;
                float u = (t - t0) / 0.75f;
                if (u <= 0f) continue;
                float s = (i + 0.5f) / dust * sig.total;
                float[] tp = sig.at(s);
                float ang = rand(i, 22) * (float) (Math.PI * 2);
                float dist = 40f + rand(i, 23) * 130f;
                float e = Ease.outCubic(Math.min(1f, u));
                float px = ox + tp[0] + (float) Math.cos(ang) * dist * (1f - e);
                float py = oy + tp[1] + (float) Math.sin(ang) * dist * (1f - e) * 0.7f;
                boolean written = pen > s;
                float a = Math.min(1f, u * 2f) * (written ? Math.max(0f, 1f - (pen - s) / (sig.total * 0.05f)) : 0.75f) * fade;
                if (a <= 0.02f) continue;
                int ix = Math.round(px);
                int iy = Math.round(py);
                // still travelling: a short hot streak; settled: a white point
                if (u < 1f) g.fill(ix - 1, iy, ix + 2, iy + 1, Colors.alpha(Colors.mix(hot, Colors.WHITE, 0.55f), a));
                else g.fill(ix, iy, ix + 1, iy + 1, Colors.alpha(Colors.WHITE, a));
            }

            // 5c. colour fringes that split apart while the shine passes
            float shineK = (float) Math.sin(Math.PI * Ease.clamp01((t - SHINE_FROM) / (SHINE_TO - SHINE_FROM)));
            if (pen > 0f) {
                float o2 = 0.9f + 2.4f * shineK;
                sig.pass(g, ox, oy, pen, false, Colors.alpha(0xFFFF3B6B, 0.38f * fade), -o2, 0f);
                sig.pass(g, ox, oy, pen, false, Colors.alpha(0xFF3BE8FF, 0.38f * fade), o2, 0f);
            }

            // 5d. the signature itself: glow, then a white-hot core with the metallic shine running over it
            float shine = t < SHINE_FROM ? -1f : (t - SHINE_FROM) / (SHINE_TO - SHINE_FROM) * 1.5f - 0.25f;
            if (t > SHINE_TO) shine = -1f;
            sig.pass(g, ox, oy, pen, true, Colors.alpha(accent, Math.min(1f, 0.34f * (0.85f + 0.7f * beat) * fade)), 0f, 0f);
            sig.pass(g, ox, oy, pen, false, Colors.alpha(Colors.mix(Colors.WHITE, hot, 0.18f), fade), 0f, 0f);
            if (shine >= 0f) sig.shine(g, ox, oy, pen, shine, fade);

            // 5e. the pen: a bright tip and a stream of sparks
            if (su > 0f && su < 1f) {
                float[] tip = sig.at(pen);
                IntroArt.glow(g, ox + tip[0], oy + tip[1], 10f, hot, 0.75f);
                IntroArt.glow(g, ox + tip[0], oy + tip[1], 4.2f, Colors.WHITE, 1f);
            }
            int last = (int) Math.floor(t * 32f);
            for (int j = last - 19; j <= last; j++) {
                float tb = j / 32f;
                if (tb < SIG_FROM || tb > SIG_TO + 0.05f) continue;
                float age = t - tb;
                if (age < 0f || age > 0.6f) continue;
                float pb = Ease.inOutCubic((tb - SIG_FROM) / (SIG_TO - SIG_FROM)) * sig.total;
                float[] at = sig.at(pb);
                float ang = rand(j, 31) * (float) (Math.PI * 2);
                float spd = 16f + rand(j, 32) * 46f;
                float sx = ox + at[0] + (float) Math.cos(ang) * spd * age;
                float sy = oy + at[1] + (float) Math.sin(ang) * spd * age + 46f * age * age;
                float a = (1f - age / 0.6f);
                a *= a * fade;
                int ix = Math.round(sx);
                int iy = Math.round(sy);
                g.fill(ix - 1, iy, ix + 2, iy + 1, Colors.alpha(Colors.mix(hot, Colors.WHITE, 0.6f), a));
            }

            // 5f. the glint at the end of the flourish
            if (t > SIG_TO - 0.05f) {
                float[] end = sig.at(sig.total);
                float gu = Ease.outBack(Ease.clamp01((t - SIG_TO + 0.05f) / 0.35f));
                float pulse = 0.75f + 0.25f * (float) Math.sin(t * 9f) + 0.4f * beat;
                Signature.glint(g, ox + end[0], oy + end[1], (6f + unit * 0.6f) * gu * pulse, hot, fade);
            }
        }

        // 6. the product name, the version badge, the beat display and the status line
        int py2 = Math.round(oy + sig.height) + 13;
        if (t > PRODUCT_FROM && t < TITLE_END) {
            int ptrack = 1;
            int pn = 0;
            for (int i = 0; i < PRODUCT.length(); i++) pn += Gfx.width(f, String.valueOf(PRODUCT.charAt(i)), true) + ptrack;
            float ps = Math.max(0.75f, Math.min(1.35f, (w - 36) / (float) Math.max(1, pn)));
            spaced(g, f, PRODUCT, cx, py2, ps, ptrack, PRODUCT_FROM, 0.022f, 0.30f, t, Colors.alpha(Colors.TEXT, 1f), accent,
                    fade * 0.95f, 2f);

            float bu = Ease.outBack(Ease.clamp01((t - PRODUCT_FROM - 0.55f) / 0.4f));
            if (bu > 0.01f) {
                String ver = "v" + VERSION;
                int bw = Gfx.width(f, ver, true) + 14;
                int bh = 13;
                var pose = g.pose();
                pose.pushMatrix();
                pose.translate(cx, py2 + Math.round(10 * ps) + 12 + bh / 2f);
                pose.scale(bu, bu);
                Gfx.panel(g, -bw / 2f, -bh / 2f, bw, bh, ShapeKind.ROUND, bh / 2f,
                        Colors.alpha(Colors.mix(accent, 0xFF05060A, 0.55f), 0.9f * fade),
                        Colors.alpha(Colors.mix(accent, 0xFF05060A, 0.75f), 0.9f * fade),
                        Colors.alpha(hot, 0.85f * fade), 1f);
                Gfx.drawStyled(g, f, ver, -bw / 2 + 7, -4, Colors.alpha(Colors.WHITE, fade), true, false, false);
                pose.popMatrix();
            }
        }
        if (t > 2.85f && t < TITLE_END) {
            // the beat display waking up: soft bars climb one after another
            int bars = Math.max(10, Math.min(30, (w - 60) / 6));
            int baseY = Math.min(y + h - 26, cy + 68);
            int bx = cx - (bars * 5) / 2;
            for (int i = 0; i < bars; i++) {
                float u = Ease.outCubic((t - 2.85f - i * 0.016f) / 0.40f);
                if (u <= 0f) continue;
                float mid = 1f - Math.abs(i - (bars - 1) / 2f) / (bars / 2f);
                float wave = 0.30f + 0.70f * (float) Math.abs(Math.sin(i * 0.65 + t * 5.0));
                float bh = Math.max(1.5f, (6f + 10f * mid) * wave * u * (1f + 0.5f * beat));
                int top = baseY - Math.round(bh);
                g.fillGradient(bx + i * 5, top, bx + i * 5 + 2, baseY, Colors.alpha(Colors.lighten(accent, 0.35f), 0.9f * fade),
                        Colors.alpha(accent, 0.55f * fade));
            }

            Snapshot s = Player.snap();
            String status = s.active()
                    ? "SIGNAL LOCKED  ·  " + (s.appName().isEmpty() ? "MEDIA SESSION" : s.appName().toUpperCase(java.util.Locale.ROOT))
                    : "STANDBY  ·  OPEN SPOTIFY AND PRESS PLAY";
            float st = Ease.clamp01((t - 3.5f) / 0.4f) * fade;
            if (st > 0.01f) {
                String fitted = Gfx.fit(f, status, w - 24);
                Gfx.drawStyled(g, f, fitted, cx - Gfx.width(f, fitted) / 2, Math.min(y + h - 12, baseY + 8),
                        Colors.alpha(Colors.TEXT_SOFT, st * 0.8f), false, false, false);
            }
        }

        // 7. the beat of the song lights the inner edge of the panel
        if (beat > 0.05f && t > OPEN_TO && t < SCAN_FROM) {
            int in = Math.max(3, Math.round(radius * 0.5f));
            float a = 0.16f * beat * fade;
            g.fillGradient(x + in, y + 2, x + w - in, y + 2 + 14, Colors.alpha(hot, a), Colors.alpha(hot, 0f));
            g.fillGradient(x + in, y + h - 16, x + w - in, y + h - 2, Colors.alpha(hot, 0f), Colors.alpha(hot, a));
        }

        // 8. rings leaving the cover
        for (int k = 0; k < 3; k++) {
            float t0 = 3.05f + 0.20f * k;
            float u = (t - t0) / 1.0f;
            if (u <= 0f || u >= 1f) continue;
            float r = 6f + Ease.outCubic(u) * 200f;
            float a = 0.7f * (1f - u) * (1f - u);
            IntroArt.ring(g, coverCx, coverCy, r, hot, a);
            IntroArt.ring(g, coverCx, coverCy, r + 2f, accent, a * 0.4f);
        }

        // a quiet reminder that it can be skipped
        if (t > 2.0f && t < 3.5f) {
            float a = Ease.clamp01((t - 2.0f) / 0.4f) * (1f - Ease.clamp01((t - 3.2f) / 0.3f)) * 0.5f;
            String skip = "CLICK OR PRESS A KEY TO SKIP";
            String shown = Gfx.fit(f, skip, w - 16);
            Gfx.drawStyled(g, f, shown, cx - Gfx.width(f, shown) / 2, y + h - 12, Colors.alpha(Colors.TEXT_SOFT, a), false, false, false);
        }

        // 9. the scan that reveals the player
        if (t >= SCAN_FROM) {
            float u = Ease.inOutCubic((t - SCAN_FROM) / (SCAN_TO - SCAN_FROM));
            int scanY = y + Math.round(h * u);
            if (scanY < y + h) {
                g.enableScissor(x, scanY, x + w, y + h);
                Gfx.panel(g, x + 1, y + 1, w - 2, h - 2, kind, Math.max(0f, radius - 1f), plateTop, plateBottom, 0, 0f);
                g.disableScissor();
            }
            int in = Math.max(3, Math.round(radius * 0.5f));
            float sf = 1f - Ease.clamp01((t - SCAN_TO) / 0.15f);
            g.fillGradient(x + in, scanY - 18, x + w - in, scanY, Colors.alpha(accent, 0f), Colors.alpha(accent, 0.34f * sf));
            g.fillGradient(x + in, scanY + 1, x + w - in, scanY + 5, Colors.alpha(accent, 0.16f * sf), Colors.alpha(accent, 0f));
            g.fill(x + in, scanY, x + w - in, scanY + 1, Colors.alpha(Colors.WHITE, 0.98f * sf));
        }
    }
}
