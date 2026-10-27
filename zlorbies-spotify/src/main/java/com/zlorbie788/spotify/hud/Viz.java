package com.zlorbie788.spotify.hud;

import com.zlorbie788.spotify.config.SpotifyConfig.ShapeKind;
import com.zlorbie788.spotify.config.SpotifyConfig.VizStyle;
import com.zlorbie788.spotify.util.Clock;
import com.zlorbie788.spotify.util.Colors;
import com.zlorbie788.spotify.util.Ease;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The beat display. Six looks, all fed by the same shared numbers ({@link Visual}): loudness, beat kick and the band
 * heights. Everything is drawn in device pixels, so it stays crisp at any size.
 *
 * <ul>
 *   <li><b>Mirror</b>: slim pills growing up and down from a centre line, with a soft light on every beat.</li>
 *   <li><b>Scope</b>: the loudness of the last few seconds scrolling past like an oscilloscope, brightest at the head.</li>
 *   <li><b>Wave</b>: a flowing line whose swell follows the music.</li>
 *   <li><b>Dots</b>: an LED matrix that lights up from the bottom.</li>
 *   <li><b>Bars</b>: classic bars with falling peak caps.</li>
 * </ul>
 * When nothing plays every look settles into a calm resting line of dots, so the card never shows a row of dashes.
 */
public final class Viz {
    private static final int HIST = 192;
    private static final float[] history = new float[HIST];
    private static int head;
    private static long lastPush;

    private Viz() {}

    /** Called by the draw methods; pushes new loudness samples (40 a second) into the scrolling history. */
    private static void tick() {
        long now = Clock.millis();
        if (lastPush == 0L || now - lastPush > 500L) lastPush = now;
        int n = (int) Math.min(8L, (now - lastPush) / 25L);
        if (n <= 0) return;
        lastPush += n * 25L;
        float sum = 0f;
        for (int i = 0; i < 12; i++) sum += Visual.bar(i, 12);
        float v = Ease.clamp01(sum / 12f * 1.15f + Visual.pulse() * 0.35f);
        for (int i = 0; i < n; i++) {
            head = (head + 1) % HIST;
            history[head] = v;
        }
    }

    /** Loudness sample {@code ago} steps back (0 = newest). */
    private static float hist(int ago) {
        return history[((head - ago) % HIST + HIST) % HIST];
    }

    public static void reset() {
        java.util.Arrays.fill(history, 0f);
        head = 0;
        lastPush = 0L;
    }

    /** Small display (the card's corner, the compact layouts). */
    public static void draw(GuiGraphics g, float dev, VizStyle style, int x, int y, int w, int h, int color, float amp,
                            boolean motion, float alpha, int seed) {
        if (style == null || style == VizStyle.OFF || w <= 0 || h <= 0) return;
        tick();
        amp = motion ? Ease.clamp01(amp) : 0f;
        switch (style) {
            case SCOPE -> scope(g, dev, x, y, w, h, color, amp, alpha);
            case WAVE -> wave(g, dev, x, y, w, h, color, amp, alpha);
            case DOTS -> dots(g, dev, x, y, w, h, color, amp, alpha);
            case BARS -> bars(g, dev, x, y, w, h, color, amp, alpha);
            case SPECTRUM -> spectrum(g, dev, x, y, w, h, color, amp, alpha);
            case RING -> {
                if (h >= 22 && w >= 22) ring(g, dev, x, y, w, h, color, amp, alpha);
                else mirror(g, dev, x, y, w, h, color, amp, alpha);
            }
            default -> mirror(g, dev, x, y, w, h, color, amp, alpha);
        }
    }

    /** Wide display (the full player). */
    public static void drawWide(GuiGraphics g, float dev, VizStyle style, int x, int y, int w, int h, int color,
                                boolean motion, float alpha) {
        draw(g, dev, style, x, y, w, h, color, PlayerHud.equalizerAmount(), motion, alpha, 0);
    }

    // ------------------------------------------------------------ helpers

    private static int top(int color) {
        return Colors.lighten(color, 0.35f);
    }

