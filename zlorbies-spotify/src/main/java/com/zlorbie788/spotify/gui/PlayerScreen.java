package com.zlorbie788.spotify.gui;

import com.zlorbie788.spotify.client.Keybinds;
import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.config.SpotifyConfig;
import com.zlorbie788.spotify.config.UiFont;
import com.zlorbie788.spotify.gui.widgets.Chrome;
import com.zlorbie788.spotify.gui.widgets.ConfigSlider;
import com.zlorbie788.spotify.gui.widgets.IconButton;
import com.zlorbie788.spotify.gui.widgets.StyledButton;
import com.zlorbie788.spotify.hud.Anim;
import com.zlorbie788.spotify.hud.Gfx;
import com.zlorbie788.spotify.hud.HelpCard;
import com.zlorbie788.spotify.hud.Icons;
import com.zlorbie788.spotify.hud.Intro;
import com.zlorbie788.spotify.hud.PanelDraw;
import com.zlorbie788.spotify.hud.PlayerHud;
import com.zlorbie788.spotify.hud.Theme;
import com.zlorbie788.spotify.local.Folder;
import com.zlorbie788.spotify.local.Sources;
import com.zlorbie788.spotify.media.Covers;
import com.zlorbie788.spotify.media.MediaBridge;
import com.zlorbie788.spotify.media.Player;
import com.zlorbie788.spotify.media.Snapshot;
import com.zlorbie788.spotify.util.Colors;
import com.zlorbie788.spotify.util.Ease;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * The full player: big cover, song details, a seek bar you can click or drag, play / pause / skip, shuffle,
 * repeat and volume. Its size, shape, corners, border and background come from the Panel tab of the settings. The lyrics can
 * sit right inside it: beside the player when the window is wide enough, or under the song details when it is tall enough
 * (the Lyrics button, or L; the Player tab of the settings says whether it opens with them). Keyboard: Space play/pause,
 * Left/Right seek, Up/Down volume, M mute, S shuffle, R repeat, N next, B previous, L lyrics, Tab moves between the
 * buttons, Esc closes. The mouse wheel changes the volume (or seeks, when it is over the seek bar).
 */
public class PlayerScreen extends Screen implements PlayerHud.HidesHud {
    private static final int PAD = 12;
    private static final int BAR_H = 6;
    /** Widest the lyrics beside the player get, and the narrowest that still reads well. */
    private static final int SIDE_MAX = 280;
    private static final int SIDE_MIN = 220;
    /** The player is at least this tall when the lyrics sit under the song details. */
    private static final int STACK_MAX = 262;
    private static final int STACK_MIN_SCREEN = 226;

    private enum Lyr { NONE, SIDE, STACK }

    private final LyricsPanel lyrics = new LyricsPanel();
    private Lyr lyr = Lyr.NONE;
    private boolean canEmbed;
    private boolean introChecked;
    private int px;
    private int py;
    private int pw;
    private int ph;
    private int barY;
    private int ctlY;
    private int coverSize;
    private int lyrX;
    private int lyrY;
    private int lyrW;
    private int lyrH;

    private boolean seeking;
    private float seekFrac;
    private ConfigSlider volume;
    private StyledButton reconnect;
    private StyledButton openFolder;
    private IconButton shuffle;
    private IconButton prev;
    private IconButton play;
    private IconButton next;
    private IconButton repeat;
    private IconButton mute;

    public PlayerScreen() {
        super(Component.literal("Spotify Integration Mod"));
    }

    /** The full player in the look chosen in the settings (Sleek or Classic). */
    public static Screen create() {
        PlayerLyrics.begin();
        return ConfigIo.get().playerLook == SpotifyConfig.Look.SLEEK ? new SleekScreen(false) : new PlayerScreen();
    }

    /** The same screen with the lyrics showing (for this visit). */
    public static Screen createWithLyrics() {
        PlayerLyrics.begin();
        PlayerLyrics.force();
        return ConfigIo.get().playerLook == SpotifyConfig.Look.SLEEK ? new SleekScreen(false) : new PlayerScreen();
    }

