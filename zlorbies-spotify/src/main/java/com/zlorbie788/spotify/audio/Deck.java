package com.zlorbie788.spotify.audio;

import java.io.IOException;
import java.nio.file.Path;

/** One song being played by the engine: the decoder plus the conversion to the engine's sample rate. */
final class Deck {
    final long token;
    final Path path;
    final PcmSource src;
    final int srcRate;
    private final boolean passthrough;
    private final double step;
    private float[] in = new float[2 * 16384];
    private long inStart;
    private int inLen;
    private double pos;
    private boolean eof;
    private long outPos;
    private final float[] tmp = new float[2 * 4096];

    Deck(long token, Path path, PcmSource src) {
        this.token = token;
        this.path = path;
        this.src = src;
        this.srcRate = src.sampleRate();
        this.passthrough = srcRate == Engine.RATE;
        this.step = srcRate / (double) Engine.RATE;
    }

    /** Length in engine frames, or -1. */
    long outLength() {
        long l = src.length();
        return l < 0 ? -1 : Math.round(l / step);
    }

    long outPos() {
        return outPos;
    }

    long remaining() {
        long l = outLength();
        return l < 0 ? Long.MAX_VALUE : Math.max(0, l - outPos);
    }

    /** Reads up to {@code frames} engine frames into {@code dst} at frame offset {@code off}; 0 means the song is over. */
    int read(float[] dst, int off, int frames) throws IOException {
        if (passthrough) {
            int n = Math.min(frames, 4096);
            int got = src.read(tmp, n);
            if (got <= 0) return 0;
            System.arraycopy(tmp, 0, dst, off * 2, got * 2);
            outPos += got;
            return got;
        }
        int produced = 0;
        while (produced < frames) {
            long base = (long) Math.floor(pos);
            long needTo = base + 3 - inStart;
            while (needTo > inLen && !eof) fill(base);
            long idx = base - inStart;
            if (idx >= inLen) break;                 // nothing left to interpolate from
            double t = pos - base;
            int i0 = (int) Math.max(0, idx - 1);
            int i1 = (int) idx;
            int i2 = (int) Math.min(inLen - 1, idx + 1);
            int i3 = (int) Math.min(inLen - 1, idx + 2);
            for (int c = 0; c < 2; c++) {
                float p0 = in[i0 * 2 + c], p1 = in[i1 * 2 + c], p2 = in[i2 * 2 + c], p3 = in[i3 * 2 + c];
                float tt = (float) t;
                dst[(off + produced) * 2 + c] = p1 + 0.5f * tt * (p2 - p0 + tt * (2f * p0 - 5f * p1 + 4f * p2 - p3
                        + tt * (3f * (p1 - p2) + p3 - p0)));
            }
            pos += step;
            produced++;
        }
        outPos += produced;
        return produced;
    }

    private void fill(long base) throws IOException {
        // drop what is behind us (keep one frame before the current position)
        long keepFrom = Math.max(inStart, base - 2);
        int drop = (int) (keepFrom - inStart);
        if (drop > 0) {
            System.arraycopy(in, drop * 2, in, 0, (inLen - drop) * 2);
            inLen -= drop;
            inStart = keepFrom;
        }
        int space = in.length / 2 - inLen;
        if (space < 1024) {
            in = java.util.Arrays.copyOf(in, in.length * 2);
            space = in.length / 2 - inLen;
        }
        int n = Math.min(space, 4096);
        int got = src.read(tmp, n);
        if (got <= 0) {
            eof = true;
            return;
        }
        System.arraycopy(tmp, 0, in, inLen * 2, got * 2);
        inLen += got;
    }

    void seek(long outFrame) throws IOException {
        long sf = Math.round(outFrame * step);
        src.seek(sf);
        long at = src.position();
        inStart = at;
        inLen = 0;
        pos = at;
        eof = false;
        outPos = Math.round(at / step);
    }

    void close() {
        src.close();
    }
}
