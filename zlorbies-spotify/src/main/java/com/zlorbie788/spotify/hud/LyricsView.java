package com.zlorbie788.spotify.hud;

import com.zlorbie788.spotify.config.LyricsConfig;
import com.zlorbie788.spotify.lyrics.LrcParser;
import com.zlorbie788.spotify.lyrics.LyricLine;
import com.zlorbie788.spotify.lyrics.Lyrics;
import com.zlorbie788.spotify.util.Colors;
import com.zlorbie788.spotify.util.Ease;
import com.zlorbie788.spotify.util.TextWrap;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a few lines of time-synced lyrics inside a box: the line being sung in the middle, lines before and after
 * it fading out. One instance keeps the scroll position and per-line animation of one place on the screen
 * (the HUD has one; the settings preview shares it). Drawing happens in the box's own coordinates: the caller
 * has already moved and scaled the pose, so (0, 0) is the box's top-left corner.
 */
public final class LyricsView {

    /** One line of the song, prepared for drawing. */
    private static final class Row {
        long start;
        long end;
        boolean dots;
        String text = "";
        List<String> parts = new ArrayList<>();
        int[] partWidth = new int[0];
        int totalWidth;
        float shrink = 1f;
        LyricLine src;
    }

    private List<Row> rows = new ArrayList<>();
    private Object builtFor;
    private String builtWith = "";
    private float[] emph = new float[0];
    private float[] vis = new float[0];
    private float scroll = -1f;
    private long lastNanos = System.nanoTime();
    private int lastCur = -2;
    private long curSince;
    private float[] hitTop = new float[0];
    private float[] hitBottom = new float[0];

    /** Forget the animation state (a new song, or a jump in the song). */
    public void reset() {
        builtFor = null;
        scroll = -1f;
        lastCur = -2;
    }

    // ------------------------------------------------------------ preparing the rows

    private void build(Font font, LyricsConfig lc, Lyrics.Doc doc, long durMs, int boxW) {
        String with = lc.romanize + "|" + lc.overflow + "|" + lc.currentScale + "|" + lc.boldCurrent + "|" + lc.interlude + "|" + boxW + "|" + durMs
                + "|" + lc.wrapRows + "|" + lc.padX + "|" + lc.textCase + "|" + lc.italic + "|" + Gfx.font();
        if (builtFor == doc && with.equals(builtWith)) return;
        builtFor = doc;
        builtWith = with;
        rows = new ArrayList<>();
        List<LyricLine> lines = doc.lines();
        int limit = Math.max(40, Math.round((boxW - 2 * LyricsLayout.padX(lc)) / lc.currentScale));
        int maxRows = lc.overflow == LyricsConfig.Overflow.WRAP ? lc.wrapRows : 1;

        if (!lines.isEmpty() && lc.interlude && lines.get(0).startMs() >= 4500 && !lines.get(0).blank()) {
            Row intro = new Row();
            intro.start = 0;
            intro.end = lines.get(0).startMs();
            intro.dots = true;
            rows.add(intro);
        }
        for (int i = 0; i < lines.size(); i++) {
            LyricLine line = lines.get(i);
            long next = i + 1 < lines.size() ? lines.get(i + 1).startMs() : (durMs > line.startMs() ? durMs : line.startMs() + 6000);
            if (line.blank()) {
                if (lc.interlude && next - line.startMs() >= 2500 && !rows.isEmpty()) {
                    Row dots = new Row();
                    dots.start = line.startMs();
                    dots.end = next;
                    dots.dots = true;
                    rows.add(dots);
                }
                continue;
            }
            Row row = new Row();
            row.start = line.startMs();
            row.end = next;
            row.text = lc.shape(line.text());
            row.src = line;
            layoutText(font, lc, row, limit, maxRows);
            rows.add(row);
        }
        emph = new float[rows.size()];
        vis = new float[rows.size()];
        scroll = -1f;
        lastCur = -2;
    }

    private static void layoutText(Font font, LyricsConfig lc, Row row, int limit, int maxRows) {
        boolean bold = lc.boldCurrent;
        boolean italic = lc.italic;
        java.util.function.ToIntFunction<String> w = s -> Gfx.width(font, s, bold, italic);
        switch (lc.overflow) {
            case SHRINK -> {
                // smaller text first; when even that is too wide the rest goes onto a second row (never "...")
                int full = w.applyAsInt(row.text);
                row.shrink = full <= limit ? 1f : Math.max(0.62f, limit / (float) full);
                if (full * row.shrink > limit + 0.5f) {
                    row.parts.addAll(TextWrap.wrapAll(row.text, Math.round(limit / row.shrink), w));
                } else {
                    row.parts.add(row.text);
                }
            }
            case CUT -> row.parts.add(Gfx.fit(font, row.text, limit, bold, italic));
            default -> {
                // wrap onto up to maxRows rows; a longer line gets a little smaller, and every word still shows
                TextWrap.Fit fit = TextWrap.fitRows(row.text, limit, maxRows, 0.72f, w);
                row.shrink = fit.scale;
                row.parts.addAll(fit.rows);
            }
        }
        if (row.parts.isEmpty()) row.parts.add(row.text);
        row.partWidth = new int[row.parts.size()];
        for (int i = 0; i < row.partWidth.length; i++) {
            row.partWidth[i] = w.applyAsInt(row.parts.get(i));
            row.totalWidth += row.partWidth[i];
        }
    }

