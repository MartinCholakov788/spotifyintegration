package com.zlorbie788.spotify.media;

import com.zlorbie788.spotify.audio.Analysis;
import com.zlorbie788.spotify.audio.Analyzer;
import com.zlorbie788.spotify.audio.Engine;
import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.local.LocalPlayer;
import com.zlorbie788.spotify.local.Sources;

/**
 * What the music looks like right now, from the best source there is:
 * <ul>
 *   <li><b>Local</b>: the mod plays the file itself, so it measures the real audio you hear (spectrum, kick, snare, hi-hat,
 *   tempo), lined up with the sound;</li>
 *   <li><b>Loopback</b>: for Spotify, the optional audio capture of Windows (see {@code Loopback});</li>
 *   <li><b>None</b>: only the loudness meter of the Windows helper is available; the old {@link Beat} model is used.</li>
 * </ul>
 * One set of numbers for everybody; read them from the game thread after {@link #update}.
 */
public final class Spectrum {
    public enum Source { NONE, LOCAL, LOOPBACK }

    private static volatile Source source = Source.NONE;
    private static final float[] bands = new float[Analysis.BANDS];
    private static float level;
    private static float bass;
    private static float mid;
    private static float treble;
    private static float flux;
    private static float kick;
    private static float snare;
    private static float hat;
    private static float energy;
    private static float bpm;
    private static long lastCenter = Long.MIN_VALUE;
    private static long lastNs;
    private static long lastFresh;

    private Spectrum() {}

    /** True when real measurements of the audio are being used (not the loudness-only model). */
    public static boolean real() {
        return source != Source.NONE;
    }

    public static Source source() {
        return source;
    }

    /** One of the 32 bands, 0..1 (40 Hz at 0, 16 kHz at 31). */
    public static float band(int i) {
        return bands[Math.max(0, Math.min(Analysis.BANDS - 1, i))];
    }

    /** The bands stretched to {@code count} values (smoothly), 0..1. */
    public static float bandAt(float t) {
        float p = Math.max(0f, Math.min(1f, t)) * (Analysis.BANDS - 1);
        int i = (int) p;
        float f = p - i;
        float a = bands[i];
        float b = bands[Math.min(Analysis.BANDS - 1, i + 1)];
        return a + (b - a) * f;
    }

    public static float level() {
        return level;
    }

    public static float bass() {
        return bass;
    }

    public static float mid() {
        return mid;
    }

    public static float treble() {
        return treble;
    }

    public static float flux() {
        return flux;
    }

    /** 0..1 kick drum, jumping up on every hit and falling back in about a fifth of a second. */
    public static float kick() {
        return kick;
    }

    public static float snare() {
        return snare;
    }

    public static float hat() {
        return hat;
    }

    /** 0..1 how energetic the music has been lately (drives the mood theme). */
    public static float energy() {
        return energy;
    }

    /** Beats per minute, or 0 while not known. */
    public static float bpm() {
        return bpm;
    }

    /** Forget everything (the song changed). */
    public static void reset() {
        java.util.Arrays.fill(bands, 0f);
        level = bass = mid = treble = flux = kick = snare = hat = 0f;
        lastCenter = Long.MIN_VALUE;
        lastNs = 0L;
    }

    /** Once per frame (more calls in the same frame are harmless). */
    public static void update(long nowNs, boolean playing) {
        float dt = lastNs == 0L ? 0.016f : Math.max(0.001f, Math.min(0.1f, (nowNs - lastNs) / 1e9f));
        if (lastNs != 0L && nowNs - lastNs < 2_000_000L) return;
        lastNs = nowNs;

        Analyzer an = null;
        long frame = 0;
        Source src = Source.NONE;
        if (playing) {
            if (Sources.local() && LocalPlayer.playing()) {
                Engine e = LocalPlayer.audio();
                an = e.analyzer();
                frame = e.visualFrame();
                src = Source.LOCAL;
            } else if (!Sources.local() && Loopback.hearing()) {
                an = Loopback.analyzer();
                frame = Loopback.visualFrame(ConfigIo.get().visualSyncMs);
                src = Source.LOOPBACK;
            }
        }
        if (src != source) {
            source = src;
            reset();
        }
        // the hits decay on their own
        kick *= (float) Math.exp(-dt / 0.17);
        snare *= (float) Math.exp(-dt / 0.12);
        hat *= (float) Math.exp(-dt / 0.07);
        if (an == null) {
            float fall = (float) Math.exp(-dt * 6f);
            for (int i = 0; i < bands.length; i++) bands[i] *= fall;
            level *= fall;
            bass *= fall;
            mid *= fall;
            treble *= fall;
            flux *= fall;
            return;
        }
        // go through every measurement since the last frame so a short hit is never skipped
        Analysis last = an.at(frame);
        long from = lastCenter == Long.MIN_VALUE || frame - lastCenter > Analyzer.HOP * 24L
                ? last.centerFrame - Analyzer.HOP : lastCenter;
        int guard = 0;
        for (long f = from + Analyzer.HOP; f <= frame && guard < 24; f += Analyzer.HOP, guard++) {
            Analysis a = an.at(f);
            kick = Math.max(kick, a.kick);
            snare = Math.max(snare, a.snare);
            hat = Math.max(hat, a.hat);
        }
        lastCenter = frame;
        // smooth values: quick up, a little slower down
        float up = 1f - (float) Math.exp(-dt * 45f);
        float down = 1f - (float) Math.exp(-dt * 12f);
        for (int i = 0; i < bands.length; i++) {
            float v = last.bands[i];
            bands[i] += (v - bands[i]) * (v > bands[i] ? up : down);
        }
        level = follow(level, last.level, up, down);
        bass = follow(bass, last.bass, up, down);
        mid = follow(mid, last.mid, up, down);
        treble = follow(treble, last.treble, up, down);
        flux = follow(flux, last.flux, up, down);
        energy = an.energy();
        bpm = an.bpmConfidence() > 0.3f ? an.bpm() : 0f;
    }

    private static float follow(float cur, float target, float up, float down) {
        return cur + (target - cur) * (target > cur ? up : down);
    }
}
