package com.zlorbie788.spotify.gui;

import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.config.SpotifyConfig;
import com.zlorbie788.spotify.gui.widgets.Chrome;
import com.zlorbie788.spotify.gui.widgets.StyledButton;
import com.zlorbie788.spotify.hud.Anim;
import com.zlorbie788.spotify.hud.Gfx;
import com.zlorbie788.spotify.hud.PlayerHud;
import com.zlorbie788.spotify.hud.Theme;
import com.zlorbie788.spotify.local.Folder;
import com.zlorbie788.spotify.local.MusicLibrary;
import com.zlorbie788.spotify.local.SongDoctor;
import com.zlorbie788.spotify.util.Colors;
import com.zlorbie788.spotify.util.TextWrap;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * "Fix my songs": checks the music folder and lists, file by file, why a song does not show up or does not play and what to
 * do about it. It only reads; nothing is changed or deleted.
 */
public class SongDoctorScreen extends Screen implements PlayerHud.HidesHud {
    private final Screen parent;
    private int px;
    private int py;
    private int pw;
    private int ph;
    private int listY;
    private int listH;
    private int scroll;
    private volatile boolean checking;
    private volatile SongDoctor.Report report;
    private String note = "";
    private long noteAt;
    private final List<Block> blocks = new ArrayList<>();
    private int blocksWidth = -1;

    /** One finding laid out as wrapped lines. */
    private record Block(SongDoctor.Level level, List<String> head, List<String> fix, int height) { }

    public SongDoctorScreen(Screen parent) {
        super(Component.literal("Fix my songs"));
        this.parent = parent;
    }

    private static SpotifyConfig cfg() {
        return ConfigIo.get();
    }

    @Override
    protected void init() {
        pw = Math.min(width - 10, 460);
        ph = Math.min(height - 6, 300);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        int accent = PlayerHud.accent(cfg());
        addRenderableWidget(new StyledButton(px + pw - 24, py + 5, 16, 16, Component.literal("x"), b -> onClose())
                .accent(Colors.RED).tip("Close (Esc)"));
        int y = py + ph - 19;
        int right = px + pw - 12;
        addRenderableWidget(Ui.button(right - 54, y, 54, 14, "Done", b -> onClose()).accent(Colors.MINT).primary().tip("Close"));
        addRenderableWidget(Ui.button(right - 54 - 4 - 84, y, 84, 14, "Check again", b -> run())
                .accent(accent).tip("Look at the folder again (after you fixed something)."));
        addRenderableWidget(Ui.button(px + 12, y, 84, 14, "Open folder", b -> {
            if (!Folder.openMusic()) flash("Could not open it. It is: " + MusicLibrary.directory());
        }).accent(accent).tip("Opens the music folder in Explorer."));
        addRenderableWidget(Ui.button(px + 12 + 88, y, 84, 14, "Copy report", b -> {
            SongDoctor.Report r = report;
            if (r == null) return;
            minecraft.keyboardHandler.setClipboard(String.join("\n", SongDoctor.lines(r)));
            flash("Copied");
        }).accent(accent).tip("Copies the findings so you can paste them to someone who helps you."));
        listY = py + 66;
        listH = ph - 66 - 26;
        if (report == null && !checking) run();
    }

    private void flash(String text) {
        note = text;
        noteAt = System.nanoTime();
    }

    /** Checks in the background (a big library takes a moment), then makes the library look again too. */
    private void run() {
        if (checking) return;
        checking = true;
        scroll = 0;
        Thread t = new Thread(() -> {
            try {
                report = SongDoctor.check(MusicLibrary.directory());
            } catch (Throwable e) {
                report = new SongDoctor.Report(MusicLibrary.directory(), true, false, 0, List.of(new SongDoctor.Finding(
                        SongDoctor.Level.BAD, "", "The check itself failed (" + e + ").", "Try again.")));
            } finally {
                blocksWidth = -1;
                checking = false;
            }
            MusicLibrary.rescanAsync();
        }, "Spotify-Song-Doctor");
        t.setDaemon(true);
        t.start();
    }

