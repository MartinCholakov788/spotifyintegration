package com.zlorbie788.spotify.audio;

/**
 * Listens to the audio the engine produces and measures it: a 32-band spectrum, loudness, bass / mid / treble, separate kick /
 * snare / hi-hat onsets, a tempo estimate and a slow "energy" number. Results are kept with the position of the audio they describe, so
 * the visuals can show what you are hearing right now rather than what was decoded a moment ago.
 *
 * <p>Works on a hop of 512 frames with a 2048-point Hann-windowed FFT (about 86 measurements a second).
 */
public final class Analyzer {
    public static final int HOP = 512;
    public static final int WINDOW = 2048;
    private static final int RING = 512;

    private final int rate;
    private final Fft fft = new Fft(WINDOW);
    private final float[] hann = new float[WINDOW];
    private final float[] mono = new float[WINDOW];
    private final float[] circ = new float[WINDOW];
    private int wpos;
    private final float[] re = new float[WINDOW];
    private final float[] im = new float[WINDOW];
    private final float[] mag = new float[WINDOW / 2];
    private final float[] prevMag = new float[WINDOW / 2];
    private int filled;
    private int pending;
    private long frames;

    // band layout
    private final double[] bandLo = new double[Analysis.BANDS];
    private final double[] bandHi = new double[Analysis.BANDS];
    private final float[] tilt = new float[Analysis.BANDS];
    private final int lowEnd;
    private final int midEnd;
    private final int highEnd;

    // automatic gain
    private float refDb = -45f;
    private float levelRef = -40f;
    private float slowLoud;

    // onset detection per group (low, mid, high)
    private final float[] fluxMean = new float[3];
    private final float[] fluxVar = new float[3];
    private final float[] fluxPrev = new float[3];
    private final long[] lastOnset = new long[3];
    private final float[] fluxScale = {1f, 1f, 1f};
    private static final double[] REFRACTORY = {0.17, 0.10, 0.07};
    private static final float[] K = {1.35f, 1.6f, 1.9f};

    // tempo
    private final float[] onsetEnv = new float[RING];
    private int envHead;
    private int sinceTempo;
    private float bpm;
    private float bpmConfidence;
    private int bpmVotes;
    private float lastBpmCandidate;

    private final Analysis[] ring = new Analysis[RING];
    private volatile int head;
    private volatile float energy;
    private volatile float tempo;
    private volatile float tempoConfidence;

