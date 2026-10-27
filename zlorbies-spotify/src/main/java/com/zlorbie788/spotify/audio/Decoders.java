package com.zlorbie788.spotify.audio;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/** Opens a music file with the right decoder. */
public final class Decoders {
    /** The file types the mod plays. */
    public static final Set<String> EXTENSIONS = Set.of("mp3", "ogg", "oga", "wav", "flac");

    private Decoders() {}

    public static String extension(Path p) {
        String n = p.getFileName() == null ? "" : p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static boolean supported(Path p) {
        return EXTENSIONS.contains(extension(p));
    }

    /**
     * What the file really is, from its first bytes (a download that was only renamed to .mp3 is common): "mp3", "ogg",
     * "flac", "wav", "opus", "mp4" (M4A / MP4 / AAC audio, which the mod cannot play) or "" when it cannot tell.
     */
    public static String sniff(Path p) {
        byte[] h = new byte[128];
        int n;
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(p)) {
            n = in.readNBytes(h, 0, h.length);
        } catch (IOException e) {
            return "";
        }
        if (n >= 4 && h[0] == 'O' && h[1] == 'g' && h[2] == 'g' && h[3] == 'S') {
            String head = new String(h, 0, n, java.nio.charset.StandardCharsets.ISO_8859_1);
            return head.contains("OpusHead") ? "opus" : "ogg";
        }
        if (n >= 4 && h[0] == 'f' && h[1] == 'L' && h[2] == 'a' && h[3] == 'C') return "flac";
        if (n >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F' && h[8] == 'W' && h[9] == 'A' && h[10] == 'V' && h[11] == 'E') return "wav";
        if (n >= 8 && h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p') return "mp4";
        if (n >= 3 && h[0] == 'I' && h[1] == 'D' && h[2] == '3') return "mp3";
        if (n >= 2 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xE0) == 0xE0) return ((h[1] >> 1) & 3) == 0 ? "aac" : "mp3";
        return "";
    }

    public static PcmSource open(Path p) throws IOException {
        String ext = extension(p);
        String real = sniff(p);
        if (real.equals("mp4") || real.equals("aac")) {
            throw new IOException("this is an M4A/AAC audio file named ." + ext + "; convert it to a real MP3 (or use OGG, WAV, FLAC)");
        }
        // trust the content over the name when they disagree (an OGG called .mp3 still plays)
        if (real.equals("opus")) {
            throw new IOException("this is an Opus audio file; convert it to MP3, OGG Vorbis, WAV or FLAC");
        }
        String kind = real.isEmpty() || (ext.equals("oga") && real.equals("ogg")) ? ext : real;
        return switch (kind) {
            case "mp3" -> new Mp3Source(p);
            case "ogg", "oga" -> new OggSource(p);
            case "flac" -> new FlacSource(p);
            case "wav" -> new WavSource(p);
            default -> throw new IOException("unsupported file type: " + ext);
        };
    }
}
