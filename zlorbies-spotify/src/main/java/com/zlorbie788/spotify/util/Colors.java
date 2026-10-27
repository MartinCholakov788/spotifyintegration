package com.zlorbie788.spotify.util;

/** Colour helpers. All colours are ARGB ints (0xAARRGGBB). */
public final class Colors {
    public static final int WHITE = 0xFFFFFFFF;
    public static final int BLACK = 0xFF000000;
    public static final int MINT = 0xFF34D399;
    public static final int CYAN = 0xFF22D3EE;
    public static final int PINK = 0xFFF472B6;
    public static final int GOLD = 0xFFFBBF24;
    public static final int RED = 0xFFF87171;
    public static final int PURPLE = 0xFFC084FC;
    public static final int BLUE = 0xFF60A5FA;

    /** Colours offered in the settings for "My colour". */
    public static final int[] PALETTE = {MINT, CYAN, BLUE, PURPLE, PINK, RED, GOLD, WHITE};
    public static final String[] PALETTE_NAMES = {"Mint", "Cyan", "Blue", "Purple", "Pink", "Red", "Gold", "White"};

    // Surfaces of the cards and screens
    public static final int CARD = 0xFF111729;
    public static final int CARD_DARK = 0xFF0A0F1C;
    public static final int CARD_RAISED = 0xFF1B2440;
    public static final int LINE = 0xFF2B365A;
    public static final int TEXT = 0xFFFFFFFF;
    public static final int TEXT_SOFT = 0xFFC9D0E2;
    public static final int TEXT_DIM = 0xFFA3ACC6;

    private Colors() {}

    /** Multiplies the alpha channel by a factor in [0, 1]. Never returns alpha 0 (the game treats it as invisible). */
    public static int alpha(int argb, float factor) {
        int a = (argb >>> 24) & 0xFF;
        a = Math.max(1, Math.min(255, Math.round(a * factor)));
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    public static int withAlpha(int argb, int alpha) {
        return (Math.max(1, Math.min(255, alpha)) << 24) | (argb & 0x00FFFFFF);
    }

    public static int opaque(int argb) {
        return 0xFF000000 | (argb & 0x00FFFFFF);
    }

    public static int mix(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int aa = (a >>> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return (Math.round(aa + (ba - aa) * t) << 24)
                | (Math.round(ar + (br - ar) * t) << 16)
                | (Math.round(ag + (bg - ag) * t) << 8)
                | Math.round(ab + (bb - ab) * t);
    }

    public static int lighten(int argb, float amount) {
        return mix(argb, (argb & 0xFF000000) | 0x00FFFFFF, amount);
    }

    public static int darken(int argb, float amount) {
        return mix(argb, argb & 0xFF000000, amount);
    }

    /** HSV to opaque ARGB. Hue wraps; saturation and value are clamped to [0, 1]. */
    public static int hsv(float hue, float saturation, float value) {
        float h = hue - (float) Math.floor(hue);
        float s = Math.max(0f, Math.min(1f, saturation));
        float v = Math.max(0f, Math.min(1f, value));
        float scaled = h * 6f;
        int sector = (int) scaled;
        float f = scaled - sector;
        float p = v * (1f - s);
        float q = v * (1f - f * s);
        float t = v * (1f - (1f - f) * s);
        float r;
        float g;
        float b;
        switch (sector % 6) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return 0xFF000000 | (Math.round(r * 255f) << 16) | (Math.round(g * 255f) << 8) | Math.round(b * 255f);
    }

    /** The same colour with its hue turned by {@code delta} (1 = a full turn). */
    public static int hsvShift(int argb, float delta) {
        float[] hsv = toHsv(argb);
        float h = hsv[0] + delta;
        h -= (float) Math.floor(h);
        return (argb & 0xFF000000) | (hsv(h, hsv[1], hsv[2]) & 0xFFFFFF);
    }

    /** {hue 0..1, saturation 0..1, value 0..1} of a colour. */
    public static float[] toHsv(int argb) {
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float d = max - min;
        float h;
        if (d < 1e-6f) {
            h = 0f;
        } else if (max == r) {
            h = ((g - b) / d) % 6f;
        } else if (max == g) {
            h = (b - r) / d + 2f;
        } else {
            h = (r - g) / d + 4f;
        }
        h /= 6f;
        if (h < 0f) h += 1f;
        return new float[]{h, max <= 0f ? 0f : d / max, max};
    }

    /** One point on the rainbow: t walks once around the hue wheel. */
    public static int rainbow(float t) {
        return hsv(t, 0.62f, 1f);
    }

    /** Index of the palette colour equal to the given one, or -1. */
    public static int paletteIndex(int argb) {
        for (int i = 0; i < PALETTE.length; i++) {
            if (PALETTE[i] == argb) return i;
        }
        return -1;
    }
}
