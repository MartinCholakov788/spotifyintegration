package com.zlorbie788.spotify.media;

import com.mojang.blaze3d.platform.NativeImage;
import com.zlorbie788.spotify.catalog.ArtFinder;
import com.zlorbie788.spotify.client.SpotifyClient;
import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.util.Colors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.TexturePaint;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Album cover art: reads the picture the helper saved, builds sharp copies for every size the player draws
 * (a square set and a round set, so nothing is ever scaled down hard by the GPU), picks the album's colours,
 * and keeps the previous cover around briefly so the player can cross-fade.
 */
public final class Covers {
    /** Edge lengths of the textures that are built for every cover, largest first. */
    public static final int[] SIZES = {256, 128, 64};

    /** The textures and colours of one cover. Textures with other corner shapes are made on demand and kept. */
    public static final class Set {
        /** The cleaned-up picture at every size, largest first (kept so new corner shapes can be made later). */
        final BufferedImage[] levels;
        private final java.util.Map<Integer, Identifier> variants = new java.util.LinkedHashMap<>();
        public final Palette palette;
        public final long createdAt = System.nanoTime();
        public final String file;
        /** True when a frame around the art (Spotify's empty margins and logo strip) was cut away. */
        public final boolean trimmed;

        Set(BufferedImage[] levels, Palette palette, String file, boolean trimmed) {
            this.levels = levels;
            this.palette = palette;
            this.file = file;
            this.trimmed = trimmed;
        }

        /** Index of the smallest texture that is still at least {@code devicePixels} wide (never stretched up). */
        public int level(float devicePixels) {
            int index = 0;
            for (int i = SIZES.length - 1; i >= 0; i--) {
                if (SIZES[i] >= devicePixels) {
                    index = i;
                    break;
                }
            }
            return index;
        }

        public int textureSize(float devicePixels) {
            return SIZES[level(devicePixels)];
        }

        /**
         * The picture with its corners cut to {@code cornerFraction} of its width (0 = square, 0.5 = circle) and
         * zoomed in by {@code zoom} (0..0.3). {@code chamfer} cuts the corners straight instead of round, {@code mask}
         * says which corners are cut (bit 0 top-left, 1 top-right, 2 bottom-right, 3 bottom-left). Made on first use.
         * Game thread only.
         */
        public Identifier texture(float devicePixels, float cornerFraction, float zoom, boolean chamfer, int mask) {
            int lvl = level(devicePixels);
            int cq = Math.max(0, Math.min(32, Math.round(cornerFraction * 64f)));
            int zq = Math.max(0, Math.min(15, Math.round(zoom * 50f)));
            mask &= 15;
            if (cq == 0) {
                mask = 0;
                chamfer = false;
            }
            int key = (((lvl * 33 + cq) * 16 + zq) * 16 + mask) * 2 + (chamfer ? 1 : 0);
            Identifier id = variants.get(key);
            if (id != null) return id;
            Minecraft mc = Minecraft.getInstance();
            try {
                BufferedImage img = levels[lvl];
                if (zq > 0) img = zoomed(img, zq / 50f);
                if (cq > 0) img = cut(img, cq / 64f, chamfer, mask);
                id = register(mc, toNative(img), "v" + key);
            } catch (Throwable t) {
                fail("making a cover variant", t);
                return variants.isEmpty() ? null : variants.values().iterator().next();
            }
            variants.put(key, id);
            while (variants.size() > 24) {
                java.util.Iterator<java.util.Map.Entry<Integer, Identifier>> it = variants.entrySet().iterator();
                java.util.Map.Entry<Integer, Identifier> old = it.next();
                it.remove();
                mc.getTextureManager().release(old.getValue());
            }
            return id;
        }

        public Identifier texture(float devicePixels, float cornerFraction, float zoom) {
            return texture(devicePixels, cornerFraction, zoom, false, 15);
        }

        void releaseAll(Minecraft mc) {
            for (Identifier id : variants.values()) mc.getTextureManager().release(id);
            variants.clear();
        }
    }