    public Analyzer(int rate) {
        this.rate = rate;
        for (int i = 0; i < WINDOW; i++) hann[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (WINDOW - 1)));
        double fMin = 40, fMax = 16000;
        double ratio = Math.pow(fMax / fMin, 1.0 / Analysis.BANDS);
        for (int b = 0; b < Analysis.BANDS; b++) {
            bandLo[b] = fMin * Math.pow(ratio, b);
            bandHi[b] = bandLo[b] * ratio;
            double fc = Math.sqrt(bandLo[b] * bandHi[b]);
            tilt[b] = (float) (2.0 * Math.log(fc / 400.0) / Math.log(2));
        }
        double bin = rate / (double) WINDOW;
        lowEnd = (int) Math.round(160 / bin);
        midEnd = (int) Math.round(2500 / bin);
        highEnd = (int) Math.round(12000 / bin);
        java.util.Arrays.fill(ring, Analysis.SILENCE);
    }

    /** Forgets what it knew (a new song, a seek). */
    public void reset() {
        java.util.Arrays.fill(prevMag, 0f);
        java.util.Arrays.fill(mono, 0f);
        java.util.Arrays.fill(circ, 0f);
        wpos = 0;
        filled = 0;
        pending = 0;
        java.util.Arrays.fill(fluxMean, 0f);
        java.util.Arrays.fill(fluxVar, 0f);
        java.util.Arrays.fill(fluxPrev, 0f);
        java.util.Arrays.fill(onsetEnv, 0f);
        sinceTempo = 0;
        slowLoud = 0f;
    }

    /**
     * Feeds the next block of interleaved stereo audio.
     *
     * @param endFrame the engine's produced-frame counter after this block
     */
    public void feed(float[] stereo, int frameCount, long endFrame) {
        long startFrame = endFrame - frameCount;
        for (int i = 0; i < frameCount; i++) {
            circ[wpos] = 0.5f * (stereo[2 * i] + stereo[2 * i + 1]);
            if (++wpos == WINDOW) wpos = 0;
            if (filled < WINDOW) filled++;
            if (++pending >= HOP) {
                pending = 0;
                if (filled >= WINDOW) {
                    // the oldest sample sits at wpos: lay the window out in order
                    int tail = WINDOW - wpos;
                    System.arraycopy(circ, wpos, mono, 0, tail);
                    System.arraycopy(circ, 0, mono, tail, wpos);
                    hop(startFrame + i + 1);
                }
            }
        }
        frames = endFrame;
    }

    /** The measurement for the audio at (about) {@code frame}, or the newest one that is not later than it. */
    public Analysis at(long frame) {
        int h = head;
        Analysis best = Analysis.SILENCE;
        for (int k = 0; k < RING; k++) {
            Analysis a = ring[((h - k) % RING + RING) % RING];
            if (a == null) break;
            if (a.centerFrame <= frame) return a;
            best = a;
        }
        return best;
    }

    public Analysis latest() {
        return ring[((head % RING) + RING) % RING];
    }

    /** Estimated tempo in beats per minute (0 while unknown). */
    public float bpm() {
        return tempo;
    }

    public float bpmConfidence() {
        return tempoConfidence;
    }

    /** 0..1 how energetic the music has been over the last few seconds (loud, busy, fast). */
    public float energy() {
        return energy;
    }

    private void hop(long endFrame) {
        // window the last 2048 samples
        float sumSq = 0;
        for (int i = 0; i < WINDOW; i++) {
            re[i] = mono[i] * hann[i];
            im[i] = 0f;
        }
        for (int i = WINDOW - HOP; i < WINDOW; i++) sumSq += mono[i] * mono[i];
        fft.transform(re, im);
        float norm = 4f / WINDOW;
        for (int k = 0; k < WINDOW / 2; k++) mag[k] = (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]) * norm;

        // loudness of the newest hop
        float rms = (float) Math.sqrt(sumSq / HOP);
        float rmsDb = rms < 1e-6f ? -120f : 20f * (float) Math.log10(rms);
        levelRef = Math.max(rmsDb, levelRef - 0.012f * 1f);
        if (levelRef < -45f) levelRef = -45f;
        float level = clamp01(1f - (levelRef - rmsDb) / 24f);
        if (rms < 0.0005f) level = 0f;
        slowLoud += (level - slowLoud) * 0.004f;

        // 32 bands: sum of power, in dB, with a gentle tilt so the highs are visible
        double bin = rate / (double) WINDOW;
        float[] bands = new float[Analysis.BANDS];
        float[] db = new float[Analysis.BANDS];
        float maxDb = -200f;
        for (int b = 0; b < Analysis.BANDS; b++) {
            double lo = bandLo[b] / bin;
            double hi = bandHi[b] / bin;
            double power = 0;
            if (hi - lo < 1.0) {
                // narrower than one FFT bin: use the bin's density over the band's width
                double c = (lo + hi) / 2;
                int i0 = (int) Math.floor(c);
                double fr = c - i0;
                double m = mag[Math.min(mag.length - 1, i0)] * (1 - fr) + mag[Math.min(mag.length - 1, i0 + 1)] * fr;
                power = m * m * (hi - lo);
            } else {
                int i0 = (int) Math.ceil(lo);
                int i1 = Math.min(mag.length - 1, (int) Math.floor(hi));
                for (int i = i0; i <= i1; i++) power += mag[i] * mag[i];
            }
            float d = power < 1e-14 ? -140f : 10f * (float) Math.log10(power) + tilt[b];
            db[b] = d;
            maxDb = Math.max(maxDb, d);
        }
        refDb = Math.max(maxDb, refDb - 0.02f);
        if (refDb < -55f) refDb = -55f;
        for (int b = 0; b < Analysis.BANDS; b++) bands[b] = clamp01(1f - (refDb - db[b]) / 46f);
        if (rms < 0.0003f) java.util.Arrays.fill(bands, 0f);

        float bass = avg(bands, 0, 7);
        float midv = avg(bands, 7, 21);
        float treble = avg(bands, 21, 32);

        // onsets: half-wave rectified change of the compressed spectrum, per group
        float[] flux = new float[3];
        for (int k = 1; k < WINDOW / 2; k++) {
            float cur = (float) Math.log1p(mag[k] * 60f);
            float pv = (float) Math.log1p(prevMag[k] * 60f);
            float d = cur - pv;
            if (d > 0) {
                if (k < lowEnd) flux[0] += d;
                else if (k < midEnd) flux[1] += d;
                else if (k < highEnd) flux[2] += d;
            }
        }
        System.arraycopy(mag, 0, prevMag, 0, mag.length);
        float[] strength = new float[3];
        float total = 0;
        for (int g = 0; g < 3; g++) {
            float f = flux[g];
            float mean = fluxMean[g];
            float var = fluxVar[g];
            float std = (float) Math.sqrt(var);
            float thr = mean + K[g] * std + 0.02f;
            boolean rising = f > fluxPrev[g];
            double since = (endFrame - lastOnset[g]) / (double) rate;
            if (f > thr && rising && since > REFRACTORY[g] && rmsDb > -55f) {
                lastOnset[g] = endFrame;
                strength[g] = clamp01(0.4f + 0.6f * (f - thr) / Math.max(0.05f, thr));
            }
            fluxPrev[g] = f;
            float a = 1f - (float) Math.exp(-HOP / (rate * 0.9));
            float diff = f - mean;
            fluxMean[g] += a * diff;
            fluxVar[g] += a * (diff * diff - var);
            total += Math.min(1f, f / Math.max(0.3f, mean * 3f + 0.1f)) * (g == 0 ? 0.5f : g == 1 ? 0.3f : 0.2f);
        }
        float overall = clamp01(total);

        // 64 samples of waveform, every 8th of the newest hop
        float[] wave = new float[Analysis.WAVE];
        for (int i = 0; i < Analysis.WAVE; i++) wave[i] = clamp(mono[WINDOW - HOP + i * (HOP / Analysis.WAVE)], -1f, 1f);

        long center = endFrame - WINDOW / 2;
        Analysis a = new Analysis(center, bands, wave, level, bass, midv, treble, strength[0], strength[1], strength[2], overall);
        int h = head + 1;
        ring[h % RING] = a;
        head = h;

        // tempo from the onset envelope
        envHead = (envHead + 1) % RING;
        onsetEnv[envHead] = Math.max(overall, Math.max(strength[0], strength[1] * 0.7f));
        if (++sinceTempo >= 43) {
            sinceTempo = 0;
            estimateTempo();
        }
        float rate1 = 0;
        float bpmNorm = tempo <= 0 ? 0.4f : clamp01((tempo - 70f) / 90f);
        energy = clamp01(0.55f * slowLoud + 0.3f * bpmNorm + 0.15f * clamp01(overall * 1.5f));
    }

    private void estimateTempo() {
        int n = RING;
        float[] e = new float[n];
        double mean = 0;
        for (int i = 0; i < n; i++) {
            e[i] = onsetEnv[(envHead - (n - 1 - i) + n * 2) % n];
            mean += e[i];
        }
        mean /= n;
        double energySum = 0;
        for (int i = 0; i < n; i++) {
            e[i] -= (float) mean;
            energySum += e[i] * e[i];
        }
        if (energySum < 0.5) {
            bpmConfidence *= 0.9f;
            return;
        }
        double hopsPerSec = rate / (double) HOP;
        int minLag = (int) Math.floor(hopsPerSec * 60 / 190);
        int maxLag = (int) Math.ceil(hopsPerSec * 60 / 62);
        double best = -1;
        int bestLag = 0;
        double[] r = new double[maxLag + 2];
        for (int lag = minLag - 1; lag <= maxLag + 1; lag++) {
            double s = 0;
            for (int i = lag; i < n; i++) s += e[i] * e[i - lag];
            r[lag] = s / (n - lag);
        }
        for (int lag = minLag; lag <= maxLag; lag++) {
            double bpmLag = 60 * hopsPerSec / lag;
            double prior = Math.exp(-0.5 * Math.pow(Math.log(bpmLag / 118.0) / 0.55, 2));
            double v = r[lag] * (0.55 + 0.45 * prior);
            if (r[lag] >= r[lag - 1] && r[lag] >= r[lag + 1] && v > best) {
                best = v;
                bestLag = lag;
            }
        }
        if (bestLag == 0 || r[bestLag] <= 0) return;
        // parabolic refinement
        double y0 = r[bestLag - 1], y1 = r[bestLag], y2 = r[bestLag + 1];
        double den = y0 - 2 * y1 + y2;
        double off = den == 0 ? 0 : 0.5 * (y0 - y2) / den;
        double lag = bestLag + Math.max(-0.5, Math.min(0.5, off));
        float cand = (float) (60 * hopsPerSec / lag);
        float conf = (float) Math.min(1.0, r[bestLag] / Math.max(1e-6, r[0] == 0 ? energySum / n : energySum / n));
        if (bpm <= 0 || Math.abs(cand - bpm) / bpm < 0.06f) {
            bpm = bpm <= 0 ? cand : bpm * 0.7f + cand * 0.3f;
            bpmVotes = 0;
        } else if (Math.abs(cand - lastBpmCandidate) / cand < 0.04f && ++bpmVotes >= 2) {
            bpm = cand;
            bpmVotes = 0;
        }
        lastBpmCandidate = cand;
        bpmConfidence = conf;
        tempo = bpm;
        tempoConfidence = conf;
    }

    private static float avg(float[] a, int from, int to) {
        float s = 0;
        for (int i = from; i < to; i++) s += a[i];
        return s / (to - from);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(1f, v);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
