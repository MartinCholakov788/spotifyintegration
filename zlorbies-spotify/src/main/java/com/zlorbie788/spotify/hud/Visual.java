package com.zlorbie788.spotify.hud;

import com.zlorbie788.spotify.config.SpotifyConfig;
import com.zlorbie788.spotify.media.Beat;
import com.zlorbie788.spotify.media.Spectrum;
import com.zlorbie788.spotify.util.Ease;

/**
 * The moving parts that follow the music: the level bars (with falling peak caps), the beat pulse and the
 * speed of the animated lines.
 *
 * <p>There is one shared set of {@link #BANDS} bars that is moved once per frame; every equalizer on screen
 * just reads from it. The bars are driven by the real loudness and beat from {@link Beat} (a loudness number,
 * not a true frequency analysis, so the spread across the bars is a shaped model: bass on the left, kicks push
 * the low bars, snares and hi-hats ripple the high ones). Falling follows gravity and each bar keeps a
 * cap that hangs for a moment and then drops, like a real analyser. When no loudness is arriving (helper
 * without audio access, preview with nothing playing) the bars use a smooth stand-in motion instead.
 */
public final class Visual {
    public static final int BANDS = 48;

    private static final float[] height = new float[BANDS];
    private static final float[] velocity = new float[BANDS];
    private static final float[] cap = new float[BANDS];
    private static final float[] capHold = new float[BANDS];
    private static final float[] capVelocity = new float[BANDS];

    private static long lastNs;
    private static float pulseNow;
    private static float levelNow;
    private static boolean reacting;
    private static boolean realNow;
    private static boolean meterNow;
    private static int lastMeterBeats;
    private static boolean kickArmed = true;
    private static int lastSeed;
    private static float restAmount;
    private static float linePosRainbow;
    private static float linePosSweep;

    private Visual() {}

    /** True when the bars are following real audio right now. */
    public static boolean reacting() {
        return reacting;
    }

    /** True when the numbers come from a real measurement of the sound (your own songs, or the optional Spotify capture). */
    public static boolean measured() {
        return realNow;
    }

    /** What the bars and the beat follow right now, in plain words (for the settings and the status command). */
    public static String following() {
        if (realNow && meterNow) return "real audio + loudness meter";
        if (realNow) return "real audio";
        if (meterNow) return "loudness meter";
        return "calm stand-in motion (nothing hears the music)";
    }

    /** The tempo the beat clock has locked onto (beats per minute), or 0. */
    public static float bpm() {
        return BeatClock.bpm(System.nanoTime());
    }

    /** 0..1 kick of the latest beat (0 when reacting is off or nothing is playing). */
    public static float pulse() {
        return pulseNow;
    }

    /** 0..1 loudness (0 when reacting is off). */
    public static float level() {
        return levelNow;
    }

    /** Position of the animated line's colours or sweep, 0..1, speeding up with the music. */
    public static float linePhase(boolean rainbow) {
        return rainbow ? linePosRainbow : linePosSweep;
    }

