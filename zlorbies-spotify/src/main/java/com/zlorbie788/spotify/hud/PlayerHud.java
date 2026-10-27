package com.zlorbie788.spotify.hud;

import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.config.LyricsConfig;
import com.zlorbie788.spotify.lyrics.Lyrics;
import com.zlorbie788.spotify.config.SpotifyConfig;
import com.zlorbie788.spotify.config.SpotifyConfig.ShapeKind;
import com.zlorbie788.spotify.config.UiFont;
import com.zlorbie788.spotify.media.Covers;
import com.zlorbie788.spotify.media.MediaBridge;
import com.zlorbie788.spotify.media.Player;
import com.zlorbie788.spotify.media.Snapshot;
import com.zlorbie788.spotify.util.Clock;
import com.zlorbie788.spotify.util.Colors;
import com.zlorbie788.spotify.util.Ease;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * The on-screen player. Five fixed-size looks (card, compact bar, vinyl, minimal, tall); none of them can grow
 * or shrink, long text scrolls or is cut with an ellipsis, and every animation can be switched off.
 */
public final class PlayerHud {
    /** Screens that draw their own player implement this, so the in-game HUD does not show up behind them. */
    public interface HidesHud { }

    private static long lastNanos = System.nanoTime();
    private static float shown;
    private static float spin;
    private static float spinSpeed;
    private static float eqAmp;
    private static int displayAccent = Colors.MINT;
    private static int lastSerial = -1;
    private static long popupUntil;
    /** 1 = fully visible, down to the "faded" opacity after a quiet while (see Settings > Alerts). */
    private static float fadeLevel = 1f;
    private static long fadeNanos = System.nanoTime();
    /** What the visibility rules wanted at the last frame (the toast uses it to know if the player is hidden). */
    private static float lastTarget = 1f;
    /** Size of the small beat display. */
    private static final int EQ_W = 26;
    private static final int EQ_H = 10;

    private PlayerHud() {}

    // ------------------------------------------------------------ per-frame state

    /** Everything one frame of drawing needs, worked out once. */
    private static final class Ctx {
        GuiGraphics g;
        Minecraft mc;
        Font font;
        SpotifyConfig cfg;
        float alpha;
        float dev;
        boolean motion;
        boolean hc;
        boolean shadow;
        int accent;
        int accent2;
        Covers.Palette palette;
        String title = "";
        String artist = "";
        String album = "";
        String label = "";
        boolean playing;
        boolean idle;
        boolean ad;
        long pos;
        long dur;
        float progress;
        long since;
        long now;
        int seed;
        int text;
        int soft;
        int dim;
        int w;
        int h;
        /** Lyrics to show inside the card, or null (off, or the song has no timed lyrics). */
        CardLyrics.Now ly;
    }

    private static void advance(SpotifyConfig cfg, float target, int accentTarget, boolean playing) {
        long nanos = System.nanoTime();
        float dt = Math.min(0.1f, (nanos - lastNanos) / 1_000_000_000f);
        lastNanos = nanos;

        int serial = Player.trackSerial();
        if (serial != lastSerial) {
            if (lastSerial != -1 || serial > 0) popupUntil = nanos + cfg.popupSeconds * 1_000_000_000L;
            lastSerial = serial;
        }

        if (cfg.motion()) {
            shown = Ease.approach(shown, target, 7f * cfg.animSpeed, dt);
            if (Math.abs(shown - target) < 0.004f) shown = target;
        } else {
            shown = target;
        }

        spinSpeed = Ease.approach(spinSpeed, playing ? 1f : 0f, 2.2f, dt);
        if (cfg.motion()) spin += dt * spinSpeed * cfg.animSpeed * (float) (Math.PI * 2 / 8.0);
        if (spin > Math.PI * 2) spin -= (float) (Math.PI * 2);
        eqAmp = Ease.approach(eqAmp, playing ? 1f : 0f, 4f, dt);
        Visual.update(cfg, playing, Player.snap().key().hashCode());

        displayAccent = cfg.motion() ? Colors.mix(displayAccent, accentTarget, 1f - (float) Math.exp(-5f * dt)) : accentTarget;
    }

    private static float targetShown(SpotifyConfig cfg, boolean force) {
        if (force) return 1f;
        if (!cfg.hudEnabled) return 0f;
        Snapshot s = Player.snap();
        switch (cfg.visibility) {
            case PLAYING:
                return s.active() && (Player.playing() || Player.pausedSeconds() < 4.0) ? 1f : 0f;
            case ON_CHANGE:
                return System.nanoTime() < popupUntil ? 1f : 0f;
            default:
                if (cfg.hidePausedSeconds > 0 && s.active() && !Player.playing()
                        && Player.pausedSeconds() >= cfg.hidePausedSeconds) return 0f;
                return 1f;
        }
    }

    /**
     * Keeps the smooth values (accent colour, equalizer, spin) moving while a screen shows its own player.
     * Call once per frame from such a screen.
     */
    public static void tickState(SpotifyConfig cfg) {
        Anim.frame();
        advance(cfg, 1f, accentTarget(cfg), Player.playing());
    }

    /** The smoothly changing accent colour currently on screen. */
    public static int smoothAccent() {
        return displayAccent;
    }

