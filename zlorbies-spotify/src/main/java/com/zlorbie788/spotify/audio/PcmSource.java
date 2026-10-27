package com.zlorbie788.spotify.audio;

import java.io.Closeable;
import java.io.IOException;

/**
 * A decoded song, read as stereo floats. Every decoder (MP3, OGG, FLAC, WAV) looks like this to the engine:
 * mono files are doubled, files with more than two channels give their first two.
 */
public interface PcmSource extends Closeable {
    /** Sample rate of the frames this source delivers, in Hz. */
    int sampleRate();

    /** Length in frames at {@link #sampleRate()}, or -1 when it is not known (yet). */
    long length();

    /** Index of the next frame {@link #read} will return. */
    long position();

    /**
     * Reads up to {@code maxFrames} stereo frames (interleaved left, right) into {@code out}, which must hold
     * {@code 2 * maxFrames} values.
     *
     * @return the number of frames, or -1 at the end of the song (never 0)
     */
    int read(float[] out, int maxFrames) throws IOException;

    /** Moves to a frame; the next {@link #read} starts there (clamped to the song). */
    void seek(long frame) throws IOException;

    /** Short name of the format ("MP3", "OGG", "FLAC", "WAV"). */
    String format();

    @Override
    void close();
}
