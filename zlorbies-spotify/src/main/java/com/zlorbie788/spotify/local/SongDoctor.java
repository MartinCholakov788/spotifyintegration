package com.zlorbie788.spotify.local;

import com.zlorbie788.spotify.audio.Decoders;
import com.zlorbie788.spotify.audio.PcmSource;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The song troubleshooter: looks at everything in the music folder (not only what the player already accepted) and says,
 * file by file, why a song does not show up or does not play, and what to do about it. It only reads; it never changes or
 * deletes a file.
 */
public final class SongDoctor {
    public enum Level { OK, NOTE, BAD }

    /** One thing found: {@code file} is relative to the music folder, {@code what} the plain reason, {@code fix} what to do. */
    public record Finding(Level level, String file, String what, String fix) { }

    public record Report(Path dir, boolean folderExists, boolean truncated, int songs, List<Finding> findings) {
        public int problems() {
            int n = 0;
            for (Finding f : findings) if (f.level == Level.BAD) n++;
            return n;
        }

        public int notes() {
            int n = 0;
            for (Finding f : findings) if (f.level == Level.NOTE) n++;
            return n;
        }

        /** One sentence for the top of the screen or the chat. */
        public String summary() {
            if (!folderExists) return "The music folder does not exist yet. Press Open folder to create it.";
            int p = problems();
            if (songs == 0 && p == 0 && notes() == 0) return "The music folder is empty. Copy MP3, OGG, WAV or FLAC files into it.";
            if (p == 0) return songs + (songs == 1 ? " song is" : " songs are") + " ready to play. No problems found.";
            return songs + (songs == 1 ? " song is" : " songs are") + " ready, " + p + (p == 1 ? " file needs" : " files need") + " attention.";
        }
    }

    private static final Set<String> PARTIAL = Set.of("crdownload", "part", "partial", "tmp", "temp", "download", "opdownload", "ytdl", "aria2", "!ut", "bc!");
    private static final Set<String> OTHER_AUDIO = Set.of("m4a", "m4b", "aac", "wma", "opus", "webm", "mp4", "mkv", "mov", "3gp", "amr",
            "aif", "aiff", "ape", "wv", "alac", "mka", "ac3", "dts", "mid", "midi", "wvx", "au", "caf", "m4p");

    private SongDoctor() {}

