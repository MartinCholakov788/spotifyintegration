package com.zlorbie788.spotify.local;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zlorbie788.spotify.audio.Decoders;
import com.zlorbie788.spotify.audio.PcmSource;
import com.zlorbie788.spotify.config.ConfigIo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Your own music folder ({@code config/zlorbies-spotify/music}). It looks for MP3, OGG, WAV and FLAC files (also
 * inside sub-folders), reads their tags and covers once, and remembers the result in {@code library-index.json}
 * so the next start is instant. New or changed files are picked up automatically.
 */
public final class MusicLibrary {
    public enum Sort {
        TITLE("Title"), ARTIST("Artist"), ALBUM("Album"), NEWEST("Newest");

        public final String label;

        Sort(String label) {
            this.label = label;
        }

        public Sort next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    static final int MAX_FILES = 30_000;
    static final int MAX_DEPTH = 8;
    private static final long POLL_MS = 10_000L;
    private static final String[] FOLDER_COVERS = {"cover", "folder", "front", "album", "albumart"};
    private static final String[] COVER_EXT = {"jpg", "jpeg", "png"};
    private static final Pattern LEADING_NUMBER = Pattern.compile("^\\s*\\d{1,3}\\s*[-._)]\\s*");

    private static volatile List<Track> tracks = List.of();
    private static volatile List<String> problems = List.of();
    private static volatile boolean scanning;
    private static volatile boolean everScanned;
    private static volatile long revision;
    private static volatile long lastScanEnd;
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static volatile Map<String, Track> byId = Map.of();
    /** Files that could not be read: id -> "size/mtime|reason", so they are not tried again until they change. */
    private static final Map<String, String> FAILED = new HashMap<>();

    private static Path musicOverride;
    private static Path dataOverride;

    private MusicLibrary() {}

    // ------------------------------------------------------------ places

    public static Path directory() {
        return musicOverride != null ? musicOverride : ConfigIo.directory().resolve("music");
    }

    static Path dataDirectory() {
        return dataOverride != null ? dataOverride : ConfigIo.directory();
    }

    public static Path coverDirectory() {
        return dataDirectory().resolve("localcovers");
    }

    /** The file of a cover name ({@link Track#cover}). */
    public static Path coverPath(String name) {
        return coverDirectory().resolve(name);
    }

    /** For tests: use other folders. */
    public static void useDirectories(Path music, Path data) {
        musicOverride = music;
        dataOverride = data;
        tracks = List.of();
        byId = Map.of();
        problems = List.of();
        FAILED.clear();
        everScanned = false;
        lastScanEnd = 0L;
        revision++;
    }

