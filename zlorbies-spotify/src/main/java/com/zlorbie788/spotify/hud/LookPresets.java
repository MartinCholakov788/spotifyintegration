package com.zlorbie788.spotify.hud;

import com.zlorbie788.spotify.config.SpotifyConfig;
import com.zlorbie788.spotify.config.SpotifyConfig.Accent;
import com.zlorbie788.spotify.config.SpotifyConfig.Background;
import com.zlorbie788.spotify.config.SpotifyConfig.CoverShape;
import com.zlorbie788.spotify.config.SpotifyConfig.ShapeKind;
import com.zlorbie788.spotify.config.SpotifyConfig.Style;
import com.zlorbie788.spotify.config.SpotifyConfig.VizStyle;
import com.zlorbie788.spotify.config.UiFont;

/**
 * One-click complete looks (Settings > Look). A preset only changes how things look (style, shapes, effects, fonts):
 * where the player is, its size, the lyrics settings, the keys and everything about playback stay as they are.
 */
public final class LookPresets {
    private LookPresets() {}

    public static final String[] NAMES = {"Factory", "Neon HUD", "Clean", "Glass", "Retro",
            "Vinyl", "Mini", "Stream", "Cover"};

    public static final String[] TIPS = {
            "The look you started with.",
            "Cut corners, a live scope, corner brackets and a mono font: a sci-fi HUD.",
            "Calm and tidy: soft round corners, no extra effects, a neutral font.",
            "Big soft corners, frosted glass, a round cover and a rounded font.",
            "Sharp cut corners, thick border, plain bars and a mono font.",
            "A spinning record: the cover is a round disc with a groove ring and a soft glow.",
            "A one-liner: one thin line with cover, title and artist. Nothing else, almost no room used.",
            "A bold, clean card for streams: big round cover, calm colours, no flicker.",
            "Cover colour: the album cover paints everything: tint, glow, bars, menus and lyrics follow its colour.",
    };

