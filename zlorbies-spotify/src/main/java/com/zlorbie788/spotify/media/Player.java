package com.zlorbie788.spotify.media;

import com.zlorbie788.spotify.local.LocalPlayer;
import com.zlorbie788.spotify.local.Sources;
import com.zlorbie788.spotify.util.Ease;

/**
 * The player as the rest of the mod sees it: smooth playback position, "what changed when" for the animations
 * and every command (play, seek, volume...). Commands also update the numbers shown right away, so the
 * interface never feels laggy while the real player catches up.
 */
public final class Player {
    private static final long HOLD_NANOS = 1_300_000_000L;

    private static String lastKey = "";
    private static long trackChangedAt = System.nanoTime();
    private static int trackSerial;
    private static long pausedSince;
    private static boolean wasPlaying;

    // Local overrides that last a moment after the user acts
    private static long seekTarget = -1;
    private static long seekUntil;
    private static int volTarget = -1;
    private static long volUntil;
    private static int muteTarget = -1;
    private static long muteUntil;
    private static int shuffleTarget = -1;
    private static long shuffleUntil;
    private static int repeatTarget = -1;
    private static long repeatUntil;
    private static int playTarget = -1;
    private static long playUntil;

    /** When something last happened that should bring a faded player back (a song change, a button, a pause). */
    private static long activityAt = System.nanoTime();

    private Player() {}

    /** Marks "something happened now": the on-screen player returns if it had faded out. */
    public static void poke() {
        activityAt = System.nanoTime();
    }

    /** Seconds since the last {@link #poke()}. */
    public static double idleSeconds() {
        return (System.nanoTime() - activityAt) / 1_000_000_000.0;
    }

    // ------------------------------------------------------------ per-frame update

    /** Call once per client tick (and it is cheap enough to call per frame). */
    public static void tick() {
        Snapshot s = snap();
        long now = System.nanoTime();
        String key = s.active() ? s.key() + "|" + s.title() : "";
        if (!key.equals(lastKey)) {
            if (s.active()) trackSerial++;
            lastKey = key;
            trackChangedAt = now;
            poke();
            seekTarget = -1;
        }
        boolean playing = playing();
        if (playing != wasPlaying) poke();
        if (wasPlaying && !playing) pausedSince = now;
        if (!wasPlaying && playing) pausedSince = 0L;
        if (!s.active()) pausedSince = 0L;
        wasPlaying = playing;
    }

    /** Increases every time a different song starts (also for the first one after startup). */
    public static int trackSerial() {
        return trackSerial;
    }

    /** Milliseconds since the song last changed. */
    public static long sinceTrackChange() {
        return (System.nanoTime() - trackChangedAt) / 1_000_000L;
    }

    /** Seconds since playback was paused, or 0 while playing / when nothing is loaded. */
    public static double pausedSeconds() {
        if (pausedSince == 0L || playing()) return 0.0;
        return (System.nanoTime() - pausedSince) / 1_000_000_000.0;
    }

    // ------------------------------------------------------------ readings

    /** The reading of the source in use: Spotify (Windows media session) or the local music folder. */
    public static Snapshot snap() {
        return Sources.local() ? LocalPlayer.snapshot() : MediaBridge.snapshot();
    }

    private static boolean local() {
        return Sources.local();
    }

    public static boolean playing() {
        long now = System.nanoTime();
        if (playTarget >= 0 && now < playUntil) return playTarget == 1;
        return snap().playing();
    }

    /** Current playback position in ms, advanced smoothly between readings. */
    public static long position() {
        Snapshot s = snap();
        long now = System.nanoTime();
        if (seekTarget >= 0 && now < seekUntil) return clampPos(seekTarget, s.durMs());
        long pos = s.posMs();
        if (playing() && s.at() != 0L) pos += (now - s.at()) / 1_000_000L;
        return clampPos(pos, s.durMs());
    }

    private static long clampPos(long pos, long dur) {
        if (pos < 0) return 0;
        if (dur > 0 && pos > dur) return dur;
        return pos;
    }

    public static long duration() {
        return snap().durMs();
    }

    /** 0..1 */
    public static float progress() {
        long d = duration();
        if (d <= 0) return 0f;
        return Ease.clamp01(position() / (float) d);
    }

    /** 0..100, or -1 when the volume of the player cannot be read. */
    public static int volume() {
        if (volTarget >= 0 && System.nanoTime() < volUntil) return volTarget;
        return snap().volume();
    }

    public static boolean muted() {
        if (muteTarget >= 0 && System.nanoTime() < muteUntil) return muteTarget == 1;
        return snap().mute();
    }

