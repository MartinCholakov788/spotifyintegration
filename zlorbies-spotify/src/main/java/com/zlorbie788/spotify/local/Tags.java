package com.zlorbie788.spotify.local;

import com.github.trilarion.sound.vorbis.jcraft.jorbis.Comment;
import com.github.trilarion.sound.vorbis.jcraft.jorbis.OggReader;
import com.zlorbie788.spotify.audio.Decoders;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;

/**
 * Reads the information stored inside a music file: title, artist, album, track number, year, the cover picture and
 * embedded lyrics. Understands ID3v2 (2.2, 2.3, 2.4) and ID3v1 in MP3 files, Vorbis comments and pictures in FLAC and
 * OGG files, and the INFO list in WAV files. It never throws: a file it cannot read simply gives an empty result.
 */
public final class Tags {
    /** What a file says about itself. Empty strings / 0 / null when the file does not say. */
    public record Meta(String title, String artist, String album, int track, int year, String lyrics,
                       byte[] cover, String coverType) {
        public static final Meta EMPTY = new Meta("", "", "", 0, 0, "", null, "");

        public boolean hasCover() {
            return cover != null && cover.length > 0;
        }
    }

    private static final int MAX_TAG = 40 * 1024 * 1024;
    private static final Charset CP1251 = charset("windows-1251", StandardCharsets.ISO_8859_1);
    private static final Charset CP1252 = charset("windows-1252", StandardCharsets.ISO_8859_1);

    private Tags() {}

    private static Charset charset(String name, Charset fallback) {
        try {
            return Charset.forName(name);
        } catch (Exception e) {
            return fallback;
        }
    }

    /** Mutable collector so every format fills the same fields. */
    private static final class Acc {
        String title = "", artist = "", album = "", albumArtist = "", lyrics = "", coverType = "";
        int track, year;
        byte[] cover;
        int coverRank = -1;

        void cover(byte[] data, String mime, int rank) {
            if (data == null || data.length < 16) return;
            if (rank > coverRank) {
                cover = data;
                coverRank = rank;
                coverType = mime == null ? "" : mime;
            }
        }

        Meta done() {
            String a = artist.isEmpty() ? albumArtist : artist;
            return new Meta(title, a, album, track, year, lyrics, cover, coverType);
        }
    }

    public static Meta read(Path path) {
        Acc acc = new Acc();
        try {
            switch (Decoders.extension(path)) {
                case "mp3" -> readMp3(path, acc);
                case "flac" -> readFlac(path, acc);
                case "ogg", "oga" -> readOgg(path, acc);
                case "wav" -> readWav(path, acc);
                default -> { }
            }
        } catch (Throwable ignored) {
            // keep whatever was found before the problem
        }
        return acc.done();
    }

    // ------------------------------------------------------------ text helpers

    /** "3/12" gives 3, "07" gives 7, anything else 0. */
    static int number(String s) {
        if (s == null) return 0;
        int i = 0, n = s.length();
        while (i < n && !Character.isDigit(s.charAt(i))) i++;
        int v = 0, digits = 0;
        while (i < n && Character.isDigit(s.charAt(i)) && digits < 6) {
            v = v * 10 + (s.charAt(i) - '0');
            i++;
            digits++;
        }
        return v;
    }

    /** First four-digit year in the text, or 0. */
    static int year(String s) {
        if (s == null) return 0;
        for (int i = 0; i + 4 <= s.length(); i++) {
            boolean ok = true;
            for (int k = 0; k < 4 && ok; k++) ok = Character.isDigit(s.charAt(i + k));
            if (ok) {
                int y = Integer.parseInt(s.substring(i, i + 4));
                if (y >= 1000 && y <= 2999) return y;
            }
        }
        return 0;
    }

