package com.zlorbie788.spotify.catalog;

import com.zlorbie788.spotify.client.SpotifyClient;
import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.config.SpotifyConfig;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Searches the public music catalogues (Deezer, then Apple Music as the backup). Nothing here needs an account or
 * a log-in. Searches run on one worker thread; the screen holds the latest {@link Result} and reads it each frame.
 */
public final class Catalog {
    public enum Mode { SONGS, ALBUMS, TRACKS_OF, TRENDING }

    /** A search in progress or finished. Fields are written once by the worker; read them after {@link #done}. */
    public static final class Result {
        public final long id;
        public final Mode mode;
        public final String query;
        public final Item parent;
        public volatile boolean done;
        public volatile List<Item> items = List.of();
        /** Which catalogue answered ("Deezer", "Apple Music"), or empty. */
        public volatile String source = "";
        /** Plain-words problem, or empty. */
        public volatile String error = "";

        Result(long id, Mode mode, String query, Item parent) {
            this.id = id;
            this.mode = mode;
            this.query = query;
            this.parent = parent;
        }
    }

    private record Page(List<Item> items, String source, long at) {}

    private static final long TTL_MS = 10 * 60_000L;
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "spotify-integration-search");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicLong IDS = new AtomicLong();
    private static final AtomicLong LATEST = new AtomicLong();
    private static final Map<String, Page> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Page> eldest) {
            return size() > 60;
        }
    };

    private Catalog() {}

    /** Starts a search and returns at once; poll {@link Result#done}. A newer search makes older ones give up. */
    public static Result submit(Mode mode, String query, Item parent) {
        String q = query == null ? "" : query.strip();
        Result r = new Result(IDS.incrementAndGet(), mode, q, parent);
        LATEST.set(r.id);
        SpotifyConfig cfg = ConfigIo.get();
        if (!cfg.onlineSearch) {
            r.error = "Online search is turned off in the settings (Search tab).";
            r.done = true;
            return r;
        }
        if ((mode == Mode.SONGS || mode == Mode.ALBUMS) && q.isEmpty()) {
            r.done = true;
            return r;
        }
        POOL.submit(() -> run(r, cfg.searchSource, cfg.searchResults));
        return r;
    }

    /** Blocking search for worker threads (used for covers and exact lookups). Returns an empty list on failure. */
    public static List<Item> songsNow(String query, int limit) {
        try {
            return query(Mode.SONGS, query, null, limit, ConfigIo.get().searchSource).items();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static void run(Result r, SpotifyConfig.SearchSource preferred, int limit) {
        try {
            if (r.id != LATEST.get()) {
                r.error = "cancelled";
                return;
            }
            Page p = query(r.mode, r.query, r.parent, limit, preferred);
            r.items = p.items();
            r.source = p.source();
        } catch (IOException e) {
            r.error = "Could not reach the music catalogues (" + e.getMessage() + "). Check your internet connection.";
            SpotifyClient.LOGGER.info("[Spotify Integration Mod] search failed: {}", e.getMessage());
        } catch (RuntimeException e) {
            r.error = "Search failed: " + e;
            SpotifyClient.LOGGER.warn("[Spotify Integration Mod] search failed", e);
        } finally {
            r.done = true;
        }
    }

    private static Page query(Mode mode, String q, Item parent, int limit, SpotifyConfig.SearchSource preferred)
            throws IOException {
        String key = mode + "|" + preferred + "|" + limit + "|" + (parent == null ? "" : parent.key()) + "|" + q.toLowerCase();
        synchronized (CACHE) {
            Page hit = CACHE.get(key);
            if (hit != null && System.currentTimeMillis() - hit.at() < TTL_MS) return hit;
        }
        List<Boolean> order = new ArrayList<>(); // true = Deezer first
        switch (preferred) {
            case ITUNES -> order.addAll(List.of(false, true));
            default -> order.addAll(List.of(true, false));
        }
        if (mode == Mode.TRENDING) order = List.of(true);
        if (mode == Mode.TRACKS_OF && parent != null) {
            order = List.of(parent.provider() == Item.Provider.ITUNES ? false : true);
        }
        IOException last = null;
        for (boolean deezer : order) {
            try {
                List<Item> items = switch (mode) {
                    case SONGS -> deezer ? Deezer.songs(q, limit) : Itunes.songs(q, limit);
                    case ALBUMS -> deezer ? Deezer.albums(q, limit) : Itunes.albums(q, limit);
                    case TRACKS_OF -> deezer ? Deezer.albumTracks(parent) : Itunes.albumTracks(parent);
                    case TRENDING -> Deezer.chart(limit);
                };
                Page page = new Page(items, deezer ? "Deezer" : "Apple Music", System.currentTimeMillis());
                // An empty answer from the first catalogue still counts; only errors move on to the backup.
                synchronized (CACHE) {
                    CACHE.put(key, page);
                }
                return page;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last == null ? new IOException("no catalogue") : last;
    }

    /** The best match for a song you are listening to, or null. Worker threads only. */
    public static Item bestMatch(String title, String artist) {
        String wantTitle = Item.norm(com.zlorbie788.spotify.lyrics.Lyrics.cleanTitle(title));
        String wantArtist = Item.norm(com.zlorbie788.spotify.lyrics.Lyrics.firstArtist(artist));
        Item best = null;
        int bestScore = -1;
        for (Item it : songsNow(com.zlorbie788.spotify.lyrics.Lyrics.cleanTitle(title) + " "
                + com.zlorbie788.spotify.lyrics.Lyrics.firstArtist(artist), 8)) {
            String t = Item.norm(com.zlorbie788.spotify.lyrics.Lyrics.cleanTitle(it.title()));
            String a = Item.norm(it.artist());
            int score = 0;
            if (t.equals(wantTitle)) score += 4;
            else if (!wantTitle.isEmpty() && (t.contains(wantTitle) || wantTitle.contains(t))) score += 2;
            if (!wantArtist.isEmpty() && (a.contains(wantArtist) || wantArtist.contains(a))) score += 3;
            if (score > bestScore) {
                bestScore = score;
                best = it;
            }
        }
        return bestScore >= 4 ? best : null;
    }
}