    /** Colours taken from a cover. */
    public record Palette(int accent, int dark, int light) {
        public static final Palette DEFAULT = new Palette(Colors.MINT, 0xFF0E1A17, 0xFFB5F5DC);
    }

    private static final class Pending {
        final String file;
        BufferedImage[] levels;
        boolean trimmed;
        Palette palette = Palette.DEFAULT;

        Pending(String file) {
            this.file = file;
        }

        void closeAll() {}
    }

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "spotify-integration-cover");
        t.setDaemon(true);
        return t;
    });
    private static final ConcurrentLinkedQueue<Pending> READY = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger NEXT_ID = new AtomicInteger();

    private static volatile Set current;
    private static volatile Set previous;
    private static String requested = "";
    private static long nextRetry;
    private static volatile int failures;
    private static volatile String lastError = "";

    private Covers() {}

    /** The cover of the song now playing (null until it has loaded, or when the song has none). */
    public static Set current() {
        return current;
    }

    /**
     * Plain-words state of the cover for the settings screen and /music status: "loaded", "none", "loading",
     * "waiting ..." or the reason it failed.
     */
    public static String status() {
        if (current != null) return "loaded";
        Snapshot s = Player.snap();
        if (!s.active()) return "none";
        if (!lastError.isEmpty()) return "could not use the picture - " + lastError;
        String note = MediaBridge.coverNote();
        if (s.cover().isEmpty()) {
            if (ConfigIo.get().webCovers && ConfigIo.get().onlineSearch && !s.isAd()) {
                return "none from Windows - looking for one online";
            }
            return note.isEmpty() || note.equals("ok") ? "waiting for the player to send one" : note;
        }
        return "loading";
    }

    /** The cover before the current one, kept for the cross-fade; null once the fade is over. */
    public static Set previous() {
        return previous;
    }

    /** 0..1: how far the cross-fade from {@link #previous()} to {@link #current()} has got. */
    public static float fade() {
        Set c = current;
        if (c == null) return 1f;
        float t = (System.nanoTime() - c.createdAt) / 600_000_000f;
        return Math.max(0f, Math.min(1f, t));
    }

    // ------------------------------------------------------------ game thread

    /** Once per client tick: starts loading a new cover and turns finished ones into textures. */
    public static void tick(Minecraft mc) {
        Snapshot s = Player.snap();
        String wanted = s.active() ? s.cover() : "";
        // Windows gave no picture: ask the public catalogues for the album cover instead.
        // (also when the picture Windows gave could not be read at all)
        if (wanted.isEmpty() || (!ArtFinder.isWeb(wanted) && current == null && failures >= 3)) {
            String web = ArtFinder.want(s);
            if (!web.isEmpty()) wanted = web;
        }
        if (!wanted.equals(requested)) {
            if (wanted.isEmpty()) {
                // The song has no cover (an ad, a local file): drop the picture after a moment so it does not
                // linger. Songs of one album keep the same file, so this is not triggered between them.
                requested = "";
                releaseAll(mc);
            } else {
                requested = wanted;
                failures = 0;
                lastError = "";
                load(wanted);
            }
        } else if (!wanted.isEmpty() && current == null && failures > 0 && failures < 6
                && System.currentTimeMillis() > nextRetry) {
            load(wanted);
        }

        Pending p = READY.poll();
        if (p != null) {
            if (!p.file.equals(requested)) {
                p.closeAll();
            } else {
                install(mc, p);
            }
        }
        Set prev = previous;
        if (prev != null && System.nanoTime() - prev.createdAt > 0 && current != null
                && System.nanoTime() - current.createdAt > 700_000_000L) {
            previous = null;
            release(mc, prev);
        }
    }

    private static void load(String file) {
        Path path = ArtFinder.isWeb(file) ? ArtFinder.path(file)
                : file.startsWith("local:") ? com.zlorbie788.spotify.local.MusicLibrary.coverPath(file.substring(6))
                : MediaBridge.coverDirectory().resolve(file);
        POOL.submit(() -> {
            try {
                READY.add(decode(path, file));
                lastError = "";
            } catch (Throwable t) {
                fail("reading " + file, t);
            }
        });
    }

    private static void install(Minecraft mc, Pending p) {
        try {
            Set set = new Set(p.levels, p.palette, p.file, p.trimmed);
            // the square texture is always wanted first; this also warms the cache
            set.texture(SIZES[0], 0f, 0f);
            Set old = current;
            if (previous != null) release(mc, previous);
            previous = old;
            current = set;
        } catch (Throwable t) {
            fail("making the textures", t);
        }
    }

    /** Throws the current cover away and loads it again (the trim setting changed). */
    public static void reload() {
        requested = "";
        failures = 0;
    }

    private static void fail(String what, Throwable t) {
        failures++;
        nextRetry = System.currentTimeMillis() + 1500L * failures;
        String reason = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
        lastError = reason;
        SpotifyClient.LOGGER.warn("[Spotify Integration Mod] cover problem while {}: {}", what, reason);
    }

    private static Identifier register(Minecraft mc, NativeImage image, String tag) {
        Identifier id = Identifier.fromNamespaceAndPath("zlorbies-spotify", "cover/c" + NEXT_ID.incrementAndGet() + tag);
        DynamicTexture texture = new DynamicTexture(() -> "zlorbie_cover_" + tag, image);
        texture.upload();
        mc.getTextureManager().register(id, texture);
        return id;
    }

    private static void release(Minecraft mc, Set set) {
        set.releaseAll(mc);
    }

    private static void releaseAll(Minecraft mc) {
        Set c = current;
        Set p = previous;
        current = null;
        previous = null;
        if (c != null) release(mc, c);
        if (p != null) release(mc, p);
    }

    // ------------------------------------------------------------ worker thread

    private static Pending decode(Path path, String file) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(bytes));
        if (source == null) {
            StringBuilder head = new StringBuilder();
            for (int i = 0; i < Math.min(8, bytes.length); i++) head.append(String.format("%02X ", bytes[i]));
            throw new IOException("format not recognised (" + bytes.length + " bytes, starts " + head.toString().trim()
                    + (MediaBridge.coverType().isEmpty() ? "" : ", " + MediaBridge.coverType()) + ")");
        }
        Pending pending = new Pending(file);
        BufferedImage square;
        int[] box = ConfigIo.get().trimCover && !file.startsWith("local:") ? findArt(source) : null;
        if (box != null) {
            square = cropSquare(source.getSubimage(box[0], box[1], box[2], box[3]));
            pending.trimmed = true;
        } else {
            square = cropSquare(source);
        }
        pending.palette = extractPalette(square);

        BufferedImage level = scale(square, SIZES[0]);
        pending.levels = new BufferedImage[SIZES.length];
        for (int i = 0; i < SIZES.length; i++) {
            if (i > 0) level = scale(level, SIZES[i]);
            pending.levels[i] = level;
        }
        return pending;
    }

    // ------------------------------------------------------------ trimming Spotify's frame

    /** Is this pixel part of an empty frame: see-through, or the same colour as the picture's corners. */
    private static boolean isFrame(int argb, int ref, boolean refClear) {
        int a = (argb >>> 24) & 0xFF;
        if (a < 24) return true;
        if (refClear) return false;
        int dr = ((argb >> 16) & 0xFF) - ((ref >> 16) & 0xFF);
        int dg = ((argb >> 8) & 0xFF) - ((ref >> 8) & 0xFF);
        int db = (argb & 0xFF) - (ref & 0xFF);
        return dr * dr + dg * dg + db * db <= 16 * 16 * 3 / 2;
    }

    /**
     * Finds the real album art inside a picture that has a frame around it: Spotify's Windows thumbnail puts the
     * art in a bigger canvas, with empty margins at the sides and its logo underneath. Returns {x, y, w, h} of the art,
     * or null when the picture has no such frame (it is then used whole). A cover that merely has a black edge is not
     * touched, because the trim only counts when all four corners agree on what "frame" looks like and the art that
     * remains is a clear square.
     */
    public static int[] findArt(BufferedImage img) {
        int W = img.getWidth();
        int H = img.getHeight();
        if (W < 24 || H < 24) return null;
        int c0 = img.getRGB(0, 0);
        int c1 = img.getRGB(W - 1, 0);
        int c2 = img.getRGB(0, H - 1);
        int c3 = img.getRGB(W - 1, H - 1);
        int[] corners = {c0, c1, c2, c3};
        int clear = 0;
        for (int c : corners) if (((c >>> 24) & 0xFF) < 24) clear++;
        boolean refClear = clear >= 3;
        int ref = c0;
        if (!refClear) {
            int agree = 0;
            for (int c : corners) if (isFrame(c, ref, false)) agree++;
            if (agree < 4) return null;
        }
        final double need = 0.97;
        int x0 = 0;
        int x1 = W;
        while (x0 < W / 2 && frameCol(img, x0, 0, H, ref, refClear) >= need) x0++;
        while (x1 > W / 2 && frameCol(img, x1 - 1, 0, H, ref, refClear) >= need) x1--;
        int w = x1 - x0;
        if (w < W * 0.45 || w <= 0) return null;
        int y0 = 0;
        while (y0 < H / 2 && frameRow(img, y0, x0, x1, ref, refClear) >= need) y0++;
        int h = H - y0;
        if (h < W * 0.30) return null;
        // the art is square: it ends where an empty gap follows it, or one width below its top
        int bottom = Math.min(H, y0 + w);
        for (int y = y0 + (int) (w * 0.9); y < Math.min(H, y0 + (int) (w * 1.1)); y++) {
            if (frameRow(img, y, x0, x1, ref, refClear) >= need) {
                bottom = y;
                break;
            }
        }
        int hh = bottom - y0;
        if (hh < w * 0.9) {
            // not a clear square: only keep the side trim when it was real
            hh = Math.min(hh, w);
        }
        int trimmedPixels = (W - w) + (H - hh);
        if (trimmedPixels < Math.max(3, Math.round(Math.min(W, H) * 0.03f))) return null;
        return new int[]{x0, y0, w, Math.max(1, hh)};
    }

    private static double frameCol(BufferedImage img, int x, int y0, int y1, int ref, boolean refClear) {
        int n = 0;
        for (int y = y0; y < y1; y++) if (isFrame(img.getRGB(x, y), ref, refClear)) n++;
        return n / (double) (y1 - y0);
    }

    private static double frameRow(BufferedImage img, int y, int x0, int x1, int ref, boolean refClear) {
        int n = 0;
        for (int x = x0; x < x1; x++) if (isFrame(img.getRGB(x, y), ref, refClear)) n++;
        return n / (double) (x1 - x0);
    }

    /** The picture zoomed in on its centre (a way to push any remaining edge out of sight). */
    static BufferedImage zoomed(BufferedImage src, float zoom) {
        int s = src.getWidth();
        int cut = Math.round(s * zoom / 2f);
        if (cut <= 0) return src;
        BufferedImage out = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(src, 0, 0, s, s, cut, cut, s - cut, s - cut, null);
        g.dispose();
        return out;
    }

    /**
     * The picture with its chosen corners cut: round (smooth, anti-aliased) or chamfered. {@code fraction} is the cut size
     * as a part of the width; 0.5 and up with all corners is a circle.
     */
    static BufferedImage cut(BufferedImage src, float fraction, boolean chamfer, int mask) {
        int s = src.getWidth();
        float r = Math.min(s / 2f, fraction * s);
        BufferedImage out = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new TexturePaint(src, new Rectangle2D.Float(0, 0, s, s)));
        java.awt.geom.Path2D.Float p = new java.awt.geom.Path2D.Float();
        boolean tl = (mask & 1) != 0, tr = (mask & 2) != 0, br = (mask & 4) != 0, bl = (mask & 8) != 0;
        float k = 0.5522847f; // quarter-circle bezier constant
        if (tl) p.moveTo(0, r); else p.moveTo(0, 0);
        if (tl) {
            if (chamfer) p.lineTo(r, 0); else p.curveTo(0, r - r * k, r - r * k, 0, r, 0);
        }
        if (tr) {
            p.lineTo(s - r, 0);
            if (chamfer) p.lineTo(s, r); else p.curveTo(s - r + r * k, 0, s, r - r * k, s, r);
        } else {
            p.lineTo(s, 0);
        }
        if (br) {
            p.lineTo(s, s - r);
            if (chamfer) p.lineTo(s - r, s); else p.curveTo(s, s - r + r * k, s - r + r * k, s, s - r, s);
        } else {
            p.lineTo(s, s);
        }
        if (bl) {
            p.lineTo(r, s);
            if (chamfer) p.lineTo(0, s - r); else p.curveTo(r - r * k, s, 0, s - r + r * k, 0, s - r);
        } else {
            p.lineTo(0, s);
        }
        p.closePath();
        g.fill(p);
        g.dispose();
        return out;
    }

    /** Centre square of the picture (covers are normally square already). */
    public static BufferedImage cropSquare(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int s = Math.min(w, h);
        int x = (w - s) / 2;
        int y = (h - s) / 2;
        BufferedImage out = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, s, s, x, y, x + s, y + s, null);
        g.dispose();
        return out;
    }

    /** Area-averaged resize: sharp and free of the jagged look a plain GPU shrink gives. */
    public static BufferedImage scale(BufferedImage src, int size) {
        if (src.getWidth() == size && src.getHeight() == size) return src;
        Image scaled = src.getScaledInstance(size, size, Image.SCALE_AREA_AVERAGING);
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(scaled, 0, 0, null);
        g.dispose();
        return out;
    }

    public static NativeImage toNative(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return NativeImage.read(NativeImage.Format.RGBA, new ByteArrayInputStream(out.toByteArray()));
    }

    /**
     * The album's main colour: the most "colourful" hue wins (weighted by saturation and brightness, so a
     * big dark or grey area does not), then it is pushed to a lively, readable strength.
     */
    public static Palette extractPalette(BufferedImage image) {
        BufferedImage small = scale(cropSquare(image), 32);
        final int bins = 12;
        double[] weight = new double[bins];
        double[] sumR = new double[bins];
        double[] sumG = new double[bins];
        double[] sumB = new double[bins];
        double avgBright = 0;
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                int argb = small.getRGB(x, y);
                if (((argb >>> 24) & 0xFF) < 128) continue;
                float[] hsv = Colors.toHsv(argb);
                avgBright += hsv[2];
                double w = hsv[1] * hsv[1] * (0.25 + hsv[2]) * (hsv[2] < 0.18f ? 0.1 : 1.0);
                int bin = Math.min(bins - 1, (int) (hsv[0] * bins));
                weight[bin] += w;
                sumR[bin] += w * ((argb >> 16) & 0xFF);
                sumG[bin] += w * ((argb >> 8) & 0xFF);
                sumB[bin] += w * (argb & 0xFF);
            }
        }
        int best = 0;
        for (int i = 1; i < bins; i++) if (weight[i] > weight[best]) best = i;
        if (weight[best] < 1.0) {
            // Black-and-white cover: a soft white-ish accent.
            return new Palette(0xFFE2E8F0, 0xFF12151C, 0xFFFFFFFF);
        }
        int r = (int) Math.round(sumR[best] / weight[best]);
        int g = (int) Math.round(sumG[best] / weight[best]);
        int b = (int) Math.round(sumB[best] / weight[best]);
        float[] hsv = Colors.toHsv(0xFF000000 | (r << 16) | (g << 8) | b);
        int accent = Colors.hsv(hsv[0], Math.max(0.5f, Math.min(0.95f, hsv[1])), Math.max(0.82f, hsv[2]));
        int dark = Colors.hsv(hsv[0], Math.max(0.35f, Math.min(0.8f, hsv[1])), 0.16f);
        int light = Colors.hsv(hsv[0], 0.28f, 1f);
        return new Palette(accent, dark, light);
    }
}
