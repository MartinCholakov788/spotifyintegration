package com.zlorbie788.spotify.catalog;

import com.google.gson.JsonObject;

import java.util.Locale;

/**
 * One song or album found in a public music catalogue (or taken from the song that is playing).
 * {@code spotify} is the Spotify link (spotify:track:...) once it is known, or empty.
 */
public record Item(Kind kind, Provider provider, String id, String title, String artist, String album, String albumId,
                   long durationMs, String cover, String coverBig, int tracks, boolean explicit, String spotify) {

    public enum Kind { SONG, ALBUM }

    /** Where the item came from. LOCAL = read from the player (the song you are listening to). */
    public enum Provider { DEEZER, ITUNES, SPOTIFY, LOCAL }

    public Item {
        id = id == null ? "" : id;
        title = title == null ? "" : title;
        artist = artist == null ? "" : artist;
        album = album == null ? "" : album;
        albumId = albumId == null ? "" : albumId;
        cover = cover == null ? "" : cover;
        coverBig = coverBig == null ? "" : coverBig;
        spotify = spotify == null ? "" : spotify;
    }

    /** Prefix of {@link #id()} for a song file of the music folder (the rest is the path inside the folder). */
    public static final String FILE_PREFIX = "file:";

    /** The item for a song of the music folder (so playlists and favourites can hold it). */
    public static Item forTrack(com.zlorbie788.spotify.local.Track t) {
        return new Item(Kind.SONG, Provider.LOCAL, FILE_PREFIX + t.id, t.title, t.artist, t.album, "", t.durMs,
                t.cover.isEmpty() ? "" : "local:" + t.cover, "", 0, false, "");
    }

    /** True for a song file of the music folder. */
    public boolean isFile() {
        return provider == Provider.LOCAL && id.startsWith(FILE_PREFIX);
    }

    /** The library entry of a song file (null when the file is gone, or this is not a file). */
    public com.zlorbie788.spotify.local.Track track() {
        return isFile() ? com.zlorbie788.spotify.local.MusicLibrary.byId(id.substring(FILE_PREFIX.length())) : null;
    }

    public boolean isSong() {
        return kind == Kind.SONG;
    }

    public boolean isAlbum() {
        return kind == Kind.ALBUM;
    }

    /** Stable key for lists and caches. */
    public String key() {
        if (isFile()) return "local:file:" + id.substring(FILE_PREFIX.length());
        if (provider == Provider.LOCAL || id.isEmpty()) {
            return "local:" + kind.name().toLowerCase(Locale.ROOT) + ":" + norm(title) + "|" + norm(firstWord(artist));
        }
        return provider.name().toLowerCase(Locale.ROOT) + ":" + kind.name().toLowerCase(Locale.ROOT) + ":" + id;
    }

    public Item withSpotify(String uri) {
        return new Item(kind, provider, id, title, artist, album, albumId, durationMs, cover, coverBig, tracks,
                explicit, uri);
    }

    public Item withAlbum(String albumTitle, String albumIdValue, String smallCover, String bigCover) {
        return new Item(kind, provider, id, title, artist, albumTitle, albumIdValue, durationMs,
                cover.isEmpty() ? smallCover : cover, coverBig.isEmpty() ? bigCover : coverBig, tracks, explicit, spotify);
    }

    /** The best picture address for the size wanted. */
    public String picture(boolean big) {
        if (big && !coverBig.isEmpty()) return coverBig;
        return cover.isEmpty() ? coverBig : cover;
    }

    public String subtitle() {
        if (isAlbum()) {
            String t = tracks > 0 ? tracks + (tracks == 1 ? " song" : " songs") : "";
            return artist.isEmpty() ? t : (t.isEmpty() ? artist : artist + " · " + t);
        }
        return album.isEmpty() ? artist : artist + " · " + album;
    }

    // ------------------------------------------------------------ saving

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("kind", kind.name());
        o.addProperty("provider", provider.name());
        o.addProperty("id", id);
        o.addProperty("title", title);
        o.addProperty("artist", artist);
        o.addProperty("album", album);
        o.addProperty("albumId", albumId);
        o.addProperty("durationMs", durationMs);
        o.addProperty("cover", cover);
        o.addProperty("coverBig", coverBig);
        o.addProperty("tracks", tracks);
        o.addProperty("explicit", explicit);
        o.addProperty("spotify", spotify);
        return o;
    }

    public static Item fromJson(JsonObject o) {
        try {
            Kind k = Kind.valueOf(Js.str(o, "kind"));
            Provider p = Provider.valueOf(Js.str(o, "provider"));
            String title = Js.str(o, "title");
            if (title.isEmpty()) return null;
            return new Item(k, p, Js.str(o, "id"), title, Js.str(o, "artist"), Js.str(o, "album"),
                    Js.str(o, "albumId"), Js.num(o, "durationMs"), Js.str(o, "cover"), Js.str(o, "coverBig"),
                    (int) Js.num(o, "tracks"), o.has("explicit") && o.get("explicit").getAsBoolean(),
                    Js.str(o, "spotify"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------ text matching

    /** Lower-case letters and digits only, so "Don't Stop (Remastered)" and "dont stop remastered" compare equal. */
    public static String norm(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    private static String firstWord(String s) {
        int i = s.indexOf(' ');
        return i > 0 ? s.substring(0, i) : s;
    }
}
