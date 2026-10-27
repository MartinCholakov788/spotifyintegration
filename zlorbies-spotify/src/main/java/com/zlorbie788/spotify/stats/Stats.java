package com.zlorbie788.spotify.stats;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zlorbie788.spotify.client.SpotifyClient;
import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.media.Player;
import com.zlorbie788.spotify.media.Snapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Listening statistics, kept on this PC only in {@code stats.json}: how long you listened, how many songs, your
 * top songs and artists, and a small history by day, both for this session and since the mod was first installed.
 * Time is counted while the song is really playing (an advertisement or a paused song counts nothing), and a song
 * counts as "played" once you have heard 30 seconds of it (or half of a short one), like Spotify itself.
 */
public final class Stats {
    /** One song in the statistics. */
    public record SongStat(String title, String artist, int plays, long ms) { }

    /** One artist in the statistics. */
    public record ArtistStat(String name, int plays, long ms) { }

    private static final long COUNT_AFTER_MS = 30_000L;
    private static final long MAX_STEP_MS = 600L;
    private static final long SAVE_EVERY_MS = 60_000L;
    private static final int KEEP_SONGS = 2500;
    private static final int KEEP_DAYS = 800;

    private static final Map<String, long[]> SONGS = new HashMap<>(); // key -> {plays, ms}
    private static final Map<String, String[]> SONG_NAMES = new HashMap<>(); // key -> {title, artist}
    private static final Map<String, long[]> ARTISTS = new HashMap<>(); // artist -> {plays, ms}
    private static final TreeMap<String, Long> DAYS = new TreeMap<>();

    private static long installedAt;
    private static long totalMs;
    private static long totalPlays;
    private static long sessionMs;
    private static long sessionPlays;
    private static final long SESSION_START = System.currentTimeMillis();

    // what is playing right now
    private static String curKey = "";
    private static long curMs;
    private static boolean curCounted;
    private static long lastPos;
    private static long lastNs;
    private static long lastSave;
    private static boolean loaded;
    private static boolean dirty;
    private static Path override;

    /** For tests: where "now" comes from. */
    static java.util.function.LongSupplier nanos = System::nanoTime;
    static java.util.function.Supplier<LocalDate> today = LocalDate::now;
    static java.util.function.LongSupplier wallMs = System::currentTimeMillis;

    private Stats() {}

    // ------------------------------------------------------------ feeding

    /** Once per client tick. */
    public static void tick() {
        if (!ConfigIo.get().trackStats) {
            lastNs = 0L;
            return;
        }
        Snapshot s = Player.snap();
        feed(s.active() && !s.isAd() && Player.playing(), s.title(), s.artist(), s.durMs(), Player.position());
    }

    /** What is playing right now. Time between two calls is added to the song when it is playing. */
    static synchronized void feed(boolean playing, String title, String artist, long durMs, long posMs) {
        load();
        long now = nanos.getAsLong();
        long step = lastNs == 0L ? 0L : Math.min(MAX_STEP_MS, Math.max(0L, (now - lastNs) / 1_000_000L));
        lastNs = now;

        String key = playing ? key(title, artist) : curKey;
        if (playing && !key.equals(curKey)) {
            finishSong(durMs);
            curKey = key;
            curMs = 0;
            curCounted = false;
            SONG_NAMES.putIfAbsent(key, new String[]{title.strip(), artist.strip()});
        }
        if (playing && key.equals(curKey) && curCounted && durMs > 0 && lastPos > durMs * 0.8 && posMs + 3000 < lastPos) {
            // the same song came round again (repeat): it is a new play
            curMs = 0;
            curCounted = false;
        }
        if (playing) lastPos = posMs;
        if (!playing || step <= 0L) {
            maybeSave(false);
            return;
        }
        // time
        curMs += step;
        totalMs += step;
        sessionMs += step;
        long[] song = SONGS.computeIfAbsent(key, k -> new long[2]);
        song[1] += step;
        long[] artistRow = ARTISTS.computeIfAbsent(firstArtist(artist), k -> new long[2]);
        artistRow[1] += step;
        DAYS.merge(today.get().toString(), step, Long::sum);
        dirty = true;
        // the play counts once enough of the song has been heard
        long need = durMs > 0 ? Math.min(COUNT_AFTER_MS, Math.max(5_000L, durMs / 2)) : COUNT_AFTER_MS;
        if (!curCounted && curMs >= need) {
            curCounted = true;
            song[0]++;
            artistRow[0]++;
            totalPlays++;
            sessionPlays++;
        }
        maybeSave(false);
    }