    /** 0..1: how lively the equalizer bars are (fades up when playing, down when paused). */
    public static float equalizerAmount() {
        return eqAmp;
    }

    /** The accent colour the player would use right now (screens use it so everything matches). */
    public static int accent(SpotifyConfig cfg) {
        return accentTarget(cfg);
    }

    private static int accentTarget(SpotifyConfig cfg) {
        switch (cfg.accent) {
            case CUSTOM:
                return Mood.tint(Colors.opaque(cfg.accentColor), cfg);
            case RAINBOW:
                return Colors.rainbow((System.nanoTime() % 9_000_000_000L) / 9_000_000_000f);
            default:
                Covers.Set set = Covers.current();
                return Mood.tint(set != null ? set.palette.accent() : Colors.opaque(cfg.accentColor), cfg);
        }
    }

    /** Slow 0..1 loop used by the accent line and the progress sweep. */
    public static float phase(SpotifyConfig cfg, double seconds) {
        double period = seconds * 1_000_000_000.0 / Math.max(0.25, cfg.animSpeed);
        return (float) ((System.nanoTime() % (long) period) / period);
    }

    /** Where the animated line is: loops with time, and runs faster with the music. */
    public static float linePhase(SpotifyConfig cfg, boolean rainbow) {
        if (!cfg.motion()) return 0f;
        return Visual.linePhase(rainbow);
    }

    private static Ctx context(GuiGraphics g, Minecraft mc, SpotifyConfig cfg, boolean preview, float alpha, float scale) {
        Ctx c = new Ctx();
        c.g = g;
        c.mc = mc;
        c.font = mc.font;
        c.cfg = cfg;
        c.alpha = alpha;
        c.dev = (float) mc.getWindow().getGuiScale() * scale;
        c.motion = cfg.motion();
        c.hc = cfg.highContrast;
        c.shadow = cfg.textShadow;
        c.now = System.currentTimeMillis();
        c.w = cfg.style.width;
        c.h = cfg.style.height;
        c.since = Player.sinceTrackChange();
        c.accent = displayAccent;
        c.accent2 = Colors.lighten(c.accent, 0.45f);
        Covers.Set cover = Covers.current();
        c.palette = cover != null ? cover.palette : null;
        c.text = c.hc ? Colors.WHITE : Colors.TEXT;
        c.soft = c.hc ? Colors.WHITE : Colors.TEXT_SOFT;
        c.dim = c.hc ? 0xFFE6E6E6 : Colors.TEXT_DIM;

        Snapshot s = Player.snap();
        if (preview && !s.active()) {
            c.title = "Midnight City";
            c.artist = "M83";
            c.album = "Hurry Up, We're Dreaming";
            c.label = "NOW PLAYING";
            c.playing = true;
            c.dur = 243_000;
            c.pos = (System.currentTimeMillis() / 3) % c.dur;
            c.seed = 7;
        } else if (s.active()) {
            c.title = s.title().isBlank() ? "Unknown title" : s.title();
            c.artist = s.artist();
            c.album = s.album();
            c.ad = s.isAd();
            c.playing = Player.playing();
            c.dur = s.durMs();
            c.pos = Player.position();
            c.label = c.ad ? "ADVERTISEMENT" : (c.playing ? "NOW PLAYING" : "PAUSED");
            c.seed = s.key().hashCode();
            if (cfg.showSourceName && !s.appName().isEmpty()) c.label += " · " + s.appName().toUpperCase(java.util.Locale.ROOT);
        } else {
            c.idle = true;
            c.label = "STANDBY";
            String[] idle = idleText(cfg);
            c.title = idle[0];
            c.artist = idle[1];
        }
        c.progress = c.dur > 0 ? Ease.clamp01(c.pos / (float) c.dur) : 0f;
        if (cfg.lyrics.cardMode != LyricsConfig.CardMode.OFF) {
            Lyrics.need();
            c.ly = CardLyrics.now(cfg.lyrics, preview, c.motion, c.now);
        }
        return c;
    }

    /** What to show when no song is available: {headline, second line}. */
    public static String[] idleText(SpotifyConfig cfg) {
        if (com.zlorbie788.spotify.local.Sources.local()) {
            String err = com.zlorbie788.spotify.local.LocalPlayer.error();
            return new String[]{"Nothing playing", err.isEmpty() ? "Open My music and pick a song" : err};
        }
        if (com.zlorbie788.spotify.local.Sources.dropHere()) {
            return new String[]{"No music yet", "Drop songs in your music folder"};
        }
        switch (MediaBridge.state()) {
            case UNSUPPORTED:
                return new String[]{"Needs Windows", "It reads the Windows media session"};
            case FAILED:
                return new String[]{"Helper stopped", MediaBridge.problem()};
            case STARTING:
                return new String[]{"Connecting", ""};
            case IDLE:
                return cfg.startBridge ? new String[]{"Connecting", ""}
                        : new String[]{"Helper is off", "Press Reconnect in the settings"};
            default:
                return new String[]{"Nothing playing", cfg.showHelp
                        ? com.zlorbie788.spotify.media.Diagnosis.nextStep(cfg)
                        : cfg.source == SpotifyConfig.Source.SPOTIFY
                        ? "Open Spotify and press play" : "Start any media player"};
        }
    }

