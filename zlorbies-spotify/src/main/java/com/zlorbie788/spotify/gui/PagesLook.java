package com.zlorbie788.spotify.gui;

import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.config.SpotifyConfig;
import com.zlorbie788.spotify.config.UiFont;
import com.zlorbie788.spotify.hud.Gfx;
import com.zlorbie788.spotify.hud.Intro;
import com.zlorbie788.spotify.hud.LookCode;
import com.zlorbie788.spotify.hud.LookPresets;
import com.zlorbie788.spotify.hud.Theme;
import com.zlorbie788.spotify.hud.Toast;
import com.zlorbie788.spotify.local.Folder;
import com.zlorbie788.spotify.media.Beat;
import com.zlorbie788.spotify.media.Covers;
import com.zlorbie788.spotify.media.MediaBridge;
import com.zlorbie788.spotify.media.Player;
import com.zlorbie788.spotify.util.Colors;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The "Look" and "Player" groups of the settings. */
final class PagesLook {
    static final String[] STYLE_NAMES = {"Card", "Compact", "Vinyl", "Minimal", "Tall", "Sleek"};
    static final String[] LOOK_NAMES = {"Classic", "Sleek"};
    static final String[] ACCENT_NAMES = {"Album", "My colour", "Rainbow"};
    static final String[] BACKGROUND_NAMES = {"Solid", "Glass", "Album tint", "None", "Fade"};
    static final String[] VISIBILITY_NAMES = {"Always", "While playing", "On song change"};
    static final String[] SOURCE_NAMES = {"Spotify only", "Any player"};
    static final String[] IN_PLAYER_NAMES = {"Off", "Remember", "Always"};
    static final String[] TOAST_WHEN_NAMES = {"When the player is hidden", "Every song"};

    private PagesLook() {}

    private static SpotifyConfig cfg() {
        return ConfigIo.get();
    }

    private static String pct(double v) {
        return Math.round(v * 100) + "%";
    }

    private static String px(double v) {
        return (int) v + " px";
    }

    private static String times(double v) {
        return String.format(Locale.ROOT, "%.1fx", v);
    }

    private static String times2(double v) {
        return String.format(Locale.ROOT, "%.2fx", v);
    }

    private static String secs(double v) {
        return (int) v + " s";
    }

    // ------------------------------------------------------------ Look

