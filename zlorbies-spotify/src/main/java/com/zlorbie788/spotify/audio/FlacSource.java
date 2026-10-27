package com.zlorbie788.spotify.audio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * A small, complete FLAC decoder (all subframe types, any block size, 8 to 24 bit, up to 8 channels). It reads
 * through a window of the file, can jump to any sample (seek table when the file has one, otherwise a search
 * over the frame headers) and delivers stereo floats.
 */
public final class FlacSource implements PcmSource {
    private static final int WIN = 1 << 20;
    private static final int REFILL_BELOW = 384 * 1024;

    private final FileChannel ch;
    private final long fileLen;
    private int rate;
    private int channels;
    private int bps;
    private long total;
    private int minBlock;
    private int maxBlock;
    private long firstFrame;
    private long[] seekSamples = new long[0];
    private long[] seekOffsets = new long[0];

    // the window over the file
    private final byte[] win = new byte[WIN];
    private long winOff = -1;
    private int winLen;
    private long bitPos;

    // the frame in hand
    private int[][] sub = new int[0][];
    private float[] frame = new float[0];
    private int frameLen;
    private int framePos;
    private long frameStart;
    private long fileOff;

    private static final byte[] CRC8 = new byte[256];

    static {
        for (int i = 0; i < 256; i++) {
            int c = i;
            for (int k = 0; k < 8; k++) c = (c & 0x80) != 0 ? ((c << 1) ^ 0x07) & 0xFF : (c << 1) & 0xFF;
            CRC8[i] = (byte) c;
        }
    }

    private static final class Hdr {
        int blockSize;
        int rate;
        int assign;
        int bps;
        long number;
        boolean variable;
        int len;
    }

    public FlacSource(Path path) throws IOException {
        ch = FileChannel.open(path, StandardOpenOption.READ);
        try {
            fileLen = ch.size();
            long p = 0;
            ByteBuffer b = ByteBuffer.allocate(10);
            read(b, 0);
            if (b.limit() >= 10 && b.get(0) == 'I' && b.get(1) == 'D' && b.get(2) == '3') {
                p = 10 + (((b.get(6) & 0x7F) << 21) | ((b.get(7) & 0x7F) << 14) | ((b.get(8) & 0x7F) << 7) | (b.get(9) & 0x7F));
            }
            b = ByteBuffer.allocate(4);
            read(b, p);
            if (b.limit() < 4 || b.get(0) != 'f' || b.get(1) != 'L' || b.get(2) != 'a' || b.get(3) != 'C') {
                throw new IOException("not a FLAC file");
            }
            p += 4;
            boolean last = false;
            boolean gotInfo = false;
            while (!last) {
                b = ByteBuffer.allocate(4);
                read(b, p);
                if (b.limit() < 4) throw new IOException("the FLAC file ends too early");
                int h = b.get(0) & 0xFF;
                last = (h & 0x80) != 0;
                int type = h & 0x7F;
                int len = ((b.get(1) & 0xFF) << 16) | ((b.get(2) & 0xFF) << 8) | (b.get(3) & 0xFF);
                long body = p + 4;
                if (type == 0 && len >= 34) {
                    ByteBuffer s = ByteBuffer.allocate(34);
                    read(s, body);
                    minBlock = ((s.get(0) & 0xFF) << 8) | (s.get(1) & 0xFF);
                    maxBlock = ((s.get(2) & 0xFF) << 8) | (s.get(3) & 0xFF);
                    long v = 0;
                    for (int i = 10; i < 18; i++) v = (v << 8) | (s.get(i) & 0xFF);
                    rate = (int) (v >>> 44);
                    channels = (int) ((v >>> 41) & 7) + 1;
                    bps = (int) ((v >>> 36) & 31) + 1;
                    total = v & 0xFFFFFFFFFL;
                    gotInfo = true;
                } else if (type == 3 && len >= 18) {
                    ByteBuffer s = ByteBuffer.allocate(len);
                    read(s, body);
                    int n = len / 18;
                    long[] ss = new long[n];
                    long[] oo = new long[n];
                    int k = 0;
                    for (int i = 0; i < n; i++) {
                        long smp = s.getLong(i * 18);
                        long off = s.getLong(i * 18 + 8);
                        if (smp == -1L || smp < 0 || off < 0) continue;
                        ss[k] = smp;
                        oo[k] = off;
                        k++;
                    }
                    seekSamples = java.util.Arrays.copyOf(ss, k);
                    seekOffsets = java.util.Arrays.copyOf(oo, k);
                }
                p = body + len;
            }
            if (!gotInfo || rate <= 0 || bps > 32 || maxBlock <= 0) throw new IOException("the FLAC file has no usable stream info");
            firstFrame = p;
            fileOff = p;
            frameStart = 0;
        } catch (IOException | RuntimeException e) {
            try {
                ch.close();
            } catch (IOException ignored) { }
            throw e;
        }
    }

