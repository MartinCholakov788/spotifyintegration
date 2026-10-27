package com.zlorbie788.spotify.catalog;

import com.google.gson.JsonObject;
import com.zlorbie788.spotify.client.SpotifyClient;
import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.util.Http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the exact Spotify version of a catalogue song or album, with no Spotify account: the public song.link
 * service (Odesli) maps a Deezer / Apple Music id to the matching Spotify link. Answers are kept in
 * {@code spotify-links.json} so a song is only looked up once. Worker threads only.
 */
public final class Resolver {
    private static final Pattern SPOTIFY_URL = Pattern.compile(
            "open\\.spotify\\.com/(?:intl-[a-z-]+/)?(track|album)/([A-Za-z0-9]{10,32})");
    private static final Pattern ENTITY = Pattern.compile("^SPOTIFY_(SONG|ALBUM)::([A-Za-z0-9]{10,32})$");

    public static final String NOT_FOUND = "not found on Spotify";
    private static final long MISS_MS = 30 * 60_000L;

    private static JsonObject cache;
    private static volatile long blockedUntil;
    /** Songs the link service said are not on Spotify, with the time it said so (this session only). */
    private static final java.util.Map<String, Long> MISSES = new java.util.concurrent.ConcurrentHashMap<>();

    private Resolver() {}

    static String base() {
        return System.getProperty("zlorbie.odesli", "https://api.song.link");
    }

    /** Result of a lookup. */
    public record Found(String uri, String note) {
        public boolean ok() {
            return !uri.isEmpty();
        }

        /** The service knows the song and it is not on Spotify. */
        public boolean missing() {
            return uri.isEmpty() && note.equals(NOT_FOUND);
        }

        /** Nothing is known yet: the service was busy or not reachable. Try again later. */
        public boolean temporary() {
            return uri.isEmpty() && !missing() && !note.equals("no catalogue id");
        }
    }

    /** What is already known without asking the internet: a Spotify link, "" (not on Spotify) or null (unknown). */
    public static String peek(Item item) {
        if (!item.spotify().isEmpty()) return item.spotify();
        String key = item.key();
        String hit = cached(key);
        if (hit != null) return hit;
        Long miss = MISSES.get(key);
        if (miss != null && System.currentTimeMillis() - miss < MISS_MS) return "";
        return null;
    }

    /** Seconds until the link service may be asked again (0 = now). */
    public static synchronized int waitSeconds() {
        long left = blockedUntil - System.currentTimeMillis();
        return left <= 0 ? 0 : (int) (left / 1000 + 1);
    }

    public static Found resolve(Item item) {
        if (!item.spotify().isEmpty()) return new Found(item.spotify(), "");
        String platform = switch (item.provider()) {
            case DEEZER -> "deezer";
            case ITUNES -> "itunes";
            default -> "";
        };
        if (platform.isEmpty() || item.id().isEmpty()) return new Found("", "no catalogue id");
        String key = item.key();
        String hit = cached(key);
        if (hit != null) return new Found(hit, "");
        Long miss = MISSES.get(key);
        if (miss != null && System.currentTimeMillis() - miss < MISS_MS) return new Found("", NOT_FOUND);
        long now = System.currentTimeMillis();
        if (now < blockedUntil) {
            return new Found("", "the link service is busy, try again in " + ((blockedUntil - now) / 1000 + 1) + " s");
        }
        String type = item.isAlbum() ? "album" : "song";
        String url = base() + "/v1-alpha.1/links?platform=" + platform + "&type=" + type + "&id=" + Http.enc(item.id())
                + "&userCountry=" + ConfigIo.get().searchCountry + "&songIfSingle=true";
        try {
            Http.Response r = Http.get(url, null);
            if (r.code() == 429) {
                int wait = 60;
                try {
                    if (r.retryAfter() != null) wait = Math.max(5, Math.min(300, Integer.parseInt(r.retryAfter().trim())));
                } catch (NumberFormatException ignored) { }
                synchronized (Resolver.class) {
                    blockedUntil = System.currentTimeMillis() + wait * 1000L;
                }
                return new Found("", "the link service is busy, try again in " + wait + " s");
            }
            if (r.code() == 404) {
                MISSES.put(key, System.currentTimeMillis());
                return new Found("", NOT_FOUND);
            }
            if (!r.ok()) return new Found("", "link service answered " + r.code());
            String uri = parse(r.body(), item.isAlbum());
            remember(key, uri);
            if (uri.isEmpty()) {
                MISSES.put(key, System.currentTimeMillis());
                return new Found("", NOT_FOUND);
            }
            return new Found(uri, "");
        } catch (IOException e) {
            return new Found("", "could not reach the link service");
        }
    }

    /** Pulls the Spotify uri out of a song.link answer; empty when there is none. */
    static String parse(String body, boolean album) throws IOException {
        JsonObject root = Js.parse(body);
        JsonObject by = Js.obj(root, "linksByPlatform");
        JsonObject sp = by == null ? null : Js.obj(by, "spotify");
        if (sp != null) {
            Matcher m = SPOTIFY_URL.matcher(Js.str(sp, "url"));
            if (m.find() && (m.group(1).equals("album")) == album) return "spotify:" + m.group(1) + ":" + m.group(2);
            m = ENTITY.matcher(Js.str(sp, "entityUniqueId"));
            if (m.find()) return "spotify:" + (m.group(1).equals("ALBUM") ? "album" : "track") + ":" + m.group(2);
        }
        return "";
    }

    // ------------------------------------------------------------ disk cache

    private static Path file() {
        return ConfigIo.directory().resolve("spotify-links.json");
    }

    private static synchronized String cached(String key) {
        load();
        if (!cache.has(key)) return null;
        String v = Js.str(cache, key);
        return v.isEmpty() ? null : v; // misses are only remembered for this session
    }

    private static synchronized void remember(String key, String uri) {
        load();
        if (uri.isEmpty()) return;
        cache.addProperty(key, uri);
        if (cache.size() > 3000) {
            String first = cache.keySet().iterator().next();
            cache.remove(first);
        }
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), cache.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            SpotifyClient.LOGGER.debug("[Spotify Integration Mod] could not save spotify-links.json: {}", e.toString());
        }
    }

    private static void load() {
        if (cache != null) return;
        cache = new JsonObject();
        try {
            if (Files.exists(file())) cache = Js.parse(Files.readString(file(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ignored) {
            cache = new JsonObject();
        }
    }

    /** For tests: forget everything held in memory. */
    static synchronized void reset() {
        cache = null;
        blockedUntil = 0;
        MISSES.clear();
    }
}