    private static void finishSong(long durMs) {
        curKey = "";
    }

    private static String key(String title, String artist) {
        return (title == null ? "" : title.strip().toLowerCase(Locale.ROOT)) + "\u0001" + firstArtist(artist).toLowerCase(Locale.ROOT);
    }

    private static String firstArtist(String artist) {
        String a = com.zlorbie788.spotify.lyrics.Lyrics.firstArtist(artist == null ? "" : artist).strip();
        return a.isEmpty() ? "Unknown artist" : a;
    }

    // ------------------------------------------------------------ readings

    public static synchronized long totalMs() {
        load();
        return totalMs;
    }

    public static synchronized long totalPlays() {
        load();
        return totalPlays;
    }

    public static synchronized long sessionMs() {
        return sessionMs;
    }

    public static synchronized long sessionPlays() {
        return sessionPlays;
    }

    public static long sessionStartedAt() {
        return SESSION_START;
    }

    public static synchronized long installedAt() {
        load();
        return installedAt;
    }

    public static synchronized long todayMs() {
        load();
        return DAYS.getOrDefault(today.get().toString(), 0L);
    }

    public static synchronized int daysListened() {
        load();
        return DAYS.size();
    }

    public static synchronized long bestDayMs() {
        load();
        long best = 0;
        for (long v : DAYS.values()) best = Math.max(best, v);
        return best;
    }

    /** Consecutive days with some listening, counting back from today (or from yesterday if today has none yet). */
    public static synchronized int streak() {
        load();
        LocalDate d = today.get();
        if (!DAYS.containsKey(d.toString())) d = d.minusDays(1);
        int n = 0;
        while (DAYS.containsKey(d.toString())) {
            n++;
            d = d.minusDays(1);
        }
        return n;
    }

    /** Milliseconds listened on each of the last {@code days} days, oldest first. */
    public static synchronized long[] lastDays(int days) {
        load();
        long[] out = new long[days];
        LocalDate d = today.get();
        for (int i = days - 1; i >= 0; i--) {
            out[i] = DAYS.getOrDefault(d.toString(), 0L);
            d = d.minusDays(1);
        }
        return out;
    }

    /** How many times this song has been played (0 if never). Matches by title and first artist. */
    public static synchronized int playsOf(String title, String artist) {
        load();
        long[] row = SONGS.get(key(title, artist));
        return row == null ? 0 : (int) row[0];
    }

    public static synchronized List<SongStat> topSongs(int limit) {
        load();
        List<SongStat> all = new ArrayList<>();
        for (Map.Entry<String, long[]> e : SONGS.entrySet()) {
            String[] n = SONG_NAMES.getOrDefault(e.getKey(), new String[]{"Unknown", ""});
            all.add(new SongStat(n[0], n[1], (int) e.getValue()[0], e.getValue()[1]));
        }
        all.sort((a, b) -> a.plays() != b.plays() ? Integer.compare(b.plays(), a.plays()) : Long.compare(b.ms(), a.ms()));
        return new ArrayList<>(all.subList(0, Math.min(limit, all.size())));
    }

    public static synchronized List<ArtistStat> topArtists(int limit) {
        load();
        List<ArtistStat> all = new ArrayList<>();
        for (Map.Entry<String, long[]> e : ARTISTS.entrySet()) all.add(new ArtistStat(e.getKey(), (int) e.getValue()[0], e.getValue()[1]));
        all.sort((a, b) -> Long.compare(b.ms(), a.ms()));
        return new ArrayList<>(all.subList(0, Math.min(limit, all.size())));
    }


    // ------------------------------------------------------------ years (for the recap)

    /** Years that have some listening, oldest first. */
    public static synchronized List<Integer> years() {
        load();
        java.util.TreeSet<Integer> out = new java.util.TreeSet<>();
        for (String d : DAYS.keySet()) {
            try {
                out.add(Integer.parseInt(d.substring(0, 4)));
            } catch (RuntimeException ignored) { }
        }
        return new ArrayList<>(out);
    }