    private void read(ByteBuffer b, long at) throws IOException {
        long p = at;
        while (b.hasRemaining()) {
            int n = ch.read(b, p);
            if (n < 0) break;
            p += n;
        }
        b.flip();
    }

    @Override public int sampleRate() { return rate; }
    @Override public long length() { return total > 0 ? total : -1; }
    @Override public long position() { return frameStart + framePos; }
    @Override public String format() { return "FLAC"; }

    // ------------------------------------------------------------ the window

    /** Makes sure the bytes from {@code off} on are in the window (at least {@code need} of them, or all that exist). */
    private void window(long off, int need) throws IOException {
        if (winOff >= 0 && off >= winOff) {
            long left = winOff + winLen - off;
            if (left >= need || winOff + winLen >= fileLen) return;
        }
        ByteBuffer b = ByteBuffer.wrap(win);
        read(b, off);
        winOff = off;
        winLen = b.limit();
    }

    // ------------------------------------------------------------ bits

    private long bits(int n) {
        long v = 0;
        int need = n;
        long bp = bitPos;
        while (need > 0) {
            int idx = (int) (bp >>> 3);
            int off = (int) (bp & 7);
            int take = Math.min(need, 8 - off);
            int by = win[idx] & 0xFF;
            v = (v << take) | ((by >>> (8 - off - take)) & ((1 << take) - 1));
            bp += take;
            need -= take;
        }
        bitPos = bp;
        return v;
    }

    private int signed(int n) {
        if (n == 0) return 0;
        long v = bits(n);
        return (int) (v << (64 - n) >> (64 - n));
    }

    private int unary() {
        int count = 0;
        long bp = bitPos;
        while (true) {
            int idx = (int) (bp >>> 3);
            int off = (int) (bp & 7);
            int by = (win[idx] & 0xFF) & (0xFF >>> off);
            if (by == 0) {
                count += 8 - off;
                bp += 8 - off;
                continue;
            }
            int zeros = Integer.numberOfLeadingZeros(by) - 24 - off;
            count += zeros;
            bp += zeros + 1;
            bitPos = bp;
            return count;
        }
    }

    // ------------------------------------------------------------ headers

