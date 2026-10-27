package com.zlorbie788.spotify.lyrics;

import com.google.gson.JsonObject;
import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.local.MusicLibrary;
import com.zlorbie788.spotify.local.Track;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * "Fetch lyrics for the whole folder": looks the lyrics up once for every song that has none of its own, so they are
 * ready (and work offline) later. They are kept in the mod's own cache, nothing is written next to your songs. One request
 * at a time with a short pause, and it can be stopped at any moment.
 */
public final class LyricsBulk {
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static volatile boolean stop;
    private static volatile int total;
    private static volatile int done;
    private static volatile int found;
    private static volatile String last = "";
    private static volatile String problem = "";

    private LyricsBulk() {}

    public static boolean running() {
        return RUNNING.get();
    }

    public static int total() {
        return total;
    }

    public static int done() {
        return done;
    }

    public static int found() {
        return found;
    }

    public static String problem() {
        return problem;
    }

    /** A sentence for the screens ("" when nothing has run). */
    public static String status() {
        if (running()) return "Fetching lyrics: " + done + " of " + total + " (" + found + " found)" + (last.isEmpty() ? "" : " - " + last);
        if (total == 0) return "";
        if (!problem.isEmpty()) return "Lyrics fetch stopped: " + problem + " (" + found + " found)";
        return "Lyrics fetch finished: " + found + " found for " + total + " songs";
    }

    public static void stop() {
        stop = true;
    }

    /** Starts the job. Returns false when it already runs, or online lookup is switched off. */
    public static boolean start() {
        if (!ConfigIo.get().lyrics.online) {
            problem = "online lyrics are switched off in the settings";
            total = Math.max(total, 1);
            return false;
        }
        if (!RUNNING.compareAndSet(false, true)) return false;
        stop = false;
        problem = "";
        total = 0;
        done = 0;
        found = 0;
        last = "";
        Thread t = new Thread(LyricsBulk::run, "spotify-integration-lyrics-bulk");
        t.setDaemon(true);
        t.start();
        return true;
    }

    private static void run() {
        try {
            List<Track> todo = new ArrayList<>();
            for (Track t : MusicLibrary.all()) {
                if (t.lyrics || t.title.isBlank()) continue;
                if (LocalLyrics.find(t) != null) continue;
                if (Lyrics.cached(Lyrics.keyFor(t.title, t.artist, t.durMs))) continue;
                todo.add(t);
            }
            total = todo.size();
            int failures = 0;
            for (Track t : todo) {
                if (stop) {
                    problem = "stopped";
                    return;
                }
                last = t.title;
                try {
                    JsonObject o = Lyrics.lookup(t.title, t.artist, t.album, t.durMs);
                    if (o != null) {
                        Lyrics.store(Lyrics.keyFor(t.title, t.artist, t.durMs), o);
                        found++;
                    }
                    failures = 0;
                } catch (Exception e) {
                    if (++failures >= 4) {
                        problem = "the lyrics service did not answer";
                        return;
                    }
                }
                done++;
                try {
                    Thread.sleep(350L);
                } catch (InterruptedException ie) {
                    return;
                }
            }
            last = "";
        } finally {
            RUNNING.set(false);
        }
    }
}