    // ------------------------------------------------------------ layout

    @Override
    protected void init() {
        SpotifyConfig cfg = ConfigIo.get();
        if (!introChecked) {
            introChecked = true;
            Intro.maybeStart(cfg);
        }
        pw = Math.min(cfg.panelW, width - 8);
        int accent = PlayerHud.accent(cfg);

        // where the lyrics go: beside the player if the window is wide enough, else under the song details if it is tall enough
        boolean wide = width >= pw + 8 + SIDE_MIN + 8;
        boolean tall = height >= STACK_MIN_SCREEN;
        canEmbed = wide || tall;
        lyr = Lyr.NONE;
        if (PlayerLyrics.open() && canEmbed) lyr = wide ? Lyr.SIDE : Lyr.STACK;
        ph = Math.min(cfg.panelH, height - 4);
        if (lyr == Lyr.STACK) ph = Math.min(Math.max(cfg.panelH, STACK_MAX), height - 8);
        barY = ph - 64;
        ctlY = ph - 40;
        coverSize = Math.max(40, Math.min(ph - 102, pw / 2 - 20));
        if (lyr == Lyr.SIDE) {
            lyrW = Math.min(SIDE_MAX, width - 16 - pw - 8);
            px = (width - (pw + 8 + lyrW)) / 2;
            py = Math.max(2, (height - ph) / 2);
            lyrX = px + pw + 8;
            lyrY = py;
            lyrH = ph;
        } else if (lyr == Lyr.STACK) {
            coverSize = 40;
            px = (width - pw) / 2;
            py = Math.max(2, (height - ph) / 2);
            lyrX = px + PAD;
            lyrY = py + 74;
            lyrW = pw - 2 * PAD;
            lyrH = barY - 74 - 8;
        } else {
            px = (width - pw) / 2;
            py = Math.max(2, (height - ph) / 2);
        }
        if (lyr == Lyr.SIDE) lyrics.layout(lyrX + 8, lyrY + 22, lyrW - 16, lyrH - 30);
        else if (lyr == Lyr.STACK) lyrics.layout(lyrX, lyrY, lyrW, lyrH);

        addRenderableWidget(new StyledButton(px + pw - 28, py + 6, 16, 14, Component.literal("x"), b -> onClose())
                .accent(Colors.RED).tip("Close (Esc)"));
        addRenderableWidget(new IconButton(px + pw - 50, py + 6, 18, 14, "Settings", () -> Icons.GEAR,
                () -> minecraft.setScreen(new SettingsScreen(this))).accent(accent).tip("Customize the player"));

        // the header buttons are as wide as their words need in the chosen font; when the label "NOW PLAYING" would not
        // fit next to them, they switch to shorter words, so nothing ever overlaps
        String[][] tiers = {{"My music", "Search", "Lyrics"}, {"Music", "Search", "Lyrics"}, {"Music", "Find", "Lyrics"}};
        int need = headerTextWidth(cfg.font, "NOW PLAYING") + 6;
        int rightEdge = px + pw - 54;
        int[] xs = new int[3];
        int[] ws = new int[3];
        for (int tier = 0; tier < tiers.length; tier++) {
            int pad = tier == tiers.length - 1 ? 8 : 12;
            int x1 = rightEdge;
            for (int i = 2; i >= 0; i--) {
                ws[i] = Math.max(32, headerTextWidth(cfg.uiFont, tiers[tier][i]) + pad);
                x1 -= ws[i];
                xs[i] = x1;
                x1 -= 4;
            }
            headTier = tier;
            headLeft = xs[0];
            if (headLeft - (px + PAD) - 6 >= need) break;
        }
        String[] names = tiers[headTier];
        StyledButton musicButton = new StyledButton(xs[0], py + 6, ws[0], 14, Component.literal(names[0]),
                b -> minecraft.setScreen(LibraryScreen.open(this))).accent(accent)
                .tip("The songs in your own music folder: play them without Spotify");
        if (Sources.local()) musicButton.primary();
        addRenderableWidget(musicButton);
        addRenderableWidget(new StyledButton(xs[1], py + 6, ws[1], 14, Component.literal(names[1]),
                b -> minecraft.setScreen(new SearchScreen(this))).accent(accent).tip("Find a song and play it in Spotify"));
        StyledButton lyricsButton = new StyledButton(xs[2], py + 6, ws[2], 14, Component.literal(names[2]),
                b -> toggleLyrics()).accent(accent).tip("Show or hide the lyrics (L)");
        if (lyr != Lyr.NONE) lyricsButton.primary();
        addRenderableWidget(lyricsButton);

        int cy = py + ctlY;
        int x = px + PAD;
        shuffle = addRenderableWidget(new IconButton(x, cy, 22, 18, "Shuffle", () -> Icons.SHUFFLE, Player::toggleShuffle)
                .accent(accent).lit(Player::shuffle).tip("Shuffle (S)"));
        x += 26;
        prev = addRenderableWidget(new IconButton(x, cy, 22, 18, "Previous song", () -> Icons.PREV, Player::previous)
                .accent(accent).tip("Previous song (B)"));
        x += 26;
        play = addRenderableWidget(new IconButton(x, cy, 30, 18, "Play or pause",
                () -> Player.playing() ? Icons.PAUSE : Icons.PLAY, Player::toggle)
                .accent(accent).solid().tip("Play / pause (Space)"));
        x += 34;
        next = addRenderableWidget(new IconButton(x, cy, 22, 18, "Next song", () -> Icons.NEXT, Player::next)
                .accent(accent).tip("Next song (N)"));
        x += 26;
        repeat = addRenderableWidget(new IconButton(x, cy, 22, 18, "Repeat", () -> Icons.REPEAT, Player::cycleRepeat)
                .accent(accent).lit(() -> Player.repeat() != 0)
                .badge(() -> Player.repeat() == 1 ? "1" : "").tip("Repeat: off / all / this song (R)"));

        int sliderW = Math.max(56, Math.min(112, pw - 2 * PAD - 26 * 4 - 22 - 34 - 12));
        int sx = px + pw - PAD - sliderW;
        mute = addRenderableWidget(new IconButton(sx - 26, cy, 22, 18, "Mute",
                () -> Player.muted() || Player.volume() == 0 ? Icons.MUTE : Icons.VOLUME, Player::toggleMute)
                .accent(accent).lit(Player::muted).tip("Mute (M)"));
        volume = addRenderableWidget(new ConfigSlider(sx, cy, sliderW, 18, "Volume ", 0, 100, 1,
                Math.max(0, Player.volume()), v -> (int) v + "%", v -> Player.setVolume((int) v))
                .accent(accent).tip("Volume of the player (Up / Down, or the mouse wheel)"));

        int tx0 = px + PAD + coverSize + 12;
        int rcX = lyr == Lyr.STACK ? px + (pw - 92) / 2 : tx0;
        int rcY = lyr == Lyr.STACK ? lyrY + lyrH / 2 + 12 : py + 88;
        reconnect = addRenderableWidget(new StyledButton(rcX, rcY, 92, 16, Component.literal("Reconnect"),
                b -> MediaBridge.restart()).accent(accent).tip("Restart the Windows helper that reads the player"));
        openFolder = addRenderableWidget(new StyledButton(rcX, rcY, 112, 16, Component.literal("Open music folder"),
                b -> Folder.openMusic()).accent(accent).primary()
                .tip("Opens the folder where your songs go (MP3, OGG, WAV, FLAC)"));
        openFolder.visible = false;
    }

