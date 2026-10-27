package com.zlorbie788.spotify.util;

/** Small pure helpers for animation and text, kept free of Minecraft classes so they can be unit-tested. */
public final class Ease {
    private Ease() {}

    public static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }

    public static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** Fast start, slow finish. t in [0, 1]. */
    public static float outCubic(float t) {
        float u = 1f - clamp01(t);
        return 1f - u * u * u;
    }

    /** Slow start and finish. */
    public static float inOutCubic(float t) {
        t = clamp01(t);
        return t < 0.5f ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3) / 2f;
    }

    /** Slight overshoot, for the cover "pop". */
    public static float outBack(float t) {
        t = clamp01(t);
        float c1 = 1.70158f;
        float c3 = c1 + 1f;
        float u = t - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }

    /**
     * Moves {@code current} towards {@code target} like a spring that never overshoots: frame-rate independent.
     * {@code rate} is how fast (per second), dt in seconds.
     */
    public static float approach(float current, float target, float rate, float dt) {
        float k = 1f - (float) Math.exp(-rate * Math.max(0f, dt));
        return current + (target - current) * k;
    }

    /**
     * Horizontal scroll for text that is wider than its box: waits, scrolls to the end, waits, then starts again.
     * Returns how many pixels to shift the text left (0 when it fits).
     */
    public static float marquee(float textWidth, float boxWidth, long elapsedMs, float pixelsPerSecond) {
        float overflow = textWidth - boxWidth;
        if (overflow <= 0.5f) return 0f;
        long wait = 1800;
        float travelMs = overflow / Math.max(1f, pixelsPerSecond) * 1000f;
        float cycle = wait + travelMs + wait;
        float t = elapsedMs % (long) Math.max(1f, cycle);
        if (t < wait) return 0f;
        if (t < wait + travelMs) return (t - wait) / travelMs * overflow;
        return overflow;
    }

    /** Height 0..1 of one equalizer bar. Looks random, is smooth, and is different per bar and per song. */
    public static float equalizer(int bar, long timeMs, int seed) {
        double t = timeMs / 1000.0;
        double a = Math.sin(t * (3.1 + bar * 0.83) + bar * 1.7 + seed * 0.013);
        double b = Math.sin(t * (5.3 + bar * 0.41) + bar * 0.9 + seed * 0.007);
        double c = Math.sin(t * (1.7 + bar * 0.29) + seed * 0.021);
        double v = 0.5 + 0.27 * a + 0.18 * b + 0.12 * c;
        return clamp01((float) v);
    }

    /** 3:07 style time. Negative or absurd values are treated as 0. */
    public static String clock(long millis) {
        if (millis < 0) millis = 0;
        long total = millis / 1000;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        if (h > 0) return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, s);
        return String.format(java.util.Locale.ROOT, "%d:%02d", m, s);
    }

    /**
     * Reads a time typed by a person: "83" (seconds), "1:23", "01:23", "1:02:03", "1m23s", "90s".
     * Returns the milliseconds, or -1 when the text is not a time.
     */
    public static long parseTime(String text) {
        if (text == null) return -1;
        String t = text.trim().toLowerCase(java.util.Locale.ROOT);
        if (t.isEmpty()) return -1;
        try {
            if (t.contains(":")) {
                String[] parts = t.split(":", -1);
                if (parts.length > 3) return -1;
                long total = 0;
                for (String part : parts) {
                    if (part.isBlank()) return -1;
                    double v = Double.parseDouble(part.trim());
                    if (v < 0) return -1;
                    total = total * 60 + (long) v;
                }
                return total * 1000L;
            }
            if (t.matches("\\d+(\\.\\d+)?")) return Math.round(Double.parseDouble(t) * 1000.0);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^(?:(\\d+)h)?\\s*(?:(\\d+)m)?\\s*(?:(\\d+)s)?$").matcher(t);
            if (m.matches() && (m.group(1) != null || m.group(2) != null || m.group(3) != null)) {
                long h = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
                long mi = m.group(2) == null ? 0 : Long.parseLong(m.group(2));
                long sec = m.group(3) == null ? 0 : Long.parseLong(m.group(3));
                return ((h * 60 + mi) * 60 + sec) * 1000L;
            }
        } catch (NumberFormatException e) {
            return -1;
        }
        return -1;
    }
}