    static void look(SettingsKit k) {
        k.group(0);
        SettingsKit.Host host = k.host;

        k.section("Style and colour", false);
        k.cycle("Style", "The shape of the on-screen player.", "Card, Compact, Vinyl, Minimal, Tall or Sleek. Click to switch.",
                STYLE_NAMES, () -> cfg().style.ordinal(), () -> cfg().style = cfg().style.next(1));
        k.cycle("Colour", "Where the player's colour comes from.",
                "Album = taken from the cover art, My colour = pick below, Rainbow = cycling colours.", ACCENT_NAMES,
                () -> cfg().accent.ordinal(), () -> {
                    cfg().accent = cfg().accent.next(1);
                });
        k.swatches("Pick a colour", "Choosing one switches Colour to \"My colour\".", "",
                () -> cfg().accent == SpotifyConfig.Accent.CUSTOM ? cfg().accentColor : 0, c -> {
                    cfg().accentColor = c;
                    cfg().accent = SpotifyConfig.Accent.CUSTOM;
                });
        k.cycle("Back", "What is behind the text.", "Solid, see-through glass, tinted with the album colour, or no box at all.",
                BACKGROUND_NAMES, () -> cfg().background.ordinal(), () -> cfg().background = cfg().background.next(1));
        k.choice("Shape", "The outline of the player.",
                "Rounded, cut corners (a HUD look), a tech cut (two corners), or square.", () -> cfg().shape.label,
                () -> cfg().shape = cfg().shape.next(1));
        k.slider("Corners", "How round (or how deeply cut) the corners are.", "The cover follows them.", 0, 28, 1, cfg().radius,
                PagesLook::px, v -> cfg().radius = (float) v);
        k.slider("Border", "The thickness of the line around the player.", "", 0, 3, 0.5, cfg().borderWidth,
                v -> v == 0 ? "none" : String.format(Locale.ROOT, "%.1f px", v), v -> cfg().borderWidth = (float) v);

        k.section("Quick looks", false);
        List<AbstractWidget> chips = new ArrayList<>();
        for (int i = 0; i < LookPresets.NAMES.length; i++) {
            final int index = i;
            chips.add(Ui.button(0, 0, 50, 15, LookPresets.NAMES[i], b -> {
                LookPresets.apply(cfg(), index);
                ConfigIo.save();
                Covers.reload();
                host.toast("Look applied: " + LookPresets.NAMES[index]);
                host.rebuild();
            }).accent(k.accent()).tip(LookPresets.TIPS[i]));
        }
        k.grid("Quick looks", "One click re-styles the player. Only the look changes: not the position, size or lyrics.", "", chips, 3);
        List<AbstractWidget> code = new ArrayList<>();
        code.add(Ui.button(0, 0, 50, 15, "Copy look code", b -> {
            host.mc().keyboardHandler.setClipboard(LookCode.export(cfg()));
            host.toast("Look code copied: paste it anywhere to share");
        }).accent(k.accent()).tip("Your whole look as one line of text. It holds no positions and no personal data."));
        code.add(Ui.button(0, 0, 50, 15, "Paste look code", b -> {
            int n = LookCode.apply(cfg(), host.mc().keyboardHandler.getClipboard());
            if (n < 0) {
                host.toast("The clipboard has no look code");
                return;
            }
            ConfigIo.save();
            Covers.reload();
            host.toast("Look applied (" + n + " settings)");
            host.rebuild();
        }).accent(k.accent()).tip("Applies a look code from the clipboard. Only the look changes."));
        k.buttons("Share your look", "Copy your look as text to share it, or paste one you were given.", "", code);

        k.section("What the player shows", true);
        k.toggle("Cover art", "The album picture.", "", () -> cfg().showCover, v -> cfg().showCover = v);
        k.toggle("Album name", "The album under the artist.", "", () -> cfg().showAlbum, v -> cfg().showAlbum = v);
        k.toggle("Time", "The played and total time.", "", () -> cfg().showTime, v -> cfg().showTime = v);
        k.toggle("Progress bar", "How far into the song you are.", "", () -> cfg().showProgress, v -> cfg().showProgress = v);
        k.toggle("Equalizer bars", "Little dancing bars while a song plays.", "", () -> cfg().showEqualizer, v -> cfg().showEqualizer = v);
        k.toggle("Top line", "The coloured line along the top edge.", "", () -> cfg().showAccentLine, v -> cfg().showAccentLine = v);
        k.toggle("Glow", "A soft light around the player.", "", () -> cfg().showGlow, v -> cfg().showGlow = v);
        k.toggle("Text shadow", "A dark shadow behind the letters.", "", () -> cfg().textShadow, v -> cfg().textShadow = v);
        k.toggle("High contrast", "Black box, white border, white text: the easiest to read.", "",
                () -> cfg().highContrast, v -> cfg().highContrast = v);
        k.toggle("Source name", "Add the player's name (Spotify...) to the heading.", "",
                () -> cfg().showSourceName, v -> cfg().showSourceName = v);
        k.toggle("Level display", "The wide level bars in the full player screen.", "",
                () -> cfg().showVisualizer, v -> cfg().showVisualizer = v);

        k.section("Cover", true);
        k.choice("Cover shape", "How the album cover is cut.", "\"Match the panel\" follows the corners of the player, so nothing sticks out.",
                () -> cfg().coverShape.label, () -> cfg().coverShape = cfg().coverShape.next(1));
        k.slider("Cover zoom", "Zoom in a little to cut away a thin edge.", "", 0, 0.3, 0.02, cfg().coverZoom,
                PagesLook::pct, v -> cfg().coverZoom = (float) v);
        k.toggle("Trim cover", "Cut away the empty margins and logo strip around the art.",
                "Spotify's Windows picture has empty margins and a Spotify logo strip around the art.", () -> cfg().trimCover, v -> {
                    cfg().trimCover = v;
                    Covers.reload();
                });
        k.toggle("Round cover", "Cut the cover into a circle.", "", () -> cfg().roundCover, v -> cfg().roundCover = v);
        k.toggle("Spin cover", "A round cover turns like a record while playing.", "", () -> cfg().spinCover, v -> cfg().spinCover = v);

        k.section("Beat and effects", true);
        k.choice("Beat display", "What moves with the music.",
                "Mirrored bars, a live scope that scrolls, a wave, a dot matrix, classic bars, a spectrum, a ring, or nothing.",
                () -> cfg().viz.label, () -> cfg().viz = cfg().viz.next(1));
        k.choice("Progress style", "The seek bar of the player screens.", "A smooth line, or a waveform you can click.",
                () -> cfg().progressStyle.label, () -> cfg().progressStyle = cfg().progressStyle.next(1));
        k.toggle("Beat pulse", "The cover bumps and the glow flashes on every beat.", "", () -> cfg().beatPulse, v -> cfg().beatPulse = v);
        k.toggle("Breathing glow", "The glow pulses gently while playing.", "", () -> cfg().breathingGlow, v -> cfg().breathingGlow = v);
        k.toggle("Border comet", "A light runs around the border while a song plays.", "", () -> cfg().borderTrace, v -> cfg().borderTrace = v);
        k.toggle("Shine", "A streak of light crosses the player now and then.", "", () -> cfg().shine, v -> cfg().shine = v);
        k.toggle("Aurora", "Soft coloured light drifting behind the player screen.", "", () -> cfg().ambient, v -> cfg().ambient = v);
        k.toggle("Light specks", "Tiny sparks floating up inside the player.", "", () -> cfg().particles, v -> cfg().particles = v);
        k.toggle("Corner brackets", "Small HUD-style brackets in the four corners.", "", () -> cfg().cornerBrackets, v -> cfg().cornerBrackets = v);
        k.toggle("Parallax", "The cover and the aurora drift a little as the mouse moves.", "", () -> cfg().parallax, v -> cfg().parallax = v);

        k.section("Mood", true);
        k.toggle("Mood themes", "Colours and speed follow the energy of the song: calm for slow ones, punchy for fast ones.",
                "Works for your own songs and for Spotify. It only changes how the player is tinted and how fast it moves; "
                        + "your chosen look stays.", () -> cfg().mood, v -> cfg().mood = v);
        k.slider("Mood strength", "How much the song's energy changes the look.", "", 0, 1, 0.05, cfg().moodAmount, PagesLook::pct,
                v -> cfg().moodAmount = (float) v).enabled(() -> cfg().mood);

        k.section("Motion", true);
        k.toggle("Animations", "Master switch. Off = nothing moves except the progress bar and the time.", "",
                () -> cfg().animations, v -> cfg().animations = v);
        k.slider("Animation speed", "How fast the animations run.", "", 0.25, 3.0, 0.05, cfg().animSpeed, PagesLook::times2,
                v -> cfg().animSpeed = (float) v);
        k.toggle("Follow the music", "Bars, lines and glow move with the real loudness and beat.", "",
                () -> cfg().reactToMusic, v -> cfg().reactToMusic = v);
        k.slider("Reaction strength", "How strongly the bars and lines react. Turn it up for quiet songs.", "", 0.3, 2.0, 0.05,
                cfg().reactStrength, PagesLook::pct, v -> cfg().reactStrength = (float) v);
        k.toggle("Beat lock", "Keeps the rhythm: the pulse stays on the beat even when a hit is missed.",
                "Finds the tempo from the hits and goes on pulsing on the beat. It stays quiet for music without a steady beat.",
                () -> cfg().beatLock, v -> cfg().beatLock = v).enabled(() -> cfg().reactToMusic);
        k.toggle("Scrolling text", "Long titles slide sideways. Off = they are cut with ...", "", () -> cfg().marquee, v -> cfg().marquee = v);
        k.toggle("Song change effect", "Text slides in and the cover pops when a new song starts.", "",
                () -> cfg().trackTransition, v -> cfg().trackTransition = v);

        k.section("Fonts", true);
        k.choice("Player font", "The letters of the on-screen player and the player screen.", "You can also click a font in the list below.",
                () -> cfg().font.label, () -> cfg().font = cfg().font.next(1));
        k.choice("Lyrics font", "The letters of the lyrics. \"Same as player\" uses the player font.", "", () -> fontName(cfg().lyrics.font), () -> {
            List<UiFont> list = UiFont.available();
            UiFont cur = cfg().lyrics.font;
            int next = cur == null ? 0 : list.indexOf(cur) + 1;
            cfg().lyrics.font = next <= 0 && cur != null || next >= list.size() ? null : list.get(next);
        });
        k.choice("Menu font", "The letters of the buttons and menus of the player screens and these settings.", "",
                () -> cfg().uiFont.label, () -> cfg().uiFont = cfg().uiFont.next(1));
        k.slider("Vertical shift", "Moves the letters of the extra fonts up or down.",
                "If a font sits too high or low next to the Minecraft font, fix it here.", -4, 4, 1, cfg().fontShift,
                v -> String.format(Locale.ROOT, "%+d px", (int) v), v -> cfg().fontShift = (int) v);
        SettingsRow gallery = k.row("Font list", "Click a font to use it for the player.", "");
        gallery.heightOf = w -> UiFont.available().size() * 13;
        gallery.controlH = 0;
        gallery.draw = (g, f, x, y, w, mx, my) -> {
            UiFont oldFont = Gfx.font();
            int oldShift = Gfx.fontShift();
            try {
                List<UiFont> all = UiFont.available();
                for (int i = 0; i < all.size(); i++) {
                    int yy = y + i * 13;
                    boolean on = all.get(i) == cfg().font;
                    boolean hover = mx >= x && mx < x + w && my >= yy && my < yy + 13;
                    if (on) {
                        com.zlorbie788.spotify.gui.widgets.Chrome.body(g, x, yy, w, 12, Colors.alpha(Theme.accent(k.accent()), 0.22f),
                                Colors.alpha(Theme.accent(k.accent()), 0.6f));
                    } else if (hover) {
                        com.zlorbie788.spotify.gui.widgets.Chrome.body(g, x, yy, w, 12, Colors.alpha(Theme.raised(), 0.6f), 0);
                    }
                    Gfx.useFont(all.get(i), cfg().fontShift);
                    Gfx.drawStyled(g, f, Gfx.fit(f, all.get(i).label + "  Aa Bb 0123", w - 10), x + 5, yy + 2,
                            Colors.opaque(on ? Theme.text() : Theme.soft()), false, false, false);
                }
            } finally {
                Gfx.useFont(oldFont, oldShift);
            }
        };
        gallery.click = (rx, ry, w) -> {
            int i = ry / 13;
            List<UiFont> all = UiFont.available();
            if (i < 0 || i >= all.size()) return false;
            cfg().font = all.get(i);
            ConfigIo.save();
            host.rebuild();
            return true;
        };
        k.button("Reset fonts", "Back to the Minecraft font everywhere.", "", "Reset fonts", false, () -> {
            cfg().font = UiFont.MINECRAFT;
            cfg().uiFont = UiFont.MINECRAFT;
            cfg().lyrics.font = null;
            cfg().fontShift = 0;
            ConfigIo.save();
            host.rebuild();
        });

        k.section("Menu theme", true);
        k.choice("Theme", "The colours of the buttons, menus and panels.", "Midnight, Graphite, Ocean, Neon or Rose.",
                () -> cfg().uiTheme.label, () -> cfg().uiTheme = cfg().uiTheme.next(1));
        k.choice("Highlight", "Where the highlight colour of the menus comes from.",
                "A colour for every tab, the album, or one you pick.", () -> cfg().uiAccent.label, () -> cfg().uiAccent = cfg().uiAccent.next(1));
        k.swatches("Menu colour", "Used when Highlight is set to your own colour.", "", () -> cfg().uiColor, c -> {
            cfg().uiColor = c;
            cfg().uiAccent = SpotifyConfig.UiAccent.CUSTOM;
        }).when(() -> cfg().uiAccent == SpotifyConfig.UiAccent.CUSTOM);
        k.slider("Roundness", "How round the corners of buttons, switches and boxes are.", "", 0, 8, 1, cfg().uiRadius,
                PagesLook::px, v -> cfg().uiRadius = (float) v);
        k.toggle("Menu animations", "Buttons fade, switches glide and tabs slide. Off = they jump.", "",
                () -> cfg().uiAnimations, v -> cfg().uiAnimations = v);
        k.toggle("Click ripples", "A ring of light spreads from where you click a button.", "", () -> cfg().ripples, v -> cfg().ripples = v);

        k.section("Opening animation", true);
        k.choice("Plays", "When the opening animation shows.",
                "Every time you open the player, once each time you join a world, once per Minecraft session, only the very first time, or never.",
                () -> cfg().introMode.label, () -> cfg().introMode = cfg().introMode.next(1));
        k.slider("Intro speed", "How fast the opening animation runs.", "", 0.5, 2.0, 0.1, cfg().introSpeed, PagesLook::times,
                v -> cfg().introSpeed = (float) v);
        List<AbstractWidget> intro = new ArrayList<>();
        intro.add(Ui.button(0, 0, 50, 15, "Replay intro", b -> {
            ConfigIo.save();
            Intro.replayNext();
            host.mc().setScreen(PlayerScreen.create());
        }).accent(k.accent()).primary().tip("Open the player and play the opening animation."));
        intro.add(Ui.button(0, 0, 50, 15, "Open the player", b -> {
            ConfigIo.save();
            Intro.suppressNext();
            host.mc().setScreen(PlayerScreen.create());
        }).accent(k.accent()).tip("Open the player screen."));
        k.buttons("Try it", "Watch the animation again, or just open the player.", "", intro);
    }