    /** Lyrics on or off: in place when they fit, otherwise in the Sleek player (which has more room for them). */
    private void toggleLyrics() {
        if (canEmbed) {
            PlayerLyrics.toggle();
            rebuildWidgets();
        } else {
            Intro.suppressNext();
            minecraft.setScreen(new SleekScreen(true));
        }
    }

    private int barX() {
        return px + PAD;
    }

    private int barW() {
        return pw - 2 * PAD;
    }

    private boolean overBar(double mx, double my) {
        return mx >= barX() - 3 && mx < barX() + barW() + 3 && my >= py + barY - 6 && my < py + barY + BAR_H + 6;
    }

    private boolean overCover(double mx, double my) {
        return mx >= px + PAD && mx < px + PAD + coverSize && my >= py + 28 && my < py + 28 + coverSize;
    }

    private float fracAt(double mx) {
        return Ease.clamp01((float) ((mx - barX()) / barW()));
    }

    // ------------------------------------------------------------ drawing

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (ConfigIo.get().panelDim) g.fill(0, 0, width, height, 0x99050810);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        SpotifyConfig cfg = ConfigIo.get();
        Snapshot s = Player.snap();
        PlayerHud.tickState(cfg);
        Gfx.useFont(cfg.font, cfg.fontShift);
        try {
            draw(g, cfg, s, mouseX, mouseY, partialTick);
        } finally {
            Gfx.useFont(UiFont.MINECRAFT, 0);
        }
    }

    private void draw(GuiGraphics g, SpotifyConfig cfg, Snapshot s, int mouseX, int mouseY, float partialTick) {
        int accent = PlayerHud.smoothAccent();
        int accent2 = Colors.lighten(accent, 0.45f);
        boolean motion = cfg.motion();
        boolean playing = Player.playing();
        Font f = font;

        // Buttons that cannot do anything right now are greyed out and skip keyboard focus.
        boolean live = s.active();
        play.active = live && !s.isAd();
        prev.active = live && s.canPrev() && !s.isAd();
        next.active = live && s.canNext();
        shuffle.active = live && s.canShuffle();
        repeat.active = live && s.canRepeat();
        mute.active = Player.canVolume();
        volume.active = Player.canVolume();
        if (Player.canVolume()) volume.setActual(Player.volume());
        reconnect.visible = !live && !Sources.local() && !Sources.dropHere() && (MediaBridge.state() == MediaBridge.State.FAILED
                || MediaBridge.state() == MediaBridge.State.IDLE
                || (MediaBridge.state() == MediaBridge.State.RUNNING && MediaBridge.silentSeconds() > 8));
        reconnect.active = reconnect.visible;
        openFolder.visible = Sources.dropHere() && lyr != Lyr.SIDE;
        openFolder.active = openFolder.visible;

        boolean intro = Intro.active();
        boolean content = Intro.showContent();
        Intro.veil(g, width, height);

        // the panel slides and scales in when it opens
        float e = intro ? 1f : Anim.smooth(this, "open", 0f, 1f, 9f);
        float sc = 0.95f + 0.05f * e;
        float dy = (1f - e) * 10f;
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(px + pw / 2f, py + ph / 2f + dy);
        pose.scale(sc, sc);
        pose.translate(-(px + pw / 2f), -(py + ph / 2f));

        float par = cfg.parallax && motion ? Ease.clamp((mouseX - width / 2f) / Math.max(1f, width) * 18f, -9f, 9f) : 0f;
        int seed = s.key().hashCode();
        if (Intro.beginPanel(g, px, py, pw, ph)) {
            PanelDraw.draw(g, cfg, px, py, pw, ph, accent, playing, true, par, seed);
            Intro.endPanel(g);
        }
        if (content) {
            if (lyr == Lyr.SIDE) PanelDraw.draw(g, cfg, lyrX, lyrY, lyrW, lyrH, accent, playing, false, 0f, seed);
            if (lyr != Lyr.NONE) {
                int light = Covers.current() != null ? Covers.current().palette.light() : Colors.lighten(accent, 0.55f);
                if (lyr == Lyr.SIDE) Gfx.text(g, f, "LYRICS", lyrX + 8, lyrY + 9, accent, 1f, true);
                lyrics.render(g, f, accent, Colors.opaque(light), playing);
            }
            Gfx.useFont(cfg.uiFont, cfg.fontShift);
            super.render(g, mouseX, mouseY, partialTick);
            Gfx.useFont(cfg.font, cfg.fontShift);
            drawContent(g, cfg, s, accent, accent2, motion, playing, live, mouseX, mouseY, par);
        }

        int coverCx = px + PAD + coverSize / 2;
        int coverCy = py + 28 + coverSize / 2;
        int[] plate = PanelDraw.colors(cfg, accent);
        int plateTop = Colors.opaque(plate[0] == 0 ? Colors.CARD : Colors.mix(Colors.CARD_DARK, plate[0], 0.9f));
        int plateBottom = Colors.opaque(plate[1] == 0 ? Colors.CARD_DARK : plate[1]);
        Intro.overlay(g, f, px, py, pw, ph, cfg.panelShape, PanelDraw.radius(cfg, pw, ph), accent, coverCx, coverCy,
                plateTop, plateBottom);
        pose.popMatrix();
    }

    private int headLeft;
    private int headTier;

    /** Width of a text in the given font as the buttons and labels will draw it. */
    private int headerTextWidth(UiFont fontChoice, String text) {
        UiFont old = Gfx.font();
        int oldShift = Gfx.fontShift();
        Gfx.useFont(fontChoice, ConfigIo.get().fontShift);
        try {
            return Gfx.width(font, text, true);
        } finally {
            Gfx.useFont(old, oldShift);
        }
    }

    private void drawContent(GuiGraphics g, SpotifyConfig cfg, Snapshot s, int accent, int accent2, boolean motion,
                             boolean playing, boolean live, int mouseX, int mouseY, float par) {
        Font f = font;
        long since = Player.sinceTrackChange();
        boolean stacked = lyr == Lyr.STACK;
        int tx = stacked ? px + PAD + coverSize + 10 : px + PAD + coverSize + 12;
        int tw = pw - (tx - px) - PAD;

        // ---- header
        String label;
        if (!live) label = "STANDBY";
        else if (s.isAd()) label = "ADVERTISEMENT";
        else label = playing ? "NOW PLAYING" : "PAUSED";
        int headRoom = headLeft - (px + PAD) - 6;
        if (Gfx.width(f, label, true) > headRoom && label.equals("NOW PLAYING")) label = "PLAYING";
        if (Gfx.width(f, label, true) > headRoom && label.equals("ADVERTISEMENT")) label = "AD";
        boolean labelFits = Gfx.width(f, label, true) <= headRoom;
        if (labelFits) Gfx.text(g, f, label, px + PAD, py + 9, accent, 1f, true);
        String app = live ? s.appName() : "";
        if (labelFits && !app.isEmpty()) {
            int lw = Gfx.width(f, label, true);
            int room = headLeft - (px + PAD + lw + 6) - 6;
            if (room >= 24) {
                Gfx.text(g, f, Gfx.fit(f, "· " + app, room), px + PAD + lw + 6, py + 9, Theme.dim(), 1f, false);
            }
        }

        // ---- cover (nudged a little against the mouse, so it seems to float above the panel)
        int cx = px + PAD;
        int cyy = py + 28;
        float pdx = cfg.parallax && motion ? Ease.clamp((cx + coverSize / 2f - mouseX) / Math.max(1f, width) * 6f, -3f, 3f) : 0f;
        float pdy = cfg.parallax && motion ? Ease.clamp((cyy + coverSize / 2f - mouseY) / Math.max(1f, height) * 6f, -3f, 3f) : 0f;
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(pdx, pdy);
        PlayerHud.cover(g, minecraft, cfg, cx, cyy, coverSize, cfg.roundCover, 1f, 1f, true);
        if (overCover(mouseX, mouseY) && live && !s.isAd()) {
            // in the cover's own space, so the outline grows and shrinks with the picture and always matches it
            PlayerHud.beginCoverSpace(g, cfg, cx, cyy, coverSize);
            float r = cfg.roundCover ? coverSize / 2f : PlayerHud.coverRadius(cfg, coverSize, cfg.panelRadius - PAD);
            Gfx.panel(g, -1, -1, coverSize + 2, coverSize + 2, cfg.roundCover || r > 0.5f ? cfg.panelShape == SpotifyConfig.ShapeKind.SQUARE
                    ? SpotifyConfig.ShapeKind.SQUARE : SpotifyConfig.ShapeKind.ROUND : SpotifyConfig.ShapeKind.SQUARE,
                    r + 1f, 0, 0, Colors.alpha(Colors.WHITE, 0.6f), 1f);
            PlayerHud.endCoverSpace(g);
        }
        float hoverCover = Anim.smooth(this, "coverHover", overCover(mouseX, mouseY) && live && !s.isAd() ? 1f : 0f, 14f);
        Ui.coverButton(g, cx, cyy, coverSize, hoverCover, playing, accent);
        pose.popMatrix();

        // ---- text
        if (live) {
            String title = s.title().isBlank() ? "Unknown title" : s.title();
            textLine(g, f, cfg, 0, title, tx, py + 30, tw, Theme.text(), true, since);
            textLine(g, f, cfg, 1, s.artist(), tx, py + (stacked ? 42 : 44), tw, Theme.soft(), false, since);
            if (cfg.showAlbum) textLine(g, f, cfg, 2, s.album(), tx, py + (stacked ? 54 : 56), tw, Theme.dim(), false, since);
            if (!stacked) {
                String state = playing ? "Playing" : "Paused";
                if (s.isAd()) state = "Ad break: controls are limited";
                boolean eq = cfg.showEqualizer && cfg.viz != SpotifyConfig.VizStyle.OFF;
                Gfx.text(g, f, Gfx.fit(f, state, tw - (eq ? 34 : 0)), tx, py + 72, Theme.dim(), 1f, false);
                if (eq) {
                    Gfx.equalizer(g, px + pw - PAD - 26, py + 70, 26, 10, accent, PlayerHud.equalizerAmount(),
                            System.currentTimeMillis(), s.key().hashCode(), motion, 1f);
                }
                if (cfg.showVisualizer && cfg.viz != SpotifyConfig.VizStyle.OFF) {
                    Gfx.spectrum(g, tx, py + 28 + coverSize - 26, tw, 26, accent, motion, 1f);
                }
            }
            if (!Player.canSeek() && !stacked) {
                Gfx.text(g, f, Gfx.fit(f, s.isAd() ? "Seeking is off during ads" : "This player does not allow seeking",
                        tw), tx, py + 86, Theme.dim(), 0.8f, false);
            }
        } else {
            String[] idle = PlayerHud.idleText(cfg);
            boolean drop = Sources.dropHere();
            boolean help = !stacked && com.zlorbie788.spotify.media.Diagnosis.needed(cfg);
            Gfx.textBold(g, f, Gfx.fitBold(f, idle[0], tw), tx, py + 30, Theme.text(), 1f, true);
            if (drop) {
                int ly = py + 46;
                for (String line : com.zlorbie788.spotify.util.TextWrap.wrap("Open Spotify and press play, or put MP3, OGG, WAV or FLAC files "
                        + "in your music folder. They play right here, no account needed.", tw, 4, t -> Gfx.width(f, t))) {
                    Gfx.text(g, f, line, tx, ly, Theme.soft(), 1f, false);
                    ly += 11;
                }
                openFolder.setX(tx);
                openFolder.setY(ly + 6);
            } else if (!help) {
                Gfx.text(g, f, Gfx.fit(f, idle[1], tw), tx, py + 44, Theme.soft(), 1f, false);
            }
            if (help) {
                int top = py + 46;
                int bottom = py + barY - 6 - (reconnect.visible ? 20 : 0);
                if (reconnect.visible) reconnect.setY(py + barY - 22);
                HelpCard.draw(g, f, cfg, com.zlorbie788.spotify.media.Diagnosis.checks(cfg), tx, top, tw,
                        Math.max(0, bottom - top), accent, 1f);
            } else {
                List<String> log = MediaBridge.recentLog();
                if (!log.isEmpty() && reconnect.visible && !stacked) {
                    Gfx.text(g, f, Gfx.fit(f, log.get(log.size() - 1), tw), tx, py + 110, Theme.dim(), 1f, false);
                }
                if (MediaBridge.state() == MediaBridge.State.RUNNING && !MediaBridge.mediaSessionOk()) {
                    Gfx.text(g, f, Gfx.fit(f, "Windows media session unavailable", tw), tx, py + 58, Colors.RED, 1f, false);
                }
            }
        }

        // ---- seek bar and times
        drawSeek(g, s, cfg, accent, accent2, motion, playing, mouseX, mouseY);

        // ---- shortcuts
        Gfx.text(g, f, Gfx.fit(f, "Space play  ·  Arrows seek / volume  ·  M mute  ·  L lyrics", pw - 2 * PAD),
                px + PAD, py + ph - 13, Theme.dim(), 0.9f, false);
    }

    /** One line of text with the song-change slide-in (the same look as the on-screen player). */
    private static void textLine(GuiGraphics g, Font f, SpotifyConfig cfg, int index, String s, int x, int y, int w,
                                 int color, boolean bold, long since) {
        if (s == null || s.isEmpty()) return;
        float t = cfg.trackTransition && cfg.motion() ? Ease.outCubic((since - index * 70L) / 380f) : 1f;
        int dx = Math.round((1f - t) * 14f);
        if (dx > 0) g.enableScissor(x, y - 1, x + w, y + 10);
        Gfx.line(g, f, s, x + dx, y, w, color, Math.max(0.02f, t), bold, cfg.textShadow, cfg.marquee && cfg.motion(), since);
        if (dx > 0) g.disableScissor();
    }

    private void drawSeek(GuiGraphics g, Snapshot s, SpotifyConfig cfg, int accent, int accent2, boolean motion,
                          boolean playing, int mouseX, int mouseY) {
        Font f = font;
        int bx = barX();
        int bw = barW();
        int by = py + barY;
        long dur = Player.duration();
        boolean can = Player.canSeek();
        boolean hover = can && overBar(mouseX, mouseY);
        float frac = seeking ? seekFrac : Player.progress();
        long shown = seeking ? Math.round(seekFrac * dur) : Player.position();

        // Slightly taller track when the bar is being used
        int th = hover || seeking ? BAR_H : 4;
        int ty = by + (BAR_H - th) / 2;
        float sweep = motion && playing ? PlayerHud.phase(cfg, 2.4) : 0f;
        Gfx.progress(g, bx, ty, bw, th, live(s) ? frac : 0f, can ? accent : Colors.mix(accent, Colors.CARD, 0.5f),
                can ? accent2 : Colors.mix(accent2, Colors.CARD, 0.5f), motion && playing, sweep, can, hover || seeking, 1f);

        String left = Ease.clock(live(s) ? shown : 0);
        String right = dur > 0 ? "-" + Ease.clock(Math.max(0, dur - shown)) : "--:--";
        if (cfg.showTime) {
            Gfx.text(g, f, left, bx, by + 12, Theme.soft(), 1f, false);
            String total = dur > 0 ? Ease.clock(dur) : "--:--";
            Gfx.textRight(g, f, total, bx + bw, by + 12, Theme.dim(), 1f, false);
            if (dur > 0) {
                String mid = right;
                Gfx.text(g, f, mid, bx + (bw - Gfx.width(f, mid)) / 2, by + 12, Theme.dim(), 0.85f, false);
            }
        }

        if ((hover || seeking) && dur > 0) {
            float at = seeking ? seekFrac : fracAt(mouseX);
            String tip = Ease.clock(Math.round(at * dur));
            int tw = Gfx.width(f, tip) + 8;
            int tipX = Math.max(bx, Math.min(bx + bw - tw, bx + Math.round(at * bw) - tw / 2));
            int tipY = by - 17;
            Chrome.body(g, tipX, tipY, tw, 12, Colors.opaque(Theme.surface()), Colors.opaque(accent));
            Gfx.drawStyled(g, f, tip, tipX + 4, tipY + 2, Colors.opaque(Theme.text()), false, false, false);
        }
    }

    private static boolean live(Snapshot s) {
        return s.active();
    }

    // ------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (Intro.active()) {
            Intro.skip();
            return true;
        }
        double mx = event.x();
        double my = event.y();
        if (event.button() == 0) {
            if (lyr != Lyr.NONE && lyrics.contains(mx, my)) {
                lyrics.click(mx, my);
                return true;
            }
            if (Player.canSeek() && overBar(mx, my)) {
                seeking = true;
                seekFrac = fracAt(mx);
                return true;
            }
            if (overCover(mx, my) && Player.snap().active() && !Player.snap().isAd()) {
                Player.toggle();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (seeking && event.button() == 0) {
            seekFrac = fracAt(event.x());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (seeking && event.button() == 0) {
            seeking = false;
            long dur = Player.duration();
            if (dur > 0) Player.seekTo(Math.round(seekFrac * dur));
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        if (lyr != Lyr.NONE && lyrics.scroll(mouseX, mouseY, scrollY)) return true;
        SpotifyConfig cfg = ConfigIo.get();
        int dir = scrollY > 0 ? 1 : -1;
        if (overBar(mouseX, mouseY) && Player.canSeek()) {
            Player.seekBy(dir * cfg.seekStepSeconds * 1000L);
            return true;
        }
        if (Player.canVolume()) {
            Player.volumeBy(dir * cfg.volumeStep);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (Intro.active() && key != GLFW.GLFW_KEY_ESCAPE) {
            Intro.skip();
            return true;
        }
        SpotifyConfig cfg = ConfigIo.get();
        if (key == GLFW.GLFW_KEY_ESCAPE || Keybinds.OPEN.matches(event)) {
            onClose();
            return true;
        }
        switch (key) {
            case GLFW.GLFW_KEY_LEFT -> {
                if (Player.canSeek()) Player.seekBy(-cfg.seekStepSeconds * 1000L);
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                if (Player.canSeek()) Player.seekBy(cfg.seekStepSeconds * 1000L);
                return true;
            }
            case GLFW.GLFW_KEY_UP -> {
                if (Player.canVolume()) Player.volumeBy(cfg.volumeStep);
                return true;
            }
            case GLFW.GLFW_KEY_DOWN -> {
                if (Player.canVolume()) Player.volumeBy(-cfg.volumeStep);
                return true;
            }
            default -> { }
        }
        // Let a focused button handle Space / Enter itself; otherwise Space is play / pause.
        if (getFocused() == null || key != GLFW.GLFW_KEY_SPACE) {
            switch (key) {
                case GLFW.GLFW_KEY_SPACE -> {
                    Player.toggle();
                    return true;
                }
                case GLFW.GLFW_KEY_M -> {
                    Player.toggleMute();
                    return true;
                }
                case GLFW.GLFW_KEY_S -> {
                    Player.toggleShuffle();
                    return true;
                }
                case GLFW.GLFW_KEY_R -> {
                    Player.cycleRepeat();
                    return true;
                }
                case GLFW.GLFW_KEY_N -> {
                    Player.next();
                    return true;
                }
                case GLFW.GLFW_KEY_B -> {
                    Player.previous();
                    return true;
                }
                case GLFW.GLFW_KEY_O -> {
                    minecraft.setScreen(LibraryScreen.open(this));
                    return true;
                }
                case GLFW.GLFW_KEY_L -> {
                    toggleLyrics();
                    return true;
                }
                default -> { }
            }
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
}
