package com.zlorbie788.spotify.catalog;

import com.zlorbie788.spotify.client.SpotifyClient;
import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.lyrics.Lyrics;
import com.zlorbie788.spotify.media.Player;
import com.zlorbie788.spotify.media.Snapshot;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * "Play this song in Spotify", without any log-in. The steps: find the exact Spotify link of the chosen song,
 * hand it to the Spotify app, then watch the Windows media session until that song shows up and (if the setting
 * is on) press play for you. {@link #tick()} runs on the game thread.
 */
public final class Playback {
    public enum Phase { IDLE, FINDING, OPENING, WAITING, PLAYING, OPENED, FAILED }

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "spotify-integration-play");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicLong TICKET = new AtomicLong();

    private static volatile Phase phase = Phase.IDLE;
    private static volatile String message = "";
    private static volatile String shownTitle = "";

    // Watching state (game thread only)
    private static volatile boolean watch;
    private static volatile boolean exact;
    private static volatile String wantTitle = "";
    private static volatile String wantArtist = "";
    private static volatile long launchedAt;
    private static long matchedAt;
    private static long lastPress;
    private static int presses;
    private static boolean pressPending;

    private Playback() {}

    public static Phase phase() {
        return phase;
    }

    /** One line for the screens ("Finding the song on Spotify...", "Playing: ..."). Empty when idle. */
    public static String message() {
        return message;
    }

    public static boolean busy() {
        Phase p = phase;
        return p == Phase.FINDING || p == Phase.OPENING || p == Phase.WAITING;
    }

    public static void clear() {
        if (!busy()) {
            phase = Phase.IDLE;
            message = "";
        }
    }

    // ------------------------------------------------------------ starting

    /** Plays a song (an album plays its first song). */
    public static void play(Item item) {
        if (item.isFile()) {
            com.zlorbie788.spotify.local.Track t = item.track();
            if (t != null) {
                com.zlorbie788.spotify.local.LocalPlayer.playFrom(java.util.List.of(t), 0);
            } else {
                phase = Phase.FAILED;
                message = "\"" + item.title() + "\" is not in your music folder any more";
            }
            return;
        }
        long ticket = TICKET.incrementAndGet();
        watch = false;
        shownTitle = item.title();
        phase = Phase.FINDING;
        message = "Finding \"" + item.title() + "\" on Spotify...";
        POOL.submit(() -> {
            try {
                start(item, ticket);
            } catch (RuntimeException e) {
                SpotifyClient.LOGGER.warn("[Spotify Integration Mod] play failed", e);
                fail(ticket, "Something went wrong: " + e.getMessage());
            }
        });
    }

    /** Plays the first search result for the words (used by "/music play ..."). */
    public static void playQuery(String words) {
        long ticket = TICKET.incrementAndGet();
        watch = false;
        shownTitle = words;
        phase = Phase.FINDING;
        message = "Searching for \"" + words + "\"...";
        POOL.submit(() -> {
            try {
                SpotifyLink link = SpotifyLink.parse(words);
                if (link != null) {
                    if (ticket != TICKET.get()) return;
                    if (!Launcher.open(link.uri())) {
                        fail(ticket, "Could not open Spotify. Is it installed?");
                        return;
                    }
                    launched(ticket, words, "", link.playable(), link.playable());
                    return;
                }
                var cfg = com.zlorbie788.spotify.config.ConfigIo.get();
                boolean onlyPlayable = cfg.onlyPlayable && cfg.exactSpotify;
                var found = Catalog.songsNow(words, onlyPlayable ? 6 : 1);
                if (ticket != TICKET.get()) return;
                if (found.isEmpty()) {
                    fail(ticket, "Nothing found for \"" + words + "\".");
                    return;
                }
                Item pick = found.get(0);
                if (onlyPlayable) {
                    // the first result that really exists on Spotify (if the link service is down, the first one)
                    Item playable = null;
                    boolean undecided = false;
                    for (Item it : found) {
                        if (ticket != TICKET.get()) return;
                        Resolver.Found f = Resolver.resolve(it);
                        if (f.ok()) {
                            playable = it.withSpotify(f.uri());
                            break;
                        }
                        if (f.temporary()) undecided = true;
                    }
                    if (playable != null) pick = playable;
                    else if (!undecided) {
                        fail(ticket, "None of the results for \"" + words + "\" can be played directly in Spotify.");
                        return;
                    }
                }
                Library.played(pick);
                shownTitle = pick.title();
                start(pick, ticket);
            } catch (RuntimeException e) {
                fail(ticket, "Something went wrong: " + e.getMessage());
            }
        });
    }

    /** Opens an album (or any Spotify page) in the Spotify app without trying to start a song. */
    public static void openPage(Item item) {
        long ticket = TICKET.incrementAndGet();
        watch = false;
        shownTitle = item.title();
        phase = Phase.FINDING;
        message = "Finding \"" + item.title() + "\" on Spotify...";
        POOL.submit(() -> {
            Resolver.Found f = item.spotify().isEmpty() ? Resolver.resolve(item) : new Resolver.Found(item.spotify(), "");
            if (ticket != TICKET.get()) return;
            String uri = f.ok() ? f.uri() : Launcher.searchUri(item.title() + " " + item.artist());
            if (!Launcher.open(uri)) {
                fail(ticket, "Could not open Spotify. Is it installed?");
                return;
            }
            phase = Phase.OPENED;
            message = f.ok() ? "Opened \"" + item.title() + "\" in Spotify."
                    : "Opened a Spotify search for \"" + item.title() + "\".";
        });
    }

    /** Opens a pasted Spotify link: plays it when it is a song. */
    public static void playLink(SpotifyLink link, String label) {
        long ticket = TICKET.incrementAndGet();
        shownTitle = label;
        POOL.submit(() -> {
            if (!Launcher.open(link.uri())) {
                fail(ticket, "Could not open Spotify. Is it installed?");
                return;
            }
            launched(ticket, label, "", link.playable(), link.playable());
        });
    }

    private static void start(Item item, long ticket) {
        Item song = item;
        if (item.isAlbum()) {
            // Play the first song of the album.
            try {
                var tracks = Catalog.submit(Catalog.Mode.TRACKS_OF, "", item);
                long until = System.currentTimeMillis() + 12_000;
                while (!tracks.done && System.currentTimeMillis() < until) Thread.sleep(40);
                if (!tracks.items.isEmpty()) song = tracks.items.get(0);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (song.provider() == Item.Provider.LOCAL) {
            Item match = Catalog.bestMatch(song.title(), song.artist());
            if (match != null) song = match.withSpotify(song.spotify());
        }
        if (ticket != TICKET.get()) return;
        String uri = song.spotify();
        String note = "";
        if (uri.isEmpty() && ConfigIo.get().exactSpotify) {
            Resolver.Found f = Resolver.resolve(song);
            uri = f.uri();
            note = f.note();
            if (f.ok()) Library.learnSpotify(song, f.uri());
        }
        if (ticket != TICKET.get()) return;
        boolean isExact = !uri.isEmpty();
        if (!isExact) uri = Launcher.searchUri(song.title() + " " + song.artist());
        phase = Phase.OPENING;
        message = isExact ? "Opening in Spotify..." : "Opening a Spotify search...";
        if (!Launcher.open(uri)) {
            fail(ticket, "Could not open Spotify. Is it installed?");
            return;
        }
        if (!isExact) {
            // Nothing to watch for: the search page only lists the song.
            phase = Phase.OPENED;
            message = "Opened a Spotify search for \"" + song.title() + "\""
                    + (note.isEmpty() ? "." : " (" + note + "). Click the song there.");
            watch = false;
            return;
        }
        launched(ticket, song.title(), song.artist(), true, true);
    }

    private static void launched(long ticket, String title, String artist, boolean verify, boolean isExact) {
        if (ticket != TICKET.get()) return;
        wantTitle = title;
        wantArtist = artist;
        exact = isExact;
        presses = 0;
        pressPending = false;
        matchedAt = 0;
        lastPress = 0;
        launchedAt = System.currentTimeMillis();
        if (!verify) {
            phase = Phase.OPENED;
            message = "Opened in Spotify.";
            return;
        }
        phase = Phase.WAITING;
        message = "Waiting for Spotify to start \"" + title + "\"...";
        watch = true;
    }

    private static void fail(long ticket, String text) {
        if (ticket != TICKET.get()) return;
        watch = false;
        phase = Phase.FAILED;
        message = text;
    }

    // ------------------------------------------------------------ watching (game thread)

    public static void tick() {
        if (!watch) return;
        long now = System.currentTimeMillis();
        Snapshot s = Player.snap();
        if (matches(s)) {
            if (s.playing()) {
                watch = false;
                phase = Phase.PLAYING;
                message = "Playing: " + shownTitle;
                return;
            }
            if (matchedAt == 0) matchedAt = now;
            boolean auto = ConfigIo.get().autoPlay;
            if (auto && presses < 3 && now - matchedAt > 900 && now - lastPress > 2500) {
                presses++;
                lastPress = now;
                Player.play();
                message = "Starting \"" + shownTitle + "\"...";
            } else if (!auto && now - matchedAt > 1500) {
                watch = false;
                phase = Phase.OPENED;
                message = "\"" + shownTitle + "\" is ready in Spotify. Press play.";
            }
        }
        if (now - launchedAt > 25_000) {
            watch = false;
            phase = Phase.OPENED;
            message = "Spotify has the link open. If the song did not start, press play in Spotify.";
        }
    }

    private static boolean matches(Snapshot s) {
        if (!s.active() || s.isAd()) return false;
        String a = Item.norm(Lyrics.cleanTitle(s.title()));
        String b = Item.norm(Lyrics.cleanTitle(wantTitle));
        if (a.isEmpty() || b.isEmpty()) return false;
        boolean title = a.equals(b) || (a.length() >= 4 && b.length() >= 4 && (a.startsWith(b) || b.startsWith(a)));
        if (!title) return false;
        String wa = Item.norm(Lyrics.firstArtist(wantArtist));
        if (wa.isEmpty()) return true;
        String sa = Item.norm(s.artist());
        return sa.contains(wa) || wa.contains(sa) || sa.isEmpty();
    }

    /** For tests. */
    static void reset() {
        TICKET.incrementAndGet();
        watch = false;
        phase = Phase.IDLE;
        message = "";
    }
}
