package com.zlorbie788.spotify.local;

import com.zlorbie788.spotify.catalog.Catalog;
import com.zlorbie788.spotify.catalog.Item;
import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.config.SpotifyConfig;
import com.zlorbie788.spotify.util.Http;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * "Fix tags online": a song of your own that has no artist, album or cover gets them from the public music catalogues.
 * The answers are kept by the mod (in its own index) - <b>the song files are never changed</b>. It runs for the song that
 * plays, and for the whole folder on request. Switch it off with {@code fixTagsOnline} (Settings).
 */
public final class TagFixer {
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "spotify-integration-tagfix");
        t.setDaemon(true);
        return t;
    });
    private static final Set<String> TRIED = new HashSet<>();
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static volatile boolean stop;
    private static volatile int total;
    private static volatile int done;
    private static volatile int fixed;

    private TagFixer() {}

    /** True when the song lacks something the internet could fill in. */
    public static boolean needsFix(Track t) {
        return t != null && (t.artist.isEmpty() || t.cover.isEmpty());
    }

    private static boolean allowed() {
        SpotifyConfig c = ConfigIo.get();
        return c.fixTagsOnline && c.onlineSearch;
    }

    /** Once per client tick: tidies up the song that plays (at most once per song and session). */
    public static void tick() {
        if (!allowed() || !Sources.local()) return;
        Track t = LocalPlayer.current();
        if (!needsFix(t)) return;
        synchronized (TRIED) {
            if (!TRIED.add(t.id)) return;
        }
        POOL.submit(() -> {
            try {
                fixOne(t);
            } catch (Throwable ignored) {
                // best effort
            }
        });
    }

    /** Looks one song up and stores what was missing. Worker threads only. Returns true when something was added. */
    static boolean fixOne(Track t) throws Exception {
        Item best = Catalog.bestMatch(t.title, t.artist);
        if (best == null) return false;
        String artist = t.artist.isEmpty() ? best.artist() : t.artist;
        String album = t.album.isEmpty() ? best.album() : t.album;
        String cover = t.cover;
        if (cover.isEmpty()) {
            String url = best.picture(true);
            if (!url.isEmpty()) {
                byte[] bytes = Http.bytes(url, 3_000_000);
                if (bytes != null && bytes.length > 200) cover = MusicLibrary.storeCover(bytes);
            }
        }
        if (artist.equals(t.artist) && album.equals(t.album) && cover.equals(t.cover)) return false;
        Track now = MusicLibrary.byId(t.id);
        if (now == null) return false;
        MusicLibrary.fix(now, now.withTags(now.title, artist, album, cover));
        return true;
    }

    // ------------------------------------------------------------ the whole folder

    public static boolean running() {
        return RUNNING.get();
    }

    public static String status() {
        if (running()) return "Fixing tags: " + done + " of " + total + " (" + fixed + " completed)";
        return total == 0 ? "" : "Tag fix finished: " + fixed + " of " + total + " songs completed";
    }

    public static void stop() {
        stop = true;
    }

    /** Starts the folder job. Returns false when it already runs or the option is off. */
    public static boolean startAll() {
        if (!allowed() || !RUNNING.compareAndSet(false, true)) return false;
        stop = false;
        total = 0;
        done = 0;
        fixed = 0;
        Thread th = new Thread(TagFixer::runAll, "spotify-integration-tagfix-all");
        th.setDaemon(true);
        th.start();
        return true;
    }

    private static void runAll() {
        try {
            List<Track> todo = new ArrayList<>();
            for (Track t : MusicLibrary.all()) if (needsFix(t)) todo.add(t);
            total = todo.size();
            int failures = 0;
            for (Track t : todo) {
                if (stop) return;
                try {
                    if (fixOne(t)) fixed++;
                    failures = 0;
                } catch (Exception e) {
                    if (++failures >= 4) return;
                }
                done++;
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        } finally {
            RUNNING.set(false);
        }
    }
}