    private static String fontName(UiFont f) {
        return f == null ? "Same as player" : f.label;
    }

    // ------------------------------------------------------------ Player

    static void player(SettingsKit k) {
        k.group(1);
        SettingsKit.Host host = k.host;

        k.section("On screen", false);
        k.toggle("Show the player", "Show or hide the on-screen player.", "The keys and commands still work when it is hidden.",
                () -> cfg().hudEnabled, v -> cfg().hudEnabled = v);
        k.cycle("Show", "When the player is on screen.", "Always, only while music plays, or only for a few seconds when the song changes.",
                VISIBILITY_NAMES, () -> cfg().visibility.ordinal(), () -> cfg().visibility = cfg().visibility.next(1));
        k.slider("Pop-up time", "How long it stays after a song change (for \"On song change\").", "", 2, 60, 1, cfg().popupSeconds,
                PagesLook::secs, v -> cfg().popupSeconds = (int) v).enabled(() -> cfg().visibility == SpotifyConfig.Visibility.ON_CHANGE);
        k.slider("Hide when paused", "Hide the player this long after you pause (0 = never).", "", 0, 300, 5, cfg().hidePausedSeconds,
                v -> v == 0 ? "never" : secs(v), v -> cfg().hidePausedSeconds = (int) v);
        k.slider("Size", "Make the whole player bigger or smaller.", "", 0.5, 3.0, 0.1, cfg().scale, PagesLook::times,
                v -> cfg().scale = (float) v);
        k.slider("Opacity", "How see-through the player is.", "", 0.1, 1.0, 0.05, cfg().opacity, PagesLook::pct,
                v -> cfg().opacity = (float) v);
        k.slider("Fade out after", "The player fades when nothing happens for this long (0 = never).",
                "It returns on a new song, a button, pausing, or when you open any screen (chat too).", 0, 120, 5,
                cfg().fadeAfterSeconds, v -> v == 0 ? "never" : secs(v), v -> cfg().fadeAfterSeconds = (int) v);
        k.slider("Faded opacity", "How visible it stays while faded (0 = gone).", "", 0, 0.6, 0.05, cfg().fadedOpacity,
                v -> v == 0 ? "gone" : pct(v), v -> cfg().fadedOpacity = (float) v);
        String[] names = {"Top left", "Top mid", "Top right", "Bottom left", "Bottom mid", "Bottom right"};
        int[] ax = {SpotifyConfig.ANCHOR_START, SpotifyConfig.ANCHOR_CENTER, SpotifyConfig.ANCHOR_END,
                SpotifyConfig.ANCHOR_START, SpotifyConfig.ANCHOR_CENTER, SpotifyConfig.ANCHOR_END};
        List<AbstractWidget> places = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            places.add(Ui.button(0, 0, 50, 15, names[i], b -> {
                SpotifyConfig c = cfg();
                c.ax = ax[index];
                c.ay = index < 3 ? SpotifyConfig.ANCHOR_START : SpotifyConfig.ANCHOR_END;
                c.x = c.ax == SpotifyConfig.ANCHOR_CENTER ? 0 : 8;
                c.y = 8;
                ConfigIo.save();
            }).accent(k.accent()).tip("Put the player in this part of the screen."));
        }
        k.grid("Quick place", "Put the player in a corner. \"Move on screen\" below places it exactly.", "", places, 3);

