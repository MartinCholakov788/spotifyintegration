package com.zlorbie788.spotify.catalog;

import com.google.gson.JsonObject;
import com.zlorbie788.spotify.util.Http;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Understands Spotify links you paste into the search bar: https://open.spotify.com/track/ID?si=... and
 * spotify:track:ID. Only public information is used (the page address and Spotify's open "oEmbed" title lookup).
 */
public record SpotifyLink(String type, String id) {
    private static final Pattern WEB = Pattern.compile(
            "open\\.spotify\\.com/(?:intl-[a-z]{2,3}(?:-[a-z]{2,4})?/)?(track|album|artist|playlist|episode|show)/([A-Za-z0-9]{10,32})");
    private static final Pattern URI = Pattern.compile("^spotify:(track|album|artist|playlist|episode|show):([A-Za-z0-9]{10,32})$");

    /** Null when the text is not a Spotify link. */
    public static SpotifyLink parse(String text) {
        if (text == null) return null;
        String t = text.strip();
        Matcher m = URI.matcher(t);
        if (m.find()) return new SpotifyLink(m.group(1), m.group(2));
        m = WEB.matcher(t);
        if (m.find()) return new SpotifyLink(m.group(1), m.group(2));
        return null;
    }

    public String uri() {
        return "spotify:" + type + ":" + id;
    }

    public String url() {
        return "https://open.spotify.com/" + type + "/" + id;
    }

    public boolean playable() {
        return type.equals("track") || type.equals("episode");
    }

    /** Title and picture of the linked page, from Spotify's public oEmbed. Worker threads only. */
    public Item describe() throws IOException {
        String base = System.getProperty("zlorbie.oembed", "https://open.spotify.com/oembed");
        Http.Response r = Http.get(base + "?url=" + Http.enc(url()), null);
        if (!r.ok()) throw new IOException("Spotify answered " + r.code());
        JsonObject o = Js.parse(r.body());
        String title = Js.str(o, "title");
        String thumb = Js.str(o, "thumbnail_url");
        Item.Kind kind = type.equals("album") ? Item.Kind.ALBUM : Item.Kind.SONG;
        return new Item(kind, Item.Provider.SPOTIFY, id, title.isEmpty() ? "Spotify " + type : title,
                "Spotify link", "", "", 0, thumb, thumb, 0, false, uri());
    }
}