    public static void apply(SpotifyConfig c, int index) {
        SpotifyConfig d = new SpotifyConfig();
        // start from the factory look, then bend it
        c.style = d.style;
        c.accent = d.accent;
        c.background = d.background;
        c.shape = d.shape;
        c.radius = d.radius;
        c.borderWidth = d.borderWidth;
        c.coverShape = d.coverShape;
        c.coverZoom = d.coverZoom;
        c.trimCover = d.trimCover;
        c.roundCover = d.roundCover;
        c.viz = d.viz;
        c.progressStyle = d.progressStyle;
        c.borderTrace = d.borderTrace;
        c.shine = d.shine;
        c.ambient = d.ambient;
        c.particles = d.particles;
        c.parallax = d.parallax;
        c.cornerBrackets = d.cornerBrackets;
        c.showGlow = d.showGlow;
        c.textShadow = d.textShadow;
        c.showEqualizer = d.showEqualizer;
        c.showAccentLine = d.showAccentLine;
        c.font = d.font;
        c.uiFont = d.uiFont;
        c.panelShape = d.panelShape;
        c.panelRadius = d.panelRadius;
        c.panelBorder = d.panelBorder;
        c.panelBack = d.panelBack;
        c.panelOpacity = d.panelOpacity;
        c.uiTheme = d.uiTheme;
        c.uiRadius = d.uiRadius;
        c.uiAccent = d.uiAccent;
        c.highContrast = d.highContrast;
        c.breathingGlow = d.breathingGlow;
        switch (index) {
            case 1 -> { // Neon HUD
                c.style = Style.SLEEK;
                c.shape = ShapeKind.TECH;
                c.radius = 12f;
                c.borderWidth = 1.5f;
                c.viz = VizStyle.SCOPE;
                c.cornerBrackets = true;
                c.coverShape = CoverShape.SOFT;
                c.font = UiFont.MONO;
                c.uiFont = UiFont.MONO;
                c.panelShape = ShapeKind.TECH;
                c.panelRadius = 14f;
                c.panelBorder = 1.5f;
                c.uiTheme = SpotifyConfig.UiTheme.NEON;
                c.uiRadius = 1f;
            }
            case 2 -> { // Clean
                c.style = Style.CARD;
                c.shape = ShapeKind.ROUND;
                c.radius = 14f;
                c.borderWidth = 0f;
                c.background = Background.SOLID;
                c.viz = VizStyle.MIRROR;
                c.borderTrace = false;
                c.shine = false;
                c.particles = false;
                c.showGlow = false;
                c.showAccentLine = false;
                c.parallax = false;
                c.font = UiFont.INTER;
                c.uiFont = UiFont.INTER;
                c.panelRadius = 16f;
                c.panelBorder = 0f;
                c.panelBack = Background.SOLID;
                c.panelOpacity = 1f;
                c.uiTheme = SpotifyConfig.UiTheme.GRAPHITE;
                c.uiRadius = 4f;
            }
            case 3 -> { // Glass
                c.style = Style.SLEEK;
                c.shape = ShapeKind.ROUND;
                c.radius = 20f;
                c.borderWidth = 1f;
                c.background = Background.GLASS;
                c.coverShape = CoverShape.ROUND;
                c.viz = VizStyle.WAVE;
                c.font = UiFont.POPPINS;
                c.uiFont = UiFont.POPPINS;
                c.panelRadius = 24f;
                c.panelBack = Background.GLASS;
                c.panelOpacity = 0.82f;
                c.uiTheme = SpotifyConfig.UiTheme.OCEAN;
                c.uiRadius = 6f;
            }
            case 4 -> { // Retro
                c.style = Style.CARD;
                c.shape = ShapeKind.CHAMFER;
                c.radius = 8f;
                c.borderWidth = 2f;
                c.background = Background.SOLID;
                c.coverShape = CoverShape.SQUARE;
                c.viz = VizStyle.BARS;
                c.borderTrace = false;
                c.shine = false;
                c.particles = false;
                c.ambient = false;
                c.font = UiFont.MONO;
                c.uiFont = UiFont.MONO;
                c.panelShape = ShapeKind.CHAMFER;
                c.panelRadius = 10f;
                c.panelBorder = 2f;
                c.panelBack = Background.SOLID;
                c.panelOpacity = 1f;
                c.uiTheme = SpotifyConfig.UiTheme.GRAPHITE;
                c.uiRadius = 0f;
            }
            case 5 -> { // Vinyl
                c.style = Style.VINYL;
                c.shape = ShapeKind.ROUND;
                c.radius = 16f;
                c.borderWidth = 1f;
                c.background = Background.TINTED;
                c.coverShape = CoverShape.CIRCLE;
                c.viz = VizStyle.DOTS;
                c.showGlow = true;
                c.font = UiFont.POPPINS;
                c.uiFont = UiFont.POPPINS;
                c.panelShape = ShapeKind.ROUND;
                c.panelRadius = 22f;
                c.panelBack = Background.TINTED;
                c.uiTheme = SpotifyConfig.UiTheme.ROSE;
                c.uiRadius = 6f;
            }
            case 6 -> { // One-liner
                c.style = Style.MINIMAL;
                c.shape = ShapeKind.ROUND;
                c.radius = 12f;
                c.borderWidth = 0f;
                c.background = Background.GLASS;
                c.coverShape = CoverShape.SOFT;
                c.viz = VizStyle.OFF;
                c.borderTrace = false;
                c.shine = false;
                c.particles = false;
                c.parallax = false;
                c.showEqualizer = false;
                c.showAccentLine = false;
                c.font = UiFont.INTER;
                c.uiFont = UiFont.INTER;
                c.panelRadius = 16f;
                c.panelBorder = 0f;
                c.uiTheme = SpotifyConfig.UiTheme.GRAPHITE;
                c.uiRadius = 4f;
            }
            case 7 -> { // Stream overlay
                c.style = Style.CARD;
                c.shape = ShapeKind.ROUND;
                c.radius = 18f;
                c.borderWidth = 0f;
                c.background = Background.FADE;
                c.coverShape = CoverShape.ROUND;
                c.viz = VizStyle.WAVE;
                c.borderTrace = false;
                c.shine = false;
                c.particles = false;
                c.parallax = false;
                c.cornerBrackets = false;
                c.showAccentLine = true;
                c.textShadow = true;
                c.font = UiFont.POPPINS;
                c.uiFont = UiFont.POPPINS;
                c.panelRadius = 20f;
                c.panelBorder = 0f;
                c.panelBack = Background.FADE;
                c.uiTheme = SpotifyConfig.UiTheme.MIDNIGHT;
                c.uiRadius = 6f;
            }
            case 8 -> { // Cover colour drives everything
                c.style = Style.SLEEK;
                c.shape = ShapeKind.ROUND;
                c.radius = 14f;
                c.borderWidth = 1f;
                c.accent = Accent.ALBUM;
                c.background = Background.TINTED;
                c.coverShape = CoverShape.SOFT;
                c.viz = VizStyle.MIRROR;
                c.showGlow = true;
                c.ambient = true;
                c.breathingGlow = true;
                c.panelBack = Background.TINTED;
                c.panelRadius = 18f;
                c.uiAccent = SpotifyConfig.UiAccent.ALBUM;
                c.uiRadius = 5f;
                if (c.lyrics != null) {
                    c.lyrics.currentTone = com.zlorbie788.spotify.config.LyricsConfig.Tone.ACCENT;
                    c.lyrics.back = com.zlorbie788.spotify.config.LyricsConfig.Back.GLASS;
                    c.lyrics.accentBar = true;
                }
            }
            default -> { }
        }
        c.sanitize();
    }
}
