package com.zlorbie788.spotify.gui;

import com.zlorbie788.spotify.catalog.Library;
import com.zlorbie788.spotify.catalog.Playback;
import com.zlorbie788.spotify.client.Keybinds;
import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.config.SpotifyConfig;
import com.zlorbie788.spotify.gui.widgets.Chrome;
import com.zlorbie788.spotify.gui.widgets.ConfigSlider;
import com.zlorbie788.spotify.gui.widgets.IconButton;
import com.zlorbie788.spotify.gui.widgets.StyledButton;
import com.zlorbie788.spotify.config.UiFont;
import com.zlorbie788.spotify.hud.Anim;
import com.zlorbie788.spotify.hud.Fx;
import com.zlorbie788.spotify.hud.Gfx;
import com.zlorbie788.spotify.hud.HelpCard;
import com.zlorbie788.spotify.hud.Icons;
import com.zlorbie788.spotify.hud.Intro;
import com.zlorbie788.spotify.hud.PlayerHud;
import com.zlorbie788.spotify.hud.Theme;
import com.zlorbie788.spotify.hud.Viz;
import com.zlorbie788.spotify.hud.Visual;
import com.zlorbie788.spotify.util.Clock;
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

/**
 * The "Sleek" full player: the whole screen takes on the colours of the album, with a big cover, large text, a
 * wide seek bar and round controls. The Lyrics button shows the lyrics next to the cover (or instead of it on
 * narrow windows); the Search button opens the song search. Click the artist or the album name to look it up.
 *
 * <p>Keys: Space play/pause, Left/Right seek, Up/Down volume, M mute, S shuffle, R repeat, N next, B previous,
 * L lyrics, F favourite, / or Ctrl+F search, Tab moves between buttons, Esc closes.
 */
public class SleekScreen extends Screen implements PlayerHud.HidesHud {
    private final LyricsPanel lyrics = new LyricsPanel();

    private int colX;
    private int cw;
    private int top;
    private int regionY;
    private int region;
    private int infoY;
    private int seekY;
    private int ctlY;
    private int volY;
    private boolean twoColumns;
    private boolean coverShown;
    private boolean tall;

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
    private IconButton heart;

    private int artistX;
    private int artistW;
    private int albumX;
    private int albumW;
    private int infoTextX;
    private boolean introChecked;
    private int mainH;
    private int total;

    public SleekScreen(boolean showLyrics) {
        super(Component.literal("Spotify Integration Mod"));
        if (showLyrics) PlayerLyrics.force();
    }

    // ------------------------------------------------------------ layout