    private Hdr parseHeader(int p) {
        byte[] w = win;
        if (p + 6 > winLen) return null;
        if ((w[p] & 0xFF) != 0xFF || (w[p + 1] & 0xFE) != 0xF8) return null;
        Hdr h = new Hdr();
        h.variable = (w[p + 1] & 1) != 0;
        int b2 = w[p + 2] & 0xFF;
        int b3 = w[p + 3] & 0xFF;
        int bsCode = b2 >> 4;
        int srCode = b2 & 15;
        h.assign = b3 >> 4;
        int bpsCode = (b3 >> 1) & 7;
        if ((b3 & 1) != 0 || bsCode == 0 || srCode == 15 || bpsCode == 3 || h.assign > 10) return null;
        int q = p + 4;
        int lead = w[q] & 0xFF;
        int extra;
        long val;
        if (lead < 0x80) { extra = 0; val = lead; }
        else if ((lead & 0xE0) == 0xC0) { extra = 1; val = lead & 0x1F; }
        else if ((lead & 0xF0) == 0xE0) { extra = 2; val = lead & 0x0F; }
        else if ((lead & 0xF8) == 0xF0) { extra = 3; val = lead & 0x07; }
        else if ((lead & 0xFC) == 0xF8) { extra = 4; val = lead & 0x03; }
        else if ((lead & 0xFE) == 0xFC) { extra = 5; val = lead & 0x01; }
        else if (lead == 0xFE) { extra = 6; val = 0; }
        else return null;
        q++;
        if (q + extra + 4 > winLen) return null;
        for (int k = 0; k < extra; k++) {
            int c = w[q++] & 0xFF;
            if ((c & 0xC0) != 0x80) return null;
            val = (val << 6) | (c & 0x3F);
        }
        h.number = val;
        if (bsCode == 1) h.blockSize = 192;
        else if (bsCode <= 5) h.blockSize = 576 << (bsCode - 2);
        else if (bsCode == 6) h.blockSize = (w[q++] & 0xFF) + 1;
        else if (bsCode == 7) { h.blockSize = (((w[q] & 0xFF) << 8) | (w[q + 1] & 0xFF)) + 1; q += 2; }
        else h.blockSize = 256 << (bsCode - 8);
        switch (srCode) {
            case 0 -> h.rate = rate;
            case 1 -> h.rate = 88200;
            case 2 -> h.rate = 176400;
            case 3 -> h.rate = 192000;
            case 4 -> h.rate = 8000;
            case 5 -> h.rate = 16000;
            case 6 -> h.rate = 22050;
            case 7 -> h.rate = 24000;
            case 8 -> h.rate = 32000;
            case 9 -> h.rate = 44100;
            case 10 -> h.rate = 48000;
            case 11 -> h.rate = 96000;
            case 12 -> h.rate = (w[q++] & 0xFF) * 1000;
            case 13 -> { h.rate = ((w[q] & 0xFF) << 8) | (w[q + 1] & 0xFF); q += 2; }
            default -> { h.rate = (((w[q] & 0xFF) << 8) | (w[q + 1] & 0xFF)) * 10; q += 2; }
        }
        switch (bpsCode) {
            case 0 -> h.bps = bps;
            case 1 -> h.bps = 8;
            case 2 -> h.bps = 12;
            case 4 -> h.bps = 16;
            case 5 -> h.bps = 20;
            case 6 -> h.bps = 24;
            default -> h.bps = 32;
        }
        if (q >= winLen) return null;
        int crc = 0;
        for (int i = p; i < q; i++) crc = CRC8[(crc ^ (w[i] & 0xFF)) & 0xFF] & 0xFF;
        if (crc != (w[q] & 0xFF)) return null;
        h.len = q + 1 - p;
        return h;
    }

    private int chCount(Hdr h) {
        return h.assign <= 7 ? h.assign + 1 : 2;
    }

    /** File offset of the first valid frame header at or after {@code from}, or -1. */
    private long findHeader(long from) throws IOException {
        long off = from;
        while (off < fileLen - 6) {
            window(off, 4096);
            int p = (int) (off - winOff);
            int end = Math.min(winLen - 16, p + WIN / 2);
            if (end <= p) {
                if (winOff + winLen >= fileLen) return -1;
                off = winOff + winLen - 16;
                continue;
            }
            for (int i = p; i < end; i++) {
                if ((win[i] & 0xFF) == 0xFF && (win[i + 1] & 0xFE) == 0xF8 && parseHeader(i) != null) {
                    Hdr h = parseHeader(i);
                    if (h.rate == rate && chCount(h) == channels) return winOff + i;
                }
            }
            off = winOff + end;
        }
        return -1;
    }

    private long startOf(Hdr h) {
        return h.variable ? h.number : h.number * maxBlock;
    }

    // ------------------------------------------------------------ frames

