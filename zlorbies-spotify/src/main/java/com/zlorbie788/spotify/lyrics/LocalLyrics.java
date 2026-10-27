package com.zlorbie788.spotify.lyrics;

import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.local.MusicLibrary;
import com.zlorbie788.spotify.local.Tags;
import com.zlorbie788.spotify.local.Track;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Lyrics that come with your own songs: a {@code .lrc} file with the same name beside the song (timed lines), a
 * {@code .txt} file with the same name (plain lines), or lyrics stored inside the song's tags (timed or plain).
 * Nothing is written anywhere. Never throws.
 */
public final class LocalLyrics {
    private static final long MAX_BYTES = 512 * 1024;

    private LocalLyrics() {}

    /** The lyrics that belong to this song, or null when it has none. Reads files, so call it off the game thread. */
    public static Lyrics.Doc find(Track t) {
        try {
            Path file = MusicLibrary.directory().resolve(t.id);
            if (ConfigIo.get().localLyricsFiles) {
                Lyrics.Doc d = fromFiles(file);
                if (d != null) return d;
            }
            if (t.lyrics) {
                Tags.Meta m = Tags.read(file);
                if (m != null && m.lyrics() != null && !m.lyrics().isBlank()) return parse(m.lyrics());
            }
        } catch (Throwable ignored) {
            // no lyrics then
        }
        return null;
    }

    private static Lyrics.Doc fromFiles(Path song) {
        String name = song.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        Path dir = song.getParent();
        if (dir == null) return null;
        for (String ext : new String[]{".lrc", ".LRC", ".Lrc", ".txt", ".TXT"}) {
            Path f = dir.resolve(base + ext);
            if (!Files.isRegularFile(f)) continue;
            try {
                if (Files.size(f) > MAX_BYTES) continue;
                Lyrics.Doc d = parse(read(Files.readAllBytes(f)));
                if (d != null) return d;
            } catch (Exception ignored) {
                // try the next one
            }
        }
        return null;
    }

    /** UTF-8 (with or without a byte order mark); older files fall back to Windows-1252. */
    static String read(byte[] b) {
        int off = b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(b, off, b.length - off)).toString();
        } catch (CharacterCodingException e) {
            return new String(b, off, b.length - off, java.nio.charset.Charset.forName("windows-1252"));
        }
    }

    /** Timed lines when the text has time tags, otherwise plain lines. Null when there is nothing to show. */
    static Lyrics.Doc parse(String text) {
        if (text == null || text.isBlank()) return null;
        List<LyricLine> lines = LrcParser.parse(text);
        if (!lines.isEmpty()) return new Lyrics.Doc(lines, List.of(), false);
        List<String> plain = LrcParser.plain(text);
        return plain.isEmpty() ? null : new Lyrics.Doc(List.of(), plain, false);
    }
}