    @Override
    protected void init() {
        SpotifyConfig cfg = ConfigIo.get();
        if (!introChecked) {
            introChecked = true;
            Intro.maybeStart(cfg);
        }
        int accent = PlayerHud.accent(cfg);
        boolean lyricsOpen = PlayerLyrics.open();
        twoColumns = lyricsOpen && width >= 560;
        cw = Math.min(Math.max(240, cfg.panelW - 40), Math.min(420, width - 24));
        int lw = twoColumns ? Math.min(300, width - cw - 56) : 0;
        total = twoColumns ? cw + 16 + lw : cw;
        colX = (width - total) / 2;
        tall = height >= 250;

        int fixed = 16 + 8 + 8 + 34 + 6 + 18 + 4 + 22 + (tall ? 24 : 0);
        region = Math.max(48, Math.min(lyricsOpen && !twoColumns ? 200 : 190, height - fixed - 16));
        mainH = fixed + region;
        top = Math.max(4, (height - mainH) / 2);
        regionY = top + 24;
        infoY = regionY + region + 8;
        seekY = infoY + 34 + 6;
        ctlY = seekY + 18 + 4;
        volY = ctlY + 22 + 4;
        coverShown = !(lyricsOpen && !twoColumns);

        // top bar
        addRenderableWidget(new StyledButton(colX, top, 64, 16, Component.literal("Search"),
                b -> minecraft.setScreen(new SearchScreen(this))).accent(accent).tip("Find a song and play it in Spotify (/)"));
        StyledButton musicButton = new StyledButton(colX + 70, top, 64, 16, Component.literal("My music"),
                b -> minecraft.setScreen(LibraryScreen.open(this))).accent(accent)
                .tip("The songs in your own music folder: play them without Spotify (O)");
        if (Sources.local()) musicButton.primary();
        addRenderableWidget(musicButton);
        StyledButton lyr = new StyledButton(colX + 140, top, 56, 16, Component.literal("Lyrics"), b -> {
            PlayerLyrics.toggle();
            rebuildWidgets();
        }).accent(accent).tip("Show or hide the lyrics (L)");
        if (lyricsOpen) lyr.primary();
        addRenderableWidget(lyr);
        int right = twoColumns ? colX + total : colX + cw;
        addRenderableWidget(new IconButton(right - 18, top, 18, 16, "Close", () -> Icons.CLOSE, this::onClose)
                .accent(Colors.RED).tip("Close (Esc)"));
        addRenderableWidget(new IconButton(right - 42, top, 20, 16, "Settings", () -> Icons.GEAR,
                () -> minecraft.setScreen(new SettingsScreen(this))).accent(accent).tip("Customize the player"));

        // title row: the heart
        heart = addRenderableWidget(new IconButton(colX + cw - 18, infoY - 1, 18, 14, "Favourite", () -> Icons.HEART,
                this::toggleHeart).accent(Colors.PINK).lit(this::isHeart).tip("Add to your favourites (F)"));

        // controls
        int ctlW = 22 + 6 + 22 + 6 + 34 + 6 + 22 + 6 + 22;
        int x = colX + (cw - ctlW) / 2;
        shuffle = addRenderableWidget(new IconButton(x, ctlY, 22, 22, "Shuffle", () -> Icons.SHUFFLE, Player::toggleShuffle)
                .accent(accent).lit(Player::shuffle).tip("Shuffle (S)"));
        x += 28;
        prev = addRenderableWidget(new IconButton(x, ctlY, 22, 22, "Previous song", () -> Icons.PREV, Player::previous)
                .accent(accent).tip("Previous song (B)"));
        x += 28;
        play = addRenderableWidget(new IconButton(x, ctlY, 34, 22, "Play or pause",
                () -> Player.playing() ? Icons.PAUSE : Icons.PLAY, Player::toggle)
                .accent(accent).solid().tip("Play / pause (Space)"));
        x += 40;
        next = addRenderableWidget(new IconButton(x, ctlY, 22, 22, "Next song", () -> Icons.NEXT, Player::next)
                .accent(accent).tip("Next song (N)"));
        x += 28;
        repeat = addRenderableWidget(new IconButton(x, ctlY, 22, 22, "Repeat", () -> Icons.REPEAT, Player::cycleRepeat)
                .accent(accent).lit(() -> Player.repeat() != 0)
                .badge(() -> Player.repeat() == 1 ? "1" : "").tip("Repeat: off / all / this song (R)"));

        int sliderW = Math.min(150, cw - 60);
        int sx = colX + (cw - sliderW - 26) / 2 + 26;
        mute = addRenderableWidget(new IconButton(sx - 26, volY, 22, 16, "Mute",
                () -> Player.muted() || Player.volume() == 0 ? Icons.MUTE : Icons.VOLUME, Player::toggleMute)
                .accent(accent).lit(Player::muted).tip("Mute (M)"));
        volume = addRenderableWidget(new ConfigSlider(sx, volY, sliderW, 16, "Volume ", 0, 100, 1,
                Math.max(0, Player.volume()), v -> (int) v + "%", v -> Player.setVolume((int) v))
                .accent(accent).tip("Volume of the player (Up / Down, or the mouse wheel)"));
        mute.visible = tall;
        volume.visible = tall;

        reconnect = addRenderableWidget(new StyledButton(colX + (cw - 92) / 2, regionY + region / 2 + 20, 92, 16,
                Component.literal("Reconnect"), b -> MediaBridge.restart()).accent(accent)
                .tip("Restart the Windows helper that reads the player"));
        openFolder = addRenderableWidget(new StyledButton(colX + (cw - 112) / 2, regionY + region / 2 + 26, 112, 16,
                Component.literal("Open music folder"), b -> Folder.openMusic()).accent(accent).primary()
                .tip("Opens the folder where your songs go (MP3, OGG, WAV, FLAC)"));
        openFolder.visible = false;

        // lyrics panel
        if (lyricsOpen) {
            if (twoColumns) lyrics.layout(colX + cw + 16, regionY, lw, ctlY + 22 - regionY);
            else lyrics.layout(colX, regionY, cw, region);
        }
    }