    private static void pill(GuiGraphics g, float dev, float x, float y, float w, float h, int c1, int c2) {
        float r = Math.min(w, h) / 2f;
        Shapes.fill(g, dev, x, y, w, h, ShapeKind.ROUND, r, c1, c2);
    }

    private static void dot(GuiGraphics g, float dev, float cx, float cy, float r, int color) {
        // one small picture instead of the several pieces of a rounded shape
        if (!IntroArt.disc(g, cx, cy, r, color)) Shapes.disc(g, dev, cx, cy, r, color);
    }

    // ------------------------------------------------------------ mirror

    private static void mirror(GuiGraphics g, float dev, int x, int y, int w, int h, int color, float amp, float alpha) {
        float step = h >= 14 ? 4f : 3f;
        int count = Math.max(3, (int) ((w + 1) / step));
        float bw = step >= 4f ? 2.4f : 1.8f;
        float used = count * step - (step - bw);
        float x0 = x + (w - used) / 2f;
        float cy = y + h / 2f;
        float pulse = Visual.pulse() * amp;
        if (pulse > 0.08f) Shapes.glow(g, dev, x + w * 0.2f, cy - 1, w * 0.6f, 2, ShapeKind.ROUND, 1f, color, pulse * 0.8f * alpha);
        for (int i = 0; i < count; i++) {
            // the bars grow from the middle outwards: the centre bars use the low bands, the edges the high ones
            float dist = Math.abs((i + 0.5f) / count * 2f - 1f);
            int src = (int) ((1f - dist) * (count - 1));
            float v = Visual.bar(Math.max(0, Math.min(count - 1, src)), count);
            v *= amp;
            float edge = 1f - dist * dist * 0.55f;
            float bh = Math.max(1.6f, v * h * edge);
            bh = Math.min(h, bh);
            float a = alpha * (0.45f + 0.55f * Ease.clamp01(v * 1.4f)) * (0.7f + 0.3f * edge);
            pill(g, dev, x0 + i * step, cy - bh / 2f, bw, bh, Colors.alpha(top(color), a), Colors.alpha(color, a));
        }
    }

    // ------------------------------------------------------------ bars

    private static void bars(GuiGraphics g, float dev, int x, int y, int w, int h, int color, float amp, float alpha) {
        float step = h >= 14 ? 4f : 3f;
        int count = Math.max(3, (int) ((w + 1) / step));
        float bw = step - 1.2f;
        float used = count * step - (step - bw);
        float x0 = x + (w - used) / 2f;
        for (int i = 0; i < count; i++) {
            float v = Visual.bar(i, count) * amp;
            float bh = Math.max(1.4f, v * h);
            float a = alpha * (0.5f + 0.5f * Ease.clamp01(v * 1.3f));
            pill(g, dev, x0 + i * step, y + h - bh, bw, bh, Colors.alpha(top(color), a), Colors.alpha(color, a));
            float cap = Visual.cap(i, count) * amp * h;
            if (cap > bh + 1.6f) {
                Shapes.fill(g, dev, x0 + i * step, y + h - cap - 1.4f, bw, 1.2f, ShapeKind.SQUARE, 0,
                        Colors.alpha(Colors.WHITE, 0.8f * alpha), Colors.alpha(Colors.WHITE, 0.8f * alpha));
            }
        }
    }

    // ------------------------------------------------------------ dots

    private static void dots(GuiGraphics g, float dev, int x, int y, int w, int h, int color, float amp, float alpha) {
        float step = h >= 14 ? 3.6f : 2.8f;
        int cols = Math.max(3, (int) (w / step));
        int rows = Math.max(2, (int) (h / step));
        float r = step * 0.30f;
        float gx = x + (w - cols * step) / 2f + step / 2f;
        float gy = y + (h - rows * step) / 2f + step / 2f;
        for (int c = 0; c < cols; c++) {
            float v = Visual.bar(c, cols) * amp;
            int lit = Math.max(1, Math.round(v * rows));
            for (int rr = 0; rr < rows; rr++) {
                boolean on = rr < lit;
                float t = rr / (float) Math.max(1, rows - 1);
                int col = Colors.mix(color, Colors.lighten(color, 0.5f), t);
                float a = on ? alpha * (rr == lit - 1 ? 1f : 0.82f) : alpha * 0.11f;
                dot(g, dev, gx + c * step, gy + (rows - 1 - rr) * step, r, Colors.alpha(col, a));
            }
        }
    }

