package com.zlorbie788.spotify.audio;

/** Where the engine sends finished audio: the sound card in the game, a simulated one in the tests. 16 bit stereo, little endian. */
public interface AudioSink {
    /** Opens the device. {@code bufferFrames} is the size of the device queue the engine would like. */
    void open(int rate, int bufferFrames) throws Exception;

    /** Writes bytes (a whole number of frames); blocks while the device queue is full. Returns the bytes written. */
    int write(byte[] data, int off, int len);

    /** Frames written but not played yet. */
    int queuedFrames();

    void start();

    void stop();

    /** Throws away everything queued. */
    void flush();

    void close();
}