    private boolean isHeart() {
        Snapshot s = Player.snap();
        return s.active() && !s.isAd() && Library.isFavouriteSong(s.title(), s.artist());
    }

    private void toggleHeart() {
        var item = Library.fromSnapshot(Player.snap());
        if (item != null) Library.toggleFavouriteSong(item);
    }

    private int barX() {
        return colX;
    }

    private int barW() {
        return cw;
    }

    private boolean overBar(double mx, double my) {
        return mx >= barX() - 3 && mx < barX() + barW() + 3 && my >= seekY - 6 && my < seekY + 12;
    }

    private float fracAt(double mx) {
        return Ease.clamp01((float) ((mx - barX()) / barW()));
    }

    private boolean stripShown() {
        return tall && ConfigIo.get().viz != SpotifyConfig.VizStyle.OFF;
    }

    private int stripH() {
        return stripShown() ? 12 : 0;
    }

    private int coverSize() {
        return Math.min(region - stripH(), cw - 30);
    }

    /** Top of the cover: the cover and its beat strip are centred in the region together. */
    private int coverY() {
        return regionY + (region - coverSize() - stripH()) / 2;
    }

    // ------------------------------------------------------------ drawing

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        SpotifyConfig cfg = ConfigIo.get();
        int accent = PlayerHud.smoothAccent();
        if (cfg.highContrast) {
            g.fill(0, 0, width, height, 0xFF000000);
            return;
        }
        float fade = Anim.smooth(this, "wash", 0f, 1f, 5f);
        int topC = Colors.opaque(Colors.mix(0xFF05060A, Colors.mix(Colors.CARD_DARK, accent, 0.40f), fade));
        int midC = Colors.opaque(Colors.mix(0xFF05060A, Colors.mix(Colors.CARD_DARK, accent, 0.16f), fade));
        int botC = Colors.opaque(Colors.mix(0xFF05060A, accent, 0.05f * fade));
        g.fillGradient(0, 0, width, height / 2, topC, midC);
        g.fillGradient(0, height / 2, width, height, midC, botC);
        boolean motion = cfg.motion();
        double secs = Clock.nanos() / 1e9;
        float beatNow = motion && cfg.beatPulse ? Visual.pulse() : 0f;
        if (cfg.ambient && motion) {
            float par = cfg.parallax ? Ease.clamp((mouseX - width / 2f) / Math.max(1f, width) * 30f, -15f, 15f) : 0f;
            Fx.aurora(g, 0, 0, width, height, 0f, accent, Colors.lighten(accent, 0.4f), secs, beatNow,
                    Player.playing() ? 1f : 0.5f, par);
        }
        if (cfg.particles && motion) {
            Fx.specks(g, 0, 0, width, height, Colors.lighten(accent, 0.5f), secs, Math.max(12, width * height / 6000), 1f, 3);
        }
        if (cfg.showGlow && coverShown && !helpMode()) {
            float beat = cfg.motion() && cfg.beatPulse ? Visual.pulse() : 0f;
            int size = coverSize();
            // drawn in the cover's own (scaled) space, so the glow always hugs the picture exactly
            PlayerHud.beginCoverSpace(g, cfg, colX + (cw - size) / 2, coverY(), size);
            float r = PlayerHud.coverRadius(cfg, size, size * 0.12f);
            com.zlorbie788.spotify.hud.Shapes.glow(g, Gfx.dev(), 0, 0, size, size, SpotifyConfig.ShapeKind.ROUND, r, accent,
                    0.9f + 0.9f * beat);
            PlayerHud.endCoverSpace(g);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        SpotifyConfig cfg = ConfigIo.get();
        PlayerHud.tickState(cfg);
        Gfx.useFont(cfg.font, cfg.fontShift);
        try {
            draw(g, cfg, mouseX, mouseY, partialTick);
        } finally {
            Gfx.useFont(UiFont.MINECRAFT, 0);
        }
    }