    // ------------------------------------------------------------ entry points

    /** HudElementRegistry callback: the in-game player. */
    public static void render(GuiGraphics g, DeltaTracker tracker) {
        Minecraft mc = Minecraft.getInstance();
        SpotifyConfig cfg = ConfigIo.get();
        if (mc.player == null || mc.options.hideGui) return;
        if (mc.screen instanceof HidesHud) return;
        lastTarget = targetShown(cfg, false);
        advance(cfg, lastTarget, accentTarget(cfg), Player.playing());
        float fade = fadeStep(cfg, mc.screen != null);
        if (shown <= 0.004f || fade <= 0.004f) return;

        int[] b = HudLayout.bounds(mc, cfg);
        float e = cfg.motion() ? Ease.outCubic(shown) : 1f;
        int dx = 0;
        int dy = 0;
        if (cfg.motion() && e < 1f) {
            float slide = 1f - e;
            if (cfg.ax == SpotifyConfig.ANCHOR_START) dx = -Math.round((b[2] + 10) * slide);
            else if (cfg.ax == SpotifyConfig.ANCHOR_END) dx = Math.round((b[2] + 10) * slide);
            else dy = cfg.ay == SpotifyConfig.ANCHOR_END ? Math.round((b[3] + 10) * slide) : -Math.round((b[3] + 10) * slide);
        }
        drawAt(g, mc, cfg, b[0] + dx, b[1] + dy, cfg.scale, false, cfg.opacity * e * fade);
    }

    /**
     * The auto-fade: after {@code fadeAfterSeconds} without anything happening the player eases down to
     * {@code fadedOpacity}; a song change, a button, pausing or opening any screen brings it back.
     */
    private static float fadeStep(SpotifyConfig cfg, boolean screenOpen) {
        if (cfg.fadeAfterSeconds <= 0) {
            fadeLevel = 1f;
            return 1f;
        }
        if (screenOpen) Player.poke();
        float target = Player.idleSeconds() >= cfg.fadeAfterSeconds ? cfg.fadedOpacity : 1f;
        long nanos = System.nanoTime();
        float dt = Math.min(0.1f, (nanos - fadeNanos) / 1_000_000_000f);
        fadeNanos = nanos;
        fadeLevel = cfg.motion() ? Ease.approach(fadeLevel, target, 2.4f, dt) : target;
        return fadeLevel;
    }

    /** True when the visibility rules keep the in-game player hidden right now (also when the HUD is switched off). */
    public static boolean hiddenNow() {
        SpotifyConfig cfg = ConfigIo.get();
        return !cfg.hudEnabled || lastTarget < 0.5f || fadeLevel < 0.2f;
    }

    /**
     * Draws the player at a screen position, ignoring the visibility rules (settings preview, layout editor).
     * When nothing is playing a sample song is shown so every setting can be seen at work.
     */
    public static void preview(GuiGraphics g, Minecraft mc, SpotifyConfig cfg, int x, int y, float scale, float alpha) {
        advance(cfg, targetShown(cfg, true), accentTarget(cfg), !Player.snap().active() || Player.playing());
        drawAt(g, mc, cfg, x, y, scale, true, alpha);
    }

    private static void drawAt(GuiGraphics g, Minecraft mc, SpotifyConfig cfg, int x, int y, float scale,
                               boolean preview, float alpha) {
        Ctx c = context(g, mc, cfg, preview, alpha, scale);
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate((float) x, (float) y);
        pose.scale(scale, scale);
        float oldDev = Gfx.setDev(c.dev);
        UiFont oldFont = Gfx.font();
        int oldShift = Gfx.fontShift();
        Gfx.useFont(cfg.font, cfg.fontShift);
        try {
            switch (cfg.style) {
                case COMPACT -> drawCompact(c);
                case VINYL -> drawVinyl(c);
                case MINIMAL -> drawMinimal(c);
                case TALL -> drawTall(c);
                case SLEEK -> drawSleek(c);
                default -> drawCard(c);
            }
        } finally {
            Gfx.useFont(oldFont, oldShift);
            Gfx.setDev(oldDev);
            pose.popMatrix();
        }
    }

    // ------------------------------------------------------------ shared pieces

    /** Corner size of the card in GUI units (never more than half the card). */
    private static float cardRadius(Ctx c) {
        return Math.min(c.cfg.radius, Math.min(c.w, c.h) / 2f);
    }

