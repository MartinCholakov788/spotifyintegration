package com.zlorbie788.spotify.catalog;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.zlorbie788.spotify.util.Http;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Deezer's public search (no account, no key). Used for songs, albums, album track lists and the charts. */
final class Deezer {
    private Deezer() {}

    static String base() {
        return System.getProperty("zlorbie.deezer", "https://api.deezer.com");
    }

    private static JsonObject fetch(String path) throws IOException {
        Http.Response r = Http.get(base() + path, null);
        if (!r.ok()) throw new IOException("Deezer answered " + r.code());
        JsonObject o = Js.parse(r.body());
        if (o.has("error")) throw new IOException("Deezer: " + (o.get("error").isJsonObject()
                ? Js.str(o.getAsJsonObject("error"), "message") : "error"));
        return o;
    }

    static List<Item> songs(String query, int limit) throws IOException {
        return parseTracks(fetch("/search?q=" + Http.enc(query) + "&limit=" + limit), null);
    }

    static List<Item> albums(String query, int limit) throws IOException {
        return parseAlbums(fetch("/search/album?q=" + Http.enc(query) + "&limit=" + limit));
    }

    static List<Item> albumTracks(Item album) throws IOException {
        return parseTracks(fetch("/album/" + Http.enc(album.id()) + "/tracks?limit=100"), album);
    }

    static List<Item> chart(int limit) throws IOException {
        return parseTracks(fetch("/chart/0/tracks?limit=" + limit), null);
    }

    // ------------------------------------------------------------ parsing (also used by the tests)

    static List<Item> parseTracks(JsonObject root, Item albumOverride) {
        List<Item> out = new ArrayList<>();
        for (JsonElement e : Js.arr(root, "data")) {
            if (!e.isJsonObject()) continue;
            JsonObject t = e.getAsJsonObject();
            String title = Js.str(t, "title");
            String id = Js.str(t, "id");
            if (title.isEmpty() || id.isEmpty()) continue;
            if (t.has("readable") && t.get("readable").isJsonPrimitive() && !t.get("readable").getAsBoolean()) continue;
            JsonObject artist = Js.obj(t, "artist");
            JsonObject album = Js.obj(t, "album");
            String albumTitle = album == null ? "" : Js.str(album, "title");
            String albumId = album == null ? "" : Js.str(album, "id");
            String small = album == null ? "" : first(Js.str(album, "cover_medium"), Js.str(album, "cover_small"));
            String big = album == null ? "" : first(Js.str(album, "cover_big"), Js.str(album, "cover_xl"));
            Item item = new Item(Item.Kind.SONG, Item.Provider.DEEZER, id, title,
                    artist == null ? "" : Js.str(artist, "name"), albumTitle, albumId,
                    Js.num(t, "duration") * 1000L, small, big, 0,
                    t.has("explicit_lyrics") && t.get("explicit_lyrics").isJsonPrimitive()
                            && t.get("explicit_lyrics").getAsBoolean(), "");
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
        for (JsonElement e : Js.arr(root, "data")) {
            if (!e.isJsonObject()) continue;
            JsonObject a = e.getAsJsonObject();
            String title = Js.str(a, "title");
            String id = Js.str(a, "id");
            if (title.isEmpty() || id.isEmpty()) continue;
            JsonObject artist = Js.obj(a, "artist");
            out.add(new Item(Item.Kind.ALBUM, Item.Provider.DEEZER, id, title,
                    artist == null ? "" : Js.str(artist, "name"), title, id,
                    0, first(Js.str(a, "cover_medium"), Js.str(a, "cover_small")),
                    first(Js.str(a, "cover_big"), Js.str(a, "cover_xl")), (int) Js.num(a, "nb_tracks"),
                    a.has("explicit_lyrics") && a.get("explicit_lyrics").isJsonPrimitive()
                            && a.get("explicit_lyrics").getAsBoolean(), ""));
        }
        return out;
    }

    private static String first(String a, String b) {
        return a == null || a.isEmpty() ? (b == null ? "" : b) : a;
    }
}
