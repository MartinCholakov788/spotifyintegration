package com.zlorbie788.spotify.catalog;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.util.Http;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Apple's public iTunes Search API (no account, no key). The fallback when Deezer cannot be reached. */
final class Itunes {
    private Itunes() {}

    static String base() {
        return System.getProperty("zlorbie.itunes", "https://itunes.apple.com");
    }

    private static String country() {
        return ConfigIo.get().searchCountry;
    }

    private static JsonObject fetch(String path) throws IOException {
        Http.Response r = Http.get(base() + path, null);
        if (!r.ok()) throw new IOException("Apple Music answered " + r.code());
        return Js.parse(r.body());
    }

    static List<Item> songs(String query, int limit) throws IOException {
        return parseSongs(fetch("/search?media=music&entity=song&limit=" + limit + "&country=" + country()
                + "&term=" + Http.enc(query)), null);
    }

    static List<Item> albums(String query, int limit) throws IOException {
        return parseAlbums(fetch("/search?media=music&entity=album&limit=" + limit + "&country=" + country()
                + "&term=" + Http.enc(query)));
    }

    static List<Item> albumTracks(Item album) throws IOException {
        return parseSongs(fetch("/lookup?entity=song&limit=200&country=" + country() + "&id=" + Http.enc(album.id())),
                album);
    }

    // ------------------------------------------------------------ parsing (also used by the tests)

    static List<Item> parseSongs(JsonObject root, Item albumOverride) {
        List<Item> out = new ArrayList<>();
        for (JsonElement e : Js.arr(root, "results")) {
            if (!e.isJsonObject()) continue;
            JsonObject t = e.getAsJsonObject();
            if (!"track".equals(Js.str(t, "wrapperType")) || !"song".equals(Js.str(t, "kind"))) continue;
            String title = Js.str(t, "trackName");
            String id = Js.str(t, "trackId");
            if (title.isEmpty() || id.isEmpty()) continue;
            String art = Js.str(t, "artworkUrl100");
            Item item = new Item(Item.Kind.SONG, Item.Provider.ITUNES, id, title, Js.str(t, "artistName"),
                    Js.str(t, "collectionName"), Js.str(t, "collectionId"), Js.num(t, "trackTimeMillis"),
                    sized(art, 200), sized(art, 600), 0, "explicit".equals(Js.str(t, "trackExplicitness")), "");
            if (albumOverride != null) {
                item = item.withAlbum(albumOverride.title(), albumOverride.id(), albumOverride.cover(),
                        albumOverride.coverBig());
            }
            out.add(item);
        }
        return out;
    }

    static List<Item> parseAlbums(JsonObject root) {
        List<Item> out = new ArrayList<>();
        for (JsonElement e : Js.arr(root, "results")) {
            if (!e.isJsonObject()) continue;
            JsonObject a = e.getAsJsonObject();
            String title = Js.str(a, "collectionName");
            String id = Js.str(a, "collectionId");
            if (title.isEmpty() || id.isEmpty()) continue;
            String art = Js.str(a, "artworkUrl100");
            out.add(new Item(Item.Kind.ALBUM, Item.Provider.ITUNES, id, title, Js.str(a, "artistName"), title, id, 0,
                    sized(art, 200), sized(art, 600), (int) Js.num(a, "trackCount"),
                    "explicit".equals(Js.str(a, "collectionExplicitness")), ""));
        }
        return out;
    }

    /** Apple serves any size by editing the number in the picture address (".../100x100bb.jpg"). */
    static String sized(String url, int size) {
        if (url == null || url.isEmpty()) return "";
        return url.replaceFirst("/\\d+x\\d+(bb|cc)?\\.(jpg|png)$", "/" + size + "x" + size + "bb.$2");
    }
}