    // ------------------------------------------------------------ scope

    private static void scope(GuiGraphics g, float dev, int x, int y, int w, int h, int color, float amp, float alpha) {
        // one slim line per 1.5 gui pixels, newest at the right, each mirrored around the centre
        float step = 1.5f;
        int cols = Math.max(4, (int) (w / step));
        float cy = y + h / 2f;
        int per = Math.max(1, HIST / cols);
        for (int i = 0; i < cols; i++) {
            int ago = (cols - 1 - i) * Math.min(per, 2);
            float v = 0f;
            for (int k = 0; k < 2; k++) v = Math.max(v, hist(Math.min(HIST - 1, ago + k)));
            v *= amp;
            float age = 1f - i / (float) (cols - 1);
            float bh = Math.max(1.0f, v * h * (0.55f + 0.45f * (1f - age)));
            float fade = Ease.clamp01((1f - age) * 3f) * (0.35f + 0.65f * (1f - age));
            float a = alpha * Math.max(0.1f, fade) * (0.5f + 0.5f * Ease.clamp01(v * 1.6f));
            int col = i >= cols - 2 ? Colors.WHITE : color;
            Shapes.fill(g, dev, x + i * step, cy - bh / 2f, 1.0f, bh, ShapeKind.SQUARE, 0, Colors.alpha(col, a), Colors.alpha(col, a));
        }
        // the baseline
        Shapes.fill(g, dev, x, cy - 0.25f, w, 0.5f, ShapeKind.SQUARE, 0, Colors.alpha(color, 0.25f * alpha),
                Colors.alpha(color, 0.25f * alpha));
        dot(g, dev, x + w - 0.8f, cy, 1.3f + Visual.pulse() * amp * 0.8f, Colors.alpha(Colors.WHITE, alpha));
    }

    // ------------------------------------------------------------ wave

    private static void wave(GuiGraphics g, float dev, int x, int y, int w, int h, int color, float amp, float alpha) {
        float t = (Clock.millis() % 600_000L) / 1000f;
        float level = Math.max(Visual.level(), 0.0f) * amp;
        float pulse = Visual.pulse() * amp;
        float cy = y + h / 2f;
        float colStep = Math.max(1.0f, w / 120f);
        int cols = Math.max(4, (int) (w / colStep));
        float thick = 1.1f + 0.5f * pulse;
        boolean measured = Visual.measured();
        float bass = com.zlorbie788.spotify.media.Spectrum.bass();
        float mid = com.zlorbie788.spotify.media.Spectrum.mid();
        float treb = com.zlorbie788.spotify.media.Spectrum.treble();
        for (int i = 0; i < cols; i++) {
            float u = i / (float) (cols - 1);
            float env = (float) Math.sin(Math.PI * u);
            env = env * env * (3f - 2f * env) ;
            float swell = 0.12f + (0.55f * level + 0.45f * pulse);
            float yy = (float) (Math.sin(u * 14.0 - t * 5.0) * 0.6 + Math.sin(u * 23.0 + t * 7.3) * 0.4);
            if (measured) {
                // a real-looking waveform: low, middle and high parts of the sound each get their own ripple
                double norm = bass + 0.8 * mid + 0.5 * treb + 0.15;
                yy = (float) ((bass * Math.sin(u * 6.0 - t * 4.5) + 0.8 * mid * Math.sin(u * 15.0 + t * 8.0)
                        + 0.5 * treb * Math.sin(u * 37.0 - t * 15.0)) / norm);
                swell = 0.08f + 0.92f * Math.min(1f, level * 1.2f + pulse * 0.5f);
            }
            float off = yy * swell * (h / 2f - thick) * env * amp;
            float a = alpha * (0.3f + 0.7f * env);
            int col = Colors.mix(color, Colors.lighten(color, 0.5f), u);
            Shapes.fill(g, dev, x + i * colStep, cy + off - thick / 2f, colStep + 0.2f, thick, ShapeKind.SQUARE, 0,
                    Colors.alpha(col, a), Colors.alpha(col, a));
        }
    }

