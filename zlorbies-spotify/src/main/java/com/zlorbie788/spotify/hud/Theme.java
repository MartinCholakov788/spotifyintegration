package com.zlorbie788.spotify.hud;

import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.config.SpotifyConfig;
import com.zlorbie788.spotify.config.SpotifyConfig.UiTheme;
import com.zlorbie788.spotify.util.Colors;

/**
 * The colours of the player's own screens: five presets (the "Interface" tab of the settings), switched to a stark black and
 * white set by "High contrast".
 */
public final class Theme {
    private Theme() {}

    /** {surface, well, raised, line} of every preset. */
    private static final int[][] SETS = {
            {0xFF111729, 0xFF0A0F1C, 0xFF1B2440, 0xFF2B365A}, // midnight
            {0xFF16181D, 0xFF0D0F12, 0xFF23262D, 0xFF3A3F4B}, // graphite
            {0xFF0E1D2B, 0xFF08121C, 0xFF16304A, 0xFF24506F}, // ocean
            {0xFF0F0B1E, 0xFF080514, 0xFF1D1438, 0xFF4B2F8A}, // neon
            {0xFF1F1117, 0xFF140A0F, 0xFF33192A, 0xFF5E2A44}, // rose
    };

    public static boolean highContrast() {
        return ConfigIo.get().highContrast;
    }

    private static int[] set() {
        UiTheme t = ConfigIo.get().uiTheme;
        return SETS[t == null ? 0 : t.ordinal()];
    }

    public static int surface() {
        return highContrast() ? 0xFF000000 : set()[0];
    }

    public static int well() {
        return highContrast() ? 0xFF000000 : set()[1];
    }

    public static int raised() {
        return highContrast() ? 0xFF1F1F1F : set()[2];
    }

    public static int line() {
        return highContrast() ? 0xFF8A8A8A : set()[3];
    }

    public static int text() {
        return Colors.WHITE;
    }

    public static int soft() {
        return highContrast() ? Colors.WHITE : Colors.TEXT_SOFT;
    }

    public static int dim() {
        return highContrast() ? 0xFFE6E6E6 : Colors.TEXT_DIM;
    }

    public static int good() {
        return Colors.MINT;
    }

    public static int bad() {
        return Colors.RED;
    }

    /** Corner size of buttons, switches and boxes. */
    public static float radius() {
        return ConfigIo.get().uiRadius;
    }

    /** The highlight colour for something that has {@code own} as its colour (a tab, say), after the "Highlight colour" setting. */
    public static int accent(int own) {
        SpotifyConfig cfg = ConfigIo.get();
        if (cfg.uiAccent == SpotifyConfig.UiAccent.ALBUM) return PlayerHud.smoothAccent();
        if (cfg.uiAccent == SpotifyConfig.UiAccent.CUSTOM) return Colors.opaque(cfg.uiColor);
        return own;
    }
}