    private static void frame(Ctx c) {
        SpotifyConfig cfg = c.cfg;
        int w = c.w;
        int h = c.h;
        float a = c.alpha;
        ShapeKind kind = cfg.shape;
        float r = cardRadius(c);
        boolean none = cfg.background == SpotifyConfig.Background.NONE;
        float breath = 0.7f;
        if (c.motion && cfg.breathingGlow && c.playing) {
            breath = 0.55f + 0.45f * (float) (0.5 + 0.5 * Math.sin(System.nanoTime() / 1.6e9 * Math.PI * 2 * cfg.animSpeed));
        }
        float beat = c.motion && cfg.beatPulse ? Visual.pulse() : 0f;
        if (cfg.showGlow && !c.hc && !none) {
            Shapes.glow(c.g, c.dev, 0, 0, w, h, kind, r, c.accent, Math.min(2.2f, breath + 1.2f * beat) * a);
        }

        if (!none) {
            int top;
            int bottom;
            int edge;
            if (c.hc) {
                top = Colors.alpha(Colors.BLACK, Math.max(0.92f, a));
                bottom = top;
                edge = Colors.alpha(Colors.WHITE, a);
            } else {
                switch (cfg.background) {
                    case GLASS -> {
                        top = Colors.alpha(Colors.CARD, 0.58f * a);
                        bottom = Colors.alpha(Colors.CARD_DARK, 0.58f * a);
                    }
                    case TINTED -> {
                        float[] hsv = Colors.toHsv(c.accent);
                        top = Colors.alpha(Colors.hsv(hsv[0], 0.55f, 0.24f), 0.96f * a);
                        bottom = Colors.alpha(Colors.CARD_DARK, 0.96f * a);
                    }
                    case FADE -> {
                        top = Colors.alpha(Colors.mix(Colors.CARD, c.accent, 0.12f), 0.94f * a);
                        bottom = Colors.alpha(Colors.CARD_DARK, 0.20f * a);
                    }
                    default -> {
                        top = Colors.alpha(Colors.CARD, 0.95f * a);
                        bottom = Colors.alpha(Colors.CARD_DARK, 0.95f * a);
                    }
                }
                edge = Colors.alpha(Colors.mix(Colors.LINE, c.accent, 0.28f), a);
            }
            float bw = c.hc ? Math.max(1.5f, cfg.borderWidth) : cfg.borderWidth;
            Gfx.panel(c.g, 0, 0, w, h, kind, r, top, bottom, edge, bw);
        }

        boolean alive = c.motion && c.playing;
        if (cfg.shine && alive && !c.hc && !none) {
            float t = (float) ((Clock.nanos() / 1e9) % 9.0) / 1.2f;
            Fx.shine(c.g, 0, 0, w, h, c.accent, t, a);
        }
        if (cfg.particles && alive && !c.hc && !none) {
            Fx.specks(c.g, 3, 3, w - 6, h - 6, c.accent, Clock.nanos() / 1e9, 7, a, c.seed);
        }
        if (cfg.borderTrace && alive && !c.hc && !none) {
            float lap = (float) ((Clock.nanos() / 1e9 * 0.085 * cfg.animSpeed) % 1.0);
            Fx.comet(c.g, c.dev, 0, 0, w, h, kind, r, Colors.lighten(c.accent, 0.25f), lap, 30f, 1.2f, 0.9f * a);
        }
        if (cfg.cornerBrackets && !none) Fx.brackets(c.g, 0, 0, w, h, 5, c.accent, 0.9f * a);

        if (cfg.showAccentLine) {
            int inset = kind == ShapeKind.SQUARE ? 0 : Math.max(1, Math.round(r * 0.8f));
            boolean rainbow = cfg.accent == SpotifyConfig.Accent.RAINBOW;
            Gfx.accentLine(c.g, inset, 0, w - 2 * inset, c.hc ? 3 : 2, c.accent, rainbow,
                    linePhase(cfg, rainbow), c.motion, a, beat);
        }
    }

    private static float lineEase(Ctx c, int index) {
        if (!(c.cfg.trackTransition && c.motion)) return 1f;
        return Ease.outCubic((c.since - index * 70L) / 380f);
    }

    /** One text line with the song-change slide-and-fade and (if too long) the scrolling. */
    private static void line(Ctx c, int index, String s, int x, int y, int w, int color, boolean bold) {
        if (s == null || s.isEmpty()) return;
        float t = lineEase(c, index);
        int dx = Math.round((1f - t) * 14f);
        boolean clip = dx > 0;
        if (clip) c.g.enableScissor(x, y - 1, x + w, y + 10);
        Gfx.line(c.g, c.font, s, x + dx, y, w, color, c.alpha * Math.max(0.02f, t), bold, c.shadow,
                c.cfg.marquee && c.motion, c.since);
        if (clip) c.g.disableScissor();
    }

    private static void status(Ctx c, int x, int y, int w) {
        int eqW = c.cfg.showEqualizer && c.cfg.viz != SpotifyConfig.VizStyle.OFF ? EQ_W : 0;
        int room = w - (eqW > 0 ? eqW + 6 : 0);
        if (c.ly != null && c.cfg.lyrics.cardMode == LyricsConfig.CardMode.STATUS) {
            lyric(c, true, x, y, room);
        } else {
            Gfx.text(c.g, c.font, Gfx.fit(c.font, c.label, room), x, y, c.accent, c.alpha, c.shadow);
        }
        if (eqW > 0) {
            Gfx.equalizer(c.g, x + w - eqW, y - 1, eqW, EQ_H, c.accent, eqAmp, c.now, c.seed, c.motion, c.alpha);
        }
    }