    /**
     * Moves everything one frame forward. Safe to call several times per frame (it ignores calls that come
     * less than 2 ms after the last one).
     */
    public static void update(SpotifyConfig cfg, boolean playing, int seed) {
        long now = System.nanoTime();
        float dt = (now - lastNs) / 1_000_000_000f;
        if (lastNs != 0L && dt < 0.002f) return;
        lastNs = now;
        dt = Math.max(0.002f, Math.min(0.1f, dt));

        boolean motion = cfg.motion();
        Spectrum.update(now, playing && motion);
        Mood.update(now, playing);
        boolean on = motion && cfg.reactToMusic && playing;
        boolean real = on && Spectrum.real();
        boolean meter = on && Beat.hearing(now);
        reacting = real || meter;
        realNow = real;
        meterNow = meter;
        float strength = cfg.reactStrength;
        // Two independent ears on the same song: the real analysis (the mod's own player or the Spotify capture) and the
        // loudness meter of the Windows helper. When both hear it, the stronger one wins for every number, so a beat that
        // one of them misses is still caught by the other (the cover, the glow and the lines all pulse on either).
        float level = 0f;
        float pulse = 0f;
        float flux = 0f;
        if (real) {
            level = Spectrum.level();
            pulse = Math.max(Spectrum.kick(), Spectrum.snare() * 0.6f);
            flux = Spectrum.flux();
        }
        if (meter) {
            level = Math.max(level, Beat.level(now));
            pulse = Math.max(pulse, Beat.pulse(now));
            flux = Math.max(flux, Beat.flux(now));
        }
        if (cfg.beatLock && on) {
            // the clock learns from the hits of either ear and keeps the beat going between and through them
            int beats = Beat.beats();
            if (meter && beats != lastMeterBeats) BeatClock.hit(Beat.lastBeatNs());
            lastMeterBeats = beats;
            float kick = real ? Spectrum.kick() : 0f;
            if (kick < 0.3f) kickArmed = true;
            else if (kickArmed && kick > 0.45f) {
                kickArmed = false;
                BeatClock.hit(now);
            }
            pulse = Math.max(pulse, BeatClock.pulse(now));
        } else if (!on) {
            lastMeterBeats = Beat.beats();
        }
        if (seed != lastSeed) {
            lastSeed = seed;
            BeatClock.reset();
        }
        levelNow = Math.min(1f, level * strength);
        pulseNow = Math.min(1f, pulse * strength);
        flux = Math.min(1f, flux * strength);

        // the animated line: slow and calm normally, quicker with the music, a jolt on every beat
        float boost = 1f + 1.4f * levelNow + 3f * pulseNow;
        float speed = motion ? cfg.animSpeed * boost * Mood.speed(cfg) : 0f;
        linePosRainbow = (linePosRainbow + dt * speed / 5f) % 1f;
        linePosSweep = (linePosSweep + dt * speed / 3.5f) % 1f;

        restAmount = Ease.approach(restAmount, playing ? 1f : 0f, 4f, dt);
        double time = (now / 1_000_000_000L % 100_000L) + (now % 1_000_000_000L) / 1e9;

        for (int i = 0; i < BANDS; i++) {
            float t = i / (float) (BANDS - 1);
            float target;
            if (!motion) {
                target = 0f;
            } else if (real) {
                // the measured spectrum, with a little of the kick on the lowest bars
                float v = Spectrum.bandAt(t);
                target = Ease.clamp01(v * strength + pulseNow * 0.25f * (float) Math.exp(-t * 5.0));
            } else if (reacting) {
                target = modelTarget(i, t, time, seed, levelNow, pulseNow, flux);
            } else if (playing) {
                target = 0.12f + 0.7f * Ease.equalizer(i, (long) (time * 1000), seed) * restAmount;
            } else {
                target = 0f;
            }
            step(i, target, dt);
        }
    }

    /** Where bar i wants to be, from loudness, kick and busyness. */
    static float modelTarget(int i, float t, double time, int seed, float level, float pulse, float flux) {
        double phase = i * 1.9 + seed * 0.013;
        double f1 = 5.0 + 9.0 * Math.pow(t, 0.8) + 0.7 * Math.sin(i * 2.3);
        double f2 = 8.0 + 14.0 * t + 0.9 * Math.cos(i * 1.7);
        float n1 = (float) (0.5 + 0.5 * Math.sin(time * f1 + phase));
        float n2 = (float) (0.5 + 0.5 * Math.sin(time * f2 + phase * 1.7));
        float tilt = 1f - 0.45f * t;
        float bassW = (float) Math.exp(-t * 4.0);
        float trebW = Ease.clamp01((t - 0.25f) / 0.75f);

        // A slowly drifting spectral shape (a few humps, different for every song) gives the ragged,
        // peaks-and-valleys look of a real analyser instead of a flat band.
        double hump = Math.sin(time * 0.31 * (1.0 + 0.25 * Math.sin(i * 0.8)) + i * 0.62 + seed * 0.011)
                * Math.sin(time * 0.17 + i * 0.37 + seed * 0.007);
        float shape = (float) Math.pow(0.5 + 0.5 * hump, 1.4);
        shape = 0.18f + 0.82f * shape;

        float body = level * tilt * shape * (0.5f + 0.5f * n1) * (0.78f + 0.22f * n2);
        float kick = pulse * bassW * (0.6f + 0.4f * n1);
        float snap = (pulse * 0.45f + flux) * trebW * shape * (0.35f + 0.65f * n2);
        return Ease.clamp01(body * 1.25f + kick + snap * 0.9f);
    }

