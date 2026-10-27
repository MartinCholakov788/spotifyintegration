package com.zlorbie788.spotify.gui;

import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.config.SafeMode;
import com.zlorbie788.spotify.config.SpotifyConfig;
import com.zlorbie788.spotify.config.UiFont;
import com.zlorbie788.spotify.gui.widgets.Chrome;
import com.zlorbie788.spotify.gui.widgets.StyledButton;
import com.zlorbie788.spotify.hud.Anim;
import com.zlorbie788.spotify.hud.Gfx;
import com.zlorbie788.spotify.hud.Intro;
import com.zlorbie788.spotify.hud.Theme;
import com.zlorbie788.spotify.hud.Visual;
import com.zlorbie788.spotify.hud.PlayerHud;
import com.zlorbie788.spotify.local.Sources;
import com.zlorbie788.spotify.local.MusicLibrary;
import com.zlorbie788.spotify.lyrics.Lyrics;
import com.zlorbie788.spotify.media.MediaBridge;
import com.zlorbie788.spotify.media.Player;
import com.zlorbie788.spotify.media.Snapshot;
import com.zlorbie788.spotify.util.Colors;
import com.zlorbie788.spotify.util.Ease;
import com.zlorbie788.spotify.util.TextWrap;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * The settings: seven groups on the left, a search box on top, folding sections in a scrolling list, and a live preview on
 * the right when the window is wide enough. Every option has a short explanation under it.
 *
 * <p>Keys: Ctrl+Tab / Ctrl+1..7 change group, Ctrl+F (or a click) searches, Tab moves between controls, Esc clears the
 * search and then closes.
 */
public class SettingsScreen extends Screen implements PlayerHud.HidesHud, SettingsKit.Host {
    private static final int HEADER_H = 24;
    private static final int FOOTER_H = 20;
    private static final int SECTION_H = 15;
    private static final int TITLE_H = 24;
    private static final int NAV_ROW_MAX = 24;
    private static final int HELP_LINE = 9;

    private static int lastGroup = 0;
    private static final float[] SCROLL_MEMORY = new float[SettingsKit.GROUPS.length];
    /** Sections whose fold state differs from their default. */
    private static final Set<String> FLIPPED = new HashSet<>();

    private final Screen parent;
    private int group;
    private SettingsKit kit;
    private EditBox search;
    private String query = "";
    private String[] words = new String[0];
    private KeyMapping listening;
    private boolean confirmReset;
    private String toast = "";
    private long toastAt;
    private int prevGroup = -1;
    private long groupChangedAt;

    // geometry
    private int px;
    private int py;
    private int pw;
    private int ph;
    private int navX;
    private int navW;
    private int listX;
    private int listY;
    private int listW;
    private int listH;
    private int prevX;
    private int prevW;
    private int navRow = NAV_ROW_MAX;

    // scrolling and layout
    private float scroll;
    private float scrollTarget;
    private int contentH;
    private boolean draggingBar;
    private SettingsRow dragRow;
    private final List<int[]> headerRects = new ArrayList<>();
    private final List<String> headerKeys = new ArrayList<>();
    private final List<String> headerTitles = new ArrayList<>();
    private final List<Integer> headerCounts = new ArrayList<>();
    private int matchTotal;
    private final int[] groupMatches = new int[SettingsKit.GROUPS.length];

    private static boolean tourChecked;

    public SettingsScreen(Screen parent) {
        super(Component.literal("Spotify Integration Mod settings"));
        this.parent = parent;
        this.group = lastGroup;
        this.scrollTarget = SCROLL_MEMORY[group];
        this.scroll = scrollTarget;
    }

    private static SpotifyConfig cfg() {
        return ConfigIo.get();
    }

    // ------------------------------------------------------------ host

    @Override
    public Minecraft mc() {
        return minecraft;
    }

    @Override
    public Screen screen() {
        return this;
    }

    @Override
    public Font font() {
        return font;
    }

    @Override
    public void rebuild() {
        float keep = scrollTarget;
        float keepNow = scroll;
        rebuildWidgets();
        scrollTarget = keep;
        scroll = keepNow;
    }

    @Override
    public void toast(String text) {
        toast = text;
        toastAt = System.nanoTime();
    }

    @Override
    public KeyMapping listening() {
        return listening;
    }

    @Override
    public void listen(KeyMapping mapping) {
        listening = mapping;
    }

    // ------------------------------------------------------------ setup

