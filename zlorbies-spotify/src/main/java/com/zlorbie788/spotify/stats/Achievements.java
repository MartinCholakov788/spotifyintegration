package com.zlorbie788.spotify.stats;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zlorbie788.spotify.config.ConfigIo;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Small goals worked out from the listening statistics (nothing is sent anywhere). The list is fixed; whether a goal is
 * reached is simply read from the numbers, so it also works for songs played before the mod had achievements. The first
 * time a goal is noticed it is written to {@code achievements.json} with its date and announced once.
 */
public final class Achievements {
    public record Goal(String id, String title, String text, LongSupplier value, long target) {
        public long progress() {
            return Math.min(target, Math.max(0L, value.getAsLong()));
        }

        public boolean reached() {
            return value.getAsLong() >= target;
        }
    }

    private static final long HOUR = 3_600_000L;
    private static final List<Goal> GOALS = new ArrayList<>();
    private static final Map<String, Long> UNLOCKED = new LinkedHashMap<>();
    private static boolean loaded;
    private static boolean firstRun;
    private static int ticks;
    private static String announcement = "";
    private static Path override;
    static LongSupplier wall = System::currentTimeMillis;

    static {
        GOALS.add(new Goal("first", "First song", "Play your first song.", Stats::totalPlays, 1));
        GOALS.add(new Goal("plays10", "Warming up", "Play 10 songs.", Stats::totalPlays, 10));
        GOALS.add(new Goal("plays100", "On a roll", "Play 100 songs.", Stats::totalPlays, 100));
        GOALS.add(new Goal("plays1000", "Jukebox", "Play 1000 songs.", Stats::totalPlays, 1000));
        GOALS.add(new Goal("hour1", "First hour", "Listen for 1 hour.", () -> Stats.totalMs() / HOUR, 1));
        GOALS.add(new Goal("hour10", "Ten hours", "Listen for 10 hours.", () -> Stats.totalMs() / HOUR, 10));
        GOALS.add(new Goal("hour100", "Hundred hours", "Listen for 100 hours.", () -> Stats.totalMs() / HOUR, 100));
        GOALS.add(new Goal("streak3", "Three in a row", "Listen 3 days in a row.", Stats::bestStreak, 3));
        GOALS.add(new Goal("streak7", "A whole week", "Listen 7 days in a row.", Stats::bestStreak, 7));
        GOALS.add(new Goal("streak30", "A whole month", "Listen 30 days in a row.", Stats::bestStreak, 30));
        GOALS.add(new Goal("songs25", "Explorer", "Hear 25 different songs.", Stats::distinctSongs, 25));
        GOALS.add(new Goal("artists10", "Wide taste", "Hear 10 different artists.", Stats::distinctArtists, 10));
        GOALS.add(new Goal("repeat10", "On repeat", "Play one song 10 times.", Stats::mostPlaysOfOneSong, 10));
        GOALS.add(new Goal("library50", "Collector", "Have 50 songs in your music folder.",
                () -> com.zlorbie788.spotify.local.MusicLibrary.count(), 50));
    }

    private Achievements() {}

    public static List<Goal> goals() {
        return GOALS;
    }

    public static synchronized boolean unlocked(String id) {
        load();
        return UNLOCKED.containsKey(id);
    }

    public static synchronized int unlockedCount() {
        load();
        int n = 0;
        for (Goal g : GOALS) if (UNLOCKED.containsKey(g.id()) || g.reached()) n++;
        return n;
    }

    /** Text of the newest unlock that has not been shown yet ("" when none); reading it clears it. */
    public static synchronized String takeAnnouncement() {
        String a = announcement;
        announcement = "";
        return a;
    }

    // ------------------------------------------------------------ checking

    /** Once per client tick; the real check runs every few seconds. */
    public static void tick() {
        if (++ticks < 100) return;
        ticks = 0;
        check();
    }

    /** Looks for goals that are reached and not yet recorded. Returns true when something new was recorded. */
    public static synchronized boolean check() {
        load();
        boolean changed = false;
        for (Goal g : GOALS) {
            if (UNLOCKED.containsKey(g.id())) continue;
            boolean reached;
            try {
                reached = g.reached();
            } catch (RuntimeException e) {
                continue;
            }
            if (!reached) continue;
            UNLOCKED.put(g.id(), wall.getAsLong());
            changed = true;
            // the first time ever, everything already reached is recorded silently
            if (!firstRun) announcement = "Achievement: " + g.title();
        }
        firstRun = false;
        if (changed) save();
        return changed;
    }

    // ------------------------------------------------------------ file

    private static Path file() {
        return override != null ? override : ConfigIo.directory().resolve("achievements.json");
    }

    static synchronized void useFile(Path p) {
        override = p;
        loaded = false;
        UNLOCKED.clear();
        announcement = "";
        ticks = 0;
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        UNLOCKED.clear();
        Path f = file();
        if (!Files.exists(f)) {
            firstRun = true;
            return;
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> e : o.entrySet()) {
                if (e.getValue().isJsonPrimitive()) UNLOCKED.put(e.getKey(), e.getValue().getAsLong());
            }
        } catch (Exception e) {
            firstRun = true;
        }
    }

    private static void save() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            JsonObject o = new JsonObject();
            for (Map.Entry<String, Long> e : UNLOCKED.entrySet()) o.addProperty(e.getKey(), e.getValue());
            Files.writeString(f, new Gson().toJson(o), StandardCharsets.UTF_8);
        } catch (Exception ignored) { }
    }

    /** Forgets which goals were recorded (used by "Reset stats"). */
    public static synchronized void reset() {
        load();
        UNLOCKED.clear();
        announcement = "";
        try {
            Files.deleteIfExists(file());
        } catch (Exception ignored) { }
        firstRun = false;
    }
}