    /** Decodes the frame at {@link #fileOff}; false at the end (or when nothing valid is left). */
    private boolean nextFrame() throws IOException {
        while (true) {
            if (fileOff >= fileLen - 6) return false;
            window(fileOff, REFILL_BELOW);
            int p = (int) (fileOff - winOff);
            Hdr h = parseHeader(p);
            if (h == null) {
                long f = findHeader(fileOff + 1);
                if (f < 0) return false;
                fileOff = f;
                continue;
            }
            int n = chCount(h);
            if (sub.length < n) sub = new int[Math.max(n, 2)][];
            for (int c = 0; c < n; c++) if (sub[c] == null || sub[c].length < h.blockSize) sub[c] = new int[Math.max(h.blockSize, 4608)];
            bitPos = (long) (p + h.len) * 8;
            try {
                for (int c = 0; c < n; c++) {
                    int sb = h.bps;
                    if ((h.assign == 8 && c == 1) || (h.assign == 9 && c == 0) || (h.assign == 10 && c == 1)) sb++;
                    subframe(sub[c], h.blockSize, sb);
                }
            } catch (IndexOutOfBoundsException | IllegalStateException e) {
                long f = findHeader(fileOff + 1);
                if (f < 0) return false;
                fileOff = f;
                continue;
            }
            bitPos = (bitPos + 7) & ~7L;
            bitPos += 16;
            long endByte = bitPos >>> 3;
            if (endByte > winLen) {
                long f = findHeader(fileOff + 1);
                if (f < 0) return false;
                fileOff = f;
                continue;
            }
            fileOff = winOff + endByte;
            decorrelate(h, n);
            toFloat(h, n);
            frameStart = startOf(h);
            framePos = 0;
            return true;
        }
    }

    private void decorrelate(Hdr h, int n) {
        int len = h.blockSize;
        if (h.assign == 8) {
            int[] l = sub[0], s = sub[1];
            for (int i = 0; i < len; i++) s[i] = l[i] - s[i];
        } else if (h.assign == 9) {
            int[] s = sub[0], r = sub[1];
            for (int i = 0; i < len; i++) s[i] = s[i] + r[i];
        } else if (h.assign == 10) {
            int[] m = sub[0], s = sub[1];
            for (int i = 0; i < len; i++) {
                int side = s[i];
                int mid = (m[i] << 1) | (side & 1);
                m[i] = (mid + side) >> 1;
                s[i] = (mid - side) >> 1;
            }
        }
    }

    private void toFloat(Hdr h, int n) {
        int len = h.blockSize;
        if (frame.length < len * 2) frame = new float[len * 2];
        float scale = 1f / (float) (1L << (h.bps - 1));
        int[] a = sub[0];
        int[] b = n > 1 ? sub[1] : sub[0];
        for (int i = 0; i < len; i++) {
            frame[2 * i] = a[i] * scale;
            frame[2 * i + 1] = b[i] * scale;
        }
        frameLen = len;
    }

    private void subframe(int[] out, int n, int sb) {
        bits(1);
        int type = (int) bits(6);
        int wasted = 0;
        if (bits(1) == 1) wasted = 1 + unary();
        int bs = sb - wasted;
        if (bs <= 0 || bs > 32) throw new IllegalStateException("bad sample size");
        if (type == 0) {
            int v = signed(bs);
            java.util.Arrays.fill(out, 0, n, v);
        } else if (type == 1) {
            for (int i = 0; i < n; i++) out[i] = signed(bs);
        } else if (type >= 8 && type <= 12) {
            int order = type - 8;
            for (int i = 0; i < order; i++) out[i] = signed(bs);
            residual(out, n, order);
            switch (order) {
                case 1 -> { for (int i = 1; i < n; i++) out[i] += out[i - 1]; }
                case 2 -> { for (int i = 2; i < n; i++) out[i] += 2 * out[i - 1] - out[i - 2]; }
                case 3 -> { for (int i = 3; i < n; i++) out[i] += 3 * out[i - 1] - 3 * out[i - 2] + out[i - 3]; }
                case 4 -> { for (int i = 4; i < n; i++) out[i] += 4 * out[i - 1] - 6 * out[i - 2] + 4 * out[i - 3] - out[i - 4]; }
                default -> { }
            }
        } else if (type >= 32) {
            int order = type - 31;
            for (int i = 0; i < order; i++) out[i] = signed(bs);
            int prec = (int) bits(4) + 1;
            if (prec == 16) throw new IllegalStateException("bad precision");
            int shift = signed(5);
            int[] coef = new int[order];
            for (int i = 0; i < order; i++) coef[i] = signed(prec);
            residual(out, n, order);
            if (shift < 0) shift = 0;
            for (int i = order; i < n; i++) {
                long sum = 0;
                for (int j = 0; j < order; j++) sum += (long) coef[j] * out[i - 1 - j];
                out[i] += (int) (sum >> shift);
            }
        } else {
            throw new IllegalStateException("bad subframe type");
        }
        if (wasted > 0) for (int i = 0; i < n; i++) out[i] <<= wasted;
    }

