package com.zlorbie788.spotify.audio;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.DecoderException;
import javazoom.jl.decoder.FileSource;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MP3 through the bundled JLayer decoder. It plays gaplessly (the encoder's padding at both ends is trimmed when the
 * file says how much), and it seeks exactly: the first time you seek it reads the frame table of the file once.
 */
public final class Mp3Source implements PcmSource {
    private static final int LEAD = 4;

    private final Path path;
    private final long fileSize;
    private FileSource src;
    private Bitstream bs;
    private Decoder dec;

    private int rate;
    private int spf = 1152;
    private long skipStart;
    private long lastRaw = Long.MAX_VALUE;
    private long length = -1;
    private boolean exactLength;
    private long[] offsets;

    /** Raw index (counting from the first audio frame, before trimming) of the next sample the decoder will give. */
    private long rawPos;
    /** Frames still to be thrown away from the front of what comes out (after a seek). */
    private long dropFront;
    private short[] pending = new short[0];
    private int pendingLen;
    private int pendingPos;
    private int pendingCh = 2;
    private boolean ended;
    private long position;

    public Mp3Source(Path path) throws IOException {
        this.path = path;
        this.fileSize = Files.size(path);
        try {
            openStream();
            Header first = bs.readFrame();
            if (first == null) throw new IOException("no MP3 audio found");
            rate = first.frequency();
            spf = first.layer() == 1 ? 384 : (first.layer() == 3 && first.version() != Header.MPEG1 ? 576 : 1152);
            skipStart = first.samplesToSkipAtStart();
            int vf = first.vbrFrames();
            if (vf > 0) {
                lastRaw = (long) vf * spf - first.samplesToSkipAtEnd();
                length = Math.max(0, lastRaw - skipStart);
                exactLength = true;
            } else {
                float ms = first.totalMs((int) Math.min(Integer.MAX_VALUE, fileSize));
                length = (long) (ms * rate / 1000.0);
            }
            // the first frame is real audio: decode it from the start again
            openStream();
            rawPos = 0;
            dropFront = skipStart;
            position = 0;
        } catch (BitstreamException e) {
            close();
            throw new IOException("could not read the MP3: " + e.getMessage(), e);
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    private void openStream() throws IOException {
        closeStream();
        src = new FileSource(path);
        bs = new Bitstream(src);
        dec = new Decoder();
    }

    private void closeStream() {
        try {
            if (bs != null) bs.close();
        } catch (BitstreamException ignored) { }
        try {
            if (src != null) src.close();
        } catch (IOException ignored) { }
        bs = null;
        src = null;
    }

    @Override public int sampleRate() { return rate; }
    @Override public long length() { return length; }
    @Override public long position() { return position; }
    @Override public String format() { return "MP3"; }

    /** True once {@link #length()} is exact (from the file's own header or from reading every frame). */
    public boolean exactLength() {
        return exactLength;
    }

    // ------------------------------------------------------------ decoding

    /** Decodes frames until some audio is pending; false at the end. */
    private boolean fill() {
        int bad = 0;
        while (pendingPos >= pendingLen) {
            if (ended) return false;
            Header h;
            try {
                h = bs.readFrame();
            } catch (BitstreamException e) {
                if (++bad > 50) {
                    ended = true;
                    return false;
                }
                bs.closeFrame();
                continue;
            }
            if (h == null) {
                ended = true;
                return false;
            }
            SampleBuffer out;
            try {
                out = (SampleBuffer) dec.decodeFrame(h, bs);
            } catch (DecoderException | RuntimeException e) {
                bs.closeFrame();
                if (++bad > 50) {
                    ended = true;
                    return false;
                }
                continue;
            }
            bs.closeFrame();
            int len = out.getBufferLength();
            if (len == 0) continue;
            int ch = Math.max(1, out.getChannelCount());
            int frames = len / ch;
            long start = rawPos;
            long end = rawPos + frames;
            rawPos = end;
            long keepFrom = Math.max(start, start + Math.max(0, dropFrontLeft(start)));
            long keepTo = Math.min(end, lastRaw);
            if (keepTo <= keepFrom) {
                if (end >= lastRaw) {
                    ended = true;
                    return false;
                }
                continue;
            }
            int from = (int) (keepFrom - start);
            int n = (int) (keepTo - keepFrom);
            if (pending.length < n * ch) pending = new short[n * ch];
            System.arraycopy(out.getBuffer(), from * ch, pending, 0, n * ch);
            pendingLen = n * ch;
            pendingPos = 0;
            pendingCh = ch;
            if (keepTo >= lastRaw) ended = true;
        }
        return true;
    }

    /** How many frames of the buffer starting at raw index {@code start} fall before the audio we want to give. */
    private long dropFrontLeft(long start) {
        // dropFront is the raw index we still have to reach (skipStart or a seek target)
        return dropFront - start;
    }

    @Override
    public int read(float[] out, int maxFrames) {
        if (pendingPos >= pendingLen && !fill()) return -1;
        int ch = pendingCh;
        int avail = (pendingLen - pendingPos) / ch;
        int n = Math.min(maxFrames, avail);
        short[] p = pending;
        int at = pendingPos;
        if (ch == 1) {
            for (int i = 0; i < n; i++) {
                float v = p[at + i] / 32768f;
                out[2 * i] = v;
                out[2 * i + 1] = v;
            }
        } else {
            for (int i = 0; i < n; i++) {
                out[2 * i] = p[at + 2 * i] / 32768f;
                out[2 * i + 1] = p[at + 2 * i + 1] / 32768f;
            }
        }
        pendingPos += n * ch;
        position += n;
        return n;
    }

    // ------------------------------------------------------------ seeking

    /** Reads the position of every frame once; afterwards the length and every seek are exact. */
    private void buildIndex() throws IOException {
        if (offsets != null) return;
        java.util.ArrayList<Long> list = new java.util.ArrayList<>();
        try (FileSource s = new FileSource(path); Bitstream b = new Bitstream(s)) {
            while (true) {
                long before = b.position();
                Header h = b.readFrame();
                if (h == null) break;
                list.add(before);
                b.closeFrame();
            }
        } catch (BitstreamException e) {
            // keep what was found
        }
        long[] arr = new long[list.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = list.get(i);
        offsets = arr;
        if (arr.length > 0 && lastRaw == Long.MAX_VALUE) {
            lastRaw = (long) arr.length * spf;
            length = Math.max(0, lastRaw - skipStart);
            exactLength = true;
        } else if (arr.length > 0) {
            length = Math.max(0, Math.min(lastRaw, (long) arr.length * spf) - skipStart);
            exactLength = true;
        }
    }

    @Override
    public void seek(long frame) throws IOException {
        buildIndex();
        long target = Math.max(0, length >= 0 ? Math.min(frame, length) : frame);
        long raw = target + skipStart;
        int k = (int) Math.min(offsets.length, raw / spf);
        pendingPos = 0;
        pendingLen = 0;
        ended = false;
        int j = Math.max(0, k - LEAD);
        try {
            if (j == 0 || j >= offsets.length) {
                openStream();
                j = 0;
            } else {
                bs.seek(offsets[j]);
                dec.seekNotify();
            }
        } catch (BitstreamException e) {
            openStream();
            j = 0;
        }
        // decode the lead-in frames and throw their audio away, so the reservoir is full when we reach the target
        rawPos = (long) j * spf;
        long begin = (long) k * spf;
        while (rawPos < begin) {
            Header h;
            try {
                h = bs.readFrame();
            } catch (BitstreamException e) {
                bs.closeFrame();
                continue;
            }
            if (h == null) {
                ended = true;
                break;
            }
            try {
                dec.decodeFrame(h, bs);
            } catch (DecoderException | RuntimeException ignored) { }
            bs.closeFrame();
            rawPos += spf;
        }
        rawPos = begin;
        dropFront = raw;
        position = target;
    }

    @Override
    public void close() {
        closeStream();
    }
}
