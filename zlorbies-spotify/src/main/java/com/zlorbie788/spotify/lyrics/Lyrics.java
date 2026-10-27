package com.zlorbie788.spotify.lyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zlorbie788.spotify.client.SpotifyClient;
import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.config.SpotifyConfig;
import com.zlorbie788.spotify.media.Player;
import com.zlorbie788.spotify.media.Snapshot;
import com.zlorbie788.spotify.util.Http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Finds and keeps the lyrics of the song now playing. The words come from LRCLIB (lrclib.net), a free community
 * database with time-synced lyrics; the song's title, artist, album and length are the only things sent.
 * Found lyrics are saved in the config folder, so a song is looked up only once.
 *
 * <p>Everything slow runs on one worker thread. The game thread only reads the finished result.
 */
public final class Lyrics {
    public enum Status { IDLE, LOADING, READY, PLAIN, INSTRUMENTAL, NONE, ERROR }

    /** The lyrics of one song. {@code lines} is empty when only plain (untimed) text exists. */
    public record Doc(List<LyricLine> lines, List<String> plain, boolean instrumental) {
        public boolean synced() {
            return !lines.isEmpty();
        }
    }

    private static final Doc EMPTY = new Doc(List.of(), List.of(), false);
    private static final long KEEP_NEEDED_MS = 2500;

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "spotify-integration-lyrics");
        t.setDaemon(true);
        return t;
    });
    private static final Map<String, Doc> MEMORY = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Doc> eldest) {
            return size() > 40;
        }
    };

    private static volatile String wantedKey = "";
    private static volatile String docKey = "";
    private static volatile Status status = Status.IDLE;
    private static volatile Doc doc = EMPTY;
    private static volatile String message = "";
    private static volatile int generation;
    private static long neededUntil;
    private static long retryAt;
    private static int attempts;
    /** Lyrics of songs that were not found, so we do not ask again and again (key -> time). */
    private static final Map<String, Long> MISSING = new LinkedHashMap<>();

    private Lyrics() {}

    // ------------------------------------------------------------ public readings

    /** Something on screen wants lyrics right now (call it from a screen's render; it lasts a moment). */
    public static void need() {
        neededUntil = System.currentTimeMillis() + KEEP_NEEDED_MS;
    }

    public static Status status() {
        return docKey.equals(wantedKey) && !wantedKey.isEmpty() ? status : Status.IDLE;
    }

    public static Doc doc() {
        return docKey.equals(wantedKey) ? doc : EMPTY;
    }

    /** A sentence for the screens: why there are no lyrics, or what is happening. */
    public static String message() {
        return message;
    }

    /** True when the timed lines are available and can be shown. */
    public static boolean ready() {
        return status() == Status.READY;
    }

    /** Position used for the lyrics: the player position plus the user's sync offset. */
    public static long position() {
        return Player.position() + ConfigIo.get().lyrics.offsetMs;
    }

    /** Index of the line being sung now (-1 before the first line, or without timed lyrics). */
    public static int currentIndex() {
        Doc d = doc();
        if (!d.synced()) return -1;
        return LrcParser.indexAt(d.lines(), position());
    }

    /** Looks up the current song again (the "Try again" button). */
    public static void retry() {
        synchronized (MEMORY) {
            MISSING.remove(wantedKey);
        }
        attempts = 0;
        if (!wantedKey.isEmpty()) request(wantedKey, Player.snap(), true);
    }

    // ------------------------------------------------------------ per tick (game thread)

    public static void tick() {
        SpotifyConfig cfg = ConfigIo.get();
        Snapshot s = Player.snap();
        boolean wanted = (cfg.lyrics.enabled || System.currentTimeMillis() < neededUntil)
                && s.active() && !s.isAd() && !s.title().isBlank();
        String key = s.active() ? keyOf(s) : "";
        wantedKey = key;
        if (!wanted) return;
        if (!key.equals(docKey)) {
            attempts = 0;
            request(key, s, false);
        } else if (status == Status.ERROR && attempts < 3 && System.currentTimeMillis() > retryAt) {
            request(key, s, true);
        }
    }

    static String keyOf(Snapshot s) {
        return keyFor(s.title(), s.artist(), s.durMs());
    }

    static String keyFor(String title, String artist, long durMs) {
        return (title + "|" + artist + "|" + (durMs / 1000)).toLowerCase(Locale.ROOT);
    }

    /** True when lyrics for this song are already saved on this PC (found earlier, online). */
    static boolean cached(String key) {
        return readDisk(key) != null;
    }

    /** Saves an answer of the lyrics service for a song (used by the "fetch for the whole folder" job). */
    static void store(String key, JsonObject found) {
        Doc d = parseDoc(found);
        if (d.synced() || !d.plain().isEmpty() || d.instrumental()) writeDisk(key, found);
    }

    private static void request(String key, Snapshot s, boolean force) {
        int gen = ++generation;
        docKey = key;
        status = Status.LOADING;
        message = "Looking for the lyrics...";
        doc = EMPTY;
        Doc cached;
        Long missed;
        synchronized (MEMORY) {
            cached = force ? null : MEMORY.get(key);
            missed = force ? null : MISSING.get(key);
        }
        if (cached != null) {
            finish(gen, key, cached, "");
            return;
        }
        if (missed != null && System.currentTimeMillis() - missed < 10 * 60_000L) {
            status = Status.NONE;
            message = "No lyrics found for this song.";
            return;
        }
        final String title = s.title();
        final String artist = s.artist();
        final String album = s.album();
        final long durMs = s.durMs();
        final com.zlorbie788.spotify.local.Track own = com.zlorbie788.spotify.local.Sources.local()
                && com.zlorbie788.spotify.local.LocalPlayer.APP.equals(s.appName())
                ? com.zlorbie788.spotify.local.LocalPlayer.current() : null;
        POOL.submit(() -> work(gen, key, title, artist, album, durMs, own));
    }

    private static void finish(int gen, String key, Doc d, String note) {
        if (gen != generation) return;
        doc = d;
        message = note;
        if (d.instrumental()) status = Status.INSTRUMENTAL;
        else if (d.synced()) status = Status.READY;
        else if (!d.plain().isEmpty()) status = Status.PLAIN;
        else status = Status.NONE;
        if (status == Status.NONE) message = "No lyrics found for this song.";
        if (status == Status.PLAIN) message = "Only untimed lyrics exist for this song.";
    }

    // ------------------------------------------------------------ worker thread

    private static void work(int gen, String key, String title, String artist, String album, long durMs,
                             com.zlorbie788.spotify.local.Track own) {
        try {
            if (own != null) {
                // a .lrc file beside the song, or lyrics inside its tags, come before anything online
                Doc mine = LocalLyrics.find(own);
                if (mine != null) {
                    finish(gen, key, mine, "");
                    return;
                }
            }
            Doc d = readDisk(key);
            if (d == null && ConfigIo.get().lyrics.online) {
                JsonObject found = lookup(title, artist, album, durMs);
                if (found != null) {
                    d = parseDoc(found);
                    if (d.synced() || !d.plain().isEmpty() || d.instrumental()) writeDisk(key, found);
                }
            } else if (d == null) {
                if (gen == generation) {
                    status = Status.NONE;
                    message = "Online lookup is off (Settings > Lyrics).";
                }
                return;
            }
            if (d == null) d = EMPTY;
            final Doc result = d;
            synchronized (MEMORY) {
                if (result.synced() || !result.plain().isEmpty() || result.instrumental()) MEMORY.put(key, result);
                else MISSING.put(key, System.currentTimeMillis());
            }
            finish(gen, key, result, "");
        } catch (Throwable t) {
            if (gen != generation) return;
            attempts++;
            retryAt = System.currentTimeMillis() + 8000L * attempts;
            status = Status.ERROR;
            String why = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            message = "Could not reach the lyrics service (" + why + ").";
            SpotifyClient.LOGGER.warn("[Spotify Integration Mod] lyrics lookup failed: {}", why);
        }
    }

    static String base() {
        return System.getProperty("zlorbie.lrclib", "https://lrclib.net");
    }

    /** Up to three web requests: exact match, cleaned-up title, then a search. Returns the best JSON object. */
    static JsonObject lookup(String title, String artist, String album, long durMs) throws IOException {
        String first = firstArtist(artist);
        String clean = cleanTitle(title);
        long secs = Math.round(durMs / 1000.0);

        JsonObject hit = get(title, artist, album, secs);
        if (usable(hit)) return hit;
        if (!clean.equalsIgnoreCase(title) || !first.equalsIgnoreCase(artist)) {
            hit = get(clean, first, album, secs);
            if (usable(hit)) return hit;
        }
        String url = base() + "/api/search?q=" + Http.enc(clean + " " + first);
        Http.Response r = Http.get(url, null);
        if (r.code() == 429) throw new IOException("too many requests, try again in a moment");
        if (!r.ok()) return null;
        return choose(parseArray(r.body()), clean, first, secs);
    }

    private static JsonObject get(String title, String artist, String album, long secs) throws IOException {
        StringBuilder url = new StringBuilder(base()).append("/api/get?track_name=").append(Http.enc(title))
                .append("&artist_name=").append(Http.enc(artist));
        if (album != null && !album.isBlank()) url.append("&album_name=").append(Http.enc(album));
        if (secs > 0) url.append("&duration=").append(secs);
        Http.Response r = Http.get(url.toString(), null);
        if (r.code() == 404) return null;
        if (r.code() == 429) throw new IOException("too many requests, try again in a moment");
        if (!r.ok()) throw new IOException("server answered " + r.code());
        try {
            JsonElement e = JsonParser.parseString(r.body());
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static boolean usable(JsonObject o) {
        if (o == null) return false;
        return truthy(o, "instrumental") || !str(o, "syncedLyrics").isBlank();
    }

    private static JsonArray parseArray(String body) {
        try {
            JsonElement e = JsonParser.parseString(body);
            return e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
        } catch (RuntimeException ex) {
            return new JsonArray();
        }
    }

    /**
     * Best search result: timed lyrics first, then the closest length (within 10 s), then a matching artist.
     * Falls back to plain lyrics when nothing is timed.
     */
    static JsonObject choose(JsonArray results, String title, String artist, long secs) {
        JsonObject bestSynced = null;
        double bestSyncedScore = Double.MAX_VALUE;
        JsonObject bestPlain = null;
        for (JsonElement e : results) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            double diff = secs > 0 && o.has("duration") && !o.get("duration").isJsonNull()
                    ? Math.abs(o.get("duration").getAsDouble() - secs) : 3;
            if (diff > 10) continue;
            String artistName = str(o, "artistName").toLowerCase(Locale.ROOT);
            String trackName = str(o, "trackName").toLowerCase(Locale.ROOT);
            double score = diff;
            if (!artist.isBlank() && !artistName.contains(artist.toLowerCase(Locale.ROOT))
                    && !artist.toLowerCase(Locale.ROOT).contains(artistName)) score += 6;
            if (!trackName.contains(title.toLowerCase(Locale.ROOT)) && !title.toLowerCase(Locale.ROOT).contains(trackName)) score += 6;
            if (!str(o, "syncedLyrics").isBlank()) {
                if (score < bestSyncedScore) {
                    bestSyncedScore = score;
                    bestSynced = o;
                }
            } else if (bestPlain == null && (!str(o, "plainLyrics").isBlank() || truthy(o, "instrumental")) && score < 8) {
                bestPlain = o;
            }
        }
        if (bestSynced != null && bestSyncedScore < 12) return bestSynced;
        return bestPlain;
    }

    static Doc parseDoc(JsonObject o) {
        boolean instrumental = truthy(o, "instrumental");
        List<LyricLine> lines = LrcParser.parse(str(o, "syncedLyrics"));
        List<String> plain = LrcParser.plain(str(o, "plainLyrics"));
        if (lines.isEmpty() && plain.isEmpty() && !instrumental) return EMPTY;
        return new Doc(lines, plain, instrumental && lines.isEmpty());
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? "" : e.getAsString();
    }

    private static boolean truthy(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    // ------------------------------------------------------------ names

    private static final Pattern BRACKETS = Pattern.compile(
            "\\s*[(\\[][^)\\]]*(feat|ft\\.|with |remaster|version|edit|live|mono|stereo|deluxe|bonus|explicit|from |radio|single|mix)[^)\\]]*[)\\]]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DASH_TAIL = Pattern.compile(
            "\\s+-\\s+.*(remaster|version|edit|live|mono|stereo|deluxe|bonus|from |radio|single|mix).*$",
            Pattern.CASE_INSENSITIVE);

    /** "Song (feat. X) - 2011 Remaster" -> "Song". Never returns an empty string. */
    public static String cleanTitle(String title) {
        if (title == null) return "";
        String t = BRACKETS.matcher(title).replaceAll("");
        t = DASH_TAIL.matcher(t).replaceAll("").strip();
        return t.isEmpty() ? title.strip() : t;
    }

    /** "A, B & C" -> "A". */
    public static String firstArtist(String artist) {
        if (artist == null) return "";
        String a = artist.split("\\s*(?:,|;|&|\\bfeat\\.?\\b|\\bft\\.?\\b| x )\\s*", 2)[0].strip();
        return a.isEmpty() ? artist.strip() : a;
    }

    // ------------------------------------------------------------ disk cache

    private static Path dir() {
        return ConfigIo.directory().resolve("lyrics");
    }

    private static Path fileFor(String key) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] hash = md.digest(key.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) sb.append(String.format("%02x", b));
        return dir().resolve(sb + ".json");
    }

    private static Doc readDisk(String key) {
        try {
            Path f = fileFor(key);
            if (!Files.exists(f)) return null;
            JsonElement e = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
            if (!e.isJsonObject()) return null;
            Doc d = parseDoc(e.getAsJsonObject());
            return d == EMPTY ? null : d;
        } catch (Exception ex) {
            return null;
        }
    }

    private static void writeDisk(String key, JsonObject o) {
        try {
            Files.createDirectories(dir());
            JsonObject slim = new JsonObject();
            slim.addProperty("syncedLyrics", str(o, "syncedLyrics"));
            slim.addProperty("plainLyrics", str(o, "plainLyrics"));
            slim.addProperty("instrumental", truthy(o, "instrumental"));
            Files.writeString(fileFor(key), slim.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // best effort
        }
    }

    // ------------------------------------------------------------ sample for previews

    /** Invented sample lines so every setting can be previewed without a song. */
    public static Doc sample() {
        String[] text = {
                "This is how your lyrics will look",
                "The line being sung is highlighted",
                "",
                "Pick the colours, the size and the motion",
                "Every line fades the further away it is",
                "And a really long line wraps onto a second row so it never leaves the box",
                "Move it anywhere on the screen",
                "Everything here can be changed",
        };
        List<LyricLine> lines = new ArrayList<>();
        long t = 0;
        for (String s : text) {
            lines.add(new LyricLine(t, s));
            t += s.isEmpty() ? 5000 : 3400;
        }
        return new Doc(lines, List.of(), false);
    }

    /** Total length of the sample, so previews can loop it. */
    public static long sampleLength() {
        Doc d = sample();
        return d.lines().get(d.lines().size() - 1).startMs() + 4000;
    }
}
