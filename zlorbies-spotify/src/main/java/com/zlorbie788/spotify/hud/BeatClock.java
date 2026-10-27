package com.zlorbie788.spotify.hud;

/**
 * Keeps the rhythm. Whatever finds the hits (the loudness meter or the real audio analysis) tells this clock when one
 * happened; the clock works out the tempo and where the beats fall, and goes on pulsing on those beats even when a single hit
 * is missed or fires a little late. It only speaks while it is sure (the hits really repeat) and stops when they stop, so it
 * never invents a rhythm for music without one.
 *
 * <p>Method: every hit is a point on a circle for a candidate period; if the hits repeat, the points bunch up. The period
 * with the tightest bunch wins, and the direction of the bunch is the phase.
 */
final class BeatClock {
    private static final int N = 48;
    private static final double MIN_P = 0.30;
    private static final double MAX_P = 1.00;
    private static final double STEP = 0.004;
    private static final double WINDOW = 9.0;
    private static final double LOCK = 0.42;

    private static final double[] hits = new double[N];
    private static int count;
    private static int head;
    private static double lastHit = -1e9;
    private static double period;
    private static double phase;
    private static double conf;

    private BeatClock() {}

    static synchronized void reset() {
        count = 0;
        head = 0;
        lastHit = -1e9;
        period = 0;
        phase = 0;
        conf = 0;
    }

    /** A hit was found at {@code ns} (System.nanoTime). */
    static synchronized void hit(long ns) {
        double s = ns / 1e9;
        if (s - lastHit < 0.12) return;
        lastHit = s;
        hits[head] = s;
        head = (head + 1) % N;
        if (count < N) count++;
        analyse(s);
    }

    private static void analyse(double now) {
        double[] t = new double[count];
        double[] w = new double[count];
        int n = 0;
        for (int i = 0; i < count; i++) {
            double h = hits[(head - 1 - i + N * 2) % N];
            double age = now - h;
            if (age > WINDOW) break;
            t[n] = h;
            w[n] = Math.exp(-age / 5.0);
            n++;
        }
        if (n < 6) {
            conf = 0;
            period = 0;
            return;
        }
        double sw = 0, sw2 = 0;
        for (int i = 0; i < n; i++) {
            sw += w[i];
            sw2 += w[i] * w[i];
        }
        double norm = sw * sw - sw2;
        int m = (int) Math.round((MAX_P - MIN_P) / STEP) + 1;
        double[] score = new double[m];
        double[] arg = new double[m];
        double max = -1;
        for (int c = 0; c < m; c++) {
            double p = MIN_P + c * STEP;
            double re = 0, im = 0;
            for (int i = 0; i < n; i++) {
                double a = 2 * Math.PI * (t[i] % p) / p;
                re += w[i] * Math.cos(a);
                im += w[i] * Math.sin(a);
            }
            score[c] = (re * re + im * im - sw2) / norm;
            arg[c] = Math.atan2(im, re);
            if (score[c] > max) max = score[c];
        }
        // among the real peaks take the best one, leaning gently to the tempos songs usually have (a half or double period
        // scores the same on a steady beat, so without a lean the clock would flip between 100 and 200 BPM)
        int pick = -1;
        double best = -1;
        for (int c = 0; c < m; c++) {
            boolean peak = (c == 0 || score[c] >= score[c - 1]) && (c == m - 1 || score[c] >= score[c + 1]);
            if (!peak) continue;
            double p = MIN_P + c * STEP;
            double oct = Math.log(p / 0.5) / Math.log(2) / 0.8;
            double adj = score[c] * Math.exp(-0.5 * oct * oct);
            if (adj > best) {
                best = adj;
                pick = c;
            }
        }
        if (pick < 0 || score[pick] < 0.05) {
            conf = 0;
            period = 0;
            return;
        }
        period = MIN_P + pick * STEP;
        double a = arg[pick];
        if (a < 0) a += 2 * Math.PI;
        phase = a / (2 * Math.PI) * period;
        double c1 = score[pick];
        // hi-hats and other off-beat hits sit half a period away and pull the score down; when they bunch up on the half
        // period, that is the same steady rhythm, so it counts as support as long as the beat itself still points one way
        if (c1 >= 0.12) {
            double re = 0, im = 0;
            double half = period / 2;
            for (int i = 0; i < n; i++) {
                double ang = 2 * Math.PI * (t[i] % half) / half;
                re += w[i] * Math.cos(ang);
                im += w[i] * Math.sin(ang);
            }
            double c2 = (re * re + im * im - sw2) / norm;
            c1 = Math.max(c1, 0.85 * c2);
        }
        conf = Math.max(0, Math.min(1, c1));
    }

    /** 0..1 kick on the clock's own beats (0 while it is not sure, or when no hit came for a while). */
    static synchronized float pulse(long ns) {
        double s = ns / 1e9;
        if (period <= 0 || conf < LOCK || s - lastHit > 2.5) return 0f;
        // the phase was measured on absolute times modulo the period, so beats fall at phase + k * period
        double k = Math.floor((s - phase) / period);
        double age = s - (phase + k * period);
        double strength = 0.40 + 0.40 * Math.min(1.0, (conf - LOCK) / 0.4);
        return (float) (strength * Math.exp(-age / 0.17));
    }

    static synchronized boolean locked(long ns) {
        return period > 0 && conf >= LOCK && ns / 1e9 - lastHit <= 2.5;
    }

    /** Beats per minute while locked, else 0. */
    static synchronized float bpm(long ns) {
        return locked(ns) ? (float) (60.0 / period) : 0f;
    }

    static synchronized float confidence() {
        return (float) conf;
    }
}