    /** Checks the whole folder. Takes a moment on a big library: call it off the game thread. */
    public static Report check(Path dir) {
        List<Finding> out = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) return new Report(dir, false, false, 0, out);
        List<Path> files = new ArrayList<>();
        boolean[] cut = {false};
        try {
            Files.walkFileTree(dir, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), MusicLibrary.MAX_DEPTH,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                            if (files.size() >= MusicLibrary.MAX_FILES) {
                                cut[0] = true;
                                return FileVisitResult.TERMINATE;
                            }
                            if (a.isRegularFile()) files.add(f);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path f, IOException e) {
                            out.add(new Finding(Level.BAD, rel(dir, f), "Windows would not let the mod look at it (" + brief(e) + ")",
                                    "Check that it is not open in another program, or copy it to a new place and back."));
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            out.add(new Finding(Level.BAD, "", "The folder could not be read (" + brief(e) + ")", "Check the folder's permissions."));
        }
        java.util.Collections.sort(files);
        int songs = 0;
        for (Path f : files) {
            Finding finding = one(dir, f);
            if (finding == null) {
                songs++;
            } else if (finding.level != Level.OK) {
                out.add(finding);
                if (finding.level == Level.NOTE) songs++; // a note means it still plays
            }
        }
        if (cut[0]) {
            out.add(new Finding(Level.NOTE, "", "The folder holds more than " + MusicLibrary.MAX_FILES + " files; the rest is not looked at.",
                    "Keep your songs in fewer folders."));
        }
        out.sort((a, b) -> a.level == b.level ? a.file.compareToIgnoreCase(b.file) : (a.level == Level.BAD ? -1 : b.level == Level.BAD ? 1 : 0));
        return new Report(dir, true, cut[0], songs, out);
    }

    /** Looks at a single file; {@code null} when it is a fine song, a finding otherwise (OK level = ignore it). */
    static Finding one(Path dir, Path f) {
        String name = f.getFileName().toString();
        String rel = rel(dir, f);
        String ext = Decoders.extension(f);
        String low = name.toLowerCase(Locale.ROOT);
        if (name.startsWith(".") || name.equalsIgnoreCase("Thumbs.db") || name.equalsIgnoreCase("desktop.ini")) {
            return new Finding(Level.OK, rel, "", "");
        }
        if (PARTIAL.contains(ext)) {
            return new Finding(Level.BAD, rel, "This looks like a download that is not finished (." + ext + ").",
                    "Wait for the download to end, then rename it to the real name, or download it again.");
        }
        if (!Decoders.supported(f)) {
            if (OTHER_AUDIO.contains(ext)) {
                String kind = switch (ext) {
                    case "m4a", "m4b", "m4p", "aac", "mp4", "alac" -> "M4A / AAC";
                    case "opus", "webm" -> "Opus / WebM";
                    case "wma" -> "WMA";
                    default -> "." + ext;
                };
                return new Finding(Level.BAD, rel, kind + " files cannot be played by the mod.",
                        "Convert it to MP3 (or OGG, WAV, FLAC), for example with VLC (Media > Convert) or: ffmpeg -i \"" + name + "\" song.mp3");
            }
            // an audio extension hidden before another one: song.mp3.txt, song.mp3.mp3
            for (String known : Decoders.EXTENSIONS) {
                if (low.contains("." + known + ".")) {
                    return new Finding(Level.BAD, rel, "The name has a second ending after ." + known + " (." + ext + "), so it is not seen as a song.",
                            "Rename it so it ends with ." + known + ". In Explorer turn on View > File name extensions to see the real ending.");
                }
            }
            if (ext.isEmpty() && Decoders.sniff(f).length() > 0 && !Decoders.sniff(f).equals("mp4")) {
                return new Finding(Level.BAD, rel, "The file has no ending (like .mp3), so it is skipped.",
                        "Rename it and add the ending that matches its kind: " + Decoders.sniff(f) + ".");
            }
            return new Finding(Level.OK, rel, "", "");
        }
        long size;
        try {
            size = Files.size(f);
        } catch (IOException e) {
            return new Finding(Level.BAD, rel, "The file cannot be read (" + brief(e) + ").", "Check that it is not open in another program.");
        }
        if (size == 0) {
            return new Finding(Level.BAD, rel, "The file is empty (0 bytes): the copy did not finish.", "Copy or download it again.");
        }
        if (size < 2048) {
            return new Finding(Level.BAD, rel, "The file is tiny (" + size + " bytes): it is cut off or not a song.", "Copy or download it again.");
        }
        try {
            String real = Decoders.sniff(f);
            try (PcmSource src = Decoders.open(f)) {
                float[] buf = new float[2 * 4096];
                int got = src.read(buf, 4096);
                if (got <= 0 && src.length() > 22050) {
                    return new Finding(Level.BAD, rel, "It opens but gives no sound: the audio inside is damaged.",
                            "Download it again, or re-save it as MP3 with a converter.");
                }
                if (!real.isEmpty() && !real.equals(ext) && !(ext.equals("oga") && real.equals("ogg"))) {
                    return new Finding(Level.NOTE, rel, "Named ." + ext + " but it is really " + real.toUpperCase(Locale.ROOT) + ". It plays anyway.",
                            "You can rename it to ." + real + " to keep things tidy.");
                }
            }
            return null;
        } catch (Throwable t) {
            return new Finding(Level.BAD, rel, why(t), fixFor(t, f));
        }
    }

    private static String why(Throwable t) {
        String m = t.getMessage() == null || t.getMessage().isBlank() ? t.getClass().getSimpleName() : t.getMessage();
        String lower = m.toLowerCase(Locale.ROOT);
        if (lower.contains("another process") || lower.contains("being used")) return "Another program is using this file right now.";
        if (lower.contains("access is denied") || lower.contains("permission")) return "Windows does not allow reading this file (access denied).";
        if (lower.contains("no mp3 audio")) return "No MP3 audio was found inside: it is not really an MP3, or it is damaged.";
        int semi = m.indexOf("; ");
        if (semi > 0 && lower.contains("convert")) return Character.toUpperCase(m.charAt(0)) + m.substring(1, semi) + ".";
        if (t instanceof Error && !(t instanceof OutOfMemoryError)) return "The decoder could not start (" + t.getClass().getSimpleName() + ").";
        return m.length() > 110 ? m.substring(0, 110) : m;
    }

    private static String fixFor(Throwable t, Path f) {
        String m = (t.getMessage() == null ? "" : t.getMessage()).toLowerCase(Locale.ROOT);
        if (m.contains("another process") || m.contains("being used")) return "Close the program that has it open (a browser, a converter, a player), then press Rescan.";
        if (m.contains("access is denied") || m.contains("permission")) return "Copy the file to another folder and back, or check it is not read-only or on a locked drive.";
        if (m.contains("m4a") || m.contains("opus") || m.contains("aac")) return "Convert it to MP3 (or OGG, WAV, FLAC), for example with VLC (Media > Convert) or ffmpeg.";
        return "Download it again, or open it in a converter (VLC, ffmpeg) and save it as MP3.";
    }

    private static String rel(Path dir, Path f) {
        try {
            return dir.relativize(f).toString().replace('\\', '/');
        } catch (RuntimeException e) {
            return f.getFileName().toString();
        }
    }

    private static String brief(Throwable e) {
        String m = e.getMessage();
        if (m == null || m.isBlank()) m = e.getClass().getSimpleName();
        return m.length() > 80 ? m.substring(0, 80) : m;
    }

    /** The report as plain lines for the chat or a copy to the clipboard. */
    public static List<String> lines(Report r) {
        List<String> out = new ArrayList<>();
        out.add(r.summary());
        out.add("Folder: " + r.dir());
        for (Finding f : r.findings) {
            if (f.level == Level.OK) continue;
            out.add((f.level == Level.BAD ? "PROBLEM " : "NOTE ") + (f.file.isEmpty() ? "" : f.file + ": ") + f.what + " -> " + f.fix);
        }
        return out;
    }
}