    /** One lyric line of the card ({@code current} = the line being sung, otherwise the next one). */
    private static void lyric(Ctx c, boolean current, int x, int y, int w) {
        LyricsConfig lc = c.cfg.lyrics;
        int light = c.palette != null ? c.palette.light() : Colors.lighten(c.accent, 0.55f);
        int hot = LyricsView.tone(lc.currentTone, lc.currentColor, c.accent, light, 0);
        int cool = LyricsView.tone(lc.otherTone, lc.otherColor, c.accent, light, 0);
        if (c.hc) {
            hot = Colors.WHITE;
            cool = 0xFFE6E6E6;
        }
        UiFont old = Gfx.font();
        int oldShift = Gfx.fontShift();
        if (lc.font != null) Gfx.useFont(lc.font, c.cfg.fontShift);
        try {
            CardLyrics.draw(c.g, c.font, lc, c.ly, current, x, y, w, hot, cool, c.alpha, c.shadow);
        } finally {
            Gfx.useFont(old, oldShift);
        }
    }

    /** Does this lyric line need two rows? Measured in the lyrics font. */
    private static boolean lyricWraps(Ctx c, int w) {
        LyricsConfig lc = c.cfg.lyrics;
        UiFont old = Gfx.font();
        int oldShift = Gfx.fontShift();
        if (lc.font != null) Gfx.useFont(lc.font, c.cfg.fontShift);
        try {
            return CardLyrics.wraps(c.font, lc, c.ly, w);
        } finally {
            Gfx.useFont(old, oldShift);
        }
    }

    /**
     * The artist line and the album line, or the lyrics in their place. {@code albumY} is -1 for looks that have no album
     * line; {@code hasStatus} says whether the look has the "NOW PLAYING" line (lyrics can take its place).
     */
    private static void infoLines(Ctx c, int x, int artistY, int albumY, int w, int artistW, boolean hasStatus) {
        LyricsConfig.CardMode mode = c.cfg.lyrics.cardMode;
        if (c.ly != null && mode == LyricsConfig.CardMode.STATUS && !hasStatus) mode = LyricsConfig.CardMode.ALBUM;
        if (c.ly != null && albumY >= 0 && (mode == LyricsConfig.CardMode.ALBUM || mode == LyricsConfig.CardMode.TWO)
                && lyricWraps(c, w)) {
            // a long line: it takes the artist row and the album row (two rows inside the same small card)
            LyricsConfig lc = c.cfg.lyrics;
            int light = c.palette != null ? c.palette.light() : Colors.lighten(c.accent, 0.55f);
            int hot = c.hc ? Colors.WHITE : LyricsView.tone(lc.currentTone, lc.currentColor, c.accent, light, 0);
            int cool = c.hc ? 0xFFE6E6E6 : LyricsView.tone(lc.otherTone, lc.otherColor, c.accent, light, 0);
            UiFont old = Gfx.font();
            int oldShift = Gfx.fontShift();
            if (lc.font != null) Gfx.useFont(lc.font, c.cfg.fontShift);
            try {
                CardLyrics.drawWrapped(c.g, c.font, lc, c.ly, x, artistY, albumY, w, hot, cool, c.alpha, c.shadow);
            } finally {
                Gfx.useFont(old, oldShift);
            }
            return;
        }
        if (c.ly != null && mode == LyricsConfig.CardMode.TWO && albumY >= 0) {
            lyric(c, true, x, artistY, w);
            lyric(c, false, x, albumY, w);
            return;
        }
        if (c.ly != null && (mode == LyricsConfig.CardMode.ALBUM || mode == LyricsConfig.CardMode.TWO)) {
            if (albumY >= 0) {
                line(c, 1, c.artist, x, artistY, artistW, c.soft, false);
                lyric(c, true, x, albumY, w);
            } else {
                lyric(c, true, x, artistY, artistW);
            }
            return;
        }
        line(c, 1, c.artist, x, artistY, artistW, c.soft, false);
        if (albumY >= 0 && c.cfg.showAlbum) line(c, 2, c.album, x, albumY, w, c.dim, false);
    }

    private static void bottom(Ctx c, int x, int barY, int w) {
        SpotifyConfig cfg = c.cfg;
        if (cfg.showProgress) {
            float sweep = c.motion && c.playing ? phase(cfg, 2.4) : 0f;
            Gfx.progress(c.g, x, barY, w, 4, c.progress, c.accent, c.accent2, c.motion && c.playing, sweep, false, false, c.alpha);
        }
        if (cfg.showTime) {
            int ty = barY + (cfg.showProgress ? 7 : 0);
            Gfx.text(c.g, c.font, Ease.clock(c.pos), x, ty, c.dim, c.alpha, c.shadow);
            String total = c.dur > 0 ? Ease.clock(c.dur) : "--:--";
            Gfx.textRight(c.g, c.font, total, x + w, ty, c.dim, c.alpha, c.shadow);
        }
    }

    // ------------------------------------------------------------ cover

    /**
     * Corner size (GUI units) for a cover of this size. In the automatic setting it follows the card: concentric with the
     * card's own corner ({@code concentric} = card radius minus the gap to the edge), so the picture never pokes out of the card
     * or leaves a gap in the corner.
     */
    public static float coverRadius(SpotifyConfig cfg, int size, float concentric) {
        if (cfg.roundCover) return size / 2f;
        switch (cfg.coverShape) {
            case SQUARE:
                return 0f;
            case SOFT:
                return size * 0.14f;
            case ROUND:
                return size * 0.28f;
            case CIRCLE:
                return size / 2f;
            default:
                if (cfg.shape == ShapeKind.SQUARE) return 0f;
                return Math.min(size * 0.3f, Math.max(Math.max(0f, concentric), size * 0.10f));
        }
    }

