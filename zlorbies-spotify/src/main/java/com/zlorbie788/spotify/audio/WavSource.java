package com.zlorbie788.spotify.audio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Plain WAV files: 8, 16, 24 and 32 bit PCM, 32 and 64 bit float, any channel count (also the "extensible" kind). */
public final class WavSource implements PcmSource {
    private static final int CHUNK_FRAMES = 4096;

    private final FileChannel ch;
    private final int rate;
    private final int channels;
    private final int bits;
    private final boolean isFloat;
    private final int blockAlign;
    private final long dataStart;
    private final long frames;
    private long pos;
    private final ByteBuffer buf;

    public WavSource(Path path) throws IOException {
        ch = FileChannel.open(path, StandardOpenOption.READ);
        try {
            long size = ch.size();
            ByteBuffer head = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
            readFully(head, 0);
            String riff = tag(head, 0);
            if (!(riff.equals("RIFF") || riff.equals("RF64")) || !tag(head, 8).equals("WAVE")) {
                throw new IOException("not a WAV file");
            }
            long p = 12;
            int fmtTag = -1;
            int ch2 = 0;
            int bps = 0;
            int rt = 0;
            int align = 0;
            long dStart = -1;
            long dLen = 0;
            ByteBuffer ck = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            while (p + 8 <= size) {
                ck.clear();
                readFully(ck, p);
                String id = tag(ck, 0);
                long len = ck.getInt(4) & 0xFFFFFFFFL;
                long body = p + 8;
                if (id.equals("fmt ")) {
                    ByteBuffer f = ByteBuffer.allocate((int) Math.min(Math.max(len, 16), 64)).order(ByteOrder.LITTLE_ENDIAN);
                    readFully(f, body);
                    fmtTag = f.getShort(0) & 0xFFFF;
                    ch2 = f.getShort(2) & 0xFFFF;
                    rt = f.getInt(4);
                    align = f.getShort(12) & 0xFFFF;
                    bps = f.getShort(14) & 0xFFFF;
                    if (fmtTag == 0xFFFE && len >= 26 && f.limit() >= 26) fmtTag = f.getShort(24) & 0xFFFF;
                } else if (id.equals("data")) {
                    dStart = body;
                    dLen = len;
                    if (len == 0 || len == 0xFFFFFFFFL || body + len > size) dLen = size - body;
                    break;
                }
                p = body + len + (len & 1);
            }
            if (fmtTag < 0 || dStart < 0) throw new IOException("the WAV file has no audio");
            boolean flt = fmtTag == 3;
            if (!(fmtTag == 1 || flt) || ch2 < 1 || rt < 1000) throw new IOException("unsupported WAV format " + fmtTag);
            if (!(bps == 8 || bps == 16 || bps == 24 || bps == 32 || (flt && bps == 64))) {
                throw new IOException("unsupported WAV sample size " + bps);
            }
            this.rate = rt;
            this.channels = ch2;
            this.bits = bps;
            this.isFloat = flt;
            this.blockAlign = align > 0 ? align : ch2 * (bps / 8);
            this.dataStart = dStart;
            this.frames = dLen / blockAlign;
            this.buf = ByteBuffer.allocate(CHUNK_FRAMES * blockAlign).order(ByteOrder.LITTLE_ENDIAN);
        } catch (IOException | RuntimeException e) {
            try {
                ch.close();
            } catch (IOException ignored) { }
            throw e;
        }
    }

    private void readFully(ByteBuffer b, long at) throws IOException {
        b.clear();
        long p = at;
        while (b.hasRemaining()) {
            int n = ch.read(b, p);
            if (n < 0) break;
            p += n;
        }
        b.flip();
    }

    private static String tag(ByteBuffer b, int at) {
        return new String(new byte[]{b.get(at), b.get(at + 1), b.get(at + 2), b.get(at + 3)}, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    @Override public int sampleRate() { return rate; }
    @Override public long length() { return frames; }
    @Override public long position() { return pos; }
    @Override public String format() { return "WAV"; }

    @Override
    public int read(float[] out, int maxFrames) throws IOException {
        long left = frames - pos;
        if (left <= 0) return -1;
        int n = (int) Math.min(Math.min(maxFrames, CHUNK_FRAMES), left);
        buf.clear();
        buf.limit(n * blockAlign);
        long at = dataStart + pos * blockAlign;
        while (buf.hasRemaining()) {
            int r = ch.read(buf, at);
            if (r < 0) break;
            at += r;
        }
        buf.flip();
        int got = buf.limit() / blockAlign;
        if (got <= 0) return -1;
        int bytes = bits / 8;
        for (int i = 0; i < got; i++) {
            int base = i * blockAlign;
            float l = sample(base, bytes);
            float r = channels > 1 ? sample(base + bytes, bytes) : l;
            out[2 * i] = l;
            out[2 * i + 1] = r;
        }
        pos += got;
        return got;
    }

    private float sample(int at, int bytes) {
        if (isFloat) return bytes == 4 ? buf.getFloat(at) : (float) buf.getDouble(at);
        switch (bytes) {
            case 1:
                return ((buf.get(at) & 0xFF) - 128) / 128f;
            case 2:
                return buf.getShort(at) / 32768f;
            case 3: {
                int v = (buf.get(at) & 0xFF) | ((buf.get(at + 1) & 0xFF) << 8) | (buf.get(at + 2) << 16);
                return v / 8388608f;
            }
            default:
                return buf.getInt(at) / 2147483648f;
        }
    }

    @Override
    public void seek(long frame) {
        pos = Math.max(0, Math.min(frame, frames));
    }

    @Override
    public void close() {
        try {
            ch.close();
        } catch (IOException ignored) { }
    }
}
