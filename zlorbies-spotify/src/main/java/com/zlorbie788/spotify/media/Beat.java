package com.zlorbie788.spotify.media;

/**
 * Turns the live loudness numbers from the Windows helper (about 40 a second, the same meter the Windows volume
 * mixer draws for the player) into three things the visuals can use:
 * <ul>
 *   <li>{@link #level()}: how loud the music is right now, 0..1, automatically scaled so it looks the same at any volume;</li>
 *   <li>{@link #pulse()}: a short kick that fires on every beat and fades out fast;</li>
 *   <li>{@link #flux()}: how much the sound is changing (busy hi-hats and snares give a high value).</li>
 * </ul>
 * It never sees the audio itself, only a loudness number, so it follows the rhythm and the dynamics of the
 * song rather than the exact notes.
 */
public final class Beat {
    private static final Object LOCK = new Object();

    // analysis state (guarded by LOCK)
    private static float ref = 0.15f;
    private static float avg;
    private static float dev;
    private static float prev;
    private static long lastSampleNs;
    private static long lastBeatNs;

    // what the drawing code reads (written under LOCK, read without it)
    private static volatile float level;
    private static volatile float flux;
    private static volatile float beatStrength;
    private static volatile long beatAtNs;
    private static volatile long lastFeedNs;
    private static volatile long lastSoundNs;
    private static volatile float lastPeak = -1f;
    private static volatile boolean present;
    private static volatile int beats;

    private Beat() {}

    /** Forgets everything (the helper restarted, or the player changed). */
    public static void reset() {
        synchronized (LOCK) {
            ref = 0.15f;
            avg = 0f;
            dev = 0f;
            prev = 0f;
            lastSampleNs = 0L;
            lastBeatNs = 0L;
            level = 0f;
            flux = 0f;
            beatStrength = 0f;
            beatAtNs = 0L;
            lastFeedNs = 0L;
            lastSoundNs = 0L;
            present = false;
            beats = 0;
        }
    }

    /**
     * One loudness reading from the helper: {@code peak} is 0..1, or negative when the player has no audio
     * session at the moment.
     */
    public static void feed(float peak, long nowNs) {
        lastPeak = peak;
        synchronized (LOCK) {
            if (Float.isNaN(peak) || peak < 0f) {
                present = false;
                level = 0f;
                flux = 0f;
                return;
            }
            present = true;
            lastFeedNs = nowNs;
            if (peak >= 0.004f) lastSoundNs = nowNs;
            float dt = lastSampleNs == 0L ? 0.025f : clamp((nowNs - lastSampleNs) / 1e9f, 0.004f, 0.25f);
            lastSampleNs = nowNs;

            float p = Math.min(1f, peak);
            // Automatic gain: the loudest recent reading is "full", and it slowly forgets, so a quiet player
            // volume and a loud one produce the same movement.
            ref = Math.max(p, ref - ref * 0.05f * dt);
            if (ref < 0.06f) ref = 0.06f;

            float n = p < 0.004f ? 0f : Math.min(1f, p / ref);
            n = clamp01((n - 0.12f) / 0.88f);
            n = (float) Math.pow(n, 1.15);

            float l = level;
            level = n >= l ? n : l + (n - l) * (1f - (float) Math.exp(-dt * 9f));

            float k = 1f - (float) Math.exp(-dt / 0.9f);
            avg += (n - avg) * k;
            dev += (Math.abs(n - avg) - dev) * k;
            float rise = n - prev;
            prev = n;
            flux = Math.max(flux * (float) Math.exp(-dt * 7f), Math.min(1f, Math.abs(rise) * 3.5f));

            float threshold = avg + Math.max(0.08f, 1.25f * dev);
            double since = (nowNs - lastBeatNs) / 1e9;
            if (n > threshold && rise > 0.03f && n > 0.3f && since > 0.19) {
                lastBeatNs = nowNs;
                beatAtNs = nowNs;
                beatStrength = clamp01(0.45f + (n - threshold) * 2.2f + rise);
                beats++;
            }
        }
    }

    /** The newest raw reading of the helper (0..1), or negative when the player has no audio session. */
    public static float peak() {
        return lastPeak;
    }

    /** True while loudness readings are arriving (the bars can follow the real music). */
    public static boolean live() {
        return live(System.nanoTime());
    }

    public static boolean live(long nowNs) {
        return present && nowNs - lastFeedNs < 700_000_000L;
    }

    /**
     * True while the meter is arriving AND has heard sound lately. A meter that keeps reading zero while a song plays is
     * listening to the wrong thing (another sound device, another program), so the visuals do not trust it.
     */
    public static boolean hearing(long nowNs) {
        return live(nowNs) && nowNs - lastSoundNs < 2_500_000_000L;
    }

    /** 0..1 loudness now. */
    public static float level() {
        return level(System.nanoTime());
    }

    public static float level(long nowNs) {
        return live(nowNs) ? level : 0f;
    }

    /** 0..1 how busy the sound is right now. */
    public static float flux(long nowNs) {
        return live(nowNs) ? flux : 0f;
    }

    /** 0..1 kick of the latest beat, fading out in a fraction of a second. */
    public static float pulse() {
        return pulse(System.nanoTime());
    }

    public static float pulse(long nowNs) {
        if (!live(nowNs)) return 0f;
        double age = (nowNs - beatAtNs) / 1e9;
        if (age < 0 || age > 1.0) return 0f;
        return (float) (beatStrength * Math.exp(-age / 0.17));
    }

    /** When the latest beat was found (System.nanoTime), or 0. */
    public static long lastBeatNs() {
        return beatAtNs;
    }

    /** How many beats have been found since the helper started (tests and diagnostics). */
    public static int beats() {
        return beats;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(1f, v);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
