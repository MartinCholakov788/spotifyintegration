package com.zlorbie788.spotify.catalog;

import com.zlorbie788.spotify.client.SpotifyClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Finds out, one result after the other, which songs of a search can really be played in the Spotify app, so the
 * search screen can hide the ones that cannot. The check is the same lookup that plays the song ({@link Resolver}),
 * so a song that shows up here also plays when you click it. Answers come in the order of the results; songs that
 * were looked up before are answered at once from the saved list. No account or log-in is involved.
 *
 * <p>When the public link service cannot be reached at all, {@link #serviceDown()} turns true and the screen shows
 * every result again (it would otherwise show nothing).
 */
public final class Verifier {
    public enum State { YES, NO, CHECKING, UNKNOWN }

    /** Result of a check: the Spotify link, or "" for "not on Spotify". */
    private static final Map<String, String> ANSWERS = new ConcurrentHashMap<>();
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "spotify-integration-verify");
        t.setDaemon(true);
        return t;
    });

    private static volatile int generation;
    private static volatile List<Item> wanted = List.of();
    private static volatile int checked;
    private static volatile boolean down;
    private static volatile boolean busy;
    private static volatile int waitLeft;
    private static volatile boolean running;
    private static int failures;
    /** For tests: the longest a check waits for a busy link service (ms). */
    static volatile long maxWaitMs = 70_000;

    private Verifier() {}

    // ------------------------------------------------------------ asking

    /** Asks about these results (in order). Anything an older call still had waiting is dropped. */
    public static void want(List<Item> items) {
        List<Item> copy = List.copyOf(items);
        for (Item it : copy) {
            String known = Resolver.peek(it);
            if (known != null) ANSWERS.put(it.key(), known);
        }
        int gen = ++generation;
        wanted = copy;
        checked = 0;
        busy = false;
        waitLeft = 0;
        for (Item it : copy) {
            if (!ANSWERS.containsKey(it.key())) {
                running = true;
                POOL.submit(() -> drain(gen));
                return;
            }
        }
        running = false;
    }

    public static State state(Item it) {
        if (!it.spotify().isEmpty()) return State.YES;
        String a = ANSWERS.get(it.key());
        if (a != null) return a.isEmpty() ? State.NO : State.YES;
        return wanted.contains(it) && running ? State.CHECKING : State.UNKNOWN;
    }

    /** The Spotify link a checked song has, or "". */
    public static String uri(Item it) {
        if (!it.spotify().isEmpty()) return it.spotify();
        String a = ANSWERS.get(it.key());
        return a == null ? "" : a;
    }

    /** True while there are results still waiting for their answer. */
    public static boolean pending() {
        return running;
    }

    /** True when the link service could not be reached: nothing can be checked, so nothing should be hidden. */
    public static boolean serviceDown() {
        return down;
    }

    /** True while the link service asked us to slow down. */
    public static boolean waiting() {
        return busy;
    }

    public static int waitSeconds() {
        return waitLeft;
    }

    /** How many of the wanted results have an answer (for the "Checking 4 of 12" line). */
    public static int answered() {
        int n = 0;
        for (Item it : wanted) {
            if (!it.spotify().isEmpty() || ANSWERS.containsKey(it.key())) n++;
        }
        return n;
    }

    public static int total() {
        return wanted.size();
    }

    /** Only the results that can be played (all of them while the service is down). */
    public static List<Item> playable(List<Item> all) {
        if (down) return all;
        java.util.ArrayList<Item> out = new java.util.ArrayList<>();
        for (Item it : all) {
            if (state(it) == State.YES) out.add(it);
        }
        return out;
    }

    // ------------------------------------------------------------ the worker

    private static void drain(int gen) {
        try {
            for (Item it : wanted) {
                if (gen != generation) return;
                if (ANSWERS.containsKey(it.key()) || !it.spotify().isEmpty()) continue;
                int tries = 0;
                while (gen == generation) {
                    Resolver.Found f = Resolver.resolve(it);
                    if (f.ok()) {
                        ANSWERS.put(it.key(), f.uri());
                        failures = 0;
                        down = false;
                        busy = false;
                        break;
                    }
                    if (f.missing()) {
                        ANSWERS.put(it.key(), "");
                        failures = 0;
                        down = false;
                        busy = false;
                        break;
                    }
                    if (!f.temporary()) {
                        ANSWERS.put(it.key(), "");
                        break;
                    }
                    int wait = Resolver.waitSeconds();
                    if (wait > 0) {
                        // the link service asked us to slow down: wait it out (the screen shows the countdown)
                        busy = true;
                        long until = System.currentTimeMillis() + Math.min(wait * 1000L, maxWaitMs);
                        while (gen == generation && System.currentTimeMillis() < until) {
                            waitLeft = (int) ((until - System.currentTimeMillis()) / 1000) + 1;
                            Thread.sleep(200);
                        }
                        busy = false;
                        waitLeft = 0;
                        if (++tries >= 4) break;
                        continue;
                    }
                    // not busy and not answering: the service could not be reached
                    if (++tries >= 2) {
                        if (++failures >= 2) down = true;
                        break;
                    }
                    Thread.sleep(400);
                }
                if (gen == generation) checked++;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            SpotifyClient.LOGGER.warn("[Spotify Integration Mod] verifying results failed", e);
        } finally {
            if (gen == generation) {
                running = false;
                busy = false;
                waitLeft = 0;
            }
        }
    }

    /** For tests: forget everything. */
    static void reset() {
        generation++;
        ANSWERS.clear();
        wanted = List.of();
        running = false;
        down = false;
        busy = false;
        failures = 0;
        waitLeft = 0;
        maxWaitMs = 70_000;
    }
}