    public static synchronized long yearMs(int year) {
        load();
        long sum = 0;
        for (Map.Entry<String, Long> e : DAYS.subMap(year + "-01-01", true, year + "-12-31", true).entrySet()) sum += e.getValue();
        return sum;
    }

    public static synchronized int yearDays(int year) {
        load();
        return DAYS.subMap(year + "-01-01", true, year + "-12-31", true).size();
    }

    /** Milliseconds listened in each month (index 0 = January) of the year. */
    public static synchronized long[] monthsMs(int year) {
        load();
        long[] out = new long[12];
        for (Map.Entry<String, Long> e : DAYS.subMap(year + "-01-01", true, year + "-12-31", true).entrySet()) {
            try {
                out[Integer.parseInt(e.getKey().substring(5, 7)) - 1] += e.getValue();
            } catch (RuntimeException ignored) { }
        }
        return out;
    }

    public static synchronized long bestDayIn(int year) {
        load();
        long best = 0;
        for (long v : DAYS.subMap(year + "-01-01", true, year + "-12-31", true).values()) best = Math.max(best, v);
        return best;
    }

    /** The longest run of consecutive listening days inside the year. */
    public static synchronized int bestStreakIn(int year) {
        load();
        int best = 0;
        int run = 0;
        LocalDate d = LocalDate.of(year, 1, 1);
        LocalDate end = LocalDate.of(year, 12, 31);
        while (!d.isAfter(end)) {
            if (DAYS.containsKey(d.toString())) {
                run++;
                best = Math.max(best, run);
            } else {
                run = 0;
            }
            d = d.plusDays(1);
        }
        return best;
    }

    /** The longest run of consecutive listening days ever. */
    public static synchronized int bestStreak() {
        load();
        int best = 0;
        int run = 0;
        LocalDate prev = null;
        for (String key : DAYS.keySet()) {
            LocalDate d;
            try {
                d = LocalDate.parse(key);
            } catch (RuntimeException e) {
                continue;
            }
            run = prev != null && prev.plusDays(1).equals(d) ? run + 1 : 1;
            best = Math.max(best, run);
            prev = d;
        }
        return best;
    }

    public static synchronized int distinctSongs() {
        load();
        return SONGS.size();
    }

    public static synchronized int distinctArtists() {
        load();
        return ARTISTS.size();
    }

    /** The highest play count of any single song. */
    public static synchronized int mostPlaysOfOneSong() {
        load();
        long best = 0;
        for (long[] row : SONGS.values()) best = Math.max(best, row[0]);
        return (int) best;
    }

    /** "3 h 25 min", "42 min", "18 s". */
    public static String duration(long ms) {
        long s = Math.max(0L, ms / 1000L);
        long h = s / 3600;
        long m = (s % 3600) / 60;
        if (h >= 24) return (h / 24) + " d " + (h % 24) + " h";
        if (h > 0) return h + " h " + m + " min";
        if (m > 0) return m + " min";
        return s + " s";
    }

    // ------------------------------------------------------------ resetting

    /** Forgets all statistics (the all-time ones and this session's). */
    public static synchronized void reset() {
        load();
        SONGS.clear();
        SONG_NAMES.clear();
        ARTISTS.clear();
        DAYS.clear();
        totalMs = 0;
        totalPlays = 0;
        sessionMs = 0;
        sessionPlays = 0;
        curKey = "";
        curMs = 0;
        installedAt = wallMs.getAsLong();
        dirty = true;
        maybeSave(true);
    }

    // ------------------------------------------------------------ file

