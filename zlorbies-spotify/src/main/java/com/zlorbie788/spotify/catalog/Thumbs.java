package com.zlorbie788.spotify.catalog;

import com.mojang.blaze3d.platform.NativeImage;
import com.zlorbie788.spotify.client.SpotifyClient;
import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.media.Covers;
import com.zlorbie788.spotify.util.Http;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Small square pictures for the search results and the library. Downloaded once (kept in {@code thumbs/}), turned
 * into 64x64 textures on the game thread, and dropped again when more than 90 are alive.
 */
public final class Thumbs {
    public record Thumb(Identifier id, int color) {}

    private static final int SIZE = 64;
    private static final int MAX_TEXTURES = 90;

    private static final class Entry {
        Thumb thumb;
        boolean loading;
        long failedAt;
    }

    private record Loaded(String url, NativeImage image, int color) {}

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "spotify-integration-thumb");
        t.setDaemon(true);
        return t;
    });
    private static final ConcurrentLinkedQueue<Loaded> READY = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<String> FAILED = new ConcurrentLinkedQueue<>();
    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>(128, 0.75f, true);
    private static final AtomicInteger NEXT = new AtomicInteger();

    private Thumbs() {}

    /** The picture for this address, or null while it is still coming. Game thread only. */
    public static Thumb get(String url) {
        if (url == null || url.isEmpty() || !ConfigIo.get().searchThumbs) return null;
        Entry e = ENTRIES.get(url);
        if (e == null) {
            e = new Entry();
            ENTRIES.put(url, e);
        }
        if (e.thumb != null) return e.thumb;
        if (!e.loading && (e.failedAt == 0 || System.currentTimeMillis() - e.failedAt > 30_000)) {
            e.loading = true;
            POOL.submit(() -> load(url));
        }
        return null;
    }

    /** Turns downloaded pictures into textures (at most 3 per tick) and frees old ones. */
    public static void tick(Minecraft mc) {
        String failed;
        while ((failed = FAILED.poll()) != null) {
            Entry e = ENTRIES.get(failed);
            if (e != null) {
                e.loading = false;
                e.failedAt = System.currentTimeMillis();
            }
        }
        for (int n = 0; n < 3; n++) {
            Loaded l = READY.poll();
            if (l == null) break;
            Entry e = ENTRIES.get(l.url());
            if (e == null) {
                l.image().close();
                continue;
            }
            try {
                Identifier id = Identifier.fromNamespaceAndPath("zlorbies-spotify", "thumb/t" + NEXT.incrementAndGet());
                DynamicTexture texture = new DynamicTexture(() -> "zlorbie_thumb", l.image());
                texture.upload();
                mc.getTextureManager().register(id, texture);
                e.thumb = new Thumb(id, l.color());
            } catch (RuntimeException ex) {
                l.image().close();
                e.failedAt = System.currentTimeMillis();
            }
            e.loading = false;
        }
        evict(mc, MAX_TEXTURES);
    }

    /** Frees every picture (the search screen closed for good, or the game is stopping). */
    public static void releaseAll(Minecraft mc) {
        evict(mc, 0);
    }

    private static void evict(Minecraft mc, int keep) {
        int alive = 0;
        for (Entry e : ENTRIES.values()) if (e.thumb != null) alive++;
        if (alive <= keep) return;
        List<String> drop = new ArrayList<>();
        Iterator<Map.Entry<String, Entry>> it = ENTRIES.entrySet().iterator(); // oldest access first
        while (it.hasNext() && alive > keep) {
            Map.Entry<String, Entry> me = it.next();
            if (me.getValue().thumb != null) {
                mc.getTextureManager().release(me.getValue().thumb.id());
                me.getValue().thumb = null;
                alive--;
            }
        }
    }

    // ------------------------------------------------------------ worker

    private static void load(String url) {
        try {
            byte[] bytes = url.startsWith("local:")
                    ? Files.readAllBytes(com.zlorbie788.spotify.local.MusicLibrary.coverPath(url.substring(6)))
                    : readCache(url);
            if (bytes == null) {
                bytes = Http.bytes(url, 2_000_000);
                if (bytes == null || bytes.length < 100) throw new IOException("no picture");
                writeCache(url, bytes);
            }
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(bytes));
            if (src == null) throw new IOException("picture format not recognised");
            BufferedImage square = Covers.scale(Covers.cropSquare(src), SIZE);
            READY.add(new Loaded(url, Covers.toNative(square), Covers.extractPalette(square).accent()));
        } catch (Throwable t) {
            SpotifyClient.LOGGER.debug("[Spotify Integration Mod] thumbnail failed: {}", t.toString());
            FAILED.add(url);
        }
    }

    private static Path cacheFile(String url) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] h = md.digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) sb.append(String.format("%02x", h[i]));
        return ConfigIo.directory().resolve("thumbs").resolve(sb + ".img");
    }

    private static byte[] readCache(String url) {
        try {
            Path p = cacheFile(url);
            return Files.exists(p) ? Files.readAllBytes(p) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeCache(String url, byte[] bytes) {
        try {
            Path p = cacheFile(url);
            Files.createDirectories(p.getParent());
            Files.write(p, bytes);
            // Keep the folder small: delete the oldest files beyond 400.
            try (var files = Files.list(p.getParent())) {
                var all = new ArrayList<>(files.toList());
                if (all.size() > 400) {
                    all.sort((a, b) -> {
                        try {
                            return Files.getLastModifiedTime(a).compareTo(Files.getLastModifiedTime(b));
                        } catch (IOException e) {
                            return 0;
                        }
                    });
                    for (int i = 0; i < all.size() - 400; i++) Files.deleteIfExists(all.get(i));
                }
            }
        } catch (Exception ignored) { }
    }

    /** Average colour for tinting a background; a neutral dark when the picture is not ready. */
    public static int colorOf(String url, int fallback) {
        Entry e = ENTRIES.get(url);
        return e != null && e.thumb != null ? e.thumb.color() : fallback;
    }
}
