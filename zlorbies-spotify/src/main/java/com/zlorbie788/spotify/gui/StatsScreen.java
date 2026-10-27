package com.zlorbie788.spotify.gui;

import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.config.SpotifyConfig;
import com.zlorbie788.spotify.gui.widgets.Chrome;
import com.zlorbie788.spotify.gui.widgets.StyledButton;
import com.zlorbie788.spotify.hud.Anim;
import com.zlorbie788.spotify.hud.Gfx;
import com.zlorbie788.spotify.hud.PlayerHud;
import com.zlorbie788.spotify.hud.Theme;
import com.zlorbie788.spotify.stats.Achievements;
import com.zlorbie788.spotify.stats.Stats;
import com.zlorbie788.spotify.util.Colors;
import com.zlorbie788.spotify.util.Ease;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Listening statistics: this session and all time (since the mod was first installed), the last two weeks as a small
 * chart, and your top songs and artists. Everything is stored on this PC only.
 */
public class StatsScreen extends Screen implements PlayerHud.HidesHud {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final int DAYS = 14;

    private final Screen parent;
    private int px;
    private int py;
    private int pw;
    private int ph;
    private boolean confirmReset;
    private static int lastTab;
    private int tab = lastTab;
    private int recapYear = -1;
    private String note = "";
    private long noteAt;
    private static final String[] TAB_NAMES = {"Overview", "Achievements", "Recap"};

    /** The tab the screen opens on: 0 overview, 1 achievements, 2 recap. */
    public static void showTab(int index) {
        lastTab = Math.max(0, Math.min(TAB_NAMES.length - 1, index));
    }

    public StatsScreen(Screen parent) {
        super(Component.literal("Listening stats"));
        this.parent = parent;
    }

    private static SpotifyConfig cfg() {
        return ConfigIo.get();
    }

    @Override
    protected void init() {
        pw = Math.min(width - 10, 440);
        ph = Math.min(height - 6, 286);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        int accent = PlayerHud.accent(cfg());
        addRenderableWidget(new StyledButton(px + pw - 24, py + 5, 16, 16, Component.literal("x"), b -> onClose())
                .accent(Colors.RED).tip("Close (Esc)"));
        int y = py + ph - 19;
        int right = px + pw - 12;
        addRenderableWidget(Ui.button(right - 54, y, 54, 14, "Done", b -> onClose()).accent(Colors.MINT).primary()
                .tip("Close"));
        addRenderableWidget(Ui.button(right - 54 - 4 - 84, y, 84, 14, confirmReset ? "Sure?" : "Reset stats", b -> {
            if (!confirmReset) {
                confirmReset = true;
                b.setMessage(Component.literal("Sure?"));
                return;
            }
            confirmReset = false;
            Stats.reset();
            Achievements.reset();
            rebuildWidgets();
        }).accent(Colors.RED).tip("Forget all statistics, this session and all time. Click twice to confirm."));
        addRenderableWidget(Ui.toggle(px + 12, y, 150, 14, "Count my listening", () -> cfg().trackStats, v -> {
            cfg().trackStats = v;
            ConfigIo.save();
        }).accent(accent).tip("Off = nothing is counted from now on (what is already counted stays until you reset)."));

        for (int i = 0; i < TAB_NAMES.length; i++) {
            final int index = i;
            StyledButton b = Ui.button(px + 12 + i * 88, py + 24, 84, 14, TAB_NAMES[i], x -> {
                tab = index;
                lastTab = index;
                rebuildWidgets();
            }).accent(accent);
            if (i == tab) b.primary();
            addRenderableWidget(b);
        }
        if (tab == 2) {
            List<Integer> years = Stats.years();
            int year = recapYear();
            int idx = years.indexOf(year);
            StyledButton prev = Ui.button(px + pw - 12 - 36, py + 24, 16, 14, "<", x -> {
                recapYear = years.get(Math.max(0, years.indexOf(recapYear()) - 1));
                rebuildWidgets();
            }).accent(accent);
            prev.active = idx > 0;
            addRenderableWidget(prev);
            StyledButton next = Ui.button(px + pw - 12 - 18, py + 24, 16, 14, ">", x -> {
                recapYear = years.get(Math.min(years.size() - 1, years.indexOf(recapYear()) + 1));
                rebuildWidgets();
            }).accent(accent);
            next.active = idx >= 0 && idx < years.size() - 1;
            addRenderableWidget(next);
            addRenderableWidget(Ui.button(right - 54 - 4 - 84 - 4 - 88, y, 88, 14, "Copy recap", x -> {
                minecraft.keyboardHandler.setClipboard(recapText(recapYear()));
                note = "Recap copied: paste it anywhere";
                noteAt = System.nanoTime();
            }).accent(accent).tip("Copies your recap as one line of text to share."));
        }
    }

