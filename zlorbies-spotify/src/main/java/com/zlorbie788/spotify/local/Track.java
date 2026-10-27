package com.zlorbie788.spotify.local;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** One song file in the music folder, as the library knows it. Immutable. */
public final class Track {
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    /** Path inside the music folder with "/" separators; also what playlists remember. */
    public final String id;
    public final String title;
    public final String artist;
    public final String album;
    public final int trackNo;
    public final int year;
    public final long durMs;
    /** When the file arrived in the folder (ms since 1970). */
    public final long addedAt;
    public final long size;
    public final long mtime;
    /** Name of the cover file inside {@code localcovers/}, or "". */
    public final String cover;
    /** True when the file carries its own lyrics. */
    public final boolean lyrics;
    public final String format;
    private volatile String hay;

    public Track(String id, String title, String artist, String album, int trackNo, int year, long durMs,
                 long addedAt, long size, long mtime, String cover, boolean lyrics, String format) {
        this.id = id;
        this.title = title == null ? "" : title;
        this.artist = artist == null ? "" : artist;
        this.album = album == null ? "" : album;
        this.trackNo = trackNo;
        this.year = year;
        this.durMs = durMs;
        this.addedAt = addedAt;
        this.size = size;
        this.mtime = mtime;
        this.cover = cover == null ? "" : cover;
        this.lyrics = lyrics;
        this.format = format == null ? "" : format;
    }

    public Track withDuration(long ms) {
        return new Track(id, title, artist, album, trackNo, year, ms, addedAt, size, mtime, cover, lyrics, format);
    }

    public Track withTags(String newTitle, String newArtist, String newAlbum, String newCover) {
        return new Track(id, newTitle, newArtist, newAlbum, trackNo, year, durMs, addedAt, size, mtime,
                newCover, lyrics, format);
    }

    /** "Artist - Title" for lists and chat. */
    public String label() {
        return artist.isEmpty() ? title : artist + " – " + title;
    }

    public String fileName() {
        int slash = id.lastIndexOf('/');
        return slash < 0 ? id : id.substring(slash + 1);
    }

    /** Lower-case, accent-free text of everything searchable about the song. */
    String haystack() {
        String h = hay;
        if (h == null) {
            h = fold(title + " " + artist + " " + album + " " + fileName());
            hay = h;
        }
        return h;
    }

    public static String fold(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        return MARKS.matcher(n).replaceAll("").toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return "Track[" + id + " | " + label() + "]";
    }
}