    /** Which corners of a cover that sits at the card's corners are cut (bit 0 top-left, 1 top-right, 2 bottom-right, 3 bottom-left). */
    private static int coverMask(SpotifyConfig cfg, int mask) {
        if (cfg.shape == ShapeKind.TECH) return mask & 5;
        return mask;
    }

    /** Draws the cover (or a coloured placeholder) in a size x size box at (x, y), with the card's corner shape. */
    private static void cover(Ctx c, int x, int y, int size, float concentric, int mask, boolean flush) {
        float r = coverRadius(c.cfg, size, concentric);
        cover(c.g, c.mc, c.cfg, x, y, size, r, mask, flush, c.alpha, c.dev, c.accent, c.ad || c.idle, c.since, true);
    }

    /** Cover drawing for screens that have no HUD context (the interactive player). */
    public static void cover(GuiGraphics g, Minecraft mc, SpotifyConfig cfg, int x, int y, int size, float radius,
                             int mask, float alpha, float uiScale, boolean spinAllowed) {
        Snapshot s = Player.snap();
        cover(g, mc, cfg, x, y, size, radius, mask, false, alpha, (float) mc.getWindow().getGuiScale() * uiScale, displayAccent,
                s.active() && s.isAd() || !s.active(), Player.sinceTrackChange(), spinAllowed);
    }

    /** Convenience for the screens: cover with the radius the settings ask for (the panel's own corner as the guide). */
    public static void cover(GuiGraphics g, Minecraft mc, SpotifyConfig cfg, int x, int y, int size, boolean round,
                             float alpha, float uiScale, boolean spinAllowed) {
        float r = round ? size / 2f : coverRadius(cfg, size, cfg.panelRadius - 12f);
        cover(g, mc, cfg, x, y, size, r, 15, alpha, uiScale, spinAllowed);
    }

    /**
     * How much the cover is scaled right now: the little pop when the song changes and the bump on every beat.
     * Everything that is drawn around the cover (shadow, glow, outline) has to move with the cover, or it would no
     * longer match: use {@link #beginCoverSpace} / {@link #endCoverSpace} for that. A cover that touches the card's edges
     * never grows (it would leave the card), it only ever shrinks and comes back.
     */
    public static float coverScale(SpotifyConfig cfg, long sinceChange, boolean mayGrow) {
        boolean motion = cfg.motion();
        float pop = 1f;
        if (motion && cfg.trackTransition) pop = Ease.lerp(0.86f, 1f, Ease.outBack(sinceChange / 520f));
        if (!mayGrow) pop = Math.min(1f, pop);
        if (motion && cfg.beatPulse && mayGrow) pop *= 1f + 0.055f * Visual.pulse();
        return pop;
    }

    public static float coverScale(SpotifyConfig cfg, long sinceChange) {
        return coverScale(cfg, sinceChange, true);
    }

    /**
     * Moves the drawing space so that (0, 0) is the cover's top-left corner and one unit is one cover pixel, scaled the
     * same way as the cover itself. Draw the frame / glow / outline at (0, 0, size, size) and call {@link #endCoverSpace}.
     */
    public static void beginCoverSpace(GuiGraphics g, SpotifyConfig cfg, int x, int y, int size) {
        float pop = coverScale(cfg, Player.sinceTrackChange());
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(x + size / 2f, y + size / 2f);
        if (pop != 1f) pose.scale(pop, pop);
        pose.translate(-size / 2f, -size / 2f);
    }

    public static void endCoverSpace(GuiGraphics g) {
        g.pose().popMatrix();
    }

    private static void cover(GuiGraphics g, Minecraft mc, SpotifyConfig cfg, int x, int y, int size, float radius, int mask,
                              boolean flush, float alpha, float dev, int accent, boolean forcePlaceholder, long since,
                              boolean spinAllowed) {
        boolean motion = cfg.motion();
        float pop = coverScale(cfg, since, !flush);
        boolean circle = radius >= size / 2f - 0.5f;
        boolean rotate = circle && cfg.spinCover && motion && spinAllowed;
        ShapeKind kind = cfg.shape == ShapeKind.SQUARE ? ShapeKind.SQUARE : cfg.shape;
        if (circle || cfg.coverShape != SpotifyConfig.CoverShape.AUTO) {
            if (kind != ShapeKind.SQUARE) kind = ShapeKind.ROUND;
        }
        if (radius <= 0.01f) kind = ShapeKind.SQUARE;
        boolean chamfer = kind == ShapeKind.CHAMFER || kind == ShapeKind.TECH;
        int m = circle ? 15 : coverMask(cfg, mask);

        Covers.Set cur = forcePlaceholder ? null : Covers.current();

        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(x + size / 2f, y + size / 2f);
        if (rotate) pose.rotate(spin);
        if (pop != 1f) pose.scale(pop, pop);
        pose.translate(-size / 2f, -size / 2f);

        // The soft shadow lives inside the cover's own transform, so it grows and shrinks together with the picture.
        if (!flush && !cfg.highContrast && cfg.coverShape != SpotifyConfig.CoverShape.CIRCLE && !cfg.roundCover) {
            Shapes.shadow(g, dev, 0, 0, size, size, kind == ShapeKind.SQUARE ? ShapeKind.SQUARE : kind, radius, 0.7f * alpha);
        }
        if (cur == null) {
            placeholder(g, dev, size, radius, kind, m, accent, alpha);
        } else {
            Covers.Set prev = Covers.previous();
            float fade = Covers.fade();
            if (prev != null && fade < 1f) blit(g, prev, size, radius, chamfer, m, cfg, dev * pop, alpha);
            blit(g, cur, size, radius, chamfer, m, cfg, dev * pop, alpha * (prev != null ? Ease.clamp01(fade) : 1f));
        }
        pose.popMatrix();
    }