    private void draw(GuiGraphics g, SpotifyConfig cfg, int mouseX, int mouseY, float partialTick) {
        Snapshot s = Player.snap();
        int accent = PlayerHud.smoothAccent();
        int accent2 = Colors.lighten(accent, 0.45f);
        boolean playing = Player.playing();
        boolean live = s.active();
        Font f = font;

        play.active = live && !s.isAd();
        prev.active = live && s.canPrev() && !s.isAd();
        next.active = live && s.canNext();
        shuffle.active = live && s.canShuffle();
        repeat.active = live && s.canRepeat();
        mute.active = Player.canVolume();
        volume.active = Player.canVolume();
        heart.active = live && !s.isAd();
        if (Player.canVolume()) volume.setActual(Player.volume());
        openFolder.visible = Sources.dropHere();
        openFolder.active = openFolder.visible;
        reconnect.visible = !live && !Sources.local() && !Sources.dropHere() && (MediaBridge.state() == MediaBridge.State.FAILED
                || MediaBridge.state() == MediaBridge.State.IDLE
                || (MediaBridge.state() == MediaBridge.State.RUNNING && MediaBridge.silentSeconds() > 8));
        reconnect.active = reconnect.visible;

        boolean intro = Intro.active();
        boolean content = Intro.showContent();
        Intro.veil(g, width, height);
        if (!content) {
            drawIntroOverlay(g, f, accent);
            return;
        }
        // everything rises into place when the screen opens
        float enter = intro ? 1f : Anim.smooth(this, "open", 0f, 1f, 8f);
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(0f, (1f - Ease.outCubic(enter)) * 10f);

        Gfx.useFont(cfg.uiFont, cfg.fontShift);
        super.render(g, mouseX, mouseY, partialTick);
        Gfx.useFont(cfg.font, cfg.fontShift);

        long since = Player.sinceTrackChange();

        // ---- the big cover (or the lyrics)
        if (coverShown && helpMode()) {
            drawIdle(g, f, cfg);
        } else if (coverShown) {
            int size = coverSize();
            int cx = colX + (cw - size) / 2;
            PlayerHud.cover(g, minecraft, cfg, cx, coverY(), size,
                    PlayerHud.coverRadius(cfg, size, size * 0.12f), 15, 1f, 1f, true);
            drawBeatStrip(g, cfg, accent, cx, coverY() + size + 4, size);
            boolean overCover = live && !s.isAd() && mouseX >= cx && mouseX < cx + size && mouseY >= coverY() && mouseY < coverY() + size;
            Ui.coverButton(g, cx, coverY(), size, Anim.smooth(this, "coverHover", overCover ? 1f : 0f, 14f), playing, accent);
            if (!live) drawIdle(g, f, cfg);
        }
        if (PlayerLyrics.open()) {
            int light = Covers.current() != null ? Covers.current().palette.light() : Colors.lighten(accent, 0.55f);
            lyrics.render(g, f, accent, Colors.opaque(light), playing);
        }

        // ---- text
        int tx = colX;
        int tw = cw - 22;
        if (!coverShown && live) {
            PlayerHud.cover(g, minecraft, cfg, colX, infoY - 1, 28, PlayerHud.coverRadius(cfg, 28, 3f), 15, 1f, 1f, false);
            tx += 34;
            tw -= 34;
        }
        infoTextX = tx;
        if (live) {
            String title = s.title().isBlank() ? "Unknown title" : s.title();
            textLine(g, f, cfg, 0, title, tx, infoY, tw, Theme.text(), true, since);
            textLine(g, f, cfg, 1, s.artist(), tx, infoY + 12, tw, Theme.soft(), false, since);
            artistX = tx;
            artistW = Math.min(tw, Gfx.width(f, s.artist()));
            albumX = tx;
            albumW = 0;
            if (cfg.showAlbum && !s.album().isBlank()) {
                textLine(g, f, cfg, 2, s.album(), tx, infoY + 23, tw, Theme.dim(), false, since);
                albumW = Math.min(tw, Gfx.width(f, s.album()));
            }
            int my = mouseY;
            if (my >= infoY + 11 && my < infoY + 22 && mouseX >= artistX && mouseX < artistX + artistW && !s.artist().isBlank()) {
                g.fill(artistX, infoY + 21, artistX + artistW, infoY + 22, Colors.alpha(accent, 0.9f));
            } else if (albumW > 0 && my >= infoY + 22 && my < infoY + 33 && mouseX >= albumX && mouseX < albumX + albumW) {
                g.fill(albumX, infoY + 32, albumX + albumW, infoY + 33, Colors.alpha(accent, 0.9f));
            }
        }

        drawSeek(g, s, cfg, accent, accent2, cfg.motion(), playing, mouseX, mouseY);

        // ---- bottom line: what the last search did, or the shortcuts
        int lineY = tall ? volY + 22 : ctlY + 28;
        String msg = Playback.message();
        if (!msg.isEmpty() && Playback.phase() != Playback.Phase.IDLE) {
            int c = Playback.phase() == Playback.Phase.FAILED ? Colors.RED : accent;
            String t = Gfx.fit(f, msg, cw);
            Gfx.text(g, f, t, colX + (cw - Gfx.width(f, t)) / 2, lineY, c, 1f, false);
        } else if (height >= 280) {
            String hint = "Space play  ·  L lyrics  ·  / search  ·  F like";
            hint = Gfx.fit(f, hint, cw);
            Gfx.text(g, f, hint, colX + (cw - Gfx.width(f, hint)) / 2, lineY, Theme.dim(), 1f, false);
        }
        pose.popMatrix();
        if (intro) drawIntroOverlay(g, f, accent);
    }