    /** Creates the music folder (with a short note inside) if it is not there yet. */
    public static void ensureDirectory() {
        try {
            Path dir = directory();
            Files.createDirectories(dir);
            Path note = dir.resolve("PUT YOUR SONGS HERE.txt");
            if (!Files.exists(note) && isEmptyOfSongs(dir)) {
                Files.writeString(note, "Spotify Integration Mod\r\n\r\nDrop your songs in this folder (MP3, OGG, WAV or FLAC;"
                        + " folders inside it work too).\r\nThey show up in the player's Library by themselves.\r\n",
                        StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException ignored) {
            // read-only config folder: the library just stays empty
        }
    }

    private static boolean isEmptyOfSongs(Path dir) {
        try (var s = Files.list(dir)) {
            return s.noneMatch(Decoders::supported);
        } catch (IOException e) {
            return true;
        }
    }

    // ------------------------------------------------------------ readings

    public static List<Track> all() {
        return tracks;
    }

    public static int count() {
        return tracks.size();
    }

    public static boolean isEmpty() {
        return tracks.isEmpty();
    }

    public static Track byId(String id) {
        return id == null ? null : byId.get(id);
    }

    public static boolean scanning() {
        return scanning;
    }

    /** True once a scan has finished at least once since the game started. */
    public static boolean ready() {
        return everScanned;
    }

    /** Increases whenever the list of songs changed (screens redraw their lists). */
    public static long revision() {
        return revision;
    }

    /** Files that were found but could not be read, with the reason ("song.ogg: not a Vorbis file"). */
    public static List<String> problems() {
        return problems;
    }

    /** Songs matching all the words typed, in the order wanted. An empty text gives everything. */
    public static List<Track> search(String text, Sort sort) {
        List<Track> src = tracks;
        List<Track> out = new ArrayList<>(src.size());
        String q = Track.fold(text == null ? "" : text).strip();
        if (q.isEmpty()) {
            out.addAll(src);
        } else {
            String[] words = q.split("\\s+");
            for (Track t : src) {
                String h = t.haystack();
                boolean ok = true;
                for (String w : words) {
                    if (!h.contains(w)) {
                        ok = false;
                        break;
                    }
                }
                if (ok) out.add(t);
            }
        }
        out.sort(comparator(sort));
        return out;
    }

    public static Comparator<Track> comparator(Sort sort) {
        Comparator<Track> byTitle = Comparator.comparing((Track t) -> Track.fold(t.title)).thenComparing(t -> t.id);
        return switch (sort == null ? Sort.TITLE : sort) {
            case TITLE -> byTitle;
            case ARTIST -> Comparator.comparing((Track t) -> Track.fold(t.artist).isEmpty() ? "￿" : Track.fold(t.artist))
                    .thenComparing(t -> Track.fold(t.album))
                    .thenComparingInt(t -> t.trackNo == 0 ? Integer.MAX_VALUE : t.trackNo)
                    .thenComparing(byTitle);
            case ALBUM -> Comparator.comparing((Track t) -> Track.fold(t.album).isEmpty() ? "￿" : Track.fold(t.album))
                    .thenComparingInt(t -> t.trackNo == 0 ? Integer.MAX_VALUE : t.trackNo)
                    .thenComparing(byTitle);
            case NEWEST -> Comparator.comparingLong((Track t) -> -t.addedAt).thenComparing(byTitle);
        };
    }

    /** The newest files first. */
    public static List<Track> recentlyAdded(int limit) {
        List<Track> list = search("", Sort.NEWEST);
        return list.size() > limit ? new ArrayList<>(list.subList(0, limit)) : list;
    }

    /** Songs never played from this mod (count taken from the stats). */
    public static List<Track> notPlayedYet() {
        List<Track> out = new ArrayList<>();
        for (Track t : search("", Sort.ARTIST)) {
            if (com.zlorbie788.spotify.stats.Stats.playsOf(t.title, t.artist) == 0) out.add(t);
        }
        return out;
    }

    /** Your most played songs first (songs with no plays are left out). */
    public static List<Track> mostPlayed(int limit) {
        List<Track> out = new ArrayList<>();
        Map<String, Integer> plays = new HashMap<>();
        for (Track t : tracks) {
            int p = com.zlorbie788.spotify.stats.Stats.playsOf(t.title, t.artist);
            if (p > 0) {
                plays.put(t.id, p);
                out.add(t);
            }
        }
        out.sort(Comparator.comparingInt((Track t) -> -plays.get(t.id)).thenComparing(t -> Track.fold(t.title)));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    /** The decoder found the exact length while playing: remember it. */
    public static synchronized void updateDuration(String id, long ms) {
        Track t = byId.get(id);
        if (t == null || ms <= 0 || Math.abs(t.durMs - ms) < 200) return;
        replace(t, t.withDuration(ms));
        saveIndex(tracks);
    }

    /** Replaces one song (after its tags were fixed). */
    public static synchronized void replace(Track old, Track fresh) {
        List<Track> copy = new ArrayList<>(tracks);
        int i = copy.indexOf(old);
        if (i < 0) return;
        copy.set(i, fresh);
        publish(copy);
    }

    /** Replaces one song and remembers the change (tags filled in from the internet). The file itself is never touched. */
    public static synchronized void fix(Track old, Track fresh) {
        replace(old, fresh);
        saveIndex(tracks);
    }

    private static void publish(List<Track> list) {
        Map<String, Track> map = new HashMap<>(list.size() * 2);
        for (Track t : list) map.put(t.id, t);
        tracks = Collections.unmodifiableList(list);
        byId = map;
        revision++;
    }

    // ------------------------------------------------------------ scanning

    /**
     * Called from the client tick: starts a background scan the first time and then looks for new files every
     * ten seconds. Cheap when nothing changed.
     */
    public static void poll() {
        if (scanning) return;
        long now = System.currentTimeMillis();
        if (!everScanned || now - lastScanEnd > POLL_MS) rescanAsync();
    }

    public static void rescanAsync() {
        if (!RUNNING.compareAndSet(false, true)) return;
        scanning = true;
        Thread t = new Thread(() -> {
            try {
                scanNow();
            } catch (Throwable e) {
                problems = List.of("The music folder could not be scanned: " + e);
            } finally {
                scanning = false;
                everScanned = true;
                lastScanEnd = System.currentTimeMillis();
                RUNNING.set(false);
            }
        }, "spotify-integration-library");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** Scans in the calling thread (tests, and the background thread). */
    public static synchronized void scanNow() {
        Path dir = directory();
        Map<String, Track> known = new HashMap<>(byId);
        if (known.isEmpty()) known = loadIndex();
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try {
                Files.walkFileTree(dir, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), MAX_DEPTH,
                        new SimpleFileVisitor<>() {
                            @Override
                            public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                                if (a.isRegularFile() && Decoders.supported(f) && files.size() < MAX_FILES) files.add(f);
                                return FileVisitResult.CONTINUE;
                            }

                            @Override
                            public FileVisitResult visitFileFailed(Path f, IOException e) {
                                return FileVisitResult.CONTINUE;
                            }
                        });
            } catch (IOException ignored) {
                // whatever was found so far is used
            }
        }
        Collections.sort(files);
        List<Track> out = new ArrayList<>(files.size());
        List<String> bad = new ArrayList<>();
        int fresh = 0;
        for (Path f : files) {
            String id = dir.relativize(f).toString().replace('\\', '/');
            BasicFileAttributes attrs;
            try {
                attrs = Files.readAttributes(f, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException e) {
                continue;
            }
            long mtime = attrs.lastModifiedTime().toMillis();
            Track old = known.get(id);
            if (old != null && old.size == attrs.size() && old.mtime == mtime
                    && (old.cover.isEmpty() || Files.exists(coverPath(old.cover)))) {
                out.add(old);
                continue;
            }
            String sig = attrs.size() + "/" + mtime;
            String failed = FAILED.get(id);
            if (failed != null && failed.startsWith(sig + "|")) {
                bad.add(f.getFileName() + ": " + failed.substring(sig.length() + 1));
                continue;
            }
            fresh++;
            try {
                out.add(index(dir, f, id, attrs));
                FAILED.remove(id);
            } catch (Throwable e) {
                String why = reason(e);
                FAILED.put(id, sig + "|" + why);
                bad.add(f.getFileName() + ": " + why);
            }
            // show the first results while the rest is still being read
            if (tracks.isEmpty() && fresh % 25 == 0) publish(new ArrayList<>(out));
        }
        problems = List.copyOf(bad);
        if (fresh > 0 || !sameIds(out, tracks)) {
            publish(out);
            saveIndex(out);
        }
        everScanned = true;
        lastScanEnd = System.currentTimeMillis();
    }

    private static boolean sameIds(List<Track> a, List<Track> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!a.get(i).id.equals(b.get(i).id)) return false;
        return true;
    }

    private static String reason(Throwable e) {
        String m = e.getMessage();
        if (m == null || m.isBlank()) m = e.getClass().getSimpleName();
        return m.length() > 90 ? m.substring(0, 90) : m;
    }

    private static Track index(Path dir, Path file, String id, BasicFileAttributes attrs) throws IOException {
        long durMs;
        String format;
        try (PcmSource src = Decoders.open(file)) {
            format = src.format();
            long len = src.length();
            durMs = len > 0 && src.sampleRate() > 0 ? len * 1000L / src.sampleRate() : 0L;
        }
        Tags.Meta meta = Tags.read(file);
        String base = file.getFileName().toString();
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        String title = meta.title();
        String artist = meta.artist();
        if (title.isEmpty()) {
            String[] parts = fromFileName(base);
            title = parts[1];
            if (artist.isEmpty()) artist = parts[0];
        }
        String cover = "";
        try {
            if (meta.hasCover()) cover = storeCover(meta.cover());
            if (cover.isEmpty()) cover = folderCover(file.getParent());
        } catch (IOException | RuntimeException ignored) {
            // no cover is fine
        }
        long created = attrs.creationTime().toMillis();
        long modified = attrs.lastModifiedTime().toMillis();
        long added = created > 0 ? Math.min(created, Math.max(modified, created)) : modified;
        if (added <= 0) added = modified;
        return new Track(id, title, artist, meta.album(), meta.track(), meta.year(), durMs, added, attrs.size(),
                modified, cover, !meta.lyrics().isEmpty(), format);
    }

    /** "01 - Artist - Title" gives {"Artist", "Title"}; a name without " - " gives {"", name}. */
    static String[] fromFileName(String base) {
        String s = base.replace('_', ' ').strip();
        Matcher m = LEADING_NUMBER.matcher(s);
        if (m.find() && m.end() < s.length()) s = s.substring(m.end());
        int dash = s.indexOf(" - ");
        if (dash > 0 && dash + 3 < s.length()) {
            return new String[]{s.substring(0, dash).strip(), s.substring(dash + 3).strip()};
        }
        return new String[]{"", s.isEmpty() ? base : s};
    }

    // ------------------------------------------------------------ covers

    private static String imageExtension(byte[] b) {
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "png";
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return "jpg";
        if (b.length > 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F') return "gif";
        if (b.length > 2 && b[0] == 'B' && b[1] == 'M') return "bmp";
        return "";
    }

    /** Saves a cover picture once (named after its content, so an album's songs share it); returns the name or "". */
    static String storeCover(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > 12 * 1024 * 1024) return "";
        String ext = imageExtension(bytes);
        if (ext.isEmpty()) return "";
        String name = hash(bytes) + "." + ext;
        Path file = coverPath(name);
        if (!Files.exists(file)) {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(name + ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        return name;
    }

    private static String folderCover(Path folder) throws IOException {
        if (folder == null) return "";
        for (String base : FOLDER_COVERS) {
            for (String ext : COVER_EXT) {
                Path p = folder.resolve(base + "." + ext);
                if (Files.isRegularFile(p)) return storeCover(Files.readAllBytes(p));
                p = folder.resolve(base.substring(0, 1).toUpperCase(Locale.ROOT) + base.substring(1) + "." + ext);
                if (Files.isRegularFile(p)) return storeCover(Files.readAllBytes(p));
            }
        }
        return "";
    }

    private static String hash(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(data);
            StringBuilder b = new StringBuilder(16);
            for (int i = 0; i < 8; i++) b.append(String.format("%02x", d[i] & 0xFF));
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------ the saved index

    private static Path indexFile() {
        return dataDirectory().resolve("library-index.json");
    }

    private static Map<String, Track> loadIndex() {
        Map<String, Track> map = new HashMap<>();
        Path f = indexFile();
        if (!Files.exists(f)) return map;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement e : root.getAsJsonArray("tracks")) {
                JsonObject o = e.getAsJsonObject();
                Track t = new Track(s(o, "id"), s(o, "title"), s(o, "artist"), s(o, "album"), (int) n(o, "trackNo"),
                        (int) n(o, "year"), n(o, "durMs"), n(o, "addedAt"), n(o, "size"), n(o, "mtime"),
                        s(o, "cover"), o.has("lyrics") && o.get("lyrics").getAsBoolean(), s(o, "format"));
                if (!t.id.isEmpty()) map.put(t.id, t);
            }
        } catch (Exception ignored) {
            map.clear(); // damaged index: read everything again
        }
        return map;
    }

    private static void saveIndex(List<Track> list) {
        try {
            JsonArray arr = new JsonArray();
            for (Track t : list) {
                JsonObject o = new JsonObject();
                o.addProperty("id", t.id);
                o.addProperty("title", t.title);
                o.addProperty("artist", t.artist);
                o.addProperty("album", t.album);
                o.addProperty("trackNo", t.trackNo);
                o.addProperty("year", t.year);
                o.addProperty("durMs", t.durMs);
                o.addProperty("addedAt", t.addedAt);
                o.addProperty("size", t.size);
                o.addProperty("mtime", t.mtime);
                o.addProperty("cover", t.cover);
                o.addProperty("lyrics", t.lyrics);
                o.addProperty("format", t.format);
                arr.add(o);
            }
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            root.add("tracks", arr);
            Files.createDirectories(dataDirectory());
            Path tmp = indexFile().resolveSibling("library-index.json.tmp");
            Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, indexFile(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // the index is only a speed-up
        }
    }

    private static String s(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    private static long n(JsonObject o, String k) {
        try {
            return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsLong() : 0L;
        } catch (RuntimeException e) {
            return 0L;
        }
    }
}
