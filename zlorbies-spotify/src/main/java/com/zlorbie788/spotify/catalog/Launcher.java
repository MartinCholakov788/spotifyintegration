package com.zlorbie788.spotify.catalog;

import com.zlorbie788.spotify.client.SpotifyClient;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Hands a spotify: link to the operating system, which opens it in the Spotify app (no log-in involved). */
public final class Launcher {
    private static final Pattern SAFE = Pattern.compile("^spotify:[a-z]+:[A-Za-z0-9%._~:-]{1,300}$");

    /** Tests replace the real launch with this. */
    static java.util.function.Predicate<String> hook;

    private Launcher() {}

    public static boolean valid(String uri) {
        return uri != null && SAFE.matcher(uri).matches();
    }

    /** "spotify:search:" + the words, percent-encoded so it is a single safe argument. */
    public static String searchUri(String words) {
        String enc = com.zlorbie788.spotify.util.Http.enc(words).replace("+", "%20");
        return "spotify:search:" + (enc.length() > 250 ? enc.substring(0, 250) : enc);
    }

    /** Opens the link. Returns false when it was refused or could not be started. */
    public static boolean open(String uri) {
        if (!valid(uri)) return false;
        if (hook != null) return hook.test(uri);
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> cmd;
        if (os.contains("win")) cmd = List.of("rundll32", "url.dll,FileProtocolHandler", uri);
        else if (os.contains("mac")) cmd = List.of("open", uri);
        else cmd = List.of("xdg-open", uri);
        try {
            new ProcessBuilder(cmd).redirectErrorStream(true).start();
            return true;
        } catch (IOException e) {
            SpotifyClient.LOGGER.warn("[Spotify Integration Mod] could not open {}: {}", uri, e.toString());
            return false;
        }
    }
}