    @Override
    protected void init() {
        if (!tourChecked) {
            tourChecked = true;
            Tour.maybeStart(cfg());
        }
        pw = Math.min(width - 10, 540);
        ph = Math.min(height - 6, 312);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        navW = pw >= 430 ? 94 : 62;
        navX = px + 8;
        int mainX = navX + navW + 8;
        prevW = pw >= 520 && ph >= 230 ? 156 : 0;
        int right = px + pw - 8 - (prevW > 0 ? prevW + 8 : 0);
        listX = mainX;
        listW = right - mainX;
        listY = py + HEADER_H + 22;
        listH = py + ph - FOOTER_H - 4 - listY;
        navRow = Math.max(14, Math.min(NAV_ROW_MAX, (py + ph - FOOTER_H - 4 - (py + HEADER_H + 2)) / SettingsKit.GROUPS.length));
        prevX = px + pw - 8 - prevW;

        addRenderableWidget(new StyledButton(px + pw - 24, py + 5, 16, 16, Component.literal("x"), b -> onClose())
                .accent(Colors.RED).tip("Save and close (Esc)"));

        search = new EditBox(font, listX, py + HEADER_H + 2, listW, 16, Component.literal("Search the settings"));
        search.setMaxLength(40);
        search.setHint(Component.literal("Search all settings...  (Ctrl+F)"));
        search.setValue(query);
        search.setResponder(v -> {
            if (v.equals(query)) return;
            query = v;
            words = v.toLowerCase(Locale.ROOT).strip().isEmpty() ? new String[0] : v.toLowerCase(Locale.ROOT).strip().split("\\s+");
            scrollTarget = 0f;
            scroll = 0f;
        });
        addRenderableWidget(search);

        kit = SettingsPages.build(this);
        for (SettingsRow row : kit.rows) for (SettingsRow.Cell c : row.cells) addWidget(c.widget());

        buildFooter();
        layoutList(false);
    }

    private void buildFooter() {
        int y = py + ph - FOOTER_H + 3;
        int rightEdge = px + pw - 10;
        addRenderableWidget(Ui.button(rightEdge - 54, y, 54, 14, "Done", b -> onClose())
                .accent(Colors.MINT).primary().tip("Save and close (Esc)"));
        addRenderableWidget(Ui.button(rightEdge - 54 - 4 - 92, y, 92, 14, "Move on screen", b ->
                minecraft.setScreen(new LayoutEditorScreen(this))).accent(Colors.CYAN)
                .tip("Drag the player and the lyrics where you want them, scroll to resize."));
        StyledButton reset = Ui.button(rightEdge - 54 - 4 - 92 - 4 - 80, y, 80, 14,
                confirmReset ? "Sure?" : "Reset all", b -> {
                    if (!confirmReset) {
                        confirmReset = true;
                        b.setMessage(Component.literal("Sure?"));
                        return;
                    }
                    confirmReset = false;
                    ConfigIo.reset();
                    MediaBridge.send("source " + (cfg().source == SpotifyConfig.Source.ANY ? "any" : "spotify"));
                    rebuild();
                }).accent(Colors.RED).tip("Put every setting back to the factory look. Click twice to confirm.");
        addRenderableWidget(reset);
    }

    private void selectGroup(int index) {
        int next = ((index % SettingsKit.GROUPS.length) + SettingsKit.GROUPS.length) % SettingsKit.GROUPS.length;
        if (next == group) return;
        SCROLL_MEMORY[group] = scrollTarget;
        prevGroup = group;
        groupChangedAt = System.nanoTime();
        group = next;
        lastGroup = next;
        scrollTarget = SCROLL_MEMORY[next];
        scroll = scrollTarget;
        listening = null;
        confirmReset = false;
        layoutList(false);
    }

    private int groupAt(double mx, double my) {
        if (mx < navX || mx >= navX + navW || my < py + HEADER_H + 2) return -1;
        int i = (int) ((my - (py + HEADER_H + 2)) / navRow);
        return i >= 0 && i < SettingsKit.GROUPS.length ? i : -1;
    }

    private boolean searching() {
        return words.length > 0;
    }

    // ------------------------------------------------------------ the list: fold state and layout

    private static boolean folded(SettingsKit kit, String key) {
        return kit.foldedByDefault.contains(key) ^ FLIPPED.contains(key);
    }

    private void toggleSection(String key) {
        if (!FLIPPED.remove(key)) FLIPPED.add(key);
    }

    /** Works out where every row is. {@code measure} is false before the first draw (the font may differ). */
    private void layoutList(boolean measure) {
        headerRects.clear();
        headerKeys.clear();
        headerTitles.clear();
        headerCounts.clear();
        boolean searching = searching();
        matchTotal = 0;
        java.util.Arrays.fill(groupMatches, 0);
        for (SettingsRow r : kit.rows) {
            r.shown = false;
            if (r.when.getAsBoolean() && (words.length == 0 || r.matches(words))) groupMatches[r.group]++;
        }
        for (int c : groupMatches) matchTotal += c;

        int y = searching ? 18 : TITLE_H;
        int w = rowW();
        Font f = font;
        int fontKey = Objects.hash(Gfx.font(), Gfx.fontShift(), w);
        for (String[] sec : kit.sections) {
            int g = Integer.parseInt(sec[0]);
            if (!searching && g != group) continue;
            String name = sec[1];
            String key = g + "/" + name;
            List<SettingsRow> inSection = new ArrayList<>();
            for (SettingsRow r : kit.rows) {
                if (r.group == g && r.section.equals(name) && r.when.getAsBoolean() && (!searching || r.matches(words))) inSection.add(r);
            }
            if (inSection.isEmpty()) continue;
            boolean isFolded = !searching && folded(kit, key);
            headerRects.add(new int[]{y, SECTION_H});
            headerKeys.add(searching ? "" : key);
            headerTitles.add(searching ? SettingsKit.GROUPS[g] + "  >  " + name : name);
            headerCounts.add(inSection.size());
            y += SECTION_H + 3;
            if (isFolded) continue;
            for (SettingsRow r : inSection) {
                if (measure || r.cacheKey != fontKey) {
                    r.helpLines = r.help.isEmpty() ? List.of() : TextWrap.wrap(r.help, w - 4, 2, t -> Gfx.width(f, t));
                    r.cacheKey = fontKey;
                }
                int body = r.heightOf != null ? r.heightOf.of(w) : r.controlH;
                r.h = body + (r.helpLines.isEmpty() ? 0 : r.helpLines.size() * HELP_LINE + 1) + 6;
                r.y = y;
                r.shown = true;
                y += r.h;
            }
            y += 4;
        }
        contentH = y + 4;
        int max = Math.max(0, contentH - listH);
        scrollTarget = Math.max(0f, Math.min(scrollTarget, max));
        scroll = Math.max(0f, Math.min(scroll, max));
        positionWidgets();
    }