    private void residual(int[] out, int n, int order) {
        int method = (int) bits(2);
        if (method > 1) throw new IllegalStateException("bad residual");
        int porder = (int) bits(4);
        int parts = 1 << porder;
        int per = n >> porder;
        int pos = order;
        int pbits = method == 0 ? 4 : 5;
        int escape = method == 0 ? 15 : 31;
        for (int p = 0; p < parts; p++) {
            int cnt = p == 0 ? per - order : per;
            if (cnt < 0) throw new IllegalStateException("bad partition");
            int param = (int) bits(pbits);
            if (param == escape) {
                int nb = (int) bits(5);
                for (int k = 0; k < cnt; k++) out[pos++] = nb == 0 ? 0 : signed(nb);
            } else {
                for (int k = 0; k < cnt; k++) {
                    long q = unary();
                    long v = (q << param) | (param > 0 ? bits(param) : 0);
                    out[pos++] = (int) ((v >>> 1) ^ -(v & 1));
                }
            }
        }
    }

    // ------------------------------------------------------------ PcmSource

    @Override
    public int read(float[] out, int maxFrames) throws IOException {
        if (framePos >= frameLen) {
            if (!nextFrame()) return -1;
        }
        int n = Math.min(maxFrames, frameLen - framePos);
        System.arraycopy(frame, framePos * 2, out, 0, n * 2);
        framePos += n;
        return n;
    }

    @Override
    public void seek(long target) throws IOException {
        long t = Math.max(0, total > 0 ? Math.min(target, total) : target);
        frameLen = 0;
        framePos = 0;
        long off = firstFrame;
        if (t > 0 && seekSamples.length > 0) {
            int best = -1;
            for (int i = 0; i < seekSamples.length; i++) if (seekSamples[i] <= t) best = i;
            if (best >= 0) off = firstFrame + seekOffsets[best];
        } else if (t > 0 && total > 0) {
            off = searchFrame(t);
        }
        fileOff = off;
        frameStart = 0;
        // decode forward to the frame that holds the target
        while (true) {
            if (!nextFrame()) {
                frameLen = 0;
                framePos = 0;
                frameStart = t;
                return;
            }
            if (frameStart + frameLen > t) {
                framePos = (int) Math.max(0, Math.min(frameLen, t - frameStart));
                return;
            }
        }
    }

    /** Binary search over the file for the frame holding {@code t}; returns a frame offset at or before it. */
    private long searchFrame(long t) throws IOException {
        long lo = firstFrame;
        long hi = fileLen;
        long best = firstFrame;
        for (int i = 0; i < 48 && hi - lo > 2048; i++) {
            long mid = lo + (hi - lo) / 2;
            long f = findHeader(mid);
            if (f < 0) {
                hi = mid;
                continue;
            }
            window(f, 64);
            Hdr h = parseHeader((int) (f - winOff));
            if (h == null) {
                hi = mid;
                continue;
            }
            long s = startOf(h);
            if (s > t) {
                hi = mid;
            } else if (s + h.blockSize <= t) {
                best = f;
                lo = f + 1;
            } else {
                return f;
            }
        }
        return best;
    }

    @Override
    public void close() {
        try {
            ch.close();
        } catch (IOException ignored) { }
    }
}