    /** The opening animation plays over the column of the player (the colours of the whole screen stay behind it). */
    private void drawIntroOverlay(GuiGraphics g, Font f, int accent) {
        int w = total + 40;
        int h = mainH + 40;
        int x = colX - 20;
        int y = Math.max(0, top - 20);
        int plate = Colors.opaque(Colors.mix(0xFF05060A, accent, 0.18f));
        Intro.overlay(g, f, x, y, w, h, SpotifyConfig.ShapeKind.ROUND, 14f, accent, colX + cw / 2, regionY + region / 2,
                plate, Colors.opaque(Colors.mix(0xFF05060A, accent, 0.06f)));
    }

    /** A thin beat display under the cover (the style from the Visual settings). */
    private void drawBeatStrip(GuiGraphics g, SpotifyConfig cfg, int accent, int x, int y, int w) {
        if (!stripShown()) return;
        Viz.drawWide(g, Gfx.dev(), cfg.viz, x, y, w, 8, Colors.lighten(accent, 0.35f), cfg.motion(), 0.85f);
    }

    /** True when the "Spotify not found" checklist takes the place of the big cover. */
    private boolean helpMode() {
        return !Player.snap().active() && region >= 110 && com.zlorbie788.spotify.media.Diagnosis.needed(ConfigIo.get());
    }

