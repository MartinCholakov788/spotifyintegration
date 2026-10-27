package com.zlorbie788.spotify.audio;

/**
 * One moment of the music, as measured by the {@link Analyzer}: what the spectrum looked like, how loud it was and whether a
 * kick, snare or hi-hat just hit. All values are 0..1 and already scaled automatically, so quiet and loud songs look alike.
 */
public final class Analysis {
    public static final int BANDS = 32;
    public static final int WAVE = 64;
    public static final Analysis SILENCE = new Analysis(0, new float[BANDS], new float[WAVE], 0, 0, 0, 0, 0, 0, 0, 0);

    /** Output frame (at the engine's rate) in the middle of the window this was measured on. */
    public final long centerFrame;
    /** 32 log-spaced bands from 40 Hz to 16 kHz. */
    public final float[] bands;
    /** The last 64 samples of the hop, -1..1 (mono). */
    public final float[] wave;
    public final float level;
    public final float bass;
    public final float mid;
    public final float treble;
    /** Strength of an onset found in this hop, or 0. */
    public final float kick;
    public final float snare;
    public final float hat;
    /** How much the spectrum changed since the hop before, over everything. */
    public final float flux;

    public Analysis(long centerFrame, float[] bands, float[] wave, float level, float bass, float mid, float treble,
                    float kick, float snare, float hat, float flux) {
        this.centerFrame = centerFrame;
        this.bands = bands;
        this.wave = wave;
        this.level = level;
        this.bass = bass;
        this.mid = mid;
        this.treble = treble;
        this.kick = kick;
        this.snare = snare;
        this.hat = hat;
        this.flux = flux;
    }
}
