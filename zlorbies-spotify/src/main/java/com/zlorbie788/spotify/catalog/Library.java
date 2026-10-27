package com.zlorbie788.spotify.catalog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.zlorbie788.spotify.client.SpotifyClient;
import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.media.Snapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Your own little library, kept in {@code library.json} next to the config: favourite songs, saved albums and
 * the songs you played from the search. It lives on your PC only; it is not your Spotify library (reading that
 * would need a Spotify log-in, which this mod never asks for).
 */
public final class Library {
    public static final int HISTORY_MAX = 100;

    private static final List<Item> FAVOURITES = new ArrayList<>();
    private static final List<Item> ALBUMS = new ArrayList<>();
    private static final List<Item> HISTORY = new ArrayList<>();
    private static boolean loaded;
    private static Path override;

    private Library() {}

    private static Path file() {
        return override != null ? override : ConfigIo.directory().resolve("library.json");
    }

    // ------------------------------------------------------------ reading

    public static synchronized List<Item> favourites() {
        load();
        return List.copyOf(FAVOURITES);
    }

    public static synchronized List<Item> albums() {
        load();
        return List.copyOf(ALBUMS);
    }

    public static synchronized List<Item> history() {
        load();
        return List.copyOf(HISTORY);
    }

    public static synchronized boolean isFavourite(Item item) {
        load();
        String key = item.key();
        for (Item i : FAVOURITES) if (i.key().equals(key)) return true;
        return false;
    }

    private static boolean sameSong(Item a, String title, String artist) {
        return a.isSong() && Item.norm(com.zlorbie788.spotify.lyrics.Lyrics.cleanTitle(a.title()))
                .equals(Item.norm(com.zlorbie788.spotify.lyrics.Lyrics.cleanTitle(title)))
                && Item.norm(com.zlorbie788.spotify.lyrics.Lyrics.firstArtist(a.artist()))
                .equals(Item.norm(com.zlorbie788.spotify.lyrics.Lyrics.firstArtist(artist)));
    }

    /** True when a favourite has this title and (first) artist, whichever catalogue it came from. */
    public static synchronized boolean isFavouriteSong(String title, String artist) {
        load();
        for (Item i : FAVOURITES) if (sameSong(i, title, artist)) return true;
        return false;
    }

    /** Heart for the song that is playing: adds it, or removes every favourite with the same title and artist. */
    public static synchronized boolean toggleFavouriteSong(Item local) {
        load();
        boolean removed = FAVOURITES.removeIf(i -> sameSong(i, local.title(), local.artist()));
        if (!removed) FAVOURITES.add(0, local);
        save();
        return !removed;
    }

    public static synchronized boolean isSaved(Item album) {
        load();
        String key = album.key();
        for (Item i : ALBUMS) if (i.key().equals(key)) return true;
        return false;
    }

    /** The song that is playing, as an item (for the heart button). Null when nothing is playing. */
    public static Item fromSnapshot(Snapshot s) {
        if (s == null || !s.active() || s.isAd() || s.title().isBlank()) return null;
        return new Item(Item.Kind.SONG, Item.Provider.LOCAL, "", s.title(), s.artist(), s.album(), "", s.durMs(),
                "", "", 0, false, "");
    }

    /** Same, but for the album of the song that is playing (null when it has no album name). */
    public static Item albumFromSnapshot(Snapshot s) {
        if (s == null || !s.active() || s.isAd() || s.album().isBlank()) return null;
        return new Item(Item.Kind.ALBUM, Item.Provider.LOCAL, "", s.album(), s.artist(), s.album(), "", 0,
                "", "", 0, false, "");
    }

    // ------------------------------------------------------------ changing

    /** Adds or removes. Returns true when the item is a favourite afterwards. */
    public static synchronized boolean toggleFavourite(Item item) {
        load();
        String key = item.key();
        boolean removed = FAVOURITES.removeIf(i -> i.key().equals(key));
        if (!removed) FAVOURITES.add(0, item);
        save();
        return !removed;
    }

    public static synchronized boolean toggleAlbum(Item album) {
        load();
        String key = album.key();
        boolean removed = ALBUMS.removeIf(i -> i.key().equals(key));
        if (!removed) ALBUMS.add(0, album);
        save();
        return !removed;
    }

    /** Remembers a played song (newest first, no repeats). Does nothing when history is turned off. */
    public static synchronized void played(Item item) {
        if (!ConfigIo.get().saveHistory) return;
        load();
        String key = item.key();
        HISTORY.removeIf(i -> i.key().equals(key));
        HISTORY.add(0, item);
        while (HISTORY.size() > HISTORY_MAX) HISTORY.remove(HISTORY.size() - 1);
        save();
    }

    public static synchronized void clearHistory() {
        load();
        HISTORY.clear();
        save();
    }

    /** Fills in the Spotify link of a saved item once it is known, so the next play is instant. */
    public static synchronized void learnSpotify(Item item, String uri) {
        if (uri == null || uri.isEmpty()) return;
        load();
        String key = item.key();
        boolean changed = false;
        for (List<Item> list : List.of(FAVOURITES, ALBUMS, HISTORY)) {
            for (int i = 0; i < list.size(); i++) {
                Item it = list.get(i);
                if (it.key().equals(key) && !uri.equals(it.spotify())) {
                    list.set(i, it.withSpotify(uri));
                    changed = true;
                }
            }
        }
        if (changed) save();
    }

    // ------------------------------------------------------------ file

    private static void load() {
        if (loaded) return;
        loaded = true;
        FAVOURITES.clear();
        ALBUMS.clear();
        HISTORY.clear();
        Path f = file();
        if (!Files.exists(f)) return;
        try {
            JsonObject root = Js.parse(Files.readString(f, StandardCharsets.UTF_8));
            read(root, "favourites", FAVOURITES);
            read(root, "albums", ALBUMS);
            read(root, "history", HISTORY);
        } catch (IOException | RuntimeException e) {
            SpotifyClient.LOGGER.warn("[Spotify Integration Mod] library.json could not be read ({}). Starting empty.", e.toString());
            try {
                Files.copy(f, f.resolveSibling("library.json.bak"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) { }
        }
    }

    private static void read(JsonObject root, String key, List<Item> into) {
        for (JsonElement e : Js.arr(root, key)) {
            if (!e.isJsonObject()) continue;
            Item item = Item.fromJson(e.getAsJsonObject());
            if (item != null) into.add(item);
        }
    }

    private static JsonArray write(List<Item> list) {
        JsonArray a = new JsonArray();
        for (Item i : list) a.add(i.toJson());
        return a;
    }

    private static void save() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("favourites", write(FAVOURITES));
        root.add("albums", write(ALBUMS));
        root.add("history", write(HISTORY));
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling("library.json.tmp");
            Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            SpotifyClient.LOGGER.warn("[Spotify Integration Mod] could not save library.json: {}", e.toString());
        }
    }

    /** For tests: use another file and forget what is loaded. */
    static synchronized void useFile(Path path) {
        override = path;
        loaded = false;
    }
}