        k.section("Mini bar", true);
        k.toggle("Mini bar", "A tiny bar shows the song for a moment when you press a music key.",
                "It replaces the text above the hotbar and hides again by itself.", () -> cfg().miniBar, v -> cfg().miniBar = v);
        k.slider("Mini bar time", "How long the mini bar stays.", "", 1, 8, 1, cfg().miniBarSeconds, PagesLook::secs,
                v -> cfg().miniBarSeconds = (int) v).enabled(() -> cfg().miniBar);

        k.section("Player screen", true);
        k.cycle("Player screen", "The look of the screen the Open key brings up.", "Sleek = big cover and album colours. Classic = the compact panel.",
                LOOK_NAMES, () -> cfg().playerLook.ordinal(), () -> cfg().playerLook = cfg().playerLook.next(1));
        k.slider("Width", "How wide the Classic player screen is.", "", 280, 560, 10, cfg().panelW, PagesLook::px, v -> cfg().panelW = (int) v);
        k.slider("Height", "How tall the Classic player screen is.", "", 176, 340, 2, cfg().panelH, PagesLook::px, v -> cfg().panelH = (int) v);
        k.choice("Panel shape", "Rounded, cut corners (HUD look), a tech cut, or square.", "", () -> cfg().panelShape.label,
                () -> cfg().panelShape = cfg().panelShape.next(1));
        k.slider("Panel corners", "How round (or how deeply cut) the corners are.", "", 0, 32, 1, cfg().panelRadius, PagesLook::px,
                v -> cfg().panelRadius = (float) v);
        k.slider("Panel border", "The thickness of the line around the panel.", "", 0, 3, 0.5, cfg().panelBorder,
                v -> v == 0 ? "none" : String.format(Locale.ROOT, "%.1f px", v), v -> cfg().panelBorder = (float) v);
        k.cycle("Panel back", "Solid, glass, tinted with the album colour, or no box.", "", BACKGROUND_NAMES,
                () -> cfg().panelBack.ordinal(), () -> cfg().panelBack = cfg().panelBack.next(1));
        k.slider("Panel opacity", "How see-through the panel is.", "", 0.3, 1.0, 0.02, cfg().panelOpacity, PagesLook::pct,
                v -> cfg().panelOpacity = (float) v);
        k.toggle("Darken the game behind", "Dim the game while the player screen is open.", "", () -> cfg().panelDim, v -> cfg().panelDim = v);
        k.cycle("Lyrics in the player", "Show the lyrics inside the player screen.",
                "Off = only when you press the Lyrics button or L. Remember = it opens the way you left it. Always = it opens with the "
                        + "lyrics showing. They sit beside the player on a wide window, or under the song details on a tall one.",
                IN_PLAYER_NAMES, () -> cfg().lyrics.inPlayer.ordinal(), () -> cfg().lyrics.inPlayer = cfg().lyrics.inPlayer.next(1));
        List<AbstractWidget> panel = new ArrayList<>();
        panel.add(Ui.button(0, 0, 50, 15, "Open the player", b -> {
            ConfigIo.save();
            Intro.suppressNext();
            host.mc().setScreen(PlayerScreen.create());
        }).accent(k.accent()).primary().tip("See the panel for real."));
        panel.add(Ui.button(0, 0, 50, 15, "Reset panel", b -> {
            SpotifyConfig d = new SpotifyConfig();
            SpotifyConfig c = cfg();
            c.panelW = d.panelW;
            c.panelH = d.panelH;
            c.panelShape = d.panelShape;
            c.panelRadius = d.panelRadius;
            c.panelBorder = d.panelBorder;
            c.panelBack = d.panelBack;
            c.panelOpacity = d.panelOpacity;
            c.panelDim = d.panelDim;
            ConfigIo.save();
            host.rebuild();
        }).accent(k.accent()).tip("Back to the factory size and shape of the player screen."));
        k.buttons("Player screen tools", "See it for real, or put its size and shape back to normal.", "", panel);