    // ------------------------------------------------------------ colours

    /** The colour for a tone at a given moment. {@code index} offsets the rainbow so neighbouring lines differ. */
    public static int tone(LyricsConfig.Tone tone, int custom, int accent, int light, int index) {
        switch (tone) {
            case LIGHT:
                return light;
            case WHITE:
                return Colors.WHITE;
            case CUSTOM:
                return Colors.opaque(custom);
            case RAINBOW:
                return Colors.rainbow((System.nanoTime() % 9_000_000_000L) / 9_000_000_000f - index * 0.06f);
            default:
                return accent;
        }
    }

    // ------------------------------------------------------------ drawing

    /**
     * @param pos      playback position in ms, sync offset already added
     * @param durMs    length of the song (0 if unknown)
     * @param accent   the player's accent colour
     * @param light    a soft light version of the album colour
     * @param motion   false = instant everything (reduced motion)
     */
    public void draw(GuiGraphics g, Font font, LyricsConfig lc, Lyrics.Doc doc, long pos, long durMs, int accent,
                     int light, boolean playing, float alpha, int boxW, int boxH, boolean motion) {
        // the lyrics have their own font; "same as player" keeps whatever font is active
        com.zlorbie788.spotify.config.UiFont oldFont = Gfx.font();
        int oldShift = Gfx.fontShift();
        if (lc.font != null) Gfx.useFont(lc.font, com.zlorbie788.spotify.config.ConfigIo.get().fontShift);
        try {
            drawRows(g, font, lc, doc, pos, durMs, accent, light, playing, alpha, boxW, boxH, motion);
        } finally {
            Gfx.useFont(oldFont, oldShift);
        }
    }