    private static void blit(GuiGraphics g, Covers.Set set, int size, float radius, boolean chamfer, int mask,
                             SpotifyConfig cfg, float dev, float alpha) {
        float px = size * dev;
        Identifier id = set.texture(px, radius / size, cfg.coverZoom, chamfer, mask);
        if (id == null) return;
        int tex = set.textureSize(px);
        int a = Math.max(4, Math.min(255, Math.round(alpha * 255f)));
        g.blit(RenderPipelines.GUI_TEXTURED, id, 0, 0, 0f, 0f, size, size, tex, tex, tex, tex, (a << 24) | 0x00FFFFFF);
    }

    private static void placeholder(GuiGraphics g, float dev, int size, float radius, ShapeKind kind, int mask, int accent,
                                    float alpha) {
        int top = Colors.alpha(Colors.mix(accent, Colors.CARD_DARK, 0.55f), alpha);
        int bottom = Colors.alpha(Colors.mix(accent, Colors.CARD_DARK, 0.85f), alpha);
        Shapes.fill(g, dev, 0, 0, size, size, kind, radius, top, bottom);
        int scale = Math.max(1, size / 24);
        int iw = Icons.width(Icons.NOTE) * scale;
        int ih = Icons.height(Icons.NOTE) * scale;
        Icons.draw(g, Icons.NOTE, (size - iw) / 2, (size - ih) / 2, scale, Colors.alpha(Colors.lighten(accent, 0.3f), 0.85f * alpha));
    }

    // ------------------------------------------------------------ layouts

    private static final int ALL = 15;
    /** Top-left and bottom-left: a cover that touches the left edge of the card. */
    private static final int LEFT = 1 | 8;

    private static void drawCard(Ctx c) {
        SpotifyConfig cfg = c.cfg;
        frame(c);
        int pad = 9;
        int tx = cfg.showCover ? 77 : 10;
        int tw = c.w - tx - 10;
        if (cfg.showCover) cover(c, pad, pad, 60, cardRadius(c) - pad, ALL, false);
        status(c, tx, 9, tw);
        line(c, 0, c.title, tx, 21, tw, c.text, true);
        infoLines(c, tx, 33, 44, tw, tw, true);
        bottom(c, tx, 58, tw);
    }

    private static void drawCompact(Ctx c) {
        SpotifyConfig cfg = c.cfg;
        frame(c);
        int tx = cfg.showCover ? 35 : 9;
        int right = c.w - 8;
        int eqW = cfg.showEqualizer && cfg.viz != SpotifyConfig.VizStyle.OFF ? EQ_W : 0;
        if (cfg.showCover) cover(c, 6, 5, 24, cardRadius(c) - 5, ALL, false);
        int tw = right - tx - (eqW > 0 ? eqW + 6 : 0);
        line(c, 0, c.title, tx, 6, tw, c.text, true);
        if (eqW > 0) {
            Gfx.equalizer(c.g, right - eqW, 5, eqW, EQ_H, c.accent, eqAmp, c.now, c.seed, c.motion, c.alpha);
        }
        int timeW = 0;
        String time = null;
        if (cfg.showTime) {
            time = Ease.clock(c.pos) + " / " + (c.dur > 0 ? Ease.clock(c.dur) : "--:--");
            timeW = Gfx.width(c.font, time) + 6;
            Gfx.textRight(c.g, c.font, time, right, 17, c.dim, c.alpha, c.shadow);
        }
        infoLines(c, tx, 17, -1, right - tx - timeW, right - tx - timeW, false);
        if (cfg.showProgress) {
            float sweep = c.motion && c.playing ? phase(cfg, 2.4) : 0f;
            Gfx.progress(c.g, tx, 28, right - tx, 2, c.progress, c.accent, c.accent2, c.motion && c.playing, sweep, false, false, c.alpha);
        }
    }

