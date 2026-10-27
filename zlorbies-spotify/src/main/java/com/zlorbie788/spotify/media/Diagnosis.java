package com.zlorbie788.spotify.media;

import com.zlorbie788.spotify.config.SpotifyConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "Why do I see no song?" The short checklist the player shows when it cannot see Spotify: Windows, the helper, the
 * Windows media session, whether Spotify is open, whether a song was played once, and which player is read.
 * The pure part ({@link #build}) takes plain values so it can be tested; {@link #checks} reads the live state.
 */
public final class Diagnosis {
    public enum Level { OK, BAD, INFO }

    public record Check(Level level, String label, String hint) {}

    private static volatile Boolean spotifyRunning;
    private static volatile long checkedAt;
    private static volatile boolean checking;

    private Diagnosis() {}

    /** True when the checklist is worth showing: no song, and the helper has had time to connect. */
    public static boolean needed(SpotifyConfig cfg) {
        if (!cfg.showHelp) return false;
        if (Player.snap().active()) return false;
        if (com.zlorbie788.spotify.local.Sources.local() || com.zlorbie788.spotify.local.Sources.dropHere()) return false;
        MediaBridge.State st = MediaBridge.state();
        if (st == MediaBridge.State.STARTING) return false;
        return true;
    }

    /** The checklist for the live state. */
    public static List<Check> checks(SpotifyConfig cfg) {
        refreshSpotifyProcess();
        return build(MediaBridge.isWindows(), MediaBridge.state(), MediaBridge.problem(), MediaBridge.mediaSessionOk(),
                spotifyRunning, Player.snap().active(), cfg.source);
    }

    /** One line for places with little room: the most useful next step. */
    public static String nextStep(SpotifyConfig cfg) {
        for (Check c : checks(cfg)) {
            if (c.level() == Level.BAD) return c.hint();
        }
        return cfg.source == SpotifyConfig.Source.SPOTIFY ? "Open Spotify and press play" : "Start any media player";
    }

    public static List<Check> build(boolean windows, MediaBridge.State state, String problem, boolean mediaSession,
                                    Boolean spotifyOpen, boolean songActive, SpotifyConfig.Source source) {
        List<Check> list = new ArrayList<>();
        if (!windows) {
            list.add(new Check(Level.BAD, "Windows",
                    "This mod reads the Windows media session, so it only works on Windows 10 or 11."));
            return list;
        }
        list.add(new Check(Level.OK, "Windows", ""));
        switch (state) {
            case RUNNING -> list.add(new Check(Level.OK, "Windows helper running", ""));
            case FAILED -> list.add(new Check(Level.BAD, "Windows helper stopped",
                    problem == null || problem.isBlank() ? "Press Reconnect in the settings." : problem));
            case STARTING -> list.add(new Check(Level.INFO, "Windows helper is starting", "Give it a few seconds."));
            case UNSUPPORTED -> list.add(new Check(Level.BAD, "Windows helper", "Needs Windows 10 or 11."));
            default -> list.add(new Check(Level.BAD, "Windows helper is off",
                    "Turn it on in Settings > Player, then press Reconnect."));
        }
        if (state == MediaBridge.State.RUNNING) {
            list.add(mediaSession
                    ? new Check(Level.OK, "Windows media session found", "")
                    : new Check(Level.BAD, "Windows media session missing",
                    "Update Windows 10 to version 1809 or newer and press Reconnect."));
        }
        if (spotifyOpen == null) {
            list.add(new Check(Level.INFO, "Is Spotify open?", "Open the Spotify app (desktop or Microsoft Store)."));
        } else if (spotifyOpen) {
            list.add(new Check(Level.OK, "Spotify is open", ""));
        } else {
            list.add(new Check(Level.BAD, "Spotify is not open",
                    "Open Spotify (desktop app or Microsoft Store)."));
        }
        if (!songActive) {
            list.add(new Check(Level.INFO, "Press play once in Spotify",
                    "Windows lists Spotify only after a song has played."));
        }
        if (source == SpotifyConfig.Source.SPOTIFY) {
            list.add(new Check(Level.INFO, "Using a browser or another player?",
                    "Set Settings > Player > Read from: Any player."));
        }
        return list;
    }

    /** True / false once known, null while it has not been checked (or cannot be). */
    public static Boolean spotifyOpen() {
        refreshSpotifyProcess();
        return spotifyRunning;
    }

    private static void refreshSpotifyProcess() {
        long now = System.currentTimeMillis();
        if (checking || now - checkedAt < 5000L) return;
        checking = true;
        checkedAt = now;
        Thread t = new Thread(() -> {
            try {
                boolean found = ProcessHandle.allProcesses().anyMatch(p -> p.info().command()
                        .map(c -> c.toLowerCase(Locale.ROOT).endsWith("spotify.exe")).orElse(false));
                spotifyRunning = found;
            } catch (RuntimeException e) {
                spotifyRunning = null;
            } finally {
                checking = false;
            }
        }, "spotify-integration-diagnosis");
        t.setDaemon(true);
        t.start();
    }
}