    private void drawRows(GuiGraphics g, Font font, LyricsConfig lc, Lyrics.Doc doc, long pos, long durMs, int accent,
                          int light, boolean playing, float alpha, int boxW, int boxH, boolean motion) {
        build(font, lc, doc, durMs, boxW);
        long nanos = System.nanoTime();
        float dt = Math.min(0.1f, (nanos - lastNanos) / 1_000_000_000f);
        lastNanos = nanos;
        if (rows.isEmpty()) return;

        int cur = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).start <= pos) cur = i;
            else break;
        }
        boolean animate = motion && lc.motion != LyricsConfig.Motion.JUMP;
        if (cur != lastCur) {
            if (lastCur != -2 && Math.abs(cur - lastCur) > 3) scroll = cur;
            lastCur = cur;
            curSince = System.currentTimeMillis();
        }
        float speed = lc.speed;
        if (animate && lc.motion == LyricsConfig.Motion.SCROLL) {
            scroll = Ease.approach(scroll, cur, 9f * speed, dt);
            if (Math.abs(scroll - cur) < 0.003f) scroll = cur;
        } else {
            scroll = cur;
        }

        final int unit = LyricsLayout.unit(lc);
        final int gap = lc.lineGap;
        final float cs = lc.currentScale;
        final int n = rows.size();

        // Emphasis (how "current" each line is) and its size
        float[] size = new float[n];
        float[] height = new float[n];
        for (int i = 0; i < n; i++) {
            float target = i == cur ? 1f : 0f;
            if (animate) emph[i] = Ease.approach(emph[i], target, 10f * speed, dt);
            else emph[i] = target;
            if (Math.abs(emph[i] - target) < 0.01f) emph[i] = target;
            float e = emph[i];
            if (lc.highlight == LyricsConfig.Highlight.POP && animate) e = Ease.outBack(e);
            size[i] = Ease.lerp(lc.otherScale, cs, e) * rows.get(i).shrink;
            height[i] = rows.get(i).parts.size() * LyricsLayout.ROW * size[i];
            if (rows.get(i).dots) height[i] = LyricsLayout.ROW * Ease.lerp(lc.otherScale, cs, emph[i]);
        }

        // Centre of every line in list space
        float[] centre = new float[n];
        float top = 0f;
        for (int i = 0; i < n; i++) {
            centre[i] = top + height[i] / 2f;
            top += height[i] + gap;
        }
        float viewpoint = viewpoint(centre, unit + gap, scroll);
        int before = lc.linesBefore;
        int after = lc.linesAfter;
        float anchor = LyricsLayout.padY(lc) + before * (unit + gap) + Math.round(unit * cs) / 2f;

        if (hitTop.length != n) {
            hitTop = new float[n];
            hitBottom = new float[n];
        }
        for (int i = 0; i < n; i++) {
            float dy = centre[i] - viewpoint;
            hitTop[i] = anchor + dy - height[i] / 2f - gap / 2f;
            hitBottom[i] = anchor + dy + height[i] / 2f + gap / 2f;
        }
        g.enableScissor(0, 0, boxW, boxH);
        float slot = unit + gap;
        for (int i = 0; i < n; i++) {
            float dy = centre[i] - viewpoint;
            float units = dy / slot;
            float span = units < 0 ? before : after;
            float outside = Math.abs(units) - span;
            if (outside > 1.2f) continue;
            float a = 1f;
            float reach = Math.max(1f, Math.max(before, after) + 0.6f);
            a -= lc.fade * Math.min(1f, Math.abs(units) / reach) * 0.92f;
            if (units < 0f) a *= 1f - lc.pastDim * 0.92f;
            if (span <= 0f && Math.abs(units) > 0.35f) a = 0f;
            else if (outside > 0f) a *= Math.max(0f, 1f - outside / 1.2f);
            if (lc.motion == LyricsConfig.Motion.FADE && animate) {
                vis[i] = Ease.approach(vis[i], a, 12f * speed, dt);
                a = vis[i];
            } else {
                vis[i] = a;
            }
            a *= alpha;
            if (a < 0.02f) continue;
            drawRow(g, font, lc, rows.get(i), i, i == cur, emph[i], size[i], anchor + dy - height[i] / 2f, boxW,
                    accent, light, pos, a, playing);
        }
        g.disableScissor();
    }

    private static float viewpoint(float[] centre, float slot, float scroll) {
        int n = centre.length;
        int i0 = (int) Math.floor(scroll);
        float f = scroll - i0;
        float a = at(centre, i0, slot);
        float b = at(centre, i0 + 1, slot);
        return a + (b - a) * f;
    }

    private static float at(float[] centre, int i, float slot) {
        int n = centre.length;
        if (i < 0) return centre[0] + i * slot;
        if (i >= n) return centre[n - 1];
        return centre[i];
    }

    private void drawRow(GuiGraphics g, Font font, LyricsConfig lc, Row row, int index, boolean current, float emphasis,
                         float size, float top, int boxW, int accent, int light, long pos, float alpha, boolean playing) {
        int hot = tone(lc.currentTone, lc.currentColor, accent, light, index);
        int cool = tone(lc.otherTone, lc.otherColor, accent, light, index);
        int base = Colors.mix(cool, hot, emphasis);
        if (row.dots) {
            drawDots(g, lc, row, top, boxW, size, hot, cool, pos, alpha, emphasis);
            return;
        }
        boolean bold = lc.boldCurrent && emphasis > 0.5f;
        float fill = 0f;
        LyricsConfig.Highlight hl = lc.highlight;
        boolean sweeping = current && (hl == LyricsConfig.Highlight.FILL || hl == LyricsConfig.Highlight.UNDERLINE
                || hl == LyricsConfig.Highlight.TYPE);
        if (sweeping) {
            long gap = Math.max(1L, row.end - row.start);
            long sing = Math.min(gap, Math.max(1000L, row.text.length() * 70L));
            fill = Ease.clamp01((pos - row.start) * lc.sweepSpeed / (sing * 0.92f));
            if (lc.wordSync && row.src != null && row.src.hasWords()) fill = row.src.wordFill(pos, row.end);
            if (hl == LyricsConfig.Highlight.FILL) base = cool;
            else base = hot;
        }
        boolean italic = lc.italic;
        float filledPx = fill * row.totalWidth;
        float usedBefore = 0f;
        for (int p = 0; p < row.parts.size(); p++) {
            String text = row.parts.get(p);
            int w = row.partWidth[p];
            float drawW = w * size;
            float x;
            switch (lc.align) {
                case LEFT -> x = LyricsLayout.padX(lc);
                case RIGHT -> x = boxW - LyricsLayout.padX(lc) - drawW;
                default -> x = (boxW - drawW) / 2f;
            }
            float y = top + p * LyricsLayout.ROW * size;
            var pose = g.pose();
            pose.pushMatrix();
            pose.translate(x, y);
            pose.scale(size, size);
            if (current && lc.highlight == LyricsConfig.Highlight.GLOW) {
                int glow = Colors.alpha(hot, 0.20f * alpha);
                for (int[] d : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}, {-1, -1}, {1, 1}, {-1, 1}, {1, -1}}) {
                    glyphs(g, font, text, d[0], d[1], glow, bold, italic, false);
                }
                glyphs(g, font, text, 0, 0, Colors.alpha(hot, 0.34f * alpha), bold, italic, false);
            }
            if (lc.outline) {
                int edge = Colors.alpha(lc.outlineColor, 0.75f * alpha);
                glyphs(g, font, text, -1, 0, edge, bold, italic, false);
                glyphs(g, font, text, 1, 0, edge, bold, italic, false);
                glyphs(g, font, text, 0, -1, edge, bold, italic, false);
                glyphs(g, font, text, 0, 1, edge, bold, italic, false);
            }
            float part = sweeping ? Math.max(0f, Math.min(w, filledPx - usedBefore)) : 0f;
            if (sweeping && hl == LyricsConfig.Highlight.TYPE) {
                // typed out: only the part already sung is there (a faint ghost keeps the line's shape)
                glyphs(g, font, text, 0, 0, Colors.alpha(base, 0.14f * alpha), bold, italic, false);
                if (part > 0f) {
                    g.enableScissor(0, -2, Math.round(part), LyricsLayout.ROW + 2);
                    glyphs(g, font, text, 0, 0, Colors.alpha(base, alpha), bold, italic, lc.shadow);
                    g.disableScissor();
                }
            } else {
                glyphs(g, font, text, 0, 0, Colors.alpha(base, alpha), bold, italic, lc.shadow);
                if (sweeping && hl == LyricsConfig.Highlight.UNDERLINE) {
                    g.fill(0, LyricsLayout.ROW, w, LyricsLayout.ROW + 1, Colors.alpha(hot, 0.22f * alpha));
                    if (part > 0f) g.fill(0, LyricsLayout.ROW, Math.round(part), LyricsLayout.ROW + 1, Colors.alpha(hot, alpha));
                } else if (sweeping && part > 0f) {
                    g.enableScissor(0, -2, Math.round(part), LyricsLayout.ROW + 2);
                    glyphs(g, font, text, 0, 0, Colors.alpha(hot, alpha), bold, italic, lc.shadow);
                    g.disableScissor();
                }
            }
            pose.popMatrix();
            usedBefore += w;
        }
    }

    private static void glyphs(GuiGraphics g, Font font, String text, int dx, int dy, int color, boolean bold, boolean italic,
                               boolean shadow) {
        Gfx.drawStyled(g, font, text, dx, dy, color, bold, italic, shadow);
    }

    /** Three dots that light up one after the other during an instrumental break. */
    private void drawDots(GuiGraphics g, LyricsConfig lc, Row row, float top, int boxW, float size, int hot, int cool,
                          long pos, float alpha, float emphasis) {
        float progress = Ease.clamp01((pos - row.start) / (float) Math.max(1L, row.end - row.start));
        int dot = Math.max(3, Math.round(4 * size));
        int pitch = dot * 2 + 1;
        int total = pitch * 3 - dot - 1;
        float x0;
        switch (lc.align) {
            case LEFT -> x0 = LyricsLayout.padX(lc);
            case RIGHT -> x0 = boxW - LyricsLayout.padX(lc) - total;
            default -> x0 = (boxW - total) / 2f;
        }
        float y = top + (LyricsLayout.ROW * size - dot) / 2f;
        for (int k = 0; k < 3; k++) {
            float lit = Ease.clamp01(progress * 3.2f - k);
            if (emphasis < 0.5f) lit = progress > (k + 1) / 3f ? 1f : 0f;
            int color = Colors.mix(cool, hot, lit);
            float breathe = emphasis > 0.5f ? 0.85f + 0.15f * (float) Math.sin(System.nanoTime() / 4.0e8 + k) : 1f;
            int d = Math.round(dot * breathe);
            int xx = Math.round(x0 + k * pitch);
            int yy = Math.round(y + (dot - d) / 2f);
            g.fill(xx, yy, xx + d, yy + d, Colors.alpha(color, alpha * (0.35f + 0.65f * lit)));
        }
    }

    /**
     * Start time (ms) of the line drawn at this height of the box, or -1 when there is none (a gap, the dots of
     * an interlude, or outside the box). Used by "click a line to jump there".
     */
    public long startAt(float y) {
        for (int i = 0; i < rows.size() && i < hitTop.length; i++) {
            if (y >= hitTop[i] && y < hitBottom[i]) return rows.get(i).dots ? -1 : rows.get(i).start;
        }
        return -1;
    }

    /** Index of the row being sung, as the view sees it (used by tests). */
    int debugRows() {
        return rows.size();
    }

    /** Which lyric line index is shown as current for this position (re-exported for the screens). */
    public static int lineAt(Lyrics.Doc doc, long pos) {
        return LrcParser.indexAt(doc.lines(), pos);
    }
}