    /** Big cover that reaches the edges, a roomy text block and a thin seek line. */
    private static void drawSleek(Ctx c) {
        SpotifyConfig cfg = c.cfg;
        frame(c);
        int inset = Math.max(1, (int) Math.ceil(cfg.borderWidth));
        int tx = cfg.showCover ? c.h - 2 * inset + inset + 9 : 12;
        int tw = c.w - tx - 12;
        if (cfg.showCover) cover(c, inset, inset, c.h - 2 * inset, cardRadius(c) - inset, LEFT, true);
        status(c, tx, 9, tw);
        line(c, 0, c.title, tx, 22, tw, c.text, true);
        infoLines(c, tx, 34, 45, tw, tw, true);
        if (cfg.showProgress) {
            float sweep = c.motion && c.playing ? phase(cfg, 2.4) : 0f;
            Gfx.progress(c.g, tx, c.h - 18, tw, 3, c.progress, c.accent, c.accent2, c.motion && c.playing, sweep, false, false, c.alpha);
        }
        if (cfg.showTime) {
            int ty = c.h - 12;
            Gfx.text(c.g, c.font, Ease.clock(c.pos), tx, ty, c.dim, c.alpha, c.shadow);
            Gfx.textRight(c.g, c.font, c.dur > 0 ? Ease.clock(c.dur) : "--:--", tx + tw, ty, c.dim, c.alpha, c.shadow);
        }
    }

    private static void drawVinyl(Ctx c) {
        SpotifyConfig cfg = c.cfg;
        frame(c);
        int cx = 39;
        int cy = 39;
        int r = 33;
        float a = c.alpha;
        // Record: dark disc, a few fine grooves, the cover as its spinning label, a centre hole.
        if (cfg.showGlow && !c.hc) Gfx.disc(c.g, cx, cy, r + 2, Colors.alpha(c.accent, 0.16f * a));
        Gfx.disc(c.g, cx, cy, r, Colors.alpha(0xFF08090E, a));
        for (int ring = 29; ring >= 23; ring -= 3) {
            Gfx.disc(c.g, cx, cy, ring, Colors.alpha(0xFF171A24, a));
            Gfx.disc(c.g, cx, cy, ring - 1, Colors.alpha(0xFF08090E, a));
        }
        Gfx.disc(c.g, cx, cy, 20, Colors.alpha(c.accent, a));
        Gfx.disc(c.g, cx, cy, 19, Colors.alpha(0xFF08090E, a));
        if (cfg.showCover) {
            cover(c.g, c.mc, cfg, cx - 18, cy - 18, 36, 18f, 15, true, a, c.dev, c.accent, c.ad || c.idle, c.since, true);
        } else {
            Gfx.disc(c.g, cx, cy, 18, Colors.alpha(Colors.mix(c.accent, Colors.CARD_DARK, 0.6f), a));
        }
        Gfx.disc(c.g, cx, cy, 3, Colors.alpha(0xFF05060A, a));
        Gfx.disc(c.g, cx, cy, 1, Colors.alpha(0xFF2A2F3F, a));

        int tx = 82;
        int tw = c.w - tx - 10;
        status(c, tx, 9, tw);
        line(c, 0, c.title, tx, 21, tw, c.text, true);
        infoLines(c, tx, 33, 44, tw, tw, true);
        bottom(c, tx, 58, tw);
    }

    private static void drawMinimal(Ctx c) {
        SpotifyConfig cfg = c.cfg;
        frame(c);
        int tx = cfg.showCover ? 33 : 6;
        int right = c.w - 6;
        if (cfg.showCover) cover(c, 5, 4, 22, cardRadius(c) - 4, ALL, false);
        int eqW = cfg.showEqualizer && cfg.viz != SpotifyConfig.VizStyle.OFF ? EQ_W : 0;
        int tw = right - tx - (eqW > 0 ? eqW + 6 : 0);
        line(c, 0, c.title, tx, 4, tw, c.text, true);
        if (eqW > 0) {
            Gfx.equalizer(c.g, right - eqW, 3, eqW, EQ_H, c.accent, eqAmp, c.now, c.seed, c.motion, c.alpha);
        }
        int timeW = 0;
        if (cfg.showTime) {
            String time = Ease.clock(c.pos);
            timeW = Gfx.width(c.font, time) + 6;
            Gfx.textRight(c.g, c.font, time, right, 15, c.dim, c.alpha, c.shadow);
        }
        infoLines(c, tx, 15, -1, right - tx - timeW, right - tx - timeW, false);
        if (cfg.showProgress) {
            float sweep = c.motion && c.playing ? phase(cfg, 2.4) : 0f;
            Gfx.progress(c.g, tx, 26, right - tx, 2, c.progress, c.accent, c.accent2, c.motion && c.playing, sweep, false, false, c.alpha);
        }
    }

    private static void drawTall(Ctx c) {
        SpotifyConfig cfg = c.cfg;
        frame(c);
        int tx = 8;
        int tw = c.w - 16;
        cover(c, 8, 8, 108, cardRadius(c) - 8, ALL, false);
        if (cfg.showEqualizer && cfg.viz != SpotifyConfig.VizStyle.OFF) {
            // a dark strip along the bottom of the cover with the beat display on it, readable on any picture
            c.g.fillGradient(8, 8 + 108 - 26, 8 + 108, 8 + 108, Colors.alpha(0xFF000000, 0.0f), Colors.alpha(0xFF000000, 0.62f * c.alpha));
            Gfx.spectrum(c.g, 12, 8 + 108 - 22, 108 - 8, 18, c.accent, c.motion, c.alpha);
        }
        line(c, 0, c.title, tx, 122, tw, c.text, true);
        infoLines(c, tx, 134, 145, tw, tw, false);
        bottom(c, tx, 158, tw);
    }
}
