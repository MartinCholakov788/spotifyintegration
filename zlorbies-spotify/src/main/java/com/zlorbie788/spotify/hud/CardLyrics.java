package com.zlorbie788.spotify.hud;

import com.zlorbie788.spotify.config.LyricsConfig;
import com.zlorbie788.spotify.lyrics.LrcParser;
import com.zlorbie788.spotify.lyrics.LyricLine;
import com.zlorbie788.spotify.lyrics.Lyrics;
import com.zlorbie788.spotify.media.Player;
import com.zlorbie788.spotify.media.Snapshot;
import com.zlorbie788.spotify.util.Colors;
import com.zlorbie788.spotify.util.Ease;
import com.zlorbie788.spotify.util.TextWrap;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Lyrics inside the player card: the line being sung (and the one after it) takes the place of one of the card's
 * text lines. {@link #now} works out what to show once per frame; {@link #draw} paints one such line. When the
 * song has no timed lyrics {@link #now} returns null and the card simply shows its normal text.
 */
public final class CardLyrics {
    /** What the card should show this frame. */
    public static final class Now {
        public String cur = "";
        public String next = "";
        /** The line that was sung just before {@link #cur}, while it slides away. */
        public String prev = "";
        /** 0..1: how far the change from {@link #prev} to {@link #cur} has got (1 = settled). */
        public float change = 1f;
        /** 0..1: how far through the current line the singer is. */
        public float fill;
        /** An instrumental part: {@link #cur} is just a note sign. */
        public boolean gap;
        /** False when the player has "reduced motion" on (long lines then do not glide on their own). */
        public boolean motion = true;
    }

    private static final String NOTE = "♪";
    private static int lastIndex = Integer.MIN_VALUE;
    private static Lyrics.Doc lastDoc;
    private static String lastCur = "";
    private static String prevCur = "";
    private static long changedAt;

    private CardLyrics() {}

    /** Forget the line animation (tests, new song). */
    static void reset() {
        lastIndex = Integer.MIN_VALUE;
        lastDoc = null;
        lastCur = "";
        prevCur = "";
    }

    /**
     * @param preview true for the settings / layout preview, which shows a made-up sample when the real song has no
     *                timed lyrics, so the setting can be seen at work
     */
    public static Now now(LyricsConfig lc, boolean preview, boolean motion, long nowMs) {
        if (lc.cardMode == LyricsConfig.CardMode.OFF) return null;
        Lyrics.Doc doc;
        long pos;
        boolean sample = preview && !Lyrics.ready();
        if (sample) {
            doc = Lyrics.sample();
            pos = nowMs % Math.max(1L, Lyrics.sampleLength());
        } else {
            Snapshot s = Player.snap();
            if (!Lyrics.ready() || !s.active() || s.isAd()) return null;
            if (!Player.playing() && !lc.showWhenPaused) return null;
            doc = Lyrics.doc();
            pos = Lyrics.position();
        }
        return compute(lc, doc, pos, motion, nowMs);
    }

    /** The pure part of {@link #now}: which words, for this doc at this position. */
    static Now compute(LyricsConfig lc, Lyrics.Doc doc, long pos, boolean motion, long nowMs) {
        java.util.List<LyricLine> lines = doc.lines();
        if (lines.isEmpty()) return null;
        int idx = LrcParser.indexAt(lines, pos);
        Now n = new Now();
        n.motion = motion;
        LyricLine curLine = null;
        long start;
        long end;
        if (idx < 0) {
            n.gap = true;
            n.cur = NOTE;
            start = 0;
            end = lines.get(0).startMs();
        } else {
            LyricLine line = lines.get(idx);
            start = line.startMs();
            end = idx + 1 < lines.size() ? lines.get(idx + 1).startMs() : start + 6000;
            if (line.blank()) {
                n.gap = true;
                n.cur = NOTE;
            } else {
                n.cur = lc.shape(line.text());
                curLine = line;
            }
        }
        for (int j = Math.max(0, idx + 1); j < lines.size(); j++) {
            if (!lines.get(j).blank()) {
                n.next = lc.shape(lines.get(j).text());
                break;
            }
        }
        long span = Math.max(1L, end - start);
        if (n.gap) {
            n.fill = Ease.clamp01((pos - start) / (float) span);
        } else {
            long sing = Math.min(span, Math.max(1000L, n.cur.length() * 70L));
            n.fill = Ease.clamp01((pos - start) * lc.sweepSpeed / (sing * 0.92f));
            if (lc.wordSync && curLine != null && curLine.hasWords()) n.fill = curLine.wordFill(pos, end);
        }

        // slide from the old line to the new one
        if (doc != lastDoc) {
            lastDoc = doc;
            lastIndex = idx;
            lastCur = n.cur;
            prevCur = "";
            changedAt = nowMs - 10_000;
        } else if (idx != lastIndex) {
            boolean near = lastIndex != Integer.MIN_VALUE && Math.abs(idx - lastIndex) <= 2;
            prevCur = near ? lastCur : "";
            lastIndex = idx;
            lastCur = n.cur;
            changedAt = nowMs;
        }
        float t = motion && lc.motion != LyricsConfig.Motion.JUMP
                ? Ease.clamp01((nowMs - changedAt) * lc.speed / 260f) : 1f;
        n.change = t;
        n.prev = t < 1f ? prevCur : "";
        return n;
    }

    // ------------------------------------------------------------ drawing

    /** How a line that is wider than its room is shown. Nothing is ever replaced by "..." unless the user chose CUT. */
    private enum Fit {
        /** The text is already the right size for the room (rows from the wrapper). */
        FIXED,
        /** Smaller first, then it scrolls along with the singing. */
        SHRINK,
        /** Normal size, scrolls along with the singing. */
        SCROLL,
        /** The user asked for "Cut with ...". */
        CUT
    }

    private static Fit fitFor(LyricsConfig lc, boolean current) {
        if (!current) return Fit.SHRINK;
        return switch (lc.cardLong) {
            case SCROLL -> Fit.SCROLL;
            case CUT -> Fit.CUT;
            default -> Fit.SHRINK; // WRAP and SHRINK: a single free row shrinks, then scrolls
        };
    }

    /**
     * Draws one lyric line of the card at (x, y), at most {@code w} wide. {@code current} = the line being sung (with
     * the sweep / glow / ... and the slide between lines); otherwise it is the quiet "next line".
     */
    public static void draw(GuiGraphics g, Font font, LyricsConfig lc, Now ly, boolean current, int x, int y, int w,
                            int hot, int cool, float alpha, boolean shadow) {
        if (w <= 8) return;
        if (!current) {
            drawText(g, font, lc, ly.next, false, x, y, w, 0f, Colors.alpha(cool, 0.8f * alpha), hot, alpha, shadow,
                    LyricsConfig.Highlight.NONE, Fit.SHRINK, 1f, ly.motion);
            return;
        }
        float t = ly.change;
        boolean sliding = t < 1f;
        if (sliding) g.enableScissor(x, y - 1, x + w, y + 10);
        if (sliding && !ly.prev.isEmpty()) {
            int dy = -Math.round(t * 6f);
            drawText(g, font, lc, ly.prev, false, x, y + dy, w, 1f, Colors.alpha(cool, (1f - t) * alpha), hot,
                    (1f - t) * alpha, shadow, LyricsConfig.Highlight.NONE, Fit.SHRINK, 1f, false);
        }
        int dy = sliding ? Math.round((1f - t) * 6f) : 0;
        float a = sliding ? Math.max(0.05f, t) * alpha : alpha;
        drawText(g, font, lc, ly.cur, true, x, y + dy, w, ly.fill, cool, hot, a, shadow, lc.highlight, fitFor(lc, true), 1f,
                ly.motion);
        if (sliding) g.disableScissor();
    }

    /** The current line on two rows at a size that fits (never below 70 %), or null when it would need more rows. */
    private static TextWrap.Fit twoRows(Font font, LyricsConfig lc, String text, int w) {
        boolean bold = lc.boldCurrent;
        boolean italic = lc.italic;
        TextWrap.Fit fit = TextWrap.fitRows(text, w, 2, 0.7f, s -> Gfx.width(font, s, bold, italic));
        return fit.rows.size() <= 2 ? fit : null;
    }

    /**
     * True when this line is wider than {@code w}, the settings want it wrapped onto two rows, and all of it fits on
     * two rows (at a slightly smaller size if need be). A line that still would not fit is shrunk and scrolled instead.
     */
    public static boolean wraps(Font font, LyricsConfig lc, Now ly, int w) {
        if (ly == null || ly.gap || lc.cardLong != LyricsConfig.CardLong.WRAP || w <= 8) return false;
        if (Gfx.width(font, ly.cur, lc.boldCurrent, lc.italic) <= w) return false;
        return twoRows(font, lc, ly.cur, w) != null;
    }

    /**
     * The current line on two rows ({@code y1} and {@code y2}), for cards that can spare the second row. The sweep runs
     * through the first row and on into the second.
     */
    public static void drawWrapped(GuiGraphics g, Font font, LyricsConfig lc, Now ly, int x, int y1, int y2, int w, int hot,
                                   int cool, float alpha, boolean shadow) {
        boolean bold = lc.boldCurrent;
        boolean italic = lc.italic;
        TextWrap.Fit fit = twoRows(font, lc, ly.cur, w);
        if (fit == null) {
            draw(g, font, lc, ly, true, x, y1, w, hot, cool, alpha, shadow);
            return;
        }
        java.util.List<String> rows = fit.rows;
        if (rows.isEmpty()) return;
        float t = ly.change;
        boolean sliding = t < 1f;
        int dy = sliding ? Math.round((1f - t) * 6f) : 0;
        float a = sliding ? Math.max(0.05f, t) * alpha : alpha;
        if (sliding) g.enableScissor(x, y1 - 1, x + w, y2 + 10);
        int total = 0;
        int[] widths = new int[rows.size()];
        for (int i = 0; i < widths.length; i++) {
            widths[i] = Math.round(Gfx.width(font, rows.get(i), bold, italic) * fit.scale);
            total += widths[i];
        }
        float done = ly.fill * total;
        float before = 0f;
        // a smaller line sits a little lower so the two rows still look evenly spaced
        int lift = fit.scale < 1f ? Math.round((1f - fit.scale) * 4f) : 0;
        for (int i = 0; i < widths.length; i++) {
            float f = widths[i] <= 0 ? 1f : Ease.clamp01((done - before) / widths[i]);
            before += widths[i];
            drawText(g, font, lc, rows.get(i), true, x, (i == 0 ? y1 : y2) + dy + lift, w, f, cool, hot, a, shadow,
                    lc.highlight, Fit.FIXED, fit.scale, ly.motion);
        }
        if (sliding) g.disableScissor();
    }

    /** Back-and-forth position for text that is wider than its room and has no singing to follow (the "next" line). */
    static float glide(float range, long ms) {
        if (range <= 0f) return 0f;
        float wait = 1.4f;
        float move = Math.max(0.6f, range / 18f);
        float cycle = 2f * (wait + move);
        float t = (ms / 1000f) % cycle;
        if (t < wait) return 0f;
        t -= wait;
        if (t < move) return range * t / move;
        t -= move;
        if (t < wait) return range;
        t -= wait;
        return range * (1f - Math.min(1f, t / move));
    }

    private static void drawText(GuiGraphics g, Font font, LyricsConfig lc, String text, boolean current, int x, int y, int w,
                                 float fill, int base, int hot, float alpha, boolean shadow, LyricsConfig.Highlight hl,
                                 Fit fit, float fixedScale, boolean motion) {
        if (text == null || text.isEmpty()) return;
        boolean bold = current && lc.boldCurrent;
        boolean italic = lc.italic;
        int tw = Gfx.width(font, text, bold, italic);
        float scale = fixedScale;
        float offset = 0f;
        boolean scrolling = false;
        String shown = text;
        if (fit != Fit.FIXED && tw * scale > w) {
            switch (fit) {
                case CUT -> shown = Gfx.fit(font, text, w, bold, italic);
                case SCROLL -> scrolling = true;
                default -> {
                    scale = Math.max(0.62f, w / (float) tw);
                    scrolling = tw * scale > w + 0.5f;
                }
            }
        }
        int sw = Math.round(Gfx.width(font, shown, bold, italic) * scale);
        if (scrolling) {
            float range = Math.max(0f, sw - w);
            if (current) {
                // stays put until the sung part reaches the right edge, then follows it; the whole line is read by the end
                offset = Math.max(0f, Math.min(range, fill * sw + 12f - w));
            } else {
                offset = motion ? glide(range, System.currentTimeMillis()) : 0f;
            }
        }
        boolean typed = current && hl == LyricsConfig.Highlight.TYPE;
        boolean sweep = current && (hl == LyricsConfig.Highlight.FILL || hl == LyricsConfig.Highlight.UNDERLINE || typed);
        int color = current ? (hl == LyricsConfig.Highlight.FILL ? base : hot) : base;
        if (current && !sweep) color = hot;
        float filled = fill * sw;

        boolean clip = scrolling;
        if (clip) g.enableScissor(x, y - 2, x + w, y + 10);
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate((float) x - offset, (float) y);
        if (scale != 1f) pose.scale(scale, scale);
        int rawFilled = Math.round(filled / scale);
        if (current && hl == LyricsConfig.Highlight.GLOW) {
            int glow = Colors.alpha(hot, 0.20f * alpha);
            for (int[] d : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                Gfx.drawStyled(g, font, shown, d[0], d[1], glow, bold, italic, false);
            }
        }
        if (lc.outline) {
            int edge = Colors.alpha(lc.outlineColor, 0.75f * alpha);
            Gfx.drawStyled(g, font, shown, -1, 0, edge, bold, italic, false);
            Gfx.drawStyled(g, font, shown, 1, 0, edge, bold, italic, false);
            Gfx.drawStyled(g, font, shown, 0, -1, edge, bold, italic, false);
            Gfx.drawStyled(g, font, shown, 0, 1, edge, bold, italic, false);
        }
        if (typed) {
            Gfx.drawStyled(g, font, shown, 0, 0, Colors.alpha(hot, 0.14f * alpha), bold, italic, false);
            if (rawFilled > 0) {
                g.enableScissor(0, -2, rawFilled, 11);
                Gfx.drawStyled(g, font, shown, 0, 0, Colors.alpha(hot, alpha), bold, italic, shadow);
                g.disableScissor();
            }
        } else {
            Gfx.drawStyled(g, font, shown, 0, 0, Colors.alpha(color, alpha), bold, italic, shadow);
            if (sweep && hl == LyricsConfig.Highlight.UNDERLINE) {
                int full = Math.round(Gfx.width(font, shown, bold, italic));
                g.fill(0, 9, full, 10, Colors.alpha(hot, 0.22f * alpha));
                if (rawFilled > 0) g.fill(0, 9, Math.min(full, rawFilled), 10, Colors.alpha(hot, alpha));
            } else if (sweep && rawFilled > 0) {
                g.enableScissor(0, -2, rawFilled, 11);
                Gfx.drawStyled(g, font, shown, 0, 0, Colors.alpha(hot, alpha), bold, italic, shadow);
                g.disableScissor();
            }
        }
        pose.popMatrix();
        if (clip) g.disableScissor();
    }
}