    private int recapYear() {
        List<Integer> years = Stats.years();
        if (recapYear > 0 && years.contains(recapYear)) return recapYear;
        int now = LocalDate.now().getYear();
        if (years.isEmpty() || years.contains(now)) return now;
        return years.get(years.size() - 1);
    }

    private static String recapText(int year) {
        StringBuilder sb = new StringBuilder("My " + year + " in music (Spotify Integration Mod): ");
        sb.append(Stats.duration(Stats.yearMs(year))).append(" listened on ").append(Stats.yearDays(year)).append(" days");
        int streak = Stats.bestStreakIn(year);
        if (streak > 1) sb.append(", best streak ").append(streak).append(" days");
        List<Stats.SongStat> songs = Stats.topSongs(1);
        if (!songs.isEmpty()) sb.append(". Top song: ").append(songs.get(0).title())
                .append(songs.get(0).artist().isEmpty() ? "" : " - " + songs.get(0).artist());
        List<Stats.ArtistStat> artists = Stats.topArtists(1);
        if (!artists.isEmpty()) sb.append(". Top artist: ").append(artists.get(0).name());
        return sb.append('.').toString();
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0x99050810);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Anim.frame();
        var oldFont = Gfx.font();
        int oldShift = Gfx.fontShift();
        Gfx.useFont(cfg().uiFont, cfg().fontShift);
        try {
            renderAll(g, mouseX, mouseY, partialTick);
        } finally {
            Gfx.useFont(oldFont, oldShift);
        }
    }

    private void renderAll(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Font f = font;
        SpotifyConfig cfg = cfg();
        float intro = Anim.smooth(this, "enter", 0f, 1f, 7f);
        float grow = cfg.motion() ? Ease.outCubic(intro) : 1f;
        int accent = PlayerHud.accent(cfg);
        PlayerHud.tickState(cfg);
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(0f, (1f - grow) * 8f);
        Chrome.body(g, px, py, pw, ph, Colors.alpha(Colors.mix(Theme.surface(), accent, 0.10f), 0.98f),
                Colors.alpha(Theme.surface(), 0.98f), Theme.highContrast() ? Colors.WHITE : Colors.LINE);
        if (!Theme.highContrast()) Gfx.halo(g, px, py, pw, ph, accent, 0.35f);
        Gfx.accentLine(g, px + 1, py, pw - 2, 3, accent, cfg.accent == SpotifyConfig.Accent.RAINBOW,
                PlayerHud.linePhase(cfg, cfg.accent == SpotifyConfig.Accent.RAINBOW), cfg.motion(), 1f, 0f);
        Gfx.textBold(g, f, "Listening stats", px + 12, py + 9, accent, 1f, true);
        Gfx.text(g, f, "Spotify Integration Mod", px + 12 + Gfx.width(f, "Listening stats", true) + 8, py + 9, Theme.dim(), 1f, false);

        int gap = 6;
        int x0 = px + 12;
        int w = pw - 24;
        int half = (w - gap) / 2;
        int y = py + 44;
        switch (tab) {
            case 1 -> drawAchievements(g, f, x0, y, w, accent, grow);
            case 2 -> drawRecap(g, f, x0, y, w, accent, grow);
            default -> drawOverview(g, f, x0, y, w, half, gap, accent, grow);
        }
        super.render(g, mouseX, mouseY, partialTick);
        if (!note.isEmpty() && (System.nanoTime() - noteAt) / 1_000_000L < 2500L) {
            Gfx.textRight(g, f, Gfx.fit(f, note, pw / 2), px + pw - 32, py + 10, Theme.good(), 1f, false);
        }
        pose.popMatrix();
    }

    private void drawOverview(GuiGraphics g, Font f, int x0, int y, int w, int half, int gap, int accent, float grow) {
        int topH = 62;
        card(g, f, x0, y, half, topH, "THIS SESSION", "this game", accent);
        bigNumber(g, f, x0 + 10, y + 22, Stats.duration(Stats.sessionMs()), "listened", accent, grow);
        smallLine(g, f, x0 + 10, y + 46, Stats.sessionPlays() + (Stats.sessionPlays() == 1 ? " song" : " songs") + " played", half - 20);

        card(g, f, x0 + half + gap, y, half, topH, "ALL TIME", since(), accent);
        bigNumber(g, f, x0 + half + gap + 10, y + 22, Stats.duration(Stats.totalMs()), "listened", accent, grow);
        smallLine(g, f, x0 + half + gap + 10, y + 46, Stats.totalPlays() + (Stats.totalPlays() == 1 ? " song" : " songs")
                + "  ·  " + Stats.daysListened() + (Stats.daysListened() == 1 ? " day" : " days")
                + (Stats.streak() > 1 ? "  ·  " + Stats.streak() + " d streak" : ""), half - 20);
        y += topH + gap;

        // the chart only shows when it really fits above the footer buttons (small windows skip it)
        int chartH = Math.min(64, py + ph - 24 - y);
        if (chartH >= 48) {
            card(g, f, x0, y, w, chartH, "LAST " + DAYS + " DAYS", "best day " + Stats.duration(Stats.bestDayMs())
                    + "  ·  today " + Stats.duration(Stats.todayMs()), accent);
            chart(g, f, x0 + 8, y + 20, w - 16, chartH - 24, accent, grow);
            y += chartH + gap;
        }

        int listH = py + ph - 24 - y;
        if (listH >= 40) {
            card(g, f, x0, y, half, listH, "TOP SONGS", "by plays", accent);
            card(g, f, x0 + half + gap, y, half, listH, "TOP ARTISTS", "by time", accent);
            int rows = Math.max(1, (listH - 22) / 11);
            List<Stats.SongStat> songs = Stats.topSongs(rows);
            List<Stats.ArtistStat> artists = Stats.topArtists(rows);
            if (songs.isEmpty()) {
                Ui.hint(g, f, "Play something: it shows up here.", x0 + 10, y + 22, half - 20);
            }
            for (int i = 0; i < songs.size(); i++) {
                Stats.SongStat s = songs.get(i);
                int ry = y + 22 + i * 11;
                rank(g, f, i + 1, x0 + 8, ry, accent);
                String right = s.plays() + "x";
                int rw = Gfx.width(f, right);
                Gfx.text(g, f, Gfx.fit(f, s.title() + (s.artist().isEmpty() ? "" : " · " + s.artist()), half - 40 - rw),
                        x0 + 22, ry, Theme.text(), 1f, false);
                Gfx.text(g, f, right, x0 + half - 10 - rw, ry, Theme.dim(), 1f, false);
            }
            if (artists.isEmpty()) {
                Ui.hint(g, f, "Your favourite artists show up here.", x0 + half + gap + 10, y + 22, half - 20);
            }
            for (int i = 0; i < artists.size(); i++) {
                Stats.ArtistStat a = artists.get(i);
                int ry = y + 22 + i * 11;
                int ax = x0 + half + gap;
                rank(g, f, i + 1, ax + 8, ry, accent);
                String right = Stats.duration(a.ms());
                int rw = Gfx.width(f, right);
                Gfx.text(g, f, Gfx.fit(f, a.name(), half - 40 - rw), ax + 22, ry, Theme.text(), 1f, false);
                Gfx.text(g, f, right, ax + half - 10 - rw, ry, Theme.dim(), 1f, false);
            }
        }
    }


    // ------------------------------------------------------------ achievements

    private void drawAchievements(GuiGraphics g, Font f, int x0, int y, int w, int accent, float grow) {
        var goals = Achievements.goals();
        int done = Achievements.unlockedCount();
        Gfx.text(g, f, done + " of " + goals.size() + " reached", x0 + 2, y - 1, Theme.soft(), 1f, false);
        int areaTop = y + 12;
        int areaH = py + ph - 24 - areaTop;
        int cols = 2;
        int gap = 6;
        int cw = (w - gap) / cols;
        int rows = (goals.size() + cols - 1) / cols;
        int ch = Math.max(22, Math.min(32, (areaH - (rows - 1) * 4) / rows));
        for (int i = 0; i < goals.size(); i++) {
            var goal = goals.get(i);
            int cx = x0 + (i % cols) * (cw + gap);
            int cy = areaTop + (i / cols) * (ch + 4);
            boolean reached = Achievements.unlocked(goal.id()) || goal.reached();
            int c = reached ? Theme.accent(Colors.GOLD) : Theme.dim();
            Chrome.body(g, cx, cy, cw, ch, Colors.alpha(reached ? Colors.mix(Theme.raised(), Colors.GOLD, 0.10f) : Theme.well(), 0.85f),
                    Colors.alpha(c, reached ? 0.55f : 0.25f));
            // the badge: a gold disc with a tick, or a grey one
            int bx = cx + 14;
            int by = cy + ch / 2;
            Gfx.disc(g, bx, by, 8, Colors.alpha(c, reached ? 0.28f : 0.14f));
            Gfx.disc(g, bx, by, 6, Colors.opaque(reached ? Colors.GOLD : Colors.mix(Theme.well(), Theme.dim(), 0.5f)));
            if (reached) {
                g.fill(bx - 3, by, bx - 2, by + 1, 0xFF1A1405);
                g.fill(bx - 2, by + 1, bx - 1, by + 2, 0xFF1A1405);
                g.fill(bx - 1, by + 2, bx, by + 3, 0xFF1A1405);
                g.fill(bx, by + 1, bx + 1, by + 2, 0xFF1A1405);
                g.fill(bx + 1, by, bx + 2, by + 1, 0xFF1A1405);
                g.fill(bx + 2, by - 1, bx + 3, by, 0xFF1A1405);
                g.fill(bx + 3, by - 2, bx + 4, by - 1, 0xFF1A1405);
            } else {
                g.fill(bx - 2, by - 1, bx + 3, by + 3, Colors.opaque(Theme.dim()));
                g.fill(bx - 1, by - 3, bx + 2, by - 1, Colors.opaque(Theme.dim()));
            }
            String right = reached ? "" : goal.progress() + " / " + goal.target();
            int rw = right.isEmpty() ? 0 : Gfx.width(f, right) + 6;
            Gfx.drawStyled(g, f, Gfx.fit(f, goal.title(), cw - 36 - rw, true, false), cx + 28, cy + 4,
                    Colors.opaque(reached ? Theme.text() : Theme.soft()), true, false, false);
            if (!right.isEmpty()) Gfx.text(g, f, right, cx + cw - 6 - Gfx.width(f, right), cy + 4, Theme.dim(), 1f, false);
            Gfx.text(g, f, Gfx.fit(f, goal.text(), cw - 36), cx + 28, cy + 14, Theme.dim(), 1f, false);
            if (!reached && ch >= 26) {
                float frac = goal.progress() / (float) goal.target() * grow;
                g.fill(cx + 28, cy + ch - 5, cx + cw - 6, cy + ch - 3, Colors.alpha(Theme.line(), 0.7f));
                g.fill(cx + 28, cy + ch - 5, cx + 28 + Math.round((cw - 34) * frac), cy + ch - 3, Colors.alpha(accent, 0.9f));
            }
        }
    }

    // ------------------------------------------------------------ yearly recap

    private void drawRecap(GuiGraphics g, Font f, int x0, int y, int w, int accent, float grow) {
        int year = recapYear();
        int h = py + ph - 24 - y;
        Ui.card(g, f, x0, y, w, h, year + " IN MUSIC", Stats.yearDays(year) == 0 ? "nothing yet" : "your recap", Colors.GOLD);
        if (Stats.yearDays(year) == 0) {
            Ui.hint(g, f, "Listen to something and your year builds up here.", x0 + 12, y + 28, w - 24);
            return;
        }
        int gold = Theme.accent(Colors.GOLD);
        int colW = (w - 24) / 2;
        int cy = y + 24;
        // big hours
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate((float) (x0 + 12), (float) cy);
        pose.scale(2f, 2f);
        Gfx.textBold(g, f, Stats.duration(Stats.yearMs(year)), 0, 0, Colors.lighten(gold, 0.2f), grow, true);
        pose.popMatrix();
        Gfx.text(g, f, "listened in " + year, x0 + 12, cy + 22, Theme.dim(), 1f, false);
        int sy = cy + 36;
        int streak = Stats.bestStreakIn(year);
        String[] left = {Stats.yearDays(year) + (Stats.yearDays(year) == 1 ? " day" : " days") + " with music",
                "Best day: " + Stats.duration(Stats.bestDayIn(year)),
                streak > 1 ? "Longest streak: " + streak + " days" : "Longest streak: 1 day"};
        for (int i = 0; i < left.length; i++) Gfx.text(g, f, Gfx.fit(f, left[i], colW - 8), x0 + 12, sy + i * 11, Theme.soft(), 1f, false);

        // favourites (counted over everything, not only this year)
        int rx = x0 + 12 + colW + 6;
        Gfx.text(g, f, "TOP SONG", rx, cy + 2, gold, 1f, false);
        List<Stats.SongStat> songs = Stats.topSongs(1);
        List<Stats.ArtistStat> artists = Stats.topArtists(1);
        if (!songs.isEmpty()) {
            Gfx.drawStyled(g, f, Gfx.fit(f, songs.get(0).title(), colW - 12, true, false), rx, cy + 13, Colors.opaque(Theme.text()), true, false, false);
            Gfx.text(g, f, Gfx.fit(f, (songs.get(0).artist().isEmpty() ? "" : songs.get(0).artist() + " · ") + songs.get(0).plays() + "x", colW - 12),
                    rx, cy + 24, Theme.dim(), 1f, false);
        }
        Gfx.text(g, f, "TOP ARTIST", rx, cy + 40, gold, 1f, false);
        if (!artists.isEmpty()) {
            Gfx.drawStyled(g, f, Gfx.fit(f, artists.get(0).name(), colW - 12, true, false), rx, cy + 51, Colors.opaque(Theme.text()), true, false, false);
            Gfx.text(g, f, Stats.duration(artists.get(0).ms()), rx, cy + 62, Theme.dim(), 1f, false);
        }
        Ui.hint(g, f, "Favourites count all your listening", rx, cy + 76, colW - 8);

        // months
        long[] months = Stats.monthsMs(year);
        long max = 1;
        for (long m : months) max = Math.max(max, m);
        int chartTop = Math.max(sy + 38, y + h - 52);
        int base = y + h - 14;
        int slot = (w - 24) / 12;
        int bar = Math.max(4, slot - 5);
        String[] initials = {"J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D"};
        g.fill(x0 + 12, base, x0 + 12 + slot * 12, base + 1, Colors.alpha(Theme.line(), 0.9f));
        for (int i = 0; i < 12; i++) {
            int bx = x0 + 12 + i * slot + (slot - bar) / 2;
            int bh = months[i] <= 0 ? 0 : Math.max(2, Math.round((base - chartTop) * (months[i] / (float) max) * grow));
            boolean best = months[i] == max && months[i] > 0;
            int c = best ? Colors.lighten(gold, 0.15f) : Colors.alpha(gold, 0.5f);
            if (bh > 0) g.fillGradient(bx, base - bh, bx + bar, base, Colors.opaque(c), Colors.alpha(c, 0.4f));
            else g.fill(bx, base - 1, bx + bar, base, Colors.alpha(Theme.dim(), 0.35f));
            Gfx.text(g, f, initials[i], bx + (bar - Gfx.width(f, initials[i])) / 2, base + 3, best ? Theme.text() : Theme.dim(), 1f, false);
        }
    }

    private static String since() {
        long at = Stats.installedAt();
        if (at <= 0) return "";
        LocalDate d = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate();
        return "since " + DATE.format(d);
    }

    private void card(GuiGraphics g, Font f, int x, int y, int w, int h, String title, String note, int accent) {
        Ui.card(g, f, x, y, w, h, title, note, accent);
    }

    private void bigNumber(GuiGraphics g, Font f, int x, int y, String value, String label, int accent, float grow) {
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate((float) x, (float) y);
        pose.scale(1.6f, 1.6f);
        Gfx.textBold(g, f, value, 0, 0, Colors.lighten(accent, 0.25f), grow, true);
        pose.popMatrix();
        Gfx.text(g, f, label, x + Math.round(Gfx.width(f, value, true) * 1.6f) + 6, y + 6, Theme.dim(), 1f, false);
    }

    private void smallLine(GuiGraphics g, Font f, int x, int y, String text, int w) {
        Gfx.text(g, f, Gfx.fit(f, text, w), x, y, Theme.soft(), 1f, false);
    }

    private void rank(GuiGraphics g, Font f, int n, int x, int y, int accent) {
        Gfx.text(g, f, String.valueOf(n), x, y, Colors.alpha(accent, 0.9f), 1f, false);
    }

    private void chart(GuiGraphics g, Font f, int x, int y, int w, int h, int accent, float grow) {
        long[] days = Stats.lastDays(DAYS);
        long max = 1;
        for (long d : days) max = Math.max(max, d);
        int slot = w / DAYS;
        int bar = Math.max(3, slot - 4);
        int base = y + h - 10;
        int top = y;
        int span = base - top;
        g.fill(x, base, x + slot * DAYS, base + 1, Colors.alpha(Theme.line(), 0.9f));
        LocalDate day = LocalDate.now().minusDays(DAYS - 1);
        for (int i = 0; i < DAYS; i++) {
            int bx = x + i * slot + (slot - bar) / 2;
            int bh = days[i] <= 0 ? 0 : Math.max(2, Math.round(span * (days[i] / (float) max) * grow));
            boolean today = i == DAYS - 1;
            int color = today ? Colors.lighten(accent, 0.2f) : Colors.alpha(accent, 0.55f);
            if (bh > 0) {
                g.fillGradient(bx, base - bh, bx + bar, base, Colors.opaque(color), Colors.alpha(color, 0.45f));
            } else {
                g.fill(bx, base - 1, bx + bar, base, Colors.alpha(Theme.dim(), 0.35f));
            }
            if (i % 2 == (DAYS - 1) % 2 || slot > 28) {
                String label = String.valueOf(day.plusDays(i).getDayOfMonth());
                Gfx.text(g, f, label, bx + (bar - Gfx.width(f, label)) / 2, base + 2, today ? Theme.text() : Theme.dim(), 1f, false);
            }
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        ConfigIo.save();
        minecraft.setScreen(parent);
    }
}