    public static boolean shuffle() {
        if (shuffleTarget >= 0 && System.nanoTime() < shuffleUntil) return shuffleTarget == 1;
        return snap().shuffle();
    }

    /** 0 = off, 1 = repeat the song, 2 = repeat the list. */
    public static int repeat() {
        if (repeatTarget >= 0 && System.nanoTime() < repeatUntil) return repeatTarget;
        return snap().repeat();
    }

    public static boolean canSeek() {
        Snapshot s = snap();
        return s.active() && s.canSeek() && s.durMs() > 0 && !s.isAd();
    }

    public static boolean canVolume() {
        if (local()) return true;
        return MediaBridge.volumeOk() && MediaBridge.snapshot().volume() >= 0;
    }

    // ------------------------------------------------------------ commands

    public static void toggle() {
        poke();
        if (local()) {
            LocalPlayer.toggle();
            LocalPlayer.refreshNow();
            return;
        }
        boolean now = playing();
        playTarget = now ? 0 : 1;
        playUntil = System.nanoTime() + HOLD_NANOS;
        MediaBridge.send("toggle");
    }

    public static void play() {
        poke();
        if (local()) {
            LocalPlayer.play();
            LocalPlayer.refreshNow();
            return;
        }
        playTarget = 1;
        playUntil = System.nanoTime() + HOLD_NANOS;
        MediaBridge.send("play");
    }

    public static void pause() {
        poke();
        if (local()) {
            LocalPlayer.pause();
            LocalPlayer.refreshNow();
            return;
        }
        playTarget = 0;
        playUntil = System.nanoTime() + HOLD_NANOS;
        MediaBridge.send("pause");
    }

    public static void next() {
        poke();
        if (local()) {
            LocalPlayer.next();
            LocalPlayer.refreshNow();
            return;
        }
        MediaBridge.send("next");
    }

    public static void previous() {
        poke();
        if (local()) {
            LocalPlayer.previous();
            LocalPlayer.refreshNow();
            return;
        }
        // Like every player: after a few seconds "previous" restarts the song, so a quick double press goes back.
        MediaBridge.send("prev");
    }

    public static void seekTo(long ms) {
        poke();
        long d = duration();
        long target = Math.max(0L, d > 0 ? Math.min(ms, Math.max(0L, d - 500L)) : ms);
        if (local()) {
            LocalPlayer.seekTo(target);
            LocalPlayer.refreshNow();
            return;
        }
        seekTarget = target;
        seekUntil = System.nanoTime() + HOLD_NANOS;
        MediaBridge.send("seek " + target);
    }

    public static void seekBy(long deltaMs) {
        poke();
        seekTo(position() + deltaMs);
    }

    public static void setVolume(int percent) {
        poke();
        int v = Math.max(0, Math.min(100, percent));
        if (local()) {
            LocalPlayer.setVolume(v);
            LocalPlayer.refreshNow();
            return;
        }
        volTarget = v;
        volUntil = System.nanoTime() + HOLD_NANOS;
        MediaBridge.send("volume " + v);
        if (v > 0 && muted()) setMuted(false);
    }

    public static void volumeBy(int delta) {
        poke();
        int base = volume();
        if (base < 0) return;
        setVolume(base + delta);
    }

    public static void setMuted(boolean mute) {
        poke();
        if (local()) {
            LocalPlayer.setMuted(mute);
            LocalPlayer.refreshNow();
            return;
        }
        muteTarget = mute ? 1 : 0;
        muteUntil = System.nanoTime() + HOLD_NANOS;
        MediaBridge.send("mute " + (mute ? 1 : 0));
    }

    public static void toggleMute() {
        poke();
        setMuted(!muted());
    }

    public static void toggleShuffle() {
        poke();
        boolean next = !shuffle();
        if (local()) {
            LocalPlayer.setShuffle(next);
            LocalPlayer.refreshNow();
            return;
        }
        shuffleTarget = next ? 1 : 0;
        shuffleUntil = System.nanoTime() + HOLD_NANOS;
        MediaBridge.send("shuffle " + (next ? 1 : 0));
    }

    public static void cycleRepeat() {
        poke();
        int next = (repeat() + 1) % 3;
        if (local()) {
            LocalPlayer.setRepeat(next);
            LocalPlayer.refreshNow();
            return;
        }
        repeatTarget = next;
        repeatUntil = System.nanoTime() + HOLD_NANOS;
        MediaBridge.send("repeat " + next);
    }

    /** Re-reads the picture and volume (used by the "Reconnect" button). */
    public static void refresh() {
        if (local()) return;
        MediaBridge.send("refresh");
    }
}
