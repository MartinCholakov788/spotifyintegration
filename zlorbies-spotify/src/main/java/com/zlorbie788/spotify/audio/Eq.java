package com.zlorbie788.spotify.audio;

/**
 * A ten-band equaliser (one octave apart, 31 Hz to 16 kHz) built from peaking filters, plus the "Night" compressor
 * that evens out loud and quiet parts. Gains are in dB, -12 to +12.
 */
public final class Eq {
    public static final int BANDS = 10;
    public static final float[] FREQS = {31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
    public static final String[] BAND_NAMES = {"31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"};
    public static final float MAX_DB = 12f;

    public static final String[] PRESET_NAMES = {"Flat", "Bass boost", "Vocal", "Night", "Treble", "Rock", "Electronic", "Custom"};
    public static final String[] PRESET_TIPS = {
            "No change to the sound.",
            "More punch in the low end.",
            "Clearer voices: less rumble, a lift in the middle.",
            "Quieter bass and treble, and loud parts are evened out. Good late at night.",
            "A brighter, airier sound.",
            "Strong bass and treble, a little less in the middle.",
            "Deep bass and shiny highs.",
            "Your own bands."};
    private static final float[][] PRESETS = {
            {0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
            {6, 5.5f, 4.5f, 2.5f, 0.5f, 0, 0, 0, 0, 0},
            {-3, -3, -2, 0, 2, 3.5f, 3.5f, 2, 0, -1},
            {-3, -3, -2, 0, 0, 0, -1, -2, -3, -4},
            {0, 0, 0, 0, 0, 1, 3, 4.5f, 5.5f, 5.5f},
            {4.5f, 3.5f, 2, 0, -1.5f, -1, 1, 3, 4, 4.5f},
            {5, 4.5f, 2.5f, 0, -1.5f, 0, 1, 3, 4.5f, 4}};
    /** Index of "Custom" in {@link #PRESET_NAMES}. */
    public static final int CUSTOM = PRESET_NAMES.length - 1;
    /** Index of the preset that also switches the compressor on. */
    public static final int NIGHT = 3;

    private Eq() {}

    public static float[] preset(int index) {
        return index >= 0 && index < PRESETS.length ? PRESETS[index].clone() : new float[BANDS];
    }

    /** What the engine needs to know: the gains and whether the compressor is on. */
    public record Settings(boolean enabled, float[] gains, boolean compress) {
        public static final Settings FLAT = new Settings(false, new float[BANDS], false);

        public boolean isFlat() {
            if (compress) return false;
            for (float g : gains) if (Math.abs(g) > 0.05f) return false;
            return true;
        }
    }

    /** Biquad filters for both channels. */
    static final class Chain {
        private final double[][] coef = new double[BANDS][5];
        private final double[][] z = new double[BANDS * 2][2];
        private boolean[] active = new boolean[BANDS];
        private double preamp = 1.0;
        private int rate = Engine.RATE;
        // compressor
        private boolean compress;
        private double env;
        private double compGain = 1.0;

        void configure(Settings s, int rate) {
            this.rate = rate;
            double maxUp = 0;
            for (int b = 0; b < BANDS; b++) {
                double db = s.enabled() ? Math.max(-MAX_DB, Math.min(MAX_DB, s.gains()[b])) : 0;
                active[b] = Math.abs(db) > 0.05;
                maxUp = Math.max(maxUp, db);
                double a = Math.pow(10, db / 40);
                double w0 = 2 * Math.PI * Math.min(FREQS[b], rate * 0.45) / rate;
                double q = 1.2;
                double alpha = Math.sin(w0) / (2 * q);
                double cos = Math.cos(w0);
                double a0 = 1 + alpha / a;
                coef[b][0] = (1 + alpha * a) / a0;
                coef[b][1] = (-2 * cos) / a0;
                coef[b][2] = (1 - alpha * a) / a0;
                coef[b][3] = (-2 * cos) / a0;
                coef[b][4] = (1 - alpha / a) / a0;
            }
            // lower everything by what the boosts could add, so the sound does not clip
            preamp = Math.pow(10, -(maxUp * 0.7) / 20);
            compress = s.enabled() && s.compress();
        }

        boolean needed() {
            if (compress) return true;
            for (boolean a : active) if (a) return true;
            return false;
        }

        /** Processes interleaved stereo in place. */
        void process(float[] buf, int frames) {
            for (int b = 0; b < BANDS; b++) {
                if (!active[b]) continue;
                double b0 = coef[b][0], b1 = coef[b][1], b2 = coef[b][2], a1 = coef[b][3], a2 = coef[b][4];
                for (int ch = 0; ch < 2; ch++) {
                    double[] zz = z[b * 2 + ch];
                    double z1 = zz[0], z2 = zz[1];
                    for (int i = 0; i < frames; i++) {
                        int k = i * 2 + ch;
                        double x = buf[k];
                        double y = b0 * x + z1;
                        z1 = b1 * x - a1 * y + z2;
                        z2 = b2 * x - a2 * y;
                        buf[k] = (float) y;
                    }
                    zz[0] = z1;
                    zz[1] = z2;
                }
            }
            if (preamp != 1.0) {
                float g = (float) preamp;
                for (int i = 0; i < frames * 2; i++) buf[i] *= g;
            }
            if (compress) {
                // gentle feed-forward compressor: threshold -20 dB, 3:1, attack 8 ms, release 250 ms, a little make-up gain
                double att = Math.exp(-1.0 / (0.008 * rate));
                double rel = Math.exp(-1.0 / (0.25 * rate));
                double thr = 0.1;
                for (int i = 0; i < frames; i++) {
                    double lv = Math.max(Math.abs(buf[2 * i]), Math.abs(buf[2 * i + 1]));
                    env = lv > env ? att * env + (1 - att) * lv : rel * env + (1 - rel) * lv;
                    double target = 1.0;
                    if (env > thr) target = Math.pow(env / thr, 1.0 / 3.0 - 1.0);
                    compGain += (target - compGain) * (target < compGain ? (1 - att) : (1 - rel));
                    float g = (float) (compGain * 1.6);
                    buf[2 * i] *= g;
                    buf[2 * i + 1] *= g;
                }
            }
        }

        void reset() {
            for (double[] zz : z) {
                zz[0] = 0;
                zz[1] = 0;
            }
            env = 0;
            compGain = 1.0;
        }
    }
}