        k.section("Controls", true);
        k.slider("Seek step", "How far the seek keys and the mouse wheel jump.", "", 1, 60, 1, cfg().seekStepSeconds, PagesLook::secs,
                v -> cfg().seekStepSeconds = (int) v);
        k.slider("Volume step", "How much the volume keys and the mouse wheel change the volume.", "", 1, 25, 1, cfg().volumeStep,
                v -> (int) v + "%", v -> cfg().volumeStep = (int) v);

        k.section("Spotify connection", true);
        k.cycle("Read from", "Spotify only, or whatever media player Windows says is active.",
                "Any player includes a browser, VLC and so on.", SOURCE_NAMES, () -> cfg().source.ordinal(), () -> {
                    cfg().source = cfg().source.next(1);
                    MediaBridge.send("source " + (cfg().source == SpotifyConfig.Source.ANY ? "any" : "spotify"));
                });
        k.toggle("Start the helper with the game", "The helper is a small hidden Windows program that reads the music player.", "",
                () -> cfg().startBridge, v -> cfg().startBridge = v);
        k.pair("Reconnect / reload cover", "Restart the helper if the player shows nothing, or read the cover and volume again.", "",
                k.buttonWidget("Reconnect", "Restart the helper if the player shows nothing.", false, MediaBridge::restart),
                k.buttonWidget("Reload cover", "Read the cover and volume again.", false, Player::refresh));
        SettingsRow helper = k.row("Helper status", "What the helper can see right now.", "");
        helper.controlH = 68;
        helper.draw = (g, f, x, y, w, mx, my) -> drawHelper(g, f, x, y, w);

