package com.zlorbie788.spotify.local;

import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.config.SpotifyConfig;
import com.zlorbie788.spotify.media.MediaBridge;
import com.zlorbie788.spotify.media.Snapshot;

/**
 * Decides what the player shows and controls: Spotify (through the Windows media session) or your own music folder.
 * <ul>
 *   <li><b>Auto</b> (the default): Spotify when it is running; your folder when it is not; and when you start a song
 *   from the other one, the player follows you and pauses the one you left.</li>
 *   <li><b>Spotify</b> / <b>My music</b>: fixed, nothing switches by itself.</li>
 * </ul>
 */
public final class Sources {
    public enum Active { SPOTIFY, LOCAL }

    private static final long GRACE_NS = 3_000_000_000L;
    private static final long SETTLE_NS = 3_000_000_000L;

    private static Active active = Active.SPOTIFY;
    private static boolean decided;
    private static boolean spotifyWasPlaying;
    private static boolean spotifyWasActive;
    private static long ignoreSpotifyUntil;
    private static long spotifyGoneSince;
    private static long startedAt = System.nanoTime();

    /** For tests: replaces the clock and the Spotify reading. */
    static java.util.function.LongSupplier clock = System::nanoTime;
    static java.util.function.Supplier<Snapshot> spotify = MediaBridge::snapshot;
    static java.util.function.Consumer<String> bridgeCommand = MediaBridge::send;

    private Sources() {}

    public static Active active() {
        return active;
    }

    public static boolean local() {
        return active == Active.LOCAL;
    }

    /** True when the folder has songs the player can offer. */
    public static boolean libraryHasSongs() {
        return !MusicLibrary.isEmpty();
    }

    /** For tests and a fresh start. */
    public static void reset() {
        active = Active.SPOTIFY;
        decided = false;
        spotifyWasPlaying = false;
        spotifyWasActive = false;
        ignoreSpotifyUntil = 0L;
        spotifyGoneSince = 0L;
        startedAt = clock.getAsLong();
    }

    // ------------------------------------------------------------ per tick

    /** Once per client tick, before the player reads its snapshot. */
    public static void tick() {
        MusicLibrary.poll();
        SpotifyConfig cfg = ConfigIo.get();
        long now = clock.getAsLong();
        Snapshot sp = spotify.get();
        boolean spPlaying = sp.playing();
        boolean spActive = sp.active();

        switch (cfg.sourceMode) {
            case SPOTIFY -> {
                if (active != Active.SPOTIFY) switchTo(Active.SPOTIFY, false);
            }
            case LOCAL -> {
                if (active != Active.LOCAL) switchTo(Active.LOCAL, false);
            }
            default -> auto(now, sp, spPlaying, spActive);
        }
        spotifyWasPlaying = spPlaying;
        spotifyWasActive = spActive;
        LocalPlayer.tick();
    }

    private static void auto(long now, Snapshot sp, boolean spPlaying, boolean spActive) {
        MediaBridge.State st = MediaBridge.state();
        boolean bridgeSettled = st == MediaBridge.State.RUNNING || st == MediaBridge.State.FAILED
                || st == MediaBridge.State.UNSUPPORTED || now - startedAt > 6 * SETTLE_NS;
        if (!decided) {
            if (spActive) {
                decided = true;
                active = Active.SPOTIFY;
            } else if (bridgeSettled && now - startedAt > SETTLE_NS && MusicLibrary.ready()) {
                decided = true;
                active = MusicLibrary.isEmpty() ? Active.SPOTIFY : Active.LOCAL;
            }
            return;
        }
        boolean graceOver = now > ignoreSpotifyUntil;
        if (active == Active.LOCAL) {
            boolean rose = spPlaying && !spotifyWasPlaying;
            boolean appeared = spActive && !spotifyWasActive && !LocalPlayer.playing();
            if (graceOver && (rose || appeared)) {
                switchTo(Active.SPOTIFY, false);
                LocalPlayer.pauseQuiet();
            }
        } else {
            if (spActive) {
                spotifyGoneSince = 0L;
            } else if (bridgeSettled && !MusicLibrary.isEmpty()) {
                if (spotifyGoneSince == 0L) spotifyGoneSince = now;
                if (now - spotifyGoneSince > SETTLE_NS) switchTo(Active.LOCAL, false);
            }
        }
    }

    // ------------------------------------------------------------ switching

    /** The person picked a source (the chips in the player). Pauses the one that is left. */
    public static void choose(Active wanted) {
        if (wanted == active) return;
        switchTo(wanted, true);
    }

    private static void switchTo(Active wanted, boolean user) {
        Active old = active;
        active = wanted;
        decided = true;
        spotifyGoneSince = 0L;
        if (old == wanted) return;
        if (wanted == Active.LOCAL) {
            if (user) {
                if (spotify.get().playing()) bridgeCommand.accept("pause");
                ignoreSpotifyUntil = clock.getAsLong() + GRACE_NS;
            }
        } else {
            LocalPlayer.pauseQuiet();
        }
    }

    /** A local song was started by the person: Spotify is paused, and the player follows. */
    static void localStarted() {
        long now = clock.getAsLong();
        SpotifyConfig cfg = ConfigIo.get();
        // The person just asked for one of their own songs: a source that was fixed to Spotify would undo it at once.
        if (cfg.sourceMode == SpotifyConfig.SourceMode.SPOTIFY) cfg.sourceMode = SpotifyConfig.SourceMode.AUTO;
        if (active != Active.LOCAL) {
            active = Active.LOCAL;
            decided = true;
        }
        if (spotify.get().playing()) bridgeCommand.accept("pause");
        ignoreSpotifyUntil = now + GRACE_NS;
    }

    // ------------------------------------------------------------ for the screens

    /**
     * What the empty player should say when nothing is playing:
     * <ul><li>{@code HAVE_SONGS}: the folder has songs;</li><li>{@code FOLDER_EMPTY}: no Spotify and no songs yet.</li></ul>
     */
    public enum Idle { HAVE_SONGS, FOLDER_EMPTY }

    /**
     * True when the player has nothing at all to offer yet: no Spotify song, no song of your own loaded and an empty
     * music folder. The player then shows the friendly "drop songs here" card instead of the Spotify checklist.
     */
    public static boolean dropHere() {
        if (LocalPlayer.hasCurrent() || !MusicLibrary.ready() || !MusicLibrary.isEmpty()) return false;
        if (spotify.get().active()) return false;
        MediaBridge.State st = MediaBridge.state();
        return st != MediaBridge.State.FAILED && st != MediaBridge.State.STARTING;
    }

    public static Idle idle() {
        return MusicLibrary.isEmpty() ? Idle.FOLDER_EMPTY : Idle.HAVE_SONGS;
    }
}
