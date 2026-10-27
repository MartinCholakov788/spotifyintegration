package com.zlorbie788.spotify.audio;

import com.github.trilarion.sound.vorbis.jcraft.jorbis.OggReader;

import java.io.IOException;
import java.nio.file.Path;

/** OGG Vorbis through the bundled JOrbis decoder. */
public final class OggSource implements PcmSource {
    private final OggReader reader;
    private final int rate;
    private final long length;
    private long position;

    public OggSource(Path path) throws IOException {
        try {
            reader = new OggReader(path.toString());
        } catch (Exception e) {
            throw new IOException("could not read the OGG file: " + e.getMessage(), e);
        }
        rate = reader.rate();
        length = reader.total();
    }

    @Override public int sampleRate() { return rate; }
    @Override public long length() { return length; }
    @Override public long position() { return position; }
    @Override public String format() { return "OGG"; }

    @Override
    public int read(float[] out, int maxFrames) {
        int n = reader.read(out, maxFrames);
        if (n > 0) position += n;
        return n;
    }

    @Override
    public void seek(long frame) throws IOException {
        long t = Math.max(0, length > 0 ? Math.min(frame, length) : frame);
        if (!reader.seek(t)) throw new IOException("could not seek in the OGG file");
        position = t;
    }

    @Override
    public void close() {
        reader.close();
    }
}
