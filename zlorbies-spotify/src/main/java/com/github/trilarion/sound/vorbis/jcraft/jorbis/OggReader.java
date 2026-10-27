package com.github.trilarion.sound.vorbis.jcraft.jorbis;

import com.github.trilarion.sound.util.SoundException;

/**
 * Spotify Integration Mod: a thin reader on top of the bundled JOrbis {@link VorbisFile}. It lives in this package
 * because it uses package-private members of the decoder; it returns the decoded audio as floats (no rounding to 16 bit).
 */
public final class OggReader {
    private final VorbisFile vf;
    private final int channels;
    private final int rate;
    private final float[][][] holder = new float[1][][];
    private final int[] index;

    public OggReader(String file) throws SoundException {
        try {
            vf = new VorbisFile(new VorbisFile.SeekableInputStream(file), null, 0);
        } catch (java.io.IOException e) {
            throw new SoundException("OggReader: " + e);
        }
        Info info = vf.getInfo(0);
        if (info == null || info.channels < 1) throw new SoundException("OggReader: no audio stream");
        channels = info.channels;
        rate = info.rate;
        index = new int[channels];
        // opening a seekable file leaves the decoder unloaded: position it at the start of the audio
        if (vf.raw_seek(0) != 0) throw new SoundException("OggReader: could not start decoding");
    }

    public int channels() {
        return channels;
    }

    public int rate() {
        return rate;
    }

    /** Length of the first logical stream in samples per channel, or -1. */
    public long total() {
        return vf.seekable() ? vf.pcm_total(0) : -1;
    }

    public long tell() {
        return vf.pcm_tell();
    }

    public boolean seek(long sample) {
        if (sample <= 0) return vf.raw_seek(0) == 0; // pcm_seek refuses the very start
        return vf.pcm_seek(sample) == 0;
    }

    public Comment comment() {
        return vf.getComment(0);
    }

    /**
     * Decodes the next block of audio into stereo floats.
     *
     * @return frames written (at most {@code max}), or -1 at the end
     */
    public int read(float[] out, int max) {
        while (true) {
            if (vf.decode_ready) {
                int samples = vf.vd.synthesis_pcmout(holder, index);
                if (samples != 0) {
                    float[][] pcm = holder[0];
                    if (samples > max) samples = max;
                    float[] l = pcm[0];
                    float[] r = channels > 1 ? pcm[1] : pcm[0];
                    int li = index[0];
                    int ri = channels > 1 ? index[1] : index[0];
                    for (int i = 0; i < samples; i++) {
                        out[2 * i] = clip(l[li + i]);
                        out[2 * i + 1] = clip(r[ri + i]);
                    }
                    vf.vd.synthesis_read(samples);
                    vf.pcm_offset += samples;
                    return samples;
                }
            }
            switch (vf.process_packet(1)) {
                case 0:
                    return -1;
                case -1:
                    // a hole in the data: carry on with the next packet
                    break;
                default:
                    break;
            }
        }
    }

    private static float clip(float v) {
        return v > 1f ? 1f : (v < -1f ? -1f : v);
    }

    public void close() {
        try {
            vf.close();
        } catch (java.io.IOException ignored) { }
    }
}
