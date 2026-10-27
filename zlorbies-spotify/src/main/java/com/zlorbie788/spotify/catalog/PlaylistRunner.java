package com.zlorbie788.spotify.catalog;

import com.zlorbie788.spotify.lyrics.Lyrics;
import com.zlorbie788.spotify.media.Player;
import com.zlorbie788.spotify.media.Snapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Plays one of your playlists song after song. Spotify itself only plays one song when it is given a link, so the
 * runner watches the Windows media session: when the song it started is nearly over it hands the next one to
 * {@link Playback}. If you start another song yourself (or pause for a long time) it stops quietly.
 * {@link #tick()} runs on the game thread.
 */
public final class PlaylistRunner {
    private static final long END_MARGIN_MS = 900L;
    private static final long GRACE_MS = 25_000L;

    private static final List<Item> QUEUE = new ArrayList<>();
    private static String name = "";
    private static int index = -1;
    private static boolean confirmed;
    private static boolean advanced;
    private static boolean loop;
    private static long startedAt;

    /** For tests: what the runner calls to start a song. */
    static java.util.function.Consumer<Item> starter = it -> {
        Library.played(it);
        Playback.play(it);
    };
    static java.util.function.LongSupplier clock = System::currentTimeMillis;

    private PlaylistRunner() {}

    public static boolean active() {
        return index >= 0;
    }

    public static String name() {
        return name;
    }

    public static int index() {
        return index;
    }

    public static int size() {
        return QUEUE.size();
    }

    public static boolean loop() {
        return loop;
    }

    public static void setLoop(boolean on) {
        loop = on;
    }

    /** The song that plays now (or null). */
    public static Item current() {
        return index >= 0 && index < QUEUE.size() ? QUEUE.get(index) : null;
    }

    /** Starts a playlist at a song (-1 = from the top). {@code shuffle} plays it in random order. */
    public static boolean start(String playlist, int from, boolean shuffle) {
        List<Item> songs = new ArrayList<>(Playlists.songs(playlist));
        if (songs.isEmpty()) return false;
        int first = Math.max(0, Math.min(songs.size() - 1, from));
        Item chosen = songs.get(first);
        if (shuffle) {
            songs.remove(first);
            Collections.shuffle(songs, new Random());
            songs.add(0, chosen);
            first = 0;
        }
        if (chosen.isFile()) return startLocal(songs, first, shuffle);
        QUEUE.clear();
        QUEUE.addAll(songs);
        name = playlist;
        playAt(first);
        return true;
    }

    /**
     * A playlist that starts with a song of the music folder is handed to the local player as a whole (it advances,
     * crossfades and shows the queue by itself). Songs that are not files are left out; songs whose file is gone too.
     */
    private static boolean startLocal(List<Item> songs, int first, boolean shuffle) {
        List<com.zlorbie788.spotify.local.Track> tracks = new ArrayList<>();
        int at = 0;
        for (int i = 0; i < songs.size(); i++) {
            com.zlorbie788.spotify.local.Track t = songs.get(i).track();
            if (t == null) continue;
            if (i == first) at = tracks.size();
            tracks.add(t);
        }
        if (tracks.isEmpty() || songs.get(first).track() == null) return false;
        stop();
        Library.played(songs.get(first));
        if (shuffle) {
            com.zlorbie788.spotify.local.LocalPlayer.setShuffle(true);
        }
        com.zlorbie788.spotify.local.LocalPlayer.playFrom(tracks, at);
        return true;
    }

    public static void stop() {
        index = -1;
        QUEUE.clear();
        name = "";
        confirmed = false;
        advanced = false;
    }

    /** Skip to the next song of the list now. */
    public static void next() {
        if (index < 0) return;
        advance();
    }

    private static void playAt(int i) {
        index = i;
        confirmed = false;
        advanced = false;
        startedAt = clock.getAsLong();
        starter.accept(QUEUE.get(i));
    }

    private static void advance() {
        int next = index + 1;
        if (next >= QUEUE.size()) {
            if (loop && !QUEUE.isEmpty()) next = 0;
            else {
                stop();
                return;
            }
        }
        playAt(next);
    }

    static boolean same(Snapshot s, Item it) {
        if (!s.active() || !it.isSong()) return false;
        return Item.norm(Lyrics.cleanTitle(s.title())).equals(Item.norm(Lyrics.cleanTitle(it.title())))
                && Item.norm(Lyrics.firstArtist(s.artist())).equals(Item.norm(Lyrics.firstArtist(it.artist())));
    }

    public static void tick() {
        if (index < 0) return;
        Snapshot s = Player.snap();
        tickWith(s, Player.position(), Player.duration(), Player.playing());
    }

    /** The logic of {@link #tick()} with the readings given (so it can be tested). */
    static void tickWith(Snapshot s, long pos, long dur, boolean playing) {
        if (index < 0) return;
        Item want = QUEUE.get(index);
        long now = clock.getAsLong();
        if (same(s, want)) {
            confirmed = true;
            if (!advanced && playing && dur > 8_000L && pos >= dur - END_MARGIN_MS) {
                advanced = true;
                advance();
            }
            return;
        }
        if (confirmed && s.active() && !advanced) {
            // another song is playing and it is not the one we started: you took over
            stop();
            return;
        }
        if (!confirmed && now - startedAt > GRACE_MS) {
            // the song never showed up (not found, Spotify closed...)
            stop();
        }
    }

    /** For tests. */
    static void reset() {
        stop();
        loop = false;
    }
}
