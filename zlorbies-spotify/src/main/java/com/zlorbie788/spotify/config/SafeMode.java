package com.zlorbie788.spotify.config;

import com.zlorbie788.spotify.hud.LookCode;

/**
 * Safe mode: when something in the look fails (a broken value, a font that cannot be loaded...), the failure is caught,
 * the player keeps working and the reason is kept here so it can be shown on screen with a one-click "Reset to safe look".
 */
public final class SafeMode {
    private static volatile String problem = "";
    private static volatile int errors;
    private static volatile long lastNs;
    private static volatile boolean dismissed;

    private SafeMode() {}

    /** Notes a failure. Only the newest reason is kept; it never throws. */
    public static void report(String what, Throwable t) {
        String reason = t == null ? "unknown" : t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
        if (reason.length() > 140) reason = reason.substring(0, 140) + "...";
        problem = what + " - " + reason;
        errors++;
        lastNs = System.nanoTime();
        dismissed = false;
        try {
            com.zlorbie788.spotify.client.SpotifyClient.LOGGER.warn("[Spotify Integration Mod] {} failed", what, t);
        } catch (Throwable ignored) { }
    }

    /** Runs {@code r}; a failure is reported instead of being thrown. Returns true when it worked. */
    public static boolean guard(String what, Runnable r) {
        try {
            r.run();
            return true;
        } catch (Throwable t) {
            report(what, t);
            return false;
        }
    }

    /** The reason of the latest failure, or "" when everything works (or it was dismissed). */
    public static String problem() {
        return dismissed ? "" : problem;
    }

    public static int errors() {
        return errors;
    }

    /** True when a failure happened in the last few seconds (the banner pulses). */
    public static boolean fresh() {
        return !problem().isEmpty() && System.nanoTime() - lastNs < 6_000_000_000L;
    }

    public static void dismiss() {
        dismissed = true;
    }

    public static void clear() {
        problem = "";
        errors = 0;
        dismissed = false;
    }

    /**
     * Puts the look back to the plain factory look (style, colours, fonts, effects, lyrics look) and switches off the
     * extras that can misbehave (moods, real audio capture, the intro). Positions, music, keys and profiles stay.
     */
    public static void resetLook(SpotifyConfig cfg) {
        LookCode.apply(cfg, LookCode.export(new SpotifyConfig()));
        SpotifyConfig d = new SpotifyConfig();
        cfg.animations = d.animations;
        cfg.panelW = d.panelW;
        cfg.panelH = d.panelH;
        cfg.panelDim = d.panelDim;
        cfg.mood = false;
        cfg.realAudio = false;
        cfg.introMode = SpotifyConfig.IntroMode.NEVER;
        cfg.sanitize();
        clear();
    }
}
