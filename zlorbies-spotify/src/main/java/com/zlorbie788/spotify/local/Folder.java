package com.zlorbie788.spotify.local;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Opens a folder in the file manager of the PC ("Open music folder"). Never throws. */
public final class Folder {
    private Folder() {}

    /** Opens the music folder (made first if it does not exist yet). Returns false when it could not be opened. */
    public static boolean openMusic() {
        MusicLibrary.ensureDirectory();
        return open(MusicLibrary.directory());
    }

    public static boolean open(Path dir) {
        try {
            Files.createDirectories(dir);
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            ProcessBuilder pb;
            if (os.contains("win")) pb = new ProcessBuilder("explorer.exe", dir.toAbsolutePath().toString());
            else if (os.contains("mac")) pb = new ProcessBuilder("open", dir.toAbsolutePath().toString());
            else pb = new ProcessBuilder("xdg-open", dir.toAbsolutePath().toString());
            pb.redirectErrorStream(true);
            pb.start();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }
}
