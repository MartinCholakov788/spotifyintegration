package com.zlorbie788.spotify.catalog;

import com.zlorbie788.spotify.client.SpotifyClient;
import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.lyrics.Lyrics;
import com.zlorbie788.spotify.media.Player;
import com.zlorbie788.spotify.media.Snapshot;
import com.zlorbie788.spotify.util.Http;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A cover for songs where Windows does not hand one over: looks the song up in the public catalogues and saves the
 * album picture in {@code webcovers/}. Used by the cover loader only when the helper's own picture is missing.
 */
public final class ArtFinder {
    public static final String PREFIX = "web:";

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "spotify-integration-art");
        t.setDaemon(true);
        return t;
    });
    private static final Set<String> BUSY = new HashSet<>();
    private static final Set<String> MISSING = new HashSet<>();

    private static String memoKey = "";
    private static String memoName = "";
    private static boolean memoReady;

    private ArtFinder() {}

    public static Path directory() {
        return ConfigIo.directory().resolve("webcovers");
    }

    /** True for names made by this class. */
    public static boolean isWeb(String file) {
        return file != null && file.startsWith(PREFIX);
    }

    public static Path path(String file) {
        return directory().resolve(file.substring(PREFIX.length()));
    }

    /**
     * Cover file name for the song ("web:..."), or empty while it is still being found or when there is none.
     * Call it every tick; it is cheap. Game thread.
     */
    public static String want(Snapshot s) {
        if (!ConfigIo.get().webCovers || !ConfigIo.get().onlineSearch || !s.active() || s.isAd()
                || (com.zlorbie788.spotify.local.Sources.local() && !ConfigIo.get().fixTagsOnline)
                || s.title().isBlank() || Player.sinceTrackChange() < 1500) {
            return "";
        }
        String key = s.title() + "|" + s.artist();
        if (!key.equals(memoKey)) {
            memoKey = key;
            memoName = name(s.title(), s.artist());
            memoReady = false;
        }
        String name = memoName;
        if (memoReady) return PREFIX + name;
        Path p = directory().resolve(name);
        if (Files.exists(p)) {
            memoReady = true;
            return PREFIX + name;
        }
        synchronized (BUSY) {
            if (MISSING.contains(name) || !BUSY.add(name)) return "";
        }
        POOL.submit(() -> {
            try {
                if (!find(s.title(), s.artist(), s.album(), p)) {
                    synchronized (BUSY) {
                        MISSING.add(name);
                    }
                }
            } catch (Throwable t) {
                SpotifyClient.LOGGER.info("[Spotify Integration Mod] web cover lookup failed: {}", t.toString());
                synchronized (BUSY) {
                    MISSING.add(name);
                }
            } finally {
                synchronized (BUSY) {
                    BUSY.remove(name);
                }
            }
        });
        return "";
    }

    private static boolean find(String title, String artist, String album, Path target) throws IOException {
        Item best = Catalog.bestMatch(title, artist);
        String url = best == null ? "" : best.picture(true);
        if (url.isEmpty()) return false;
        byte[] bytes = Http.bytes(url, 3_000_000);
        if (bytes == null || bytes.length < 200) return false;
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        trim();
        return true;
    }

    /** Keeps the folder from growing without limit: the oldest pictures go first. */
    private static void trim() {
        try (var files = Files.list(directory())) {
            var list = files.filter(f -> f.getFileName().toString().endsWith(".img")).sorted((a, b) -> {
                try {
                    return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                } catch (IOException e) {
                    return 0;
                }
            }).toList();
            for (int i = 150; i < list.size(); i++) Files.deleteIfExists(list.get(i));
        } catch (IOException ignored) { }
    }

    static String name(String title, String artist) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] h = md.digest((Item.norm(Lyrics.cleanTitle(title)) + "|" + Item.norm(Lyrics.firstArtist(artist)))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10; i++) sb.append(String.format("%02x", h[i]));
            return sb + ".img";
        } catch (Exception e) {
            return Math.abs((title + artist).hashCode()) + ".img";
        }
    }
}
