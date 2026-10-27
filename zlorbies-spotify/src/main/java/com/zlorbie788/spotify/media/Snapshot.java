package com.zlorbie788.spotify.media;

/**
 * One reading from the Windows media session. Immutable; {@code at} is the System.nanoTime() when it was read,
 * so the playback position can be advanced between readings.
 */
public record Snapshot(
        Status status, String app, String title, String artist, String album,
        long posMs, long durMs, boolean shuffle, int repeat,
        boolean canSeek, boolean canNext, boolean canPrev, boolean canToggle, boolean canShuffle, boolean canRepeat,
        int volume, boolean mute, String cover, String key, boolean basic, long at) {

    public enum Status { PLAYING, PAUSED, STOPPED, NONE }

    public static final Snapshot EMPTY = new Snapshot(Status.NONE, "", "", "", "", 0, 0, false, 0,
            false, false, false, false, false, false, -1, false, "", "", false, 0L);

    public boolean playing() {
        return status == Status.PLAYING;
    }

    /** True when there is a song (or ad) to show. */
    public boolean active() {
        return status != Status.NONE;
    }

    /** Spotify's ads come through as a track called "Advertisement" by "Spotify". */
    public boolean isAd() {
        return "Advertisement".equalsIgnoreCase(title.trim())
                || ("Spotify".equalsIgnoreCase(artist.trim()) && album.isBlank() && durMs > 0 && durMs < 125_000);
    }

    /** Friendly name of the player ("Spotify", "Chrome"...). */
    public String appName() {
        String a = app == null ? "" : app;
        if (a.isEmpty()) return "";
        int bang = a.indexOf('!');
        if (a.toLowerCase(java.util.Locale.ROOT).contains("spotify")) return "Spotify";
        if (bang > 0) a = a.substring(bang + 1);
        if (a.toLowerCase(java.util.Locale.ROOT).endsWith(".exe")) a = a.substring(0, a.length() - 4);
        if (a.isEmpty()) return "";
        return Character.toUpperCase(a.charAt(0)) + a.substring(1);
    }
}