    private static void step(int i, float target, float dt) {
        float h = height[i];
        if (target >= h) {
            h += (target - h) * (1f - (float) Math.exp(-dt * 30f));
            velocity[i] = 0f;
        } else {
            velocity[i] += 5.0f * dt;
            h = Math.max(target, h - velocity[i] * dt);
        }
        height[i] = h;

        if (h >= cap[i]) {
            cap[i] = h;
            capHold[i] = 0.32f;
            capVelocity[i] = 0f;
        } else if (capHold[i] > 0f) {
            capHold[i] -= dt;
        } else {
            capVelocity[i] += 1.8f * dt;
            cap[i] = Math.max(h, cap[i] - capVelocity[i] * dt);
        }
    }

    /** Height 0..1 of bar {@code index} when {@code count} bars are drawn. */
    public static float bar(int index, int count) {
        if (count <= 0) return 0f;
        int from = (int) ((long) index * BANDS / count);
        int to = Math.max(from + 1, (int) ((long) (index + 1) * BANDS / count));
        float sum = 0f;
        int n = 0;
        for (int b = from; b < Math.min(BANDS, to); b++) {
            sum += height[b];
            n++;
        }
        return n == 0 ? height[Math.min(BANDS - 1, from)] : sum / n;
    }

    /** Smooth height 0..1 at position {@code t} (0..1) along the whole spectrum, interpolating between the bars. */
    public static float curve(float t) {
        float p = Math.max(0f, Math.min(1f, t)) * (BANDS - 1);
        int i = (int) p;
        float f = p - i;
        float y0 = height[Math.max(0, i - 1)];
        float y1 = height[i];
        float y2 = height[Math.min(BANDS - 1, i + 1)];
        float y3 = height[Math.min(BANDS - 1, i + 2)];
        // Catmull-Rom
        float v = 0.5f * ((2f * y1) + (-y0 + y2) * f + (2f * y0 - 5f * y1 + 4f * y2 - y3) * f * f
                + (-y0 + 3f * y1 - 3f * y2 + y3) * f * f * f);
        return Math.max(0f, Math.min(1f, v));
    }

    /** Height 0..1 of the falling peak cap above bar {@code index}. */
    public static float cap(int index, int count) {
        if (count <= 0) return 0f;
        int from = (int) ((long) index * BANDS / count);
        int to = Math.max(from + 1, (int) ((long) (index + 1) * BANDS / count));
        float max = 0f;
        for (int b = from; b < Math.min(BANDS, to); b++) max = Math.max(max, cap[b]);
        return max;
    }

    /** Clears the bars (tests). */
    public static void reset() {
        for (int i = 0; i < BANDS; i++) {
            height[i] = 0f;
            velocity[i] = 0f;
            cap[i] = 0f;
            capHold[i] = 0f;
            capVelocity[i] = 0f;
        }
        lastNs = 0L;
        pulseNow = 0f;
        levelNow = 0f;
        reacting = false;
        realNow = false;
        meterNow = false;
        BeatClock.reset();
        restAmount = 0f;
        Spectrum.reset();
        Mood.reset();
    }
}