    // ------------------------------------------------------------ spectrum

    /** A smooth mountain range of the whole spectrum with a bright edge and a soft fill below it. */
    private static void spectrum(GuiGraphics g, float dev, int x, int y, int w, int h, int color, float amp, float alpha) {
        // one column per GUI pixel on a small area, never more than 120 columns on a big one
        float colW = Math.max(1.0f, w / 120f);
        int cols = Math.max(6, (int) (w / colW));
        float prev = -1f;
        for (int i = 0; i < cols; i++) {
            float u = i / (float) (cols - 1);
            float v = Visual.curve(u) * amp;
            float bh = Math.max(1.0f, v * h);
            int col = Colors.mix(color, Colors.lighten(color, 0.55f), u);
            float a = alpha * (0.35f + 0.65f * Ease.clamp01(v * 1.3f));
            float cw = w / (float) cols;
            float px = x + i * (w / (float) cols);
            // soft fill: strongest at the top, fading towards the bottom
            Shapes.fill(g, dev, px, y + h - bh, cw + 0.3f, bh, ShapeKind.SQUARE, 0,
                    Colors.alpha(col, a * 0.55f), Colors.alpha(col, a * 0.06f));
            // the bright edge, as tall as the step to its neighbour so the line has no gaps
            float edge = Math.max(1.0f, prev < 0f ? 1.2f : Math.abs(bh - prev) + 1.2f);
            Shapes.fill(g, dev, px, y + h - bh - 0.4f, cw + 0.3f, Math.min(edge, bh + 1f), ShapeKind.SQUARE, 0,
                    Colors.alpha(Colors.lighten(col, 0.3f), a), Colors.alpha(Colors.lighten(col, 0.3f), a));
            prev = bh;
        }
        float pulse = Visual.pulse() * amp;
        if (pulse > 0.08f) {
            Shapes.glow(g, dev, x, y + h - 2, w, 2, ShapeKind.ROUND, 1f, color, pulse * 0.7f * alpha);
        }
    }

    // ------------------------------------------------------------ ring

    /** Bars standing on a circle, the circle breathing with the kick. Needs a roughly square area. */
    private static void ring(GuiGraphics g, float dev, int x, int y, int w, int h, int color, float amp, float alpha) {
        float cx = x + w / 2f;
        float cy = y + h / 2f;
        float outer = Math.min(w, h) / 2f - 1f;
        float pulse = Visual.pulse() * amp;
        float inner = outer * (0.42f + 0.04f * pulse);
        int n = Math.max(24, Math.min(64, (int) (outer * 2.2f)));
        float spin = (Clock.millis() % 90_000L) / 90_000f * (float) (Math.PI * 2) * (1f + 0.0f);
        float arc = (float) (Math.PI * 2 * inner / n);
        float dotR = Math.max(0.6f, Math.min(1.6f, arc * 0.36f));
        Shapes.glow(g, dev, cx - inner, cy - inner, inner * 2, inner * 2, ShapeKind.ROUND, inner,
                color, (0.10f + 0.5f * pulse) * alpha);
        for (int i = 0; i < n; i++) {
            float u = i / (float) n;
            // mirror the spectrum around the circle so it closes without a jump
            float t = u < 0.5f ? u * 2f : (1f - u) * 2f;
            float v = Visual.curve(t) * amp;
            float len = Math.max(dotR * 1.2f, v * (outer - inner));
            double ang = u * Math.PI * 2 + spin - Math.PI / 2;
            float dx = (float) Math.cos(ang);
            float dy = (float) Math.sin(ang);
            int col = Colors.mix(color, Colors.lighten(color, 0.55f), Ease.clamp01(v * 1.3f));
            float a = alpha * (0.45f + 0.55f * Ease.clamp01(v * 1.5f));
            float stepLen = dotR * 1.5f;
            int steps = Math.max(1, (int) (len / stepLen));
            for (int k = 0; k <= steps; k++) {
                float rr = inner + Math.min(len, k * stepLen);
                float fade = 1f - 0.35f * (k / (float) (steps + 1));
                dot(g, dev, cx + dx * rr, cy + dy * rr, dotR, Colors.alpha(col, a * fade));
            }
        }
    }
}
