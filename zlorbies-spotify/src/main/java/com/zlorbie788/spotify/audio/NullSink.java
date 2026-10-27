package com.zlorbie788.spotify.audio;

/**
 * A pretend sound card for tests (and for computers with no sound device): it "plays" the audio at {@code speed}
 * times real time, keeps the queue length like a real device would, and counts what it was given.
 */
public final class NullSink implements AudioSink {
    private final double speed;
    private int rate = Engine.RATE;
    private int capacity;
    private double queued;
    private long lastNs;
    private boolean running;
    public volatile long framesWritten;
    public volatile byte[] lastBlock = new byte[0];
    /** When set, everything written is kept (tests compare it with the expected sound). */
    public java.io.ByteArrayOutputStream capture;

    public NullSink(double speed) {
        this.speed = speed;
    }

    @Override
    public synchronized void open(int rate, int bufferFrames) {
        this.rate = rate;
        this.capacity = bufferFrames;
        this.queued = 0;
        this.lastNs = System.nanoTime();
    }

    private void drain() {
        long now = System.nanoTime();
        if (running) {
            queued = Math.max(0, queued - (now - lastNs) / 1e9 * rate * speed);
        }
        lastNs = now;
    }

    @Override
    public int write(byte[] data, int off, int len) {
        int frames = len / 4;
        while (true) {
            synchronized (this) {
                drain();
                if (queued + frames <= capacity) {
                    queued += frames;
                    framesWritten += frames;
                    lastBlock = java.util.Arrays.copyOfRange(data, off, off + len);
                    if (capture != null) capture.write(data, off, len);
                    return len;
                }
            }
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                return 0;
            }
        }
    }

    @Override
    public synchronized int queuedFrames() {
        drain();
        return (int) queued;
    }

    @Override public synchronized void start() { drain(); running = true; lastNs = System.nanoTime(); }
    @Override public synchronized void stop() { drain(); running = false; }
    @Override public synchronized void flush() { queued = 0; }
    @Override public void close() { }
}