        k.section("Song-change toast", true);
        k.toggle("Song-change toast", "A small card slides in with the title, artist and cover when a new song starts.", "",
                () -> cfg().toast, v -> cfg().toast = v);
        k.cycle("Show toast", "Only while the on-screen player is hidden, or on every song change.", "", TOAST_WHEN_NAMES,
                () -> cfg().toastWhen.ordinal(), () -> cfg().toastWhen = cfg().toastWhen.next(1)).enabled(() -> cfg().toast);
        k.choice("Corner", "Where the toast slides in.", "", () -> cfg().toastCorner.label, () -> cfg().toastCorner = cfg().toastCorner.next(1))
                .enabled(() -> cfg().toast);
        k.slider("Stays", "How long the toast stays.", "", 2, 15, 1, cfg().toastSeconds, PagesLook::secs, v -> cfg().toastSeconds = (int) v)
                .enabled(() -> cfg().toast);
        k.slider("Toast size", "How big the toast is.", "", 0.7, 1.6, 0.1, cfg().toastScale, PagesLook::times, v -> cfg().toastScale = (float) v)
                .enabled(() -> cfg().toast);
        k.toggle("Toast cover", "Show the album cover on the toast.", "", () -> cfg().toastCover, v -> cfg().toastCover = v)
                .enabled(() -> cfg().toast);
        k.button("Test the toast", "Shows it now, with your current song or a sample.", "", "Test the toast", false, Toast::test);

