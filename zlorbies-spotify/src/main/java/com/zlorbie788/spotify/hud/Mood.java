package com.zlorbie788.spotify.hud;

import com.zlorbie788.spotify.config.SpotifyConfig;
import com.zlorbie788.spotify.media.Beat;
import com.zlorbie788.spotify.media.Spectrum;
import com.zlorbie788.spotify.util.Colors;
import com.zlorbie788.spotify.util.Ease;

/**
 * "Mood themes": the look follows how energetic the song is. A calm song gets softer, cooler colours and slower
 * animation; a hard-hitting one gets richer, warmer colours and faster animation. The change is slow (a few seconds), so
 * it never flickers, and the "Mood amount" setting decides how far it goes.
 */
public final class Mood {
    private static float energy = 0.5f;
    private static float meter;
    private static long lastNs;

    private Mood() {}

    /** 0 = very calm, 1 = very energetic (smoothed over a few seconds). */
    public static float energy() {
        return energy;
    }

    /** Moves the energy towards what the music is doing. Call once per frame; extra calls are ignored. */
    public static void update(long nowNs, boolean playing) {
        float dt = lastNs == 0L ? 0.016f : Math.max(0.001f, Math.min(0.2f, (nowNs - lastNs) / 1e9f));
        if (lastNs != 0L && nowNs - lastNs < 5_000_000L) return;
        lastNs = nowNs;
        float target;
        if (!playing) {
            target = 0.5f;
        } else if (Spectrum.real()) {
            target = Spectrum.energy();
        } else if (Beat.hearing(nowNs)) {
            // only a loudness meter is available: loud, busy music counts as energetic
            meter += (Math.min(1f, Beat.level(nowNs) * 0.8f + Beat.flux(nowNs) * 0.6f) - meter) * (1f - (float) Math.exp(-dt / 3f));
            target = meter;
        } else {
            target = 0.5f;
        }
        energy += (Ease.clamp01(target) - energy) * (1f - (float) Math.exp(-dt / 2.5f));
    }

    public static void reset() {
        energy = 0.5f;
        meter = 0f;
        lastNs = 0L;
    }

    /** -1 (calm) .. +1 (energetic), scaled by the setting; 0 when mood themes are off. */
    public static float lean(SpotifyConfig cfg) {
        if (cfg == null || !cfg.mood || !cfg.motion()) return 0f;
        return Ease.clamp01(cfg.moodAmount) * Math.max(-1f, Math.min(1f, (energy - 0.5f) * 2f));
    }

    /** The accent colour after the mood: calmer = cooler and softer, livelier = warmer and brighter. */
    public static int tint(int argb, SpotifyConfig cfg) {
        float lean = lean(cfg);
        if (Math.abs(lean) < 0.01f) return argb;
        float[] hsv = Colors.toHsv(argb);
        float hue = hsv[0] - 0.035f * lean;          // lively -> towards red / orange, calm -> towards blue
        float sat = Ease.clamp01(hsv[1] * (1f + 0.30f * lean));
        float val = Ease.clamp01(hsv[2] * (1f + 0.10f * lean));
        hue = hue - (float) Math.floor(hue);
        return Colors.withAlpha(Colors.hsv(hue, sat, val), (argb >>> 24) & 0xFF);
    }

    /** Multiplier for the speed of the animated lines (0.7 .. 1.4 at full amount). */
    public static float speed(SpotifyConfig cfg) {
        return 1f + 0.4f * lean(cfg);
    }
}