    /** Width of a row: the list minus a gutter for the scroll bar. */
    private int rowW() {
        return listW - 14;
    }

    private int maxScroll() {
        return Math.max(0, contentH - listH);
    }

    /** Places every control for the current scroll position; the ones out of view cannot be clicked. */
    private void positionWidgets() {
        int w = rowW();
        int x0 = listX + 4;
        int top = Math.round(scroll);
        for (SettingsRow r : kit.rows) {
            boolean inView = r.shown && r.y - top + r.h > 0 && r.y - top < listH;
            boolean enabled = r.enabled.getAsBoolean();
            for (SettingsRow.Cell c : r.cells) {
                AbstractWidget wd = c.widget();
                wd.visible = inView;
                wd.active = enabled;
                if (!inView) {
                    wd.setY(-10000);
                    continue;
                }
                wd.setX(x0 + Math.round(c.fx() * w) + c.px());
                wd.setWidth(Math.max(10, Math.round(c.fw() * w) + c.pw()));
                wd.setY(listY + r.y - top + c.ry());
            }
        }
    }

    // ------------------------------------------------------------ drawing

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0x99050810);
        int accent = Theme.accent(SettingsKit.COLORS[group]);
        float tt = Ease.outCubic(Math.min(1f, (System.nanoTime() - groupChangedAt) / 260_000_000f));
        int tint = prevGroup < 0 || !Anim.enabled() ? SettingsKit.COLORS[group] : Colors.mix(SettingsKit.COLORS[prevGroup], SettingsKit.COLORS[group], tt);
        Chrome.body(g, px, py, pw, ph, Colors.alpha(Colors.mix(Theme.surface(), tint, 0.10f), 0.98f), Colors.alpha(Theme.surface(), 0.98f),
                Theme.highContrast() ? Colors.WHITE : Colors.LINE);
        if (!Theme.highContrast()) Gfx.halo(g, px, py, pw, ph, accent, 0.30f);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Anim.frame();
        UiFont oldFont = Gfx.font();
        int oldShift = Gfx.fontShift();
        Gfx.useFont(cfg().uiFont, cfg().fontShift);
        try {
            renderAll(g, mouseX, mouseY, partialTick);
            Tour.render(g, font, width, height, mouseX, mouseY);
        } finally {
            Gfx.useFont(oldFont, oldShift);
        }
    }

    private void renderAll(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        float enter = Anim.smooth(this, "enter", 0f, 1f, 9f);
        var pose = g.pose();
        boolean lift = enter < 0.999f;
        if (lift) {
            pose.pushMatrix();
            pose.translate(0f, (1f - Ease.outCubic(enter)) * 8f);
        }
        Font f = font;
        SpotifyConfig cfg = cfg();
        int accent = PlayerHud.accent(cfg);

        // easing of the scroll position
        if (Math.abs(scrollTarget - scroll) > 0.3f) {
            scroll += (scrollTarget - scroll) * Math.min(1f, Anim.enabled() ? Anim.dt() * 16f : 1f);
        } else {
            scroll = scrollTarget;
        }
        layoutList(false);

        // header
        Visual.update(cfg, Player.playing(), Player.snap().key().hashCode());
        boolean rainbowLine = cfg.accent == SpotifyConfig.Accent.RAINBOW;
        Gfx.accentLine(g, px + 1, py, pw - 2, 3, accent, rainbowLine, PlayerHud.linePhase(cfg, rainbowLine), cfg.motion(), 1f,
                cfg.beatPulse ? Visual.pulse() : 0f);
        Gfx.textBold(g, f, "Spotify Integration Mod", px + 12, py + 9, accent, 1f, true);
        int tx = px + 12 + Gfx.width(f, "Spotify Integration Mod", true) + 8;
        int statusLeft = drawStatus(g, f) - 8;
        if (tx + Gfx.width(f, "Settings") <= statusLeft) {
            Gfx.text(g, f, "Settings", tx, py + 9, Theme.dim(), 1f, false);
            int vx = tx + Gfx.width(f, "Settings") + 8;
            if (vx + Gfx.width(f, "v" + Intro.VERSION) <= statusLeft) {
                Gfx.text(g, f, "v" + Intro.VERSION, vx, py + 9, Colors.alpha(Theme.dim(), 0.6f), 1f, false);
            }
        }

        drawNav(g, f, mouseX, mouseY);

        // the fixed widgets (close, search, footer)
        super.render(g, mouseX, mouseY, partialTick);

        drawList(g, f, mouseX, mouseY, partialTick);
        if (prevW > 0) drawPreview(g, f);

        int fy = py + ph - FOOTER_H;
        g.fill(px + 8, fy, px + pw - 8, fy + 1, Colors.alpha(Theme.line(), 0.8f));
        drawFooterText(g, f);
        if (lift) pose.popMatrix();
    }

    /** Draws the connection status at the top right and returns where it starts. */
    private int drawStatus(GuiGraphics g, Font f) {
        Snapshot s = Player.snap();
        String text;
        int color;
        if (Sources.local() && s.active()) {
            text = "My music · " + (Player.playing() ? "Playing" : "Paused");
            color = Player.playing() ? Theme.good() : Colors.GOLD;
        } else if (s.active()) {
            text = (s.appName().isEmpty() ? "Player" : s.appName()) + " · " + (Player.playing() ? "Playing" : "Paused");
            color = Player.playing() ? Theme.good() : Colors.GOLD;
        } else if (MediaBridge.state() == MediaBridge.State.RUNNING) {
            text = "Connected · nothing playing";
            color = Theme.dim();
        } else if (MediaBridge.state() == MediaBridge.State.FAILED || MediaBridge.state() == MediaBridge.State.UNSUPPORTED) {
            text = MusicLibrary.count() > 0 ? "Spotify not found · " + MusicLibrary.count() + " own songs" : "Spotify not found";
            color = Theme.dim();
        } else {
            text = "Connecting...";
            color = Theme.dim();
        }
        String shown = Gfx.fit(f, text, pw / 2 - 40);
        Gfx.textRight(g, f, shown, px + pw - 32, py + 10, color, 1f, false);
        return px + pw - 32 - Gfx.width(f, shown);
    }

    private void drawNav(GuiGraphics g, Font f, int mouseX, int mouseY) {
        int hover = groupAt(mouseX, mouseY);
        int y0 = py + HEADER_H + 2;
        float gy = Anim.smooth(this, "navy", group * navRow, 22f);
        int gc = SettingsKit.COLORS[group];
        Chrome.body(g, navX, Math.round(y0 + gy), navW, navRow - 2, Theme.raised(), Colors.alpha(gc, 0.55f));
        Gfx.panel(g, navX + 3, Math.round(y0 + gy) + 4, 2.5f, navRow - 10, SpotifyConfig.ShapeKind.ROUND, 1.2f, Colors.opaque(gc),
                Colors.opaque(gc), 0, 0f);
        for (int i = 0; i < SettingsKit.GROUPS.length; i++) {
            int y = y0 + i * navRow;
            boolean active = i == group;
            if (i == hover && !active) Chrome.body(g, navX, y, navW, navRow - 2, Colors.alpha(Theme.raised(), 0.55f), 0);
            String name = SettingsKit.GROUPS[i];
            int color = active || i == hover ? Theme.text() : Theme.soft();
            int roomForCount = searching() ? 16 : 0;
            Gfx.drawStyled(g, f, Gfx.fit(f, name, navW - 16 - roomForCount, active, false), navX + 10, y + (navRow - 8) / 2, Colors.opaque(color), active,
                    false, false);
            if (!active) g.fill(navX + 4, y + navRow / 2 - 2, navX + 6, y + navRow / 2 + 2, Colors.alpha(SettingsKit.COLORS[i], 0.55f));
            if (searching()) {
                String n = String.valueOf(groupMatches[i]);
                Gfx.drawStyled(g, f, n, navX + navW - 6 - Gfx.width(f, n), y + (navRow - 8) / 2,
                        Colors.opaque(groupMatches[i] > 0 ? Theme.accent(SettingsKit.COLORS[i]) : Theme.dim()), false, false, false);
            }
        }
        // the loaded profile, under the groups
        int by = y0 + SettingsKit.GROUPS.length * navRow + 6;
        if (by + 24 < py + ph - FOOTER_H) {
            Ui.label(g, f, "PROFILE", navX + 4, by);
            String p = cfg().profile.isEmpty() ? "none" : cfg().profile;
            Gfx.drawStyled(g, f, Gfx.fit(f, p, navW - 8), navX + 4, by + 10, Colors.opaque(cfg().profile.isEmpty() ? Theme.dim() : Theme.good()),
                    false, false, false);
        }
    }

    private void drawList(GuiGraphics g, Font f, int mouseX, int mouseY, float partialTick) {
        int accent = SettingsKit.COLORS[group];
        Chrome.body(g, listX, listY, listW, listH, Colors.alpha(Colors.mix(Theme.well(), Theme.surface(), 0.3f), 0.55f),
                Colors.alpha(Theme.well(), 0.5f), Theme.line());
        int top = Math.round(scroll);
        boolean mouseIn = mouseX >= listX && mouseX < listX + listW && mouseY >= listY && mouseY < listY + listH;
        int mx = mouseIn ? mouseX : -1000;
        int my = mouseIn ? mouseY : -1000;
        int x0 = listX + 4;
        int w = rowW();

        g.enableScissor(listX + 1, listY + 1, listX + listW - 1, listY + listH - 1);
        try {
            // title or search summary
            int ty = listY - top + 5;
            if (searching()) {
                String t = matchTotal == 0 ? "Nothing matches \"" + query.strip() + "\""
                        : matchTotal + (matchTotal == 1 ? " setting matches" : " settings match") + " \"" + query.strip() + "\"";
                Gfx.drawStyled(g, f, Gfx.fit(f, t, w), x0 + 2, ty, Colors.opaque(matchTotal == 0 ? Theme.bad() : Theme.soft()), false, false, false);
            } else {
                String title = SettingsKit.GROUPS[group].toUpperCase(Locale.ROOT);
                int titleW = Gfx.width(f, title, true);
                Gfx.drawStyled(g, f, title, x0 + 2, ty, Colors.opaque(Theme.accent(accent)), true, false, true);
                int blurbRoom = w - titleW - 16;
                if (blurbRoom >= 40) {
                    Gfx.drawStyled(g, f, Gfx.fit(f, SettingsKit.BLURBS[group], blurbRoom), x0 + 2 + titleW + 10, ty,
                            Colors.opaque(Theme.dim()), false, false, false);
                }
            }
            // section headers
            for (int i = 0; i < headerRects.size(); i++) {
                int[] r = headerRects.get(i);
                int y = listY + r[0] - top;
                if (y + r[1] < listY || y > listY + listH) continue;
                boolean hover = mouseIn && my >= y && my < y + r[1] && !headerKeys.get(i).isEmpty();
                int rowGroup = headerKeys.get(i).isEmpty() ? group : Integer.parseInt(headerKeys.get(i).substring(0, headerKeys.get(i).indexOf('/')));
                int hc = SettingsKit.COLORS[rowGroup];
                Chrome.body(g, x0, y, w, r[1], Colors.alpha(hover ? Theme.raised() : Colors.mix(Theme.raised(), Theme.surface(), 0.4f), 0.9f),
                        Colors.alpha(hc, hover ? 0.55f : 0.30f));
                g.fill(x0 + 1, y + 2, x0 + 3, y + r[1] - 2, Colors.alpha(hc, 0.9f));
                boolean open = headerKeys.get(i).isEmpty() || !folded(kit, headerKeys.get(i));
                drawChevron(g, x0 + 9, y + 5, open, Theme.accent(hc));
                Gfx.drawStyled(g, f, Gfx.fit(f, headerTitles.get(i), w - 60, true, false), x0 + 18, y + 4, Colors.opaque(Theme.text()), true, false, false);
                String count = String.valueOf(headerCounts.get(i));
                Gfx.drawStyled(g, f, count, x0 + w - 6 - Gfx.width(f, count), y + 4, Colors.opaque(Theme.dim()), false, false, false);
            }
            // the rows
            for (SettingsRow r : kit.rows) {
                if (!r.shown) continue;
                int ry = listY + r.y - top;
                if (ry + r.h < listY || ry > listY + listH) continue;
                int body = r.heightOf != null ? r.heightOf.of(w) : r.controlH;
                if (r.draw != null) r.draw.draw(g, f, x0, ry + 2, w, mx, my);
                boolean dim = !r.enabled.getAsBoolean();
                for (SettingsRow.Cell c : r.cells) c.widget().render(g, mx, my, partialTick);
                int hy = ry + 2 + body + 2;
                for (String line : r.helpLines) {
                    Gfx.drawStyled(g, f, line, x0 + 3, hy, Colors.opaque(dim ? Colors.alpha(Theme.dim(), 0.6f) : Theme.dim()), false, false, false);
                    hy += HELP_LINE;
                }
            }
        } finally {
            g.disableScissor();
        }
        drawScrollbar(g, mouseX, mouseY);
        // soft fade at the top and bottom edge when there is more
        if (scroll > 1f) g.fillGradient(listX + 1, listY + 1, listX + listW - 7, listY + 8, Colors.alpha(Theme.surface(), 0.8f), 0);
        if (scroll < maxScroll() - 1f) {
            g.fillGradient(listX + 1, listY + listH - 9, listX + listW - 7, listY + listH - 1, 0, Colors.alpha(Theme.surface(), 0.8f));
        }
    }

    private void drawChevron(GuiGraphics g, int x, int y, boolean open, int color) {
        int c = Colors.opaque(color);
        if (open) {
            g.fill(x - 3, y + 1, x + 4, y + 2, c);
            g.fill(x - 2, y + 2, x + 3, y + 3, c);
            g.fill(x - 1, y + 3, x + 2, y + 4, c);
            g.fill(x, y + 4, x + 1, y + 5, c);
        } else {
            g.fill(x - 1, y - 2, x, y + 5, c);
            g.fill(x, y - 1, x + 1, y + 4, c);
            g.fill(x + 1, y, x + 2, y + 3, c);
            g.fill(x + 2, y + 1, x + 3, y + 2, c);
        }
    }

    private int[] barRect() {
        int max = maxScroll();
        if (max <= 0) return null;
        int trackH = listH - 4;
        int thumb = Math.max(18, trackH * listH / Math.max(listH, contentH));
        int ty = listY + 2 + Math.round((trackH - thumb) * (scroll / (float) max));
        return new int[]{listX + listW - 6, ty, 4, thumb};
    }

    private void drawScrollbar(GuiGraphics g, int mouseX, int mouseY) {
        int[] r = barRect();
        if (r == null) return;
        boolean hover = draggingBar || (mouseX >= r[0] - 2 && mouseX < r[0] + r[2] + 2 && mouseY >= r[1] && mouseY < r[1] + r[3]);
        g.fill(r[0], listY + 2, r[0] + r[2], listY + listH - 2, Colors.alpha(Theme.line(), 0.4f));
        g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], Colors.alpha(Theme.accent(SettingsKit.COLORS[group]), hover ? 0.95f : 0.6f));
    }

    private void drawFooterText(GuiGraphics g, Font f) {
        int room = pw - 24 - 250;
        if (listening != null) {
            Ui.hint(g, f, "Press the new key  ·  Backspace removes it  ·  Esc cancels", px + 12, py + ph - 13, room);
            return;
        }
        long since = (System.nanoTime() - toastAt) / 1_000_000L;
        if (!toast.isEmpty() && since < 2600L) {
            float fade = since > 2000L ? 1f - (since - 2000L) / 600f : 1f;
            Gfx.drawStyled(g, f, Gfx.fit(f, toast, room), px + 12, py + ph - 13, Colors.alpha(Theme.good(), fade), true, false, false);
            return;
        }
        String problem = SafeMode.problem();
        if (!problem.isEmpty()) {
            Gfx.drawStyled(g, f, Gfx.fit(f, "Something failed: " + problem, room), px + 12, py + ph - 13, Colors.opaque(Theme.bad()), false,
                    false, false);
            return;
        }
        String help = Gfx.width(f, "Hover for more  ·  Ctrl+F search  ·  Ctrl+Tab groups") <= room ? "Hover for more  ·  Ctrl+F search  ·  Ctrl+Tab groups"
                : "Hover for more";
        Ui.hint(g, f, help, px + 12, py + ph - 13, room);
    }

    // ------------------------------------------------------------ the preview

    private void drawPreview(GuiGraphics g, Font f) {
        int accent = SettingsKit.COLORS[group];
        int h = listY + listH - (py + HEADER_H + 2);
        int y = py + HEADER_H + 2;
        switch (group) {
            case 0, 1 -> drawPlayerPreview(g, f, prevX, y, prevW, h, accent);
            case 3 -> drawLyricsPreview(g, f, prevX, y, prevW, h, accent);
            case 2 -> drawMusicCard(g, f, prevX, y, prevW, h, accent);
            case 5 -> drawNoteCard(g, f, prevX, y, prevW, h, accent, "YOUR LISTENING", PagesStats.preview());
            case 4 -> drawNoteCard(g, f, prevX, y, prevW, h, accent, "NO LOG-IN", new String[]{
                    "Nothing here asks for your Spotify account.",
                    "Songs are found in public catalogues, then your own Spotify app opens the song.",
                    "Press / in the player screen, or type /music search."});
            default -> drawNoteCard(g, f, prevX, y, prevW, h, accent, "TIPS", new String[]{
                    "Click a key, then press the new one.",
                    "A profile saves your whole setup, keys included. Switch between \"Survival\" and \"Build\" in one click.",
                    "Safe look puts the plain factory look back if anything looks broken."});
        }
    }

    private void checker(GuiGraphics g, int x, int y, int w, int top, int bottom) {
        g.fill(x + 6, top, x + w - 6, bottom, Colors.alpha(0xFF2C3550, 0.5f));
        for (int yy = top; yy < bottom; yy += 8) {
            for (int xx = x + 6 + (((yy - top) / 8) % 2 == 0 ? 0 : 8); xx < x + w - 6; xx += 16) {
                g.fill(xx, yy, Math.min(xx + 8, x + w - 6), Math.min(yy + 8, bottom), Colors.alpha(0xFF3A4566, 0.5f));
            }
        }
    }

    private void drawPlayerPreview(GuiGraphics g, Font f, int x, int y, int w, int h, int accent) {
        SpotifyConfig cfg = cfg();
        Ui.card(g, f, x, y, w, h, "PREVIEW", cfg.style.width + " x " + cfg.style.height, accent);
        float availW = w - 16;
        float availH = Math.min(h - 46, 150);
        float scale = Math.min(1f, Math.min(availW / cfg.style.width, availH / cfg.style.height));
        int pxw = Math.round(cfg.style.width * scale);
        int pxh = Math.round(cfg.style.height * scale);
        int top = y + 20;
        int bottom = top + (int) availH + 4;
        checker(g, x, y, w, top, bottom);
        PlayerHud.preview(g, minecraft, cfg, x + (w - pxw) / 2, top + Math.max(2, ((bottom - top) - pxh) / 2), scale, cfg.opacity);
        Ui.hint(g, f, Player.snap().active() ? "Live: your current song" : "Sample song (nothing is playing)", x + 8, bottom + 5, w - 16);
        String[] tips = group == 0
                ? new String[]{"Everything you change", "shows up here at once."}
                : new String[]{"\"Move on screen\" below", "places it exactly."};
        Ui.hint(g, f, tips[0], x + 8, bottom + 18, w - 16);
        Ui.hint(g, f, tips[1], x + 8, bottom + 28, w - 16);
    }

    private void drawLyricsPreview(GuiGraphics g, Font f, int x, int y, int w, int h, int accent) {
        SpotifyConfig cfg = cfg();
        var lc = cfg.lyrics;
        int bw = com.zlorbie788.spotify.hud.LyricsLayout.boxWidth(lc);
        int bh = com.zlorbie788.spotify.hud.LyricsLayout.boxHeight(lc);
        Ui.card(g, f, x, y, w, h, "PREVIEW", bw + " x " + bh, accent);
        float availW = w - 16;
        float availH = Math.min(h - 46, 150);
        float scale = Math.min(1f, Math.min(availW / bw, availH / bh));
        int pxw = Math.round(bw * scale);
        int pxh = Math.round(bh * scale);
        int top = y + 20;
        int bottom = top + (int) availH + 4;
        checker(g, x, y, w, top, bottom);
        com.zlorbie788.spotify.hud.LyricsHud.preview(g, minecraft, cfg, x + (w - pxw) / 2, top + Math.max(2, ((bottom - top) - pxh) / 2), scale, lc.opacity);
        Ui.hint(g, f, Lyrics.ready() ? "Live: the lyrics of your song" : "Sample lyrics", x + 8, bottom + 5, w - 16);
    }

    private void drawMusicCard(GuiGraphics g, Font f, int x, int y, int w, int h, int accent) {
        Ui.card(g, f, x, y, w, h, "RIGHT NOW", null, accent);
        int yy = y + 22;
        SpotifyConfig c = cfg();
        String[] lines = {
                "Playing from: " + (Sources.local() ? "My music" : "Spotify"),
                "Songs in folder: " + MusicLibrary.count(),
                "Equaliser: " + (c.eqOn ? com.zlorbie788.spotify.audio.Eq.PRESET_NAMES[c.eqPreset] : "off"),
                "Crossfade: " + (c.crossfadeSeconds == 0 ? "off (gapless)" : c.crossfadeSeconds + " s"),
                "Output: " + (c.audioDevice.isEmpty() ? "System default" : c.audioDevice),
        };
        for (String line : lines) {
            for (String row : TextWrap.wrap(line, w - 16, 2, t -> Gfx.width(f, t))) {
                Gfx.drawStyled(g, f, row, x + 8, yy, Colors.opaque(Theme.soft()), false, false, false);
                yy += 10;
            }
            yy += 3;
        }
    }

    private void drawNoteCard(GuiGraphics g, Font f, int x, int y, int w, int h, int accent, String title, String[] lines) {
        Ui.card(g, f, x, y, w, h, title, null, accent);
        int yy = y + 22;
        for (String line : lines) {
            for (String row : TextWrap.wrap(line, w - 16, 6, t -> Gfx.width(f, t))) {
                Gfx.drawStyled(g, f, row, x + 8, yy, Colors.opaque(Theme.soft()), false, false, false);
                yy += 10;
            }
            yy += 5;
        }
    }

    // ------------------------------------------------------------ input

    private boolean inList(double mx, double my) {
        return mx >= listX && mx < listX + listW && my >= listY && my < listY + listH;
    }

    private void setAllVisible(boolean on, List<boolean[]> saved) {
        int i = 0;
        for (SettingsRow r : kit.rows) {
            for (SettingsRow.Cell c : r.cells) {
                if (on) c.widget().visible = saved.get(i)[0];
                else {
                    saved.add(new boolean[]{c.widget().visible});
                    c.widget().visible = false;
                }
                i++;
            }
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (Tour.mouseClicked(event.x(), event.y())) return true;
        double mx = event.x();
        double my = event.y();
        if (event.button() == 0) {
            int clicked = groupAt(mx, my);
            if (clicked >= 0) {
                selectGroup(clicked);
                return true;
            }
            int[] bar = barRect();
            if (bar != null && mx >= bar[0] - 3 && mx < bar[0] + bar[2] + 3 && my >= listY && my < listY + listH) {
                draggingBar = true;
                dragBar(my);
                return true;
            }
            if (inList(mx, my)) {
                int top = Math.round(scroll);
                for (int i = 0; i < headerRects.size(); i++) {
                    int[] r = headerRects.get(i);
                    int y = listY + r[0] - top;
                    if (!headerKeys.get(i).isEmpty() && my >= y && my < y + r[1]) {
                        toggleSection(headerKeys.get(i));
                        layoutList(false);
                        return true;
                    }
                }
                int w = rowW();
                for (SettingsRow r : kit.rows) {
                    if (!r.shown || r.click == null) continue;
                    int ry = listY + r.y - top + 2;
                    int body = r.heightOf != null ? r.heightOf.of(w) : r.controlH;
                    if (my >= ry && my < ry + body) {
                        if (r.click.click((int) mx - (listX + 4), (int) my - ry, w)) {
                            if (r.drag != null) dragRow = r;
                            return true;
                        }
                    }
                }
            }
        }
        if (inList(mx, my)) return super.mouseClicked(event, doubleClick);
        // outside the list the scrolled-away controls must not react
        List<boolean[]> saved = new ArrayList<>();
        setAllVisible(false, saved);
        try {
            return super.mouseClicked(event, doubleClick);
        } finally {
            setAllVisible(true, saved);
        }
    }

    private void dragBar(double my) {
        int[] r = barRect();
        int max = maxScroll();
        if (r == null || max <= 0) return;
        int trackH = listH - 4;
        float t = (float) ((my - listY - 2 - r[3] / 2.0) / Math.max(1, trackH - r[3]));
        scrollTarget = Math.max(0, Math.min(max, t * max));
        scroll = scrollTarget;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (Tour.active()) return true;
        if (draggingBar) {
            dragBar(event.y());
            return true;
        }
        if (dragRow != null) {
            int w = rowW();
            int ry = listY + dragRow.y - Math.round(scroll) + 2;
            dragRow.drag.click((int) event.x() - (listX + 4), (int) event.y() - ry, w);
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        boolean used = draggingBar || dragRow != null;
        draggingBar = false;
        if (dragRow != null) ConfigIo.save();
        dragRow = null;
        boolean r = super.mouseReleased(event);
        ConfigIo.save();
        return used || r;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (Tour.active()) return true;
        if (mouseX >= listX - 4 && mouseX < listX + listW + 4 && mouseY >= py + HEADER_H && mouseY < py + ph - FOOTER_H) {
            scrollTarget = Math.max(0, Math.min(maxScroll(), scrollTarget - (float) scrollY * 26f));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (Tour.key(key)) return true;
        if (listening != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                listening = null;
            } else if (key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE) {
                com.zlorbie788.spotify.client.Keybinds.rebind(listening, GLFW.GLFW_KEY_UNKNOWN);
                listening = null;
            } else {
                com.zlorbie788.spotify.client.Keybinds.rebind(listening, key);
                listening = null;
            }
            rebuild();
            return true;
        }
        boolean typing = getFocused() instanceof EditBox;
        if (key == GLFW.GLFW_KEY_ESCAPE && !query.isEmpty()) {
            search.setValue("");
            setFocused(null);
            return true;
        }
        if (minecraft != null && minecraft.hasControlDown()) {
            if (key == GLFW.GLFW_KEY_F) {
                setFocused(search);
                return true;
            }
            if (key == GLFW.GLFW_KEY_TAB) {
                selectGroup(group + (minecraft.hasShiftDown() ? -1 : 1));
                return true;
            }
            if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_1 + SettingsKit.GROUPS.length - 1) {
                selectGroup(key - GLFW.GLFW_KEY_1);
                return true;
            }
        }
        if (!typing) {
            if (key == GLFW.GLFW_KEY_PAGE_DOWN || key == GLFW.GLFW_KEY_PAGE_UP) {
                float step = listH - 24;
                scrollTarget = Math.max(0, Math.min(maxScroll(), scrollTarget + (key == GLFW.GLFW_KEY_PAGE_DOWN ? step : -step)));
                return true;
            }
            if (key == GLFW.GLFW_KEY_HOME) {
                scrollTarget = 0;
                return true;
            }
            if (key == GLFW.GLFW_KEY_END) {
                scrollTarget = maxScroll();
                return true;
            }
        }
        boolean used = super.keyPressed(event);
        if (key == GLFW.GLFW_KEY_TAB) ensureVisible(getFocused());
        return used;
    }

    private void ensureVisible(Object focused) {
        if (!(focused instanceof AbstractWidget wd)) return;
        for (SettingsRow r : kit.rows) {
            for (SettingsRow.Cell c : r.cells) {
                if (c.widget() != wd || !r.shown) continue;
                if (r.y < scrollTarget + 4) scrollTarget = Math.max(0, r.y - 8);
                else if (r.y + r.h > scrollTarget + listH - 4) scrollTarget = Math.min(maxScroll(), r.y + r.h - listH + 8);
                return;
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
    public void removed() {
        SCROLL_MEMORY[group] = scrollTarget;
        ConfigIo.save();
    }

    @Override
    public void onClose() {
        ConfigIo.save();
        minecraft.setScreen(parent);
    }
}