        k.section("Help card", true);
        k.toggle("Help card", "When no song is found, show a short checklist: is Spotify open, did you press play once...", "",
                () -> cfg().showHelp, v -> cfg().showHelp = v);
    }

    private static void drawHelper(net.minecraft.client.gui.GuiGraphics g, net.minecraft.client.gui.Font f, int x, int y, int w) {
        MediaBridge.State st = MediaBridge.state();
        String helper = switch (st) {
            case RUNNING -> "Helper: running";
            case STARTING -> "Helper: starting...";
            case FAILED -> "Helper: stopped";
            case UNSUPPORTED -> "Helper: needs Windows";
            default -> "Helper: off";
        };
        int helperColor = st == MediaBridge.State.RUNNING ? Theme.good()
                : st == MediaBridge.State.FAILED || st == MediaBridge.State.UNSUPPORTED ? Theme.bad() : Theme.dim();
        if (!MediaBridge.powerShellVersion().isEmpty() && st == MediaBridge.State.RUNNING) {
            helper += " (PowerShell " + MediaBridge.powerShellVersion() + ")";
        }
        Gfx.text(g, f, Gfx.fit(f, helper, w), x + 2, y + 1, helperColor, 1f, false);
        boolean running = st == MediaBridge.State.RUNNING;
        Gfx.text(g, f, running ? (MediaBridge.mediaSessionOk() ? "Windows media session: ready" : "Windows media session: not available")
                : "Windows media session: -", x + 2, y + 12, running && !MediaBridge.mediaSessionOk() ? Theme.bad() : Theme.soft(), 1f, false);
        String audio;
        if (!running) audio = "Volume and music level: -";
        else if (!MediaBridge.volumeOk()) audio = "Volume and music level: not available";
        else audio = "Volume: available  ·  Music level: " + (Beat.hearing(System.nanoTime()) ? "live" : Beat.live() ? "reads silence (not hearing the player)" : "waiting");
        Gfx.text(g, f, Gfx.fit(f, audio, w), x + 2, y + 23, Theme.soft(), 1f, false);
        if (running && cfg().reactToMusic && com.zlorbie788.spotify.hud.Visual.reacting()) {
            float bpm = com.zlorbie788.spotify.hud.Visual.bpm();
            audio = "Following: " + com.zlorbie788.spotify.hud.Visual.following() + (bpm > 0 ? "  ·  " + Math.round(bpm) + " BPM" : "");
            Gfx.text(g, f, Gfx.fit(f, audio, w), x + 2, y + 56, Theme.soft(), 1f, false);
        }
        String cover = Covers.status();
        boolean coverBad = !cover.equals("loaded") && !cover.startsWith("waiting") && !cover.equals("none");
        Gfx.text(g, f, Gfx.fit(f, "Cover art: " + cover, w), x + 2, y + 34, coverBad ? Theme.bad() : Theme.soft(), 1f, false);
        List<String> log = MediaBridge.recentLog();
        String last = !MediaBridge.problem().isEmpty() ? MediaBridge.problem() : (log.isEmpty() ? "" : log.get(log.size() - 1));
        if (!last.isEmpty()) Gfx.text(g, f, Gfx.fit(f, last, w), x + 2, y + 45, Theme.bad(), 1f, false);
    }
}
