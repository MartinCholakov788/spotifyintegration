package com.zlorbie788.spotify.lyrics;

/**
 * One line of lyrics. {@code text} is empty for the gaps between verses (instrumental breaks). Some lyric files
 * (the "enhanced" .lrc format) also say when every word starts: {@code wordMs[i]} is the time of the word that
 * begins at character {@code wordChar[i]} of {@code text}. Both arrays are null for ordinary line-by-line lyrics.
 */
public record LyricLine(long startMs, String text, long[] wordMs, int[] wordChar) {
    public LyricLine(long startMs, String text) {
        this(startMs, text, null, null);
    }

    public boolean blank() {
        return text.isBlank();
    }

    /** True when the line carries the start time of its words. */
    public boolean hasWords() {
        return wordMs != null && wordChar != null && wordMs.length > 0 && wordMs.length == wordChar.length && !text.isBlank();
    }

    /**
     * How much of the line has been sung at {@code posMs}, 0..1 (by characters), following the real word times.
     * {@code endMs} is when the next line starts, used for the last word. Only meaningful when {@link #hasWords()}.
     */
    public float wordFill(long posMs, long endMs) {
        int len = Math.max(1, text.length());
        if (!hasWords()) return 0f;
        if (posMs <= wordMs[0]) return 0f;
        int n = wordMs.length;
        for (int i = 0; i < n; i++) {
            long from = wordMs[i];
            int fromChar = Math.max(0, Math.min(len, wordChar[i]));
            long to;
            int toChar;
            if (i + 1 < n) {
                to = wordMs[i + 1];
                toChar = Math.max(fromChar, Math.min(len, wordChar[i + 1]));
            } else {
                // the last word lasts about as long as it would take to sing it, but never past the next line
                long sing = Math.max(300L, (len - fromChar) * 70L);
                to = endMs > from ? Math.min(endMs, from + sing) : from + sing;
                toChar = len;
            }
            if (posMs < to || i == n - 1) {
                if (posMs >= to) return 1f;
                float t = to > from ? (posMs - from) / (float) (to - from) : 1f;
                return Math.max(0f, Math.min(1f, (fromChar + (toChar - fromChar) * t) / len));
            }
        }
        return 1f;
    }
}