    private static Path file() {
        return override != null ? override : ConfigIo.directory().resolve("stats.json");
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        SONGS.clear();
        SONG_NAMES.clear();
        ARTISTS.clear();
        DAYS.clear();
        Path f = file();
        if (!Files.exists(f)) {
            installedAt = guessInstallDate();
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            installedAt = lng(root, "installedAt", guessInstallDate());
            totalMs = lng(root, "totalMs", 0);
            totalPlays = lng(root, "plays", 0);
            if (root.has("songs") && root.get("songs").isJsonArray()) {
                for (JsonElement e : root.getAsJsonArray("songs")) {
                    if (!e.isJsonObject()) continue;
                    JsonObject o = e.getAsJsonObject();
                    String title = str(o, "t");
                    String artist = str(o, "a");
                    String key = key(title, artist);
                    SONG_NAMES.put(key, new String[]{title, artist});
                    SONGS.put(key, new long[]{lng(o, "p", 0), lng(o, "ms", 0)});
                }
            }
            if (root.has("artists") && root.get("artists").isJsonArray()) {
                for (JsonElement e : root.getAsJsonArray("artists")) {
                    if (!e.isJsonObject()) continue;
                    JsonObject o = e.getAsJsonObject();
                    ARTISTS.put(str(o, "a"), new long[]{lng(o, "p", 0), lng(o, "ms", 0)});
                }
            }
            if (root.has("days") && root.get("days").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("days").entrySet()) {
                    try {
                        LocalDate.parse(e.getKey());
                        DAYS.put(e.getKey(), e.getValue().getAsLong());
                    } catch (RuntimeException ignored) { }
                }
            }
        } catch (IOException | RuntimeException ex) {
            SpotifyClient.LOGGER.warn("[Spotify Integration Mod] stats.json could not be read ({}). Starting empty.", ex.toString());
            try {
                Files.copy(f, f.resolveSibling("stats.json.bak"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) { }
            installedAt = guessInstallDate();
        }
    }

    /** The day the config file was first made, which is when the mod was first used on this PC. */
    private static long guessInstallDate() {
        long now = wallMs.getAsLong();
        try {
            Path c = ConfigIo.file();
            if (Files.exists(c)) {
                long created = Files.readAttributes(c, java.nio.file.attribute.BasicFileAttributes.class).creationTime().toMillis();
                if (created > 0 && created <= now) return created;
            }
        } catch (IOException | RuntimeException ignored) { }
        return now;
    }

    private static void maybeSave(boolean force) {
        long now = wallMs.getAsLong();
        if (!force && (!dirty || now - lastSave < SAVE_EVERY_MS)) return;
        lastSave = now;
        save();
    }

    /** Writes stats.json (called every minute while listening, and when the game closes). */
    public static synchronized void save() {
        if (!loaded) return;
        trim();
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("installedAt", installedAt);
        root.addProperty("totalMs", totalMs);
        root.addProperty("plays", totalPlays);
        JsonArray songs = new JsonArray();
        for (Map.Entry<String, long[]> e : SONGS.entrySet()) {
            String[] n = SONG_NAMES.getOrDefault(e.getKey(), new String[]{"Unknown", ""});
            JsonObject o = new JsonObject();
            o.addProperty("t", n[0]);
            o.addProperty("a", n[1]);
            o.addProperty("p", e.getValue()[0]);
            o.addProperty("ms", e.getValue()[1]);
            songs.add(o);
        }
        root.add("songs", songs);
        JsonArray artists = new JsonArray();
        for (Map.Entry<String, long[]> e : ARTISTS.entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("a", e.getKey());
            o.addProperty("p", e.getValue()[0]);
            o.addProperty("ms", e.getValue()[1]);
            artists.add(o);
        }
        root.add("artists", artists);
        JsonObject days = new JsonObject();
        for (Map.Entry<String, Long> e : DAYS.entrySet()) days.addProperty(e.getKey(), e.getValue());
        root.add("days", days);
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling("stats.json.tmp");
            Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
        } catch (IOException e) {
            SpotifyClient.LOGGER.warn("[Spotify Integration Mod] could not save stats.json: {}", e.toString());
        }
    }

    private static void trim() {
        while (DAYS.size() > KEEP_DAYS) DAYS.pollFirstEntry();
        if (SONGS.size() > KEEP_SONGS) {
            List<Map.Entry<String, long[]>> rows = new ArrayList<>(SONGS.entrySet());
            rows.sort((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]));
            for (int i = KEEP_SONGS - 300; i < rows.size(); i++) {
                SONGS.remove(rows.get(i).getKey());
                SONG_NAMES.remove(rows.get(i).getKey());
            }
        }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    private static long lng(JsonObject o, String k, long dflt) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsLong() : dflt;
        } catch (RuntimeException e) {
            return dflt;
        }
    }

    /** For tests: another file, forget everything that is in memory. */
    static synchronized void useFile(Path path) {
        override = path;
        loaded = false;
        dirty = false;
        totalMs = totalPlays = sessionMs = sessionPlays = 0;
        curKey = "";
        curMs = 0;
        curCounted = false;
        lastNs = 0;
        lastSave = 0;
    }
}