    static String clean(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 0 || c == '﻿') continue;
            b.append(c < 32 && c != '\n' ? ' ' : c);
        }
        return b.toString().trim();
    }

    /** Old files store text in the local code page while saying "Latin-1". Guess the real one. */
    static String decodeLegacy(byte[] b, int off, int len) {
        boolean high = false;
        for (int i = 0; i < len; i++) {
            if ((b[off + i] & 0x80) != 0) {
                high = true;
                break;
            }
        }
        if (!high) return new String(b, off, len, StandardCharsets.ISO_8859_1);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b, off, len)).toString();
        } catch (CharacterCodingException notUtf8) {
            // not UTF-8
        }
        // a run of three or more Cyrillic letters in windows-1251 (0xC0..0xFF) is very unlikely in windows-1252
        int run = 0, best = 0;
        for (int i = 0; i < len; i++) {
            int v = b[off + i] & 0xFF;
            if (v >= 0xC0 || v == 0xA8 || v == 0xB8 || v == 0xB2 || v == 0xB3) {
                run++;
                best = Math.max(best, run);
            } else {
                run = 0;
            }
        }
        return new String(b, off, len, best >= 3 ? CP1251 : CP1252);
    }

    // ------------------------------------------------------------ ID3

    private static int syncsafe(byte[] b, int o) {
        return ((b[o] & 0x7F) << 21) | ((b[o + 1] & 0x7F) << 14) | ((b[o + 2] & 0x7F) << 7) | (b[o + 3] & 0x7F);
    }

    private static int be32(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    private static byte[] unsync(byte[] b, int off, int len) {
        byte[] out = new byte[len];
        int n = 0;
        for (int i = 0; i < len; i++) {
            byte v = b[off + i];
            out[n++] = v;
            if (v == (byte) 0xFF && i + 1 < len && b[off + i + 1] == 0) i++;
        }
        return java.util.Arrays.copyOf(out, n);
    }

    private static void readMp3(Path path, Acc acc) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(path.toFile(), "r")) {
            long size = f.length();
            if (size >= 10) {
                byte[] head = new byte[10];
                f.readFully(head);
                if (head[0] == 'I' && head[1] == 'D' && head[2] == '3') readId3v2(f, head, size, acc);
            }
            readId3v1(f, size, acc);
        }
    }

    /** Reads an ID3v2 tag whose 10-byte header is in {@code head} and whose body follows at the file position. */
    static void readId3v2(RandomAccessFile f, byte[] head, long fileSize, Acc acc) throws IOException {
        int ver = head[3] & 0xFF;
        int flags = head[5] & 0xFF;
        int size = syncsafe(head, 6);
        if (ver < 2 || ver > 4 || size <= 0 || size > MAX_TAG || size > fileSize - 10) return;
        byte[] body = new byte[size];
        f.readFully(body);
        parseId3Body(body, ver, flags, acc);
    }

    static void parseId3Body(byte[] raw, int ver, int flags, Acc acc) {
        byte[] body = raw;
        int len = raw.length;
        if (ver < 4 && (flags & 0x80) != 0) {
            body = unsync(raw, 0, raw.length);
            len = body.length;
        }
        int pos = 0;
        if (ver >= 3 && (flags & 0x40) != 0 && len >= 4) {
            int ext = ver == 4 ? syncsafe(body, 0) : be32(body, 0) + 4;
            if (ext > 0 && ext < len) pos = ext;
        }
        int idLen = ver == 2 ? 3 : 4;
        int hdr = ver == 2 ? 6 : 10;
        while (pos + hdr <= len) {
            if (body[pos] == 0) break; // padding
            String id = new String(body, pos, idLen, StandardCharsets.ISO_8859_1);
            int fsize;
            int fflags = 0;
            if (ver == 2) {
                fsize = ((body[pos + 3] & 0xFF) << 16) | ((body[pos + 4] & 0xFF) << 8) | (body[pos + 5] & 0xFF);
            } else if (ver == 3) {
                fsize = be32(body, pos + 4);
                fflags = ((body[pos + 8] & 0xFF) << 8) | (body[pos + 9] & 0xFF);
            } else {
                fsize = syncsafe(body, pos + 4);
                fflags = ((body[pos + 8] & 0xFF) << 8) | (body[pos + 9] & 0xFF);
            }
            int start = pos + hdr;
            if (fsize < 0 || start + fsize > len) break;
            pos = start + fsize;
            if (fsize == 0) continue;

            byte[] data = body;
            int off = start;
            int n = fsize;
            boolean skip = false;
            if (ver == 3) {
                if ((fflags & 0x00C0) != 0) skip = true; // compressed or encrypted
                else if ((fflags & 0x0020) != 0) {       // grouping byte
                    off++;
                    n--;
                }
            } else if (ver == 4) {
                if ((fflags & 0x000C) != 0) skip = true; // compressed or encrypted
                else {
                    if ((fflags & 0x0040) != 0) {        // grouping
                        off++;
                        n--;
                    }
                    if ((fflags & 0x0001) != 0 && n >= 4) { // data length indicator
                        off += 4;
                        n -= 4;
                    }
                    if ((fflags & 0x0002) != 0 && n > 0) {  // unsynchronised frame
                        data = unsync(body, off, n);
                        off = 0;
                        n = data.length;
                    }
                }
            }
            if (skip || n <= 0) continue;
            try {
                frame(id, data, off, n, ver, acc);
            } catch (RuntimeException ignored) {
                // one broken frame does not stop the others
            }
        }
    }

    /** Decodes the text of a frame: {@code enc} is the ID3 text-encoding byte. */
    static String text(byte[] b, int off, int len, int enc) {
        if (len <= 0) return "";
        return switch (enc) {
            case 1 -> utf16(b, off, len);
            case 2 -> new String(b, off, len, StandardCharsets.UTF_16BE);
            case 3 -> new String(b, off, len, StandardCharsets.UTF_8);
            default -> decodeLegacy(b, off, len);
        };
    }

    private static String utf16(byte[] b, int off, int len) {
        if (len >= 2) {
            int b0 = b[off] & 0xFF, b1 = b[off + 1] & 0xFF;
            if (b0 == 0xFF && b1 == 0xFE) return new String(b, off + 2, len - 2, StandardCharsets.UTF_16LE);
            if (b0 == 0xFE && b1 == 0xFF) return new String(b, off + 2, len - 2, StandardCharsets.UTF_16BE);
        }
        return new String(b, off, len, StandardCharsets.UTF_16LE);
    }

    /** Index of the end of a terminated string starting at {@code from} (the terminator itself), or -1. */
    private static int terminator(byte[] b, int from, int end, int enc) {
        if (enc == 1 || enc == 2) {
            for (int i = from; i + 1 < end; i += 2) if (b[i] == 0 && b[i + 1] == 0) return i;
            return -1;
        }
        for (int i = from; i < end; i++) if (b[i] == 0) return i;
        return -1;
    }

    private static String firstText(byte[] b, int off, int len, boolean join) {
        int enc = b[off] & 0xFF;
        int end = off + len;
        int from = off + 1;
        StringBuilder out = new StringBuilder();
        while (from < end) {
            int t = terminator(b, from, end, enc);
            int to = t < 0 ? end : t;
            String part = clean(text(b, from, to - from, enc));
            if (!part.isEmpty()) {
                if (out.length() > 0) out.append(", ");
                out.append(part);
                if (!join) break;
            }
            if (t < 0) break;
            from = t + ((enc == 1 || enc == 2) ? 2 : 1);
        }
        return out.toString();
    }

    private static void frame(String id, byte[] b, int off, int n, int ver, Acc acc) {
        if (id.charAt(0) == 'T' && !id.equals("TXXX") && !id.equals("TXX")) {
            switch (id) {
                case "TIT2", "TT2" -> acc.title = firstText(b, off, n, false);
                case "TPE1", "TP1" -> acc.artist = firstText(b, off, n, true);
                case "TPE2", "TP2" -> acc.albumArtist = firstText(b, off, n, true);
                case "TALB", "TAL" -> acc.album = firstText(b, off, n, false);
                case "TRCK", "TRK" -> acc.track = number(firstText(b, off, n, false));
                case "TDRC", "TYER", "TYE", "TDAT" -> {
                    if (acc.year == 0) acc.year = year(firstText(b, off, n, false));
                }
                default -> { }
            }
            return;
        }
        switch (id) {
            case "USLT", "ULT" -> {
                if (n < 5) return;
                int enc = b[off] & 0xFF;
                int p = off + 4; // encoding + 3 language bytes
                int t = terminator(b, p, off + n, enc);
                if (t < 0) return;
                p = t + ((enc == 1 || enc == 2) ? 2 : 1);
                String lyrics = text(b, p, off + n - p, enc).replace("\r\n", "\n").replace('\r', '\n');
                lyrics = lyrics.replace("\u0000", "").replace("﻿", "").strip();
                if (!lyrics.isEmpty() && acc.lyrics.isEmpty()) acc.lyrics = lyrics;
            }
            case "TXXX", "TXX" -> {
                // "user text": a description, then the value. Some taggers keep lyrics here.
                if (n < 3) return;
                int enc = b[off] & 0xFF;
                int t = terminator(b, off + 1, off + n, enc);
                if (t < 0) return;
                String desc = clean(text(b, off + 1, t - off - 1, enc)).toUpperCase(Locale.ROOT);
                int p = t + ((enc == 1 || enc == 2) ? 2 : 1);
                if (desc.equals("LYRICS") || desc.equals("UNSYNCEDLYRICS") || desc.equals("USLT") || desc.equals("UNSYNCED LYRICS")) {
                    String lyrics = text(b, p, off + n - p, enc).replace("\r\n", "\n").replace('\r', '\n')
                            .replace("\u0000", "").replace("\uFEFF", "").strip();
                    if (!lyrics.isEmpty() && acc.lyrics.isEmpty()) acc.lyrics = lyrics;
                }
            }
            case "APIC" -> {
                int enc = b[off] & 0xFF;
                int m = terminator(b, off + 1, off + n, 0);
                if (m < 0) return;
                String mime = new String(b, off + 1, m - off - 1, StandardCharsets.ISO_8859_1).trim();
                if (mime.equals("-->")) return;
                int type = b[m + 1] & 0xFF;
                int d = terminator(b, m + 2, off + n, enc);
                if (d < 0) return;
                int dataAt = d + ((enc == 1 || enc == 2) ? 2 : 1);
                if (dataAt >= off + n) return;
                acc.cover(java.util.Arrays.copyOfRange(b, dataAt, off + n), mime, pictureRank(type));
            }
            case "PIC" -> {
                if (n < 6) return;
                int enc = b[off] & 0xFF;
                String fmt = new String(b, off + 1, 3, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
                int type = b[off + 4] & 0xFF;
                int d = terminator(b, off + 5, off + n, enc);
                if (d < 0) return;
                int dataAt = d + ((enc == 1 || enc == 2) ? 2 : 1);
                if (dataAt >= off + n) return;
                acc.cover(java.util.Arrays.copyOfRange(b, dataAt, off + n),
                        fmt.equals("png") ? "image/png" : "image/jpeg", pictureRank(type));
            }
            default -> { }
        }
    }

    /** Front cover first, then the other pictures; artist photos, logos and the like last. */
    static int pictureRank(int type) {
        return switch (type) {
            case 3 -> 10;
            case 0 -> 8;
            case 4, 2, 1 -> 6;
            default -> 3;
        };
    }

    private static void readId3v1(RandomAccessFile f, long size, Acc acc) throws IOException {
        if (size < 128) return;
        f.seek(size - 128);
        byte[] t = new byte[128];
        f.readFully(t);
        if (t[0] != 'T' || t[1] != 'A' || t[2] != 'G') return;
        if (acc.title.isEmpty()) acc.title = clean(field(t, 3, 30));
        if (acc.artist.isEmpty() && acc.albumArtist.isEmpty()) acc.artist = clean(field(t, 33, 30));
        if (acc.album.isEmpty()) acc.album = clean(field(t, 63, 30));
        if (acc.year == 0) acc.year = year(field(t, 93, 4));
        if (acc.track == 0 && t[125] == 0 && t[126] != 0) acc.track = t[126] & 0xFF;
    }

    private static String field(byte[] b, int off, int len) {
        int n = len;
        while (n > 0 && (b[off + n - 1] == 0 || b[off + n - 1] == ' ')) n--;
        for (int i = 0; i < n; i++) if (b[off + i] == 0) {
            n = i;
            break;
        }
        return decodeLegacy(b, off, n);
    }

    // ------------------------------------------------------------ Vorbis comments (FLAC and OGG)

    private static void comment(Acc acc, String key, String value) {
        String k = key.toUpperCase(Locale.ROOT);
        String v = clean(value);
        switch (k) {
            case "TITLE" -> {
                if (acc.title.isEmpty()) acc.title = v;
            }
            case "ARTIST" -> {
                if (acc.artist.isEmpty()) acc.artist = v;
                else if (!v.isEmpty() && !acc.artist.contains(v)) acc.artist = acc.artist + ", " + v;
            }
            case "ALBUMARTIST", "ALBUM ARTIST" -> {
                if (acc.albumArtist.isEmpty()) acc.albumArtist = v;
            }
            case "ALBUM" -> {
                if (acc.album.isEmpty()) acc.album = v;
            }
            case "TRACKNUMBER", "TRACK" -> {
                if (acc.track == 0) acc.track = number(v);
            }
            case "DATE", "YEAR", "ORIGINALDATE" -> {
                if (acc.year == 0) acc.year = year(v);
            }
            case "LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS" -> {
                if (acc.lyrics.isEmpty()) acc.lyrics = value.replace("\r\n", "\n").replace('\r', '\n').strip();
            }
            default -> { }
        }
    }

    private static void readFlac(Path path, Acc acc) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(path.toFile(), "r")) {
            long size = f.length();
            byte[] four = new byte[4];
            f.readFully(four);
            long pos = 0;
            if (four[0] == 'I' && four[1] == 'D' && four[2] == '3') { // a tag in front of the FLAC data
                byte[] head = new byte[10];
                f.seek(0);
                f.readFully(head);
                int tagSize = syncsafe(head, 6);
                if (tagSize > 0 && tagSize < MAX_TAG && tagSize < size - 14) {
                    byte[] body = new byte[tagSize];
                    f.readFully(body);
                    parseId3Body(body, head[3] & 0xFF, head[5] & 0xFF, acc);
                }
                pos = 10L + tagSize;
                f.seek(pos);
                f.readFully(four);
            }
            if (four[0] != 'f' || four[1] != 'L' || four[2] != 'a' || four[3] != 'C') return;
            pos += 4;
            boolean last = false;
            while (!last && pos + 4 <= size) {
                f.seek(pos);
                int h = f.readUnsignedByte();
                last = (h & 0x80) != 0;
                int type = h & 0x7F;
                int len = (f.readUnsignedByte() << 16) | (f.readUnsignedByte() << 8) | f.readUnsignedByte();
                pos += 4;
                if (len < 0 || pos + len > size) break;
                if ((type == 4 || type == 6) && len <= MAX_TAG) {
                    byte[] block = new byte[len];
                    f.readFully(block);
                    if (type == 4) vorbisComments(ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN), acc);
                    else flacPicture(block, 0, acc);
                }
                pos += len;
            }
        }
    }

    private static void vorbisComments(ByteBuffer bb, Acc acc) {
        if (bb.remaining() < 8) return;
        int vendor = bb.getInt();
        if (vendor < 0 || vendor > bb.remaining() - 4) return;
        bb.position(bb.position() + vendor);
        int count = bb.getInt();
        for (int i = 0; i < count && bb.remaining() >= 4; i++) {
            int len = bb.getInt();
            if (len < 0 || len > bb.remaining()) return;
            byte[] raw = new byte[len];
            bb.get(raw);
            commentLine(acc, raw, len);
        }
    }

    private static void commentLine(Acc acc, byte[] raw, int len) {
        int eq = -1;
        for (int i = 0; i < len; i++) if (raw[i] == '=') {
            eq = i;
            break;
        }
        if (eq <= 0) return;
        String key = new String(raw, 0, eq, StandardCharsets.US_ASCII);
        String value = new String(raw, eq + 1, len - eq - 1, StandardCharsets.UTF_8);
        if (key.equalsIgnoreCase("METADATA_BLOCK_PICTURE")) {
            try {
                flacPicture(Base64.getMimeDecoder().decode(value), 0, acc);
            } catch (IllegalArgumentException ignored) { }
            return;
        }
        comment(acc, key, value);
    }

    private static void flacPicture(byte[] b, int off, Acc acc) {
        ByteBuffer bb = ByteBuffer.wrap(b, off, b.length - off).order(ByteOrder.BIG_ENDIAN);
        if (bb.remaining() < 32) return;
        int type = bb.getInt();
        int ml = bb.getInt();
        if (ml < 0 || ml > bb.remaining() - 28) return;
        byte[] mime = new byte[ml];
        bb.get(mime);
        int dl = bb.getInt();
        if (dl < 0 || dl > bb.remaining() - 20) return;
        bb.position(bb.position() + dl);
        bb.position(bb.position() + 16); // width, height, depth, colours
        int data = bb.getInt();
        if (data <= 0 || data > bb.remaining()) return;
        byte[] pic = new byte[data];
        bb.get(pic);
        acc.cover(pic, new String(mime, StandardCharsets.ISO_8859_1), pictureRank(type));
    }

    private static void readOgg(Path path, Acc acc) {
        OggReader r = null;
        try {
            r = new OggReader(path.toString());
            Comment c = r.comment();
            if (c == null) return;
            for (int i = 0; i < c.comments; i++) {
                byte[] raw = c.user_comments[i];
                int len = c.comment_lengths[i];
                if (raw == null) continue;
                len = Math.min(len, raw.length);
                while (len > 0 && raw[len - 1] == 0) len--;
                commentLine(acc, raw, len);
            }
        } catch (Throwable ignored) {
            // not a Vorbis file (for example Opus) or damaged
        } finally {
            if (r != null) r.close();
        }
    }

    // ------------------------------------------------------------ WAV

    private static void readWav(Path path, Acc acc) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(path.toFile(), "r")) {
            long size = f.length();
            if (size < 12) return;
            byte[] riff = new byte[12];
            f.readFully(riff);
            if (riff[0] != 'R' || riff[1] != 'I' || riff[2] != 'F') return;
            long pos = 12;
            while (pos + 8 <= size) {
                f.seek(pos);
                byte[] h = new byte[8];
                f.readFully(h);
                String id = new String(h, 0, 4, StandardCharsets.ISO_8859_1);
                long len = ByteBuffer.wrap(h, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
                long body = pos + 8;
                if (len > size - body) len = size - body;
                if (id.equals("LIST") && len >= 4 && len < 1_000_000) {
                    byte[] b = new byte[(int) len];
                    f.readFully(b);
                    if (b[0] == 'I' && b[1] == 'N' && b[2] == 'F' && b[3] == 'O') wavInfo(b, acc);
                } else if ((id.equals("id3 ") || id.equals("ID3 ")) && len > 10 && len < MAX_TAG) {
                    byte[] b = new byte[(int) len];
                    f.readFully(b);
                    if (b[0] == 'I' && b[1] == 'D' && b[2] == '3') {
                        int sz = Math.min(syncsafe(b, 6), b.length - 10);
                        if (sz > 0) {
                            parseId3Body(java.util.Arrays.copyOfRange(b, 10, 10 + sz), b[3] & 0xFF, b[5] & 0xFF, acc);
                        }
                    }
                }
                pos = body + len + (len & 1);
            }
        }
    }

    private static void wavInfo(byte[] b, Acc acc) {
        int p = 4;
        while (p + 8 <= b.length) {
            String id = new String(b, p, 4, StandardCharsets.ISO_8859_1);
            int len = ByteBuffer.wrap(b, p + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            p += 8;
            if (len < 0 || p + len > b.length) break;
            int n = len;
            while (n > 0 && b[p + n - 1] == 0) n--;
            String v = clean(decodeLegacy(b, p, n));
            switch (id) {
                case "INAM" -> acc.title = v;
                case "IART" -> acc.artist = v;
                case "IPRD" -> acc.album = v;
                case "ITRK", "IPRT" -> acc.track = number(v);
                case "ICRD" -> acc.year = year(v);
                default -> { }
            }
            p += len + (len & 1);
        }
    }
}
