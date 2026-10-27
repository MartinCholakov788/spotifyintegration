package com.zlorbie788.spotify.util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/** Word wrapping that does not depend on Minecraft: the caller says how wide a piece of text is. */
public final class TextWrap {
    private TextWrap() {}

    /**
     * Splits {@code text} into at most {@code maxRows} rows no wider than {@code maxPx}. A word that is wider
     * than a row is broken by letters. If the text does not fit, the last row ends in an ellipsis.
     */
    public static List<String> wrap(String text, int maxPx, int maxRows, ToIntFunction<String> width) {
        List<String> rows = new ArrayList<>();
        if (text == null) return rows;
        String t = text.strip();
        if (t.isEmpty() || maxRows < 1) return rows;
        if (width.applyAsInt(t) <= maxPx) {
            rows.add(t);
            return rows;
        }
        String[] words = t.split("\\s+");
        StringBuilder row = new StringBuilder();
        int i = 0;
        while (i < words.length) {
            String word = words[i];
            String tryRow = row.length() == 0 ? word : row + " " + word;
            if (width.applyAsInt(tryRow) <= maxPx) {
                row.setLength(0);
                row.append(tryRow);
                i++;
                continue;
            }
            if (row.length() == 0) {
                // one word wider than the box: put as many letters as fit, carry the rest
                int n = word.length();
                while (n > 1 && width.applyAsInt(word.substring(0, n)) > maxPx) n--;
                rows.add(word.substring(0, n));
                words[i] = word.substring(n);
                if (words[i].isEmpty()) i++;
            } else {
                rows.add(row.toString());
                row.setLength(0);
            }
            if (rows.size() == maxRows) return ellipsize(rows, words, i, row, maxPx, width);
        }
        if (row.length() > 0) {
            rows.add(row.toString());
            if (rows.size() > maxRows) {
                rows = new ArrayList<>(rows.subList(0, maxRows));
                String last = rows.get(maxRows - 1);
                rows.set(maxRows - 1, cut(last + "...", maxPx, width));
            }
        }
        return rows;
    }

    private static List<String> ellipsize(List<String> rows, String[] words, int next, StringBuilder row, int maxPx,
                                          ToIntFunction<String> width) {
        boolean more = next < words.length || row.length() > 0;
        if (more) {
            String last = rows.get(rows.size() - 1);
            rows.set(rows.size() - 1, cut(last + "...", maxPx, width));
        }
        return rows;
    }

    /** Shortens {@code s} (which ends in "...") from the left part until it fits. */
    private static String cut(String s, int maxPx, ToIntFunction<String> width) {
        String body = s.endsWith("...") ? s.substring(0, s.length() - 3) : s;
        String dots = "...";
        while (!body.isEmpty() && width.applyAsInt(body + dots) > maxPx) body = body.substring(0, body.length() - 1);
        return body.stripTrailing() + dots;
    }

    /** Every row of {@code text} at {@code maxPx} wide: nothing is dropped and nothing is replaced by an ellipsis. */
    public static List<String> wrapAll(String text, int maxPx, ToIntFunction<String> width) {
        return wrap(text, Math.max(1, maxPx), Integer.MAX_VALUE, width);
    }

    /** Rows plus the size (1 = normal) they are meant to be drawn at. */
    public static final class Fit {
        public final List<String> rows;
        public final float scale;

        Fit(List<String> rows, float scale) {
            this.rows = rows;
            this.scale = scale;
        }
    }

    /**
     * Shows ALL of {@code text} in a box {@code maxPx} wide: first as normal sized rows; when that needs more than
     * {@code maxRows} rows the text is made smaller (down to {@code minScale}) until it fits. If it still needs more
     * rows at the smallest size they are all kept (the caller makes room), so no word is ever lost.
     */
    public static Fit fitRows(String text, int maxPx, int maxRows, float minScale, ToIntFunction<String> width) {
        float scale = 1f;
        while (true) {
            int limit = Math.max(8, Math.round(maxPx / scale));
            List<String> rows = wrapAll(text, limit, width);
            if (rows.size() <= maxRows || scale <= minScale + 0.001f) return new Fit(rows, scale);
            scale = Math.max(minScale, scale - 0.07f);
        }
    }
}
