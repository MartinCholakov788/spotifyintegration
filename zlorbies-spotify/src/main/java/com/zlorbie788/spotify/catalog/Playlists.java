package com.zlorbie788.spotify.catalog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.zlorbie788.spotify.client.SpotifyClient;
import com.zlorbie788.spotify.config.ConfigIo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Your own named playlists, kept in {@code playlists.json} next to the config. They live on this PC only (no
 * Spotify account is involved): the songs are found and played through the same search and link service as everything
 * else. {@link PlaylistRunner} plays a list one song after the other.
 */
public final class Playlists {
    public static final int MAX_LISTS = 40;
    public static final int MAX_SONGS = 300;
    public static final int MAX_NAME = 28;

    private static final Map<String, List<Item>> LISTS = new LinkedHashMap<>();
    private static boolean loaded;
    private static Path override;

    private Playlists() {}

    private static Path file() {
        return override != null ? override : ConfigIo.directory().resolve("playlists.json");
    }

    // ------------------------------------------------------------ reading

    public static synchronized List<String> names() {
        load();
        return new ArrayList<>(LISTS.keySet());
    }

    public static synchronized int count() {
        load();
        return LISTS.size();
    }

    public static synchronized List<Item> songs(String name) {
        load();
        List<Item> l = LISTS.get(name);
        return l == null ? List.of() : List.copyOf(l);
    }

    public static synchronized boolean exists(String name) {
        load();
        return LISTS.containsKey(name);
    }

    public static synchronized boolean contains(String name, Item song) {
        load();
        List<Item> l = LISTS.get(name);
        if (l == null) return false;
        for (Item i : l) if (same(i, song)) return true;
        return false;
    }

    private static boolean same(Item a, Item b) {
        if (a.key().equals(b.key())) return true;
        return Item.norm(a.title()).equals(Item.norm(b.title()))
                && Item.norm(a.artist()).equals(Item.norm(b.artist()));
    }

    // ------------------------------------------------------------ changing

    /** A usable name: trimmed, no control characters, at most {@link #MAX_NAME} characters, "Playlist" when empty. */
    public static String cleanName(String raw) {
        String s = raw == null ? "" : raw.replaceAll("\\p{Cntrl}", "").strip();
        if (s.length() > MAX_NAME) s = s.substring(0, MAX_NAME).strip();
        return s.isEmpty() ? "Playlist" : s;
    }

    /** Makes a new empty playlist. A name that is already taken gets " 2", " 3"... Returns the name, or null at the limit. */
    public static synchronized String create(String wanted) {
        load();
        if (LISTS.size() >= MAX_LISTS) return null;
        String base = cleanName(wanted);
        String name = base;
        for (int n = 2; LISTS.containsKey(name); n++) {
            String suffix = " " + n;
            name = (base.length() + suffix.length() > MAX_NAME ? base.substring(0, MAX_NAME - suffix.length()) : base) + suffix;
        }
        LISTS.put(name, new ArrayList<>());
        save();
        return name;
    }

    /** Renames a playlist, keeping its place in the list. Returns the final name, or null if it does not exist. */
    public static synchronized String rename(String oldName, String wanted) {
        load();
        if (!LISTS.containsKey(oldName)) return null;
        String base = cleanName(wanted);
        if (base.equals(oldName)) return oldName;
        String name = base;
        for (int n = 2; LISTS.containsKey(name); n++) name = base + " " + n;
        Map<String, List<Item>> copy = new LinkedHashMap<>(LISTS);
        LISTS.clear();
        for (Map.Entry<String, List<Item>> e : copy.entrySet()) {
            LISTS.put(e.getKey().equals(oldName) ? name : e.getKey(), e.getValue());
        }
        save();
        return name;
    }

    public static synchronized boolean delete(String name) {
        load();
        boolean removed = LISTS.remove(name) != null;
        if (removed) save();
        return removed;
    }

    /** Adds a song at the end. False when it is already there, the list is full, or there is no such list. */
    public static synchronized boolean add(String name, Item song) {
        load();
        List<Item> l = LISTS.get(name);
        if (l == null || song == null || !song.isSong() || l.size() >= MAX_SONGS || contains(name, song)) return false;
        l.add(song);
        save();
        return true;
    }

    public static synchronized boolean removeAt(String name, int index) {
        load();
        List<Item> l = LISTS.get(name);
        if (l == null || index < 0 || index >= l.size()) return false;
        l.remove(index);
        save();
        return true;
    }

    /** Moves a song to a new place in its list. */
    public static synchronized boolean move(String name, int from, int to) {
        load();
        List<Item> l = LISTS.get(name);
        if (l == null || from < 0 || from >= l.size() || to < 0 || to >= l.size() || from == to) return false;
        l.add(to, l.remove(from));
        save();
        return true;
    }

    // ------------------------------------------------------------ file

    private static void load() {
        if (loaded) return;
        loaded = true;
        LISTS.clear();
        Path f = file();
        if (!Files.exists(f)) return;
        try {
            JsonObject root = Js.parse(Files.readString(f, StandardCharsets.UTF_8));
            for (JsonElement e : Js.arr(root, "lists")) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                String name = cleanName(Js.str(o, "name"));
                if (LISTS.containsKey(name) || LISTS.size() >= MAX_LISTS) continue;
                List<Item> songs = new ArrayList<>();
                for (JsonElement s : Js.arr(o, "songs")) {
                    if (!s.isJsonObject() || songs.size() >= MAX_SONGS) continue;
                    Item it = Item.fromJson(s.getAsJsonObject());
                    if (it != null && it.isSong()) songs.add(it);
                }
                LISTS.put(name, songs);
            }
        } catch (IOException | RuntimeException e) {
            SpotifyClient.LOGGER.warn("[Spotify Integration Mod] playlists.json could not be read ({}). Starting empty.", e.toString());
            try {
                Files.copy(f, f.resolveSibling("playlists.json.bak"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) { }
        }
    }

    private static void save() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonArray lists = new JsonArray();
        for (Map.Entry<String, List<Item>> e : LISTS.entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", e.getKey());
            JsonArray songs = new JsonArray();
            for (Item i : e.getValue()) songs.add(i.toJson());
            o.add("songs", songs);
            lists.add(o);
        }
        root.add("lists", lists);
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling("playlists.json.tmp");
            Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            SpotifyClient.LOGGER.warn("[Spotify Integration Mod] could not save playlists.json: {}", e.toString());
        }
    }

    /** For tests: use another file and forget what is loaded. */
    static synchronized void useFile(Path path) {
        override = path;
        loaded = false;
    }
}