    private void layout(Font f, int w) {
        blocks.clear();
        SongDoctor.Report r = report;
        if (r == null) return;
        for (SongDoctor.Finding fi : r.findings()) {
            String head = (fi.file().isEmpty() ? "" : fi.file() + ": ") + fi.what();
            List<String> h = TextWrap.wrap(head, w - 18, 3, t -> Gfx.width(f, t));
            List<String> x = TextWrap.wrap("What to do: " + fi.fix(), w - 18, 3, t -> Gfx.width(f, t));
            blocks.add(new Block(fi.level(), h, x, (h.size() + x.size()) * 10 + 8));
        }
        blocksWidth = w;
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
        int accent = PlayerHud.accent(cfg);
        PlayerHud.tickState(cfg);
        Chrome.body(g, px, py, pw, ph, Colors.alpha(Colors.mix(Theme.surface(), accent, 0.10f), 0.98f),
                Colors.alpha(Theme.surface(), 0.98f), Theme.highContrast() ? Colors.WHITE : Colors.LINE);
        if (!Theme.highContrast()) Gfx.halo(g, px, py, pw, ph, accent, 0.35f);
        Gfx.accentLine(g, px + 1, py, pw - 2, 3, accent, cfg.accent == SpotifyConfig.Accent.RAINBOW,
                PlayerHud.linePhase(cfg, cfg.accent == SpotifyConfig.Accent.RAINBOW), cfg.motion(), 1f, 0f);
        Gfx.textBold(g, f, "Fix my songs", px + 12, py + 9, accent, 1f, true);
        Gfx.text(g, f, "Spotify Integration Mod", px + 12 + Gfx.width(f, "Fix my songs", true) + 8, py + 9, Theme.dim(), 1f, false);

        SongDoctor.Report r = report;
        int x0 = px + 12;
        int w = pw - 24;
        Gfx.text(g, f, Gfx.fit(f, "Folder: " + MusicLibrary.directory(), w), x0, py + 26, Theme.dim(), 1f, false);
        if (checking || r == null) {
            Gfx.text(g, f, "Looking at every file...", x0, py + 42, Theme.soft(), 1f, false);
        } else {
            int color = r.problems() == 0 ? Theme.good() : Colors.GOLD;
            for (String line : TextWrap.wrap(r.summary(), w, 2, t -> Gfx.width(f, t))) {
                Gfx.text(g, f, line, x0, py + 40 + 0, color, 1f, false);
                break;
            }
            Gfx.text(g, f, Gfx.fit(f, "Works with MP3, OGG (Vorbis), WAV and FLAC. Nothing here changes or deletes your files.", w), x0, py + 52, Theme.dim(), 1f, false);
        }
        g.fill(px + 8, listY - 3, px + pw - 8, listY - 2, Colors.alpha(Theme.line(), 0.7f));

        if (r != null && !checking) {
            if (blocksWidth != w) layout(f, w);
            if (blocks.isEmpty()) {
                String good = r.folderExists() ? (r.songs() == 0 ? "Nothing to check yet." : "Everything in the folder is fine.") : "";
                Gfx.text(g, f, good, x0, listY + 6, Theme.soft(), 1f, false);
                if (r.songs() > 0 || !r.folderExists()) {
                    for (String line : TextWrap.wrap(r.folderExists()
                                    ? "If a song is still missing in My music, press Rescan there. Songs can sit in subfolders."
                                    : "Press Open folder: it is created for you.", w, 3, t -> Gfx.width(f, t))) {
                        Gfx.text(g, f, line, x0, listY + 20, Theme.dim(), 1f, false);
                        break;
                    }
                }
            } else {
                int total = 0;
                for (Block b : blocks) total += b.height;
                int max = Math.max(0, total - listH);
                scroll = Math.max(0, Math.min(max, scroll));
                g.enableScissor(px + 8, listY, px + pw - 8, listY + listH);
                int y = listY - scroll;
                for (Block b : blocks) {
                    if (y + b.height >= listY && y <= listY + listH) {
                        int c = b.level == SongDoctor.Level.BAD ? Colors.GOLD : Theme.dim();
                        g.fill(x0, y + 2, x0 + 2, y + b.height - 4, Colors.alpha(c, 0.9f));
                        int ty = y + 2;
                        for (String line : b.head) {
                            Gfx.text(g, f, line, x0 + 8, ty, Theme.text(), 1f, false);
                            ty += 10;
                        }
                        for (String line : b.fix) {
                            Gfx.text(g, f, line, x0 + 8, ty, Theme.soft(), 1f, false);
                            ty += 10;
                        }
                    }
                    y += b.height;
                }
                g.disableScissor();
                if (max > 0) {
                    int trackH = listH;
                    int barH = Math.max(12, trackH * listH / Math.max(listH, total));
                    int barY = listY + (trackH - barH) * scroll / max;
                    g.fill(px + pw - 10, barY, px + pw - 8, barY + barH, Colors.alpha(accent, 0.7f));
                }
            }
        }
        super.render(g, mouseX, mouseY, partialTick);
        if (!note.isEmpty() && (System.nanoTime() - noteAt) / 1_000_000L < 2500L) {
            Gfx.textRight(g, f, Gfx.fit(f, note, pw / 2), px + pw - 32, py + 10, Theme.good(), 1f, false);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0 && mouseX >= px && mouseX < px + pw && mouseY >= listY && mouseY < listY + listH) {
            scroll -= (int) Math.signum(scrollY) * 24;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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
        if (minecraft != null) minecraft.setScreen(parent);
    }
}