    private void drawIdle(GuiGraphics g, Font f, SpotifyConfig cfg) {
        String[] idle = PlayerHud.idleText(cfg);
        boolean help = helpMode();
        int y = help ? regionY + 6 : regionY + region / 2 - 6;
        String a = Gfx.fitBold(f, idle[0], cw);
        Gfx.textBold(g, f, a, colX + (cw - Gfx.width(f, a, true)) / 2, y, Theme.text(), 1f, true);
        String b = Gfx.fit(f, idle[1], cw);
        if (Sources.dropHere()) {
            int ly = y + 12;
            for (String line : com.zlorbie788.spotify.util.TextWrap.wrap("Open Spotify and press play, or put MP3, OGG, WAV or FLAC files "
                    + "in your music folder. They play right here, no account needed.", Math.min(cw - 16, 300), 3, t -> Gfx.width(f, t))) {
                Gfx.text(g, f, line, colX + (cw - Gfx.width(f, line)) / 2, ly, Theme.soft(), 1f, false);
                ly += 11;
            }
            openFolder.setY(ly + 6);
        } else if (!help) {
            Gfx.text(g, f, b, colX + (cw - Gfx.width(f, b)) / 2, y + 12, Theme.soft(), 1f, false);
        }
        if (help) {
            int top = regionY + 24;
            int bottom = regionY + region - (reconnect.visible ? 22 : 2);
            if (reconnect.visible) reconnect.setY(regionY + region - 18);
            int w = Math.min(cw, 420);
            HelpCard.draw(g, f, cfg, com.zlorbie788.spotify.media.Diagnosis.checks(cfg), colX + (cw - w) / 2, top, w,
                    Math.max(0, bottom - top), PlayerHud.smoothAccent(), 1f);
        }
    }

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
        long dur = Player.duration();
        boolean can = Player.canSeek();
        boolean hover = can && overBar(mouseX, mouseY);
        float frac = seeking ? seekFrac : Player.progress();
        long shown = seeking ? Math.round(seekFrac * dur) : Player.position();
        int th = hover || seeking ? 6 : 4;
        int ty = seekY + (6 - th) / 2;
        float sweep = motion && playing ? PlayerHud.phase(cfg, 2.4) : 0f;
        Gfx.progress(g, bx, ty, bw, th, s.active() ? frac : 0f, can ? accent : Colors.mix(accent, Colors.CARD, 0.5f),
                can ? accent2 : Colors.mix(accent2, Colors.CARD, 0.5f), motion && playing, sweep, can, hover || seeking, 1f);
        if (cfg.showTime) {
            Gfx.text(g, f, Ease.clock(s.active() ? shown : 0), bx, seekY + 9, Theme.soft(), 1f, false);
            Gfx.textRight(g, f, dur > 0 ? Ease.clock(dur) : "--:--", bx + bw, seekY + 9, Theme.dim(), 1f, false);
        }
        if ((hover || seeking) && dur > 0) {
            float at = seeking ? seekFrac : fracAt(mouseX);
            String tip = Ease.clock(Math.round(at * dur));
            int tw = Gfx.width(f, tip) + 8;
            int tipX = Math.max(bx, Math.min(bx + bw - tw, bx + Math.round(at * bw) - tw / 2));
            int tipY = seekY - 17;
            Chrome.body(g, tipX, tipY, tw, 12, Colors.opaque(Theme.surface()), Colors.opaque(accent));
            Gfx.text(g, f, tip, tipX + 4, tipY + 2, Theme.text(), 1f, false);
        }
    }

    // ------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        if (Intro.active()) {
            Intro.skip();
            return true;
        }
        if (event.button() == 0) {
            if (PlayerLyrics.open() && lyrics.contains(mx, my)) {
                lyrics.click(mx, my);
                return true;
            }
            if (Player.canSeek() && overBar(mx, my)) {
                seeking = true;
                seekFrac = fracAt(mx);
                return true;
            }
            Snapshot s = Player.snap();
            if (s.active() && !s.isAd()) {
                if (coverShown && mx >= colX + (cw - coverSize()) / 2 && mx < colX + (cw + coverSize()) / 2
                        && my >= coverY() && my < coverY() + coverSize()) {
                    Player.toggle();
                    return true;
                }
                if (my >= infoY + 11 && my < infoY + 22 && mx >= artistX && mx < artistX + artistW && !s.artist().isBlank()) {
                    minecraft.setScreen(SearchScreen.forArtist(this, s));
                    return true;
                }
                if (albumW > 0 && my >= infoY + 22 && my < infoY + 33 && mx >= albumX && mx < albumX + albumW) {
                    minecraft.setScreen(SearchScreen.forAlbumOf(this, s));
                    return true;
                }
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
        if (PlayerLyrics.open() && lyrics.scroll(mouseX, mouseY, scrollY)) return true;
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
        SpotifyConfig cfg = ConfigIo.get();
        if (Intro.active() && key != GLFW.GLFW_KEY_ESCAPE) {
            Intro.skip();
            return true;
        }
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
            case GLFW.GLFW_KEY_O -> {
                minecraft.setScreen(LibraryScreen.open(this));
                return true;
            }
            case GLFW.GLFW_KEY_SLASH -> {
                minecraft.setScreen(new SearchScreen(this));
                return true;
            }
            default -> { }
        }
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
                case GLFW.GLFW_KEY_L -> {
                    PlayerLyrics.toggle();
                    rebuildWidgets();
                    return true;
                }
                case GLFW.GLFW_KEY_F -> {
                    if (minecraft.hasControlDown()) {
                        minecraft.setScreen(new SearchScreen(this));
                    } else {
                        toggleHeart();
                    }
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
