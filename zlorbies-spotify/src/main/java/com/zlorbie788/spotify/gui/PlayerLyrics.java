package com.zlorbie788.spotify.gui;

import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.config.LyricsConfig;

/**
 * Whether the lyrics are showing inside the full player screen. One answer for both looks (Classic and Sleek).
 * The setting {@link LyricsConfig#inPlayer} decides how a new visit starts: always without lyrics, with what you chose
 * last time, or always with lyrics. The Lyrics button and the L key switch them for the rest of the visit (and, in
 * "Remember my choice", for next time).
 */
public final class PlayerLyrics {
    private static boolean visit;

    private PlayerLyrics() {}

    /** The player screen is being opened (with the Open key or a command): decide how it starts. */
    public static void begin() {
        LyricsConfig lc = ConfigIo.get().lyrics;
        visit = switch (lc.inPlayer) {
            case ALWAYS -> true;
            case REMEMBER -> lc.playerOpen;
            default -> false;
        };
    }

    /** Show the lyrics for this visit only (the /spotify lyrics command). */
    public static void force() {
        visit = true;
    }

    public static boolean open() {
        return visit;
    }

    public static void set(boolean on) {
        visit = on;
        LyricsConfig lc = ConfigIo.get().lyrics;
        if (lc.inPlayer == LyricsConfig.InPlayer.REMEMBER) {
            lc.playerOpen = on;
            ConfigIo.save();
        }
    }

    public static void toggle() {
        set(!visit);
    }
}
