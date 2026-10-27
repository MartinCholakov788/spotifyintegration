package com.zlorbie788.spotify.util;

/** One clock for every animation, so a test can jump to any moment of an animation. */
public final class Clock {
    private static long skewNs;

    private Clock() {}

    public static long nanos() {
        return System.nanoTime() + skewNs;
    }

    public static long millis() {
        return nanos() / 1_000_000L;
    }

    /** Test hook: pretend this many extra milliseconds have passed. */
    public static void skewMs(long ms) {
        skewNs += ms * 1_000_000L;
    }
}
