package com.zlorbie788.spotify.gui;

import com.zlorbie788.spotify.catalog.Item;
import com.zlorbie788.spotify.catalog.Library;
import com.zlorbie788.spotify.catalog.PlaylistRunner;
import com.zlorbie788.spotify.catalog.Playlists;
import com.zlorbie788.spotify.catalog.Thumbs;
import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.config.SpotifyConfig;
import com.zlorbie788.spotify.gui.widgets.Chrome;
import com.zlorbie788.spotify.gui.widgets.IconButton;
import com.zlorbie788.spotify.gui.widgets.StyledButton;
import com.zlorbie788.spotify.hud.Gfx;
import com.zlorbie788.spotify.hud.Icons;
import com.zlorbie788.spotify.hud.PlayerHud;
import com.zlorbie788.spotify.hud.Theme;
import com.zlorbie788.spotify.local.Folder;
import com.zlorbie788.spotify.local.LocalPlayer;
import com.zlorbie788.spotify.local.MusicLibrary;
import com.zlorbie788.spotify.local.Track;
import com.zlorbie788.spotify.lyrics.Lyrics;
import com.zlorbie788.spotify.stats.Stats;
import com.zlorbie788.spotify.util.Colors;
import com.zlorbie788.spotify.util.Ease;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "My music": the songs in the music folder, the queue, your playlists and a few automatic lists (Favourites, Most
 * played, Recently added, Not played yet). Works without Spotify and without any login.
 *
 * <p>Keys: type to search, Up/Down choose, Enter play, Delete removes a queued song, Esc goes back. In the queue, drag a
 * song up or down to change the order.
 */
public class LibraryScreen extends Screen implements PlayerHud.HidesHud {
    private enum Tab {
        SONGS("Songs"), QUEUE("Queue"), LISTS("Lists"), SMART("Smart");

        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    /** The automatic lists of the Smart tab. */
    private enum Auto {
        FAVOURITES("Favourites", "Songs you liked with the heart", Icons.HEART),
        MOST("Most played", "What you listen to most", Icons.NOTE),
        RECENT("Recently added", "The newest files in your folder", Icons.ALBUM),
        UNPLAYED("Not played yet", "Songs you have not listened to", Icons.NOTE);

        final String label;
        final String hint;
        final String[] icon;

        Auto(String label, String hint, String[] icon) {
            this.label = label;
            this.hint = hint;
            this.icon = icon;
        }
    }

    /** One line of the list: a song, or a card (a playlist or an automatic list). */
    private record Row(Track track, String card, String title, String sub, int listIndex) {
        boolean isSong() {
            return track != null;
        }
    }

    private static final int ROW = 28;
    private static final int THUMB = 24;

    private static Tab lastTab = Tab.SONGS;
    private static String lastText = "";
    private static MusicLibrary.Sort lastSort = MusicLibrary.Sort.TITLE;

    private final Screen parent;
    private Tab tab = lastTab;
    private MusicLibrary.Sort sort = lastSort;
    private EditBox box;
    private String text = lastText;

    private int px;
    private int py;
    private int pw;
    private int ph;
    private int listY;
    private int listH;
    private int scroll;
    private int selected = -1;
    private int tint = 0xFF1B2440;
    private String hint = "";
    private String note = "";
    private long noteAt;

    /** The playlist open in the Lists tab, or the automatic list open in the Smart tab. */
    private String openList;
    private Auto openAuto;
    private String confirmDelete = "";

    private Track menuFor;
    private int menuX;
    private int menuY;

    // queue drag and drop
    private int pressRow = -1;
    private double pressY;
    private boolean dragging;
    private int dragTo = -1;

    // row cache
    private List<Row> cache = List.of();
    private String cacheKey = "";
    private long cacheAt;
    private int hiddenInList;
    private Map<String, Track> byName = Map.of();
    private long byNameRevision = -1;

    public LibraryScreen(Screen parent) {
        super(Component.literal("My music"));
        this.parent = parent;
    }

    public static LibraryScreen open(Screen parent) {
        MusicLibrary.ensureDirectory();
        return new LibraryScreen(parent);
    }

    // ------------------------------------------------------------ layout

    @Override
    protected void init() {
        SpotifyConfig cfg = ConfigIo.get();
        int accent = PlayerHud.accent(cfg);
        pw = Math.min(400, width - 16);
        px = (width - pw) / 2;
        ph = Math.min(height - 12, 320);
        py = Math.max(4, (height - ph) / 2);

        addRenderableWidget(new IconButton(px + 8, py + 8, 20, 16, "Back", () -> Icons.BACK, this::back)
                .accent(accent).tip("Back (Esc)"));
        addRenderableWidget(new IconButton(px + pw - 28, py + 8, 20, 16, "Close", () -> Icons.CLOSE, () -> minecraft.setScreen(null))
                .accent(Colors.RED).tip("Close everything"));

        box = new EditBox(font, px + 34, py + 8, pw - 34 - 34, 16, Component.literal("Search"));
        box.setMaxLength(120);
        box.setBordered(false);
        box.setHint(Component.literal(tab == Tab.LISTS && openList == null ? "Filter, or type a name for a new playlist..."
                : "Search title, artist, album or file name..."));
        box.setValue(text);
        box.setResponder(v -> {
            if (v.equals(text)) return;
            text = v;
            lastText = v;
            selected = -1;
            scroll = 0;
        });
        addRenderableWidget(box);
        setInitialFocus(box);

        boolean wide = pw >= 340;
        int folderW = wide ? 84 : 52;
        int scanW = wide ? 50 : 36;
        int gap = 3;
        int tabW = (pw - 16 - folderW - scanW - gap * 5) / Tab.values().length;
        int x = px + 8;
        for (Tab t : Tab.values()) {
            StyledButton b = new StyledButton(x, py + 30, tabW, 15, Component.literal(t.label), btn -> switchTab(t)).accent(accent);
            if (t == tab && openList == null && openAuto == null) b.primary();
            addRenderableWidget(b);
            x += tabW + gap;
        }
        addRenderableWidget(new StyledButton(x, py + 30, folderW, 15, Component.literal(wide ? "Open folder" : "Folder"), b -> {
            if (!Folder.openMusic()) flash("Could not open the folder. It is: " + MusicLibrary.directory());
        }).accent(accent).tip("Opens your music folder. Drop MP3, OGG, WAV or FLAC files in it (subfolders are fine)."));
        x += folderW + gap;
        addRenderableWidget(new StyledButton(x, py + 30, scanW, 15, Component.literal(wide ? "Rescan" : "Scan"), b -> {
            MusicLibrary.rescanAsync();
            flash("Looking through the folder...");
        }).accent(accent).tip("Looks for new, changed and removed songs. This also happens by itself."));

        int top = py + 52;
        int by = py + 52;
        int right = px + pw - 8;
        switch (tab) {
            case SONGS -> {
                top += 20;
                right -= 66;
                addRenderableWidget(new StyledButton(right, by, 66, 15, Component.literal("Play all"), b -> playAll(false))
                        .accent(accent).primary().tip("Plays everything shown, from the top."));
                right -= 3 + 58;
                addRenderableWidget(new StyledButton(right, by, 58, 15, Component.literal("Shuffle"), b -> playAll(true))
                        .accent(accent).tip("Plays everything shown in random order."));
                right -= 3 + 84;
                addRenderableWidget(new StyledButton(right, by, 84, 15, Component.literal("Sort: " + sort.label), b -> {
                    sort = sort.next();
                    lastSort = sort;
                    b.setMessage(Component.literal("Sort: " + sort.label));
                    selected = -1;
                    scroll = 0;
                }).accent(accent).tip("Changes the order: title, artist, album or newest first."));
            }
            case QUEUE -> {
                top += 20;
                right -= 66;
                addRenderableWidget(new StyledButton(right, by, 66, 15, Component.literal("Clear"), b -> {
                    LocalPlayer.clearUpcoming();
                    selected = -1;
                }).accent(Colors.RED).tip("Empties the list of songs that come next. The song playing stays."));
            }
            case LISTS -> {
                top += 20;
                if (openList == null) {
                    right -= 104;
                    addRenderableWidget(new StyledButton(right, by, 104, 15, Component.literal("New playlist"), b -> createPlaylist())
                            .accent(accent).primary().tip("Makes a playlist with the name typed in the box above (or \"Playlist\")."));
                } else {
                    openButtons(by, right, accent, true);
                }
            }
            case SMART -> {
                top += 20;
                if (openAuto != null) openButtons(by, right, accent, false);
            }
        }
        listY = top;
        listH = py + ph - 20 - top;
    }

    /** Play / Shuffle (and Delete for a playlist) for an opened list. */
    private void openButtons(int by, int right, int accent, boolean playlist) {
        int x = right;
        if (playlist) {
            x -= 56;
            addRenderableWidget(new StyledButton(x, by, 56, 15, Component.literal(confirmDelete.equals(openList) ? "Sure?" : "Delete"), b -> {
                if (!confirmDelete.equals(openList)) {
                    confirmDelete = openList;
                    b.setMessage(Component.literal("Sure?"));
                    return;
                }
                Playlists.delete(openList);
                if (PlaylistRunner.active() && PlaylistRunner.name().equals(openList)) PlaylistRunner.stop();
                confirmDelete = "";
                openList = null;
                rebuildWidgets();
            }).accent(Colors.RED).tip("Deletes this playlist (the songs stay in your folder). Click twice."));
            x -= 4;
        }
        x -= 58;
        addRenderableWidget(new StyledButton(x, by, 58, 15, Component.literal("Shuffle"), b -> playOpen(true))
                .accent(accent).tip("Plays this list in random order."));
        x -= 4 + 46;
        addRenderableWidget(new StyledButton(x, by, 46, 15, Component.literal("Play"), b -> playOpen(false))
                .accent(accent).primary().tip("Plays this list from the top."));
    }

    private void switchTab(Tab t) {
        if (t == tab && openList == null && openAuto == null) return;
        tab = t;
        lastTab = t;
        openList = null;
        openAuto = null;
        menuFor = null;
        confirmDelete = "";
        scroll = 0;
        selected = -1;
        rebuildWidgets();
    }

    private void back() {
        if (menuFor != null) {
            menuFor = null;
            return;
        }
        if (openList != null || openAuto != null) {
            openList = null;
            openAuto = null;
            confirmDelete = "";
            scroll = 0;
            selected = -1;
            rebuildWidgets();
            return;
        }
        minecraft.setScreen(parent);
    }

    // ------------------------------------------------------------ rows

    private List<Row> rows() {
        long now = System.currentTimeMillis();
        String key = tab + "|" + text + "|" + sort + "|" + openList + "|" + openAuto + "|" + MusicLibrary.revision();
        boolean live = tab == Tab.QUEUE || tab == Tab.LISTS || tab == Tab.SMART;
        if (!key.equals(cacheKey) || (live && now - cacheAt > 350)) {
            cacheKey = key;
            cacheAt = now;
            cache = build();
        }
        return cache;
    }

    private List<Row> build() {
        List<Row> out = new ArrayList<>();
        hiddenInList = 0;
        switch (tab) {
            case SONGS -> {
                for (Track t : MusicLibrary.search(text, sort)) out.add(song(t, -1));
            }
            case QUEUE -> {
                for (Track t : LocalPlayer.upcoming()) out.add(song(t, -1));
            }
            case LISTS -> {
                if (openList == null) {
                    String q = Item.norm(text);
                    for (String n : Playlists.names()) {
                        if (!q.isEmpty() && !Item.norm(n).contains(q)) continue;
                        int files = 0;
                        List<Item> songs = Playlists.songs(n);
                        for (Item i : songs) if (i.isFile()) files++;
                        String sub = songs.size() + (songs.size() == 1 ? " song" : " songs")
                                + (files != songs.size() ? " · " + files + " from your folder" : "");
                        out.add(new Row(null, n, n, sub, -1));
                    }
                } else {
                    List<Item> songs = Playlists.songs(openList);
                    for (int i = 0; i < songs.size(); i++) {
                        Track t = songs.get(i).track();
                        if (t == null) {
                            hiddenInList++;
                            continue;
                        }
                        if (matches(t)) out.add(song(t, i));
                    }
                }
            }
            case SMART -> {
                if (openAuto == null) {
                    for (Auto a : Auto.values()) out.add(new Row(null, a.name(), a.label, a.hint, -1));
                } else {
                    for (Track t : autoTracks(openAuto)) if (matches(t)) out.add(song(t, -1));
                }
            }
        }
        return out;
    }

    private boolean matches(Track t) {
        if (text.isBlank()) return true;
        return Track.fold(t.title + " " + t.artist + " " + t.album + " " + t.fileName()).contains(Track.fold(text.strip()));
    }

    private Row song(Track t, int listIndex) {
        String sub = t.album.isEmpty() ? t.artist : t.artist.isEmpty() ? t.album : t.artist + " · " + t.album;
        return new Row(t, null, t.title, sub, listIndex);
    }

    private List<Track> autoTracks(Auto a) {
        switch (a) {
            case MOST -> {
                return MusicLibrary.mostPlayed(60);
            }
            case RECENT -> {
                return MusicLibrary.recentlyAdded(60);
            }
            case UNPLAYED -> {
                return MusicLibrary.notPlayedYet();
            }
            default -> {
                // Favourites follow you: a song you liked in Spotify counts when the same song is in your folder.
                if (byNameRevision != MusicLibrary.revision()) {
                    Map<String, Track> m = new HashMap<>();
                    for (Track t : MusicLibrary.all()) m.putIfAbsent(nameKey(t.title, t.artist), t);
                    byName = m;
                    byNameRevision = MusicLibrary.revision();
                }
                List<Track> out = new ArrayList<>();
                for (Item i : Library.favourites()) {
                    if (!i.isSong()) continue;
                    Track t = i.isFile() ? i.track() : byName.get(nameKey(i.title(), i.artist()));
                    if (t != null && !out.contains(t)) out.add(t);
                }
                return out;
            }
        }
    }

    private static String nameKey(String title, String artist) {
        return Item.norm(Lyrics.cleanTitle(title)) + "|" + Item.norm(Lyrics.firstArtist(artist));
    }

    private List<Track> tracksOf(List<Row> rows) {
        List<Track> out = new ArrayList<>();
        for (Row r : rows) if (r.isSong()) out.add(r.track());
        return out;
    }

    // ------------------------------------------------------------ actions

    private void flash(String message) {
        note = message;
        noteAt = System.currentTimeMillis();
    }

    private void playAll(boolean shuffle) {
        List<Track> list = tracksOf(rows());
        if (list.isEmpty()) {
            flash("There is nothing to play yet");
            return;
        }
        PlaylistRunner.stop();
        if (shuffle) LocalPlayer.playShuffled(list);
        else LocalPlayer.playFrom(list, 0);
    }

    private void playOpen(boolean shuffle) {
        if (tab == Tab.LISTS && openList != null) {
            if (!PlaylistRunner.start(openList, 0, shuffle)) flash("This playlist has no songs from your folder");
            return;
        }
        playAll(shuffle);
    }

    private void playRow(int idx) {
        List<Row> rows = rows();
        if (idx < 0 || idx >= rows.size()) return;
        Row r = rows.get(idx);
        if (!r.isSong()) {
            openCard(r);
            return;
        }
        if (tab == Tab.QUEUE) {
            LocalPlayer.playUpcoming(idx);
            return;
        }
        if (tab == Tab.LISTS && openList != null) {
            if (!PlaylistRunner.start(openList, r.listIndex(), false)) flash("Could not start the playlist");
            return;
        }
        PlaylistRunner.stop();
        int at = 0;
        List<Track> list = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            if (!rows.get(i).isSong()) continue;
            if (i == idx) at = list.size();
            list.add(rows.get(i).track());
        }
        Library.played(Item.forTrack(r.track()));
        LocalPlayer.playFrom(list, at);
    }

    private void openCard(Row r) {
        text = "";
        lastText = "";
        scroll = 0;
        selected = -1;
        confirmDelete = "";
        if (tab == Tab.LISTS) openList = r.card();
        else openAuto = Auto.valueOf(r.card());
        rebuildWidgets();
    }

    private void createPlaylist() {
        String made = Playlists.create(text.strip());
        if (made == null) {
            flash("You have the most playlists allowed (" + Playlists.MAX_LISTS + ")");
            return;
        }
        text = "";
        lastText = "";
        openList = made;
        scroll = 0;
        selected = -1;
        rebuildWidgets();
        flash("Playlist \"" + made + "\" made: add songs with the + button");
    }

    private void addTo(String list, Track t) {
        Item it = Item.forTrack(t);
        if (Playlists.contains(list, it)) flash("Already in \"" + list + "\"");
        else if (Playlists.add(list, it)) flash("Added to \"" + list + "\"");
        else flash("Could not add (the playlist is full)");
    }

    private static boolean isCurrent(Track t) {
        Track c = LocalPlayer.current();
        return c != null && c.id.equals(t.id);
    }

    private static String coverOf(Track t) {
        return t.cover.isEmpty() ? "" : "local:" + t.cover;
    }

    // ------------------------------------------------------------ menu

    private List<String> menuEntries() {
        List<String> names = new ArrayList<>();
        names.add("Play next");
        names.add("Add to queue");
        List<String> lists = new ArrayList<>(Playlists.names());
        if (lists.size() > 6) lists = new ArrayList<>(lists.subList(0, 6));
        names.addAll(lists);
        names.add("+ New playlist");
        return names;
    }

    private void drawMenu(GuiGraphics g, Font f, int mx, int my, int accent) {
        List<String> entries = menuEntries();
        int w = 150;
        int h = 6 + entries.size() * 13;
        int x = Math.max(px + 4, Math.min(menuX, px + pw - w - 4));
        int y = Math.max(py + 4, Math.min(menuY, py + ph - h - 4));
        menuX = x;
        menuY = y;
        Chrome.body(g, x, y, w, h, Colors.opaque(Theme.surface()), Colors.opaque(Colors.mix(Theme.line(), accent, 0.5f)));
        for (int i = 0; i < entries.size(); i++) {
            int ry = y + 3 + i * 13;
            boolean over = mx >= x + 2 && mx < x + w - 2 && my >= ry && my < ry + 13;
            if (over) g.fill(x + 2, ry, x + w - 2, ry + 13, Colors.alpha(accent, 0.3f));
            boolean special = i < 2 || i == entries.size() - 1;
            boolean has = !special && menuFor != null && Playlists.contains(entries.get(i), Item.forTrack(menuFor));
            Gfx.text(g, f, Gfx.fit(f, entries.get(i), w - 26), x + 8, ry + 3, special ? accent : Theme.text(), 1f, false);
            if (has) {
                int tc = Colors.opaque(accent);
                int tx = x + w - 15;
                int[][] ticks = {{0, 4}, {1, 5}, {2, 6}, {3, 5}, {4, 4}, {5, 3}, {6, 2}};
                for (int[] q : ticks) g.fill(tx + q[0], ry + q[1], tx + q[0] + 1, ry + q[1] + 1, tc);
            }
            if (i == 1) g.fill(x + 6, ry + 13, x + w - 6, ry + 14, Colors.alpha(Theme.line(), 0.8f));
        }
    }

    private boolean menuClick(double mx, double my) {
        List<String> entries = menuEntries();
        int w = 150;
        int h = 6 + entries.size() * 13;
        Track t = menuFor;
        menuFor = null;
        if (t == null || mx < menuX || mx >= menuX + w || my < menuY || my >= menuY + h) return true;
        int i = (int) ((my - menuY - 3) / 13);
        if (i < 0 || i >= entries.size()) return true;
        if (i == 0) {
            LocalPlayer.playNext(t);
            flash("Plays next: " + t.label());
        } else if (i == 1) {
            LocalPlayer.addToQueue(t);
            flash("Added to the queue");
        } else if (i == entries.size() - 1) {
            String made = Playlists.create(tab == Tab.LISTS || text.isBlank() ? "" : text.strip());
            if (made == null) {
                flash("You have the most playlists allowed (" + Playlists.MAX_LISTS + ")");
                return true;
            }
            addTo(made, t);
        } else {
            addTo(entries.get(i), t);
        }
        return true;
    }

    // ------------------------------------------------------------ drawing

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        SpotifyConfig cfg = ConfigIo.get();
        if (cfg.highContrast) {
            g.fill(0, 0, width, height, 0xFF000000);
            return;
        }
        int c = cfg.searchTint ? tint : Colors.CARD;
        g.fillGradient(0, 0, width, height, Colors.opaque(Colors.mix(Colors.CARD_DARK, c, 0.55f)),
                Colors.opaque(Colors.mix(0xFF05060A, c, 0.06f)));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        com.zlorbie788.spotify.config.UiFont oldFont = Gfx.font();
        int oldShift = Gfx.fontShift();
        Gfx.useFont(ConfigIo.get().uiFont, ConfigIo.get().fontShift);
        try {
            renderScreen(g, mouseX, mouseY, partialTick);
        } finally {
            Gfx.useFont(oldFont, oldShift);
        }
    }

    private void renderScreen(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        SpotifyConfig cfg = ConfigIo.get();
        Font f = font;
        int accent = PlayerHud.accent(cfg);
        long now = System.currentTimeMillis();

        List<Row> rows = rows();
        int perPage = Math.max(1, listH / ROW);
        scroll = Math.max(0, Math.min(Math.max(0, rows.size() - perPage), scroll));

        int tintTarget = PlayerHud.smoothAccent();
        if (!rows.isEmpty() && cfg.searchThumbs) {
            Row pick = rows.get(Math.max(0, Math.min(rows.size() - 1, selected < 0 ? 0 : selected)));
            if (pick.isSong()) tintTarget = Thumbs.colorOf(coverOf(pick.track()), tintTarget);
        }
        tint = cfg.motion() ? Colors.mix(tint, tintTarget, 0.08f) : tintTarget;

        Gfx.box(g, px, py, pw, ph, Math.max(1, cfg.corner),
                Colors.alpha(Colors.mix(Colors.CARD, tint, 0.18f), cfg.highContrast ? 1f : 0.92f),
                Colors.alpha(Colors.CARD_DARK, cfg.highContrast ? 1f : 0.94f),
                Colors.opaque(Colors.mix(Colors.LINE, tint, 0.35f)));
        Chrome.body(g, px + 32, py + 6, pw - 32 - 32, 20, Colors.opaque(Theme.well()),
                Colors.opaque(box != null && box.isFocused() ? accent : Theme.line()));

        super.render(g, mouseX, mouseY, partialTick);
        hint = "";

        drawBar(g, f, accent, rows);

        g.enableScissor(px + 4, listY, px + pw - 4, listY + listH);
        for (int i = 0; i < perPage + 1 && scroll + i < rows.size(); i++) {
            int idx = scroll + i;
            int ry = listY + i * ROW;
            if (ry + ROW > listY + listH + ROW / 2) break;
            if (dragging && idx == pressRow) continue;
            drawRow(g, f, rows.get(idx), idx, ry, mouseX, mouseY, accent);
        }
        if (dragging && pressRow >= 0 && pressRow < rows.size()) {
            int lineIdx = dragTo - scroll;
            int ly = listY + lineIdx * ROW + (dragTo > pressRow ? ROW - 1 : 0);
            g.fill(px + 8, ly, px + pw - 8, ly + 2, Colors.opaque(accent));
            int fy = (int) Math.max(listY - ROW / 2, Math.min(listY + listH - ROW / 2, mouseY - ROW / 2));
            drawRow(g, f, rows.get(pressRow), pressRow, fy, -1, -1, accent);
        }
        g.disableScissor();
        if (rows.size() > perPage) {
            int thumbH = Math.max(10, listH * perPage / rows.size());
            int ty = listY + (listH - thumbH) * scroll / Math.max(1, rows.size() - perPage);
            g.fill(px + pw - 6, listY, px + pw - 4, listY + listH, Colors.alpha(Theme.line(), 0.6f));
            g.fill(px + pw - 6, ty, px + pw - 4, ty + thumbH, Colors.opaque(Colors.mix(Theme.soft(), accent, 0.4f)));
        }
        if (rows.isEmpty()) drawEmpty(g, f, accent);
        drawStatus(g, f, rows, accent, now);
        if (menuFor != null) drawMenu(g, f, mouseX, mouseY, accent);
    }

    /** The line under the tabs: what is shown, with the buttons of the tab on its right. */
    private void drawBar(GuiGraphics g, Font f, int accent, List<Row> rows) {
        int y = py + 52;
        String title;
        String sub;
        int room;
        switch (tab) {
            case SONGS -> {
                title = "All songs";
                sub = rows.size() + (rows.size() == 1 ? " song" : " songs");
                room = pw - 16 - 66 - 58 - 84 - 9;
            }
            case QUEUE -> {
                Track c = LocalPlayer.current();
                title = c == null ? "Nothing is queued" : "Up next";
                int q = LocalPlayer.queuedCount();
                sub = rows.size() + " to come" + (q > 0 ? " · " + q + " added by you" : "");
                room = pw - 16 - 66 - 6;
            }
            case LISTS -> {
                title = openList == null ? "Your playlists" : openList;
                sub = openList == null ? Playlists.count() + (Playlists.count() == 1 ? " playlist" : " playlists")
                        : rows.size() + (rows.size() == 1 ? " song" : " songs")
                        + (hiddenInList > 0 ? " · " + hiddenInList + " not in your folder" : "");
                room = openList == null ? pw - 16 - 104 - 6 : pw - 16 - 56 - 58 - 46 - 20;
            }
            default -> {
                title = openAuto == null ? "Smart lists" : openAuto.label;
                sub = openAuto == null ? "made for you, always up to date" : rows.size() + (rows.size() == 1 ? " song" : " songs");
                room = openAuto == null ? pw - 24 : pw - 16 - 58 - 46 - 12;
            }
        }
        String t = Gfx.fitBold(f, title, Math.max(30, room));
        Gfx.textBold(g, f, t, px + 10, y + 3, Theme.text(), 1f, true);
        int left = room - Gfx.width(f, t, true) - 10;
        if (left > 28) Gfx.text(g, f, Gfx.fit(f, sub, left), px + 10 + Gfx.width(f, t, true) + 8, y + 3, Theme.dim(), 1f, false);
    }

    private void drawRow(GuiGraphics g, Font f, Row row, int idx, int ry, int mx, int my, int accent) {
        int rx = px + 6;
        int rw = pw - 14;
        boolean over = mx >= rx && mx < rx + rw && my >= ry && my < ry + ROW && my >= listY && my < listY + listH;
        boolean sel = idx == selected;
        if (over || sel || (dragging && idx == pressRow)) {
            g.fill(rx, ry, rx + rw, ry + ROW - 1, Colors.alpha(accent, sel || dragging ? 0.26f : 0.14f));
            if (sel) g.renderOutline(rx, ry, rw, ROW - 1, Colors.alpha(accent, 0.7f));
        }
        int tx0 = rx + 4;
        if (tab == Tab.QUEUE) {
            int gc = Colors.alpha(over ? Theme.soft() : Theme.dim(), 0.8f);
            for (int k = 0; k < 3; k++) g.fill(rx + 4, ry + 9 + k * 4, rx + 12, ry + 10 + k * 4, gc);
            tx0 = rx + 16;
        }
        int heartX = rx + rw - 20;
        int plusX = heartX - 20;
        if (!row.isSong()) {
            String[] icon = tab == Tab.SMART ? Auto.valueOf(row.card()).icon : Icons.ALBUM;
            g.fillGradient(tx0, ry + 2, tx0 + THUMB, ry + 2 + THUMB, Colors.opaque(Colors.mix(accent, Colors.CARD_DARK, 0.55f)),
                    Colors.opaque(Colors.mix(accent, Colors.CARD_DARK, 0.82f)));
            Icons.draw(g, icon, tx0 + (THUMB - Icons.width(icon)) / 2, ry + 2 + (THUMB - Icons.height(icon)) / 2, 1,
                    Colors.alpha(Colors.lighten(accent, 0.3f), 0.85f));
            int tx = tx0 + THUMB + 8;
            int right = rx + rw - 28;
            Gfx.line(g, f, Gfx.fit(f, row.title(), right - tx), tx, ry + 5, right - tx, Theme.text(), 1f, false, false, false, 0L);
            Gfx.text(g, f, Gfx.fit(f, row.sub(), right - tx), tx, ry + 16, Theme.dim(), 1f, false);
            if (tab == Tab.LISTS) {
                boolean overDel = over && mx >= heartX - 2 && mx < heartX + 14;
                Icons.draw(g, Icons.CLOSE, heartX, ry + 9, 1, Colors.alpha(overDel ? Colors.RED : Theme.dim(), overDel || over ? 0.95f : 0.5f));
                if (overDel) hint = confirmDelete.equals(row.card()) ? "Click again to delete the playlist" : "Delete this playlist";
            } else {
                Icons.draw(g, Icons.PLAY, heartX, ry + 9, 1, Colors.alpha(over ? accent : Theme.dim(), over ? 0.95f : 0.5f));
            }
            if (over && hint.isEmpty()) hint = "Open";
            return;
        }
        Track t = row.track();
        boolean now = isCurrent(t);
        drawThumb(g, coverOf(t), tx0, ry + 2, THUMB, accent);
        int right = plusX - 4;
        String dur = t.durMs > 0 ? Ease.clock(t.durMs) : "";
        int durW = dur.isEmpty() || pw <= 260 ? 0 : Gfx.width(f, dur) + 6;
        if (durW > 0) Gfx.drawStyled(g, f, dur, right - Gfx.width(f, dur), ry + 10, Colors.opaque(Theme.dim()), false, false, false);
        int tx = tx0 + THUMB + 8;
        int tw = right - durW - tx - 2;
        Gfx.line(g, f, Gfx.fit(f, row.title(), tw), tx, ry + 5, tw, now ? accent : Theme.text(), 1f, false, false, false, 0L);
        Gfx.text(g, f, Gfx.fit(f, row.sub(), tw), tx, ry + 16, Theme.dim(), 1f, false);

        boolean removable = tab == Tab.QUEUE || (tab == Tab.LISTS && openList != null);
        boolean overHeart = over && mx >= heartX - 2 && mx < heartX + 14;
        if (removable) {
            Icons.draw(g, Icons.CLOSE, heartX, ry + 9, 1, Colors.alpha(overHeart ? Colors.RED : Theme.dim(), overHeart || over ? 0.95f : 0.5f));
            if (overHeart) hint = tab == Tab.QUEUE ? "Take this song out of the queue" : "Take this song out of the playlist";
        } else {
            boolean liked = Library.isFavouriteSong(t.title, t.artist);
            Icons.draw(g, Icons.HEART, heartX, ry + 9, 1, liked ? Colors.opaque(Colors.PINK)
                    : Colors.alpha(overHeart ? Theme.text() : Theme.dim(), overHeart || over ? 0.9f : 0.5f));
            if (overHeart) hint = liked ? "Remove from Favourites" : "Add to Favourites";
        }
        boolean overAdd = over && mx >= plusX - 2 && mx < plusX + 14;
        int pc = Colors.alpha(overAdd ? accent : Theme.dim(), overAdd ? 1f : over ? 0.9f : 0.5f);
        g.fill(plusX + 2, ry + 13, plusX + 11, ry + 15, pc);
        g.fill(plusX + 5, ry + 10, plusX + 8, ry + 18, pc);
        if (overAdd) hint = "Play next, add to the queue or to a playlist";
        if (over && hint.isEmpty()) hint = tab == Tab.QUEUE ? "Click to play it now, drag to move it" : "Play from this song";
    }

    private void drawThumb(GuiGraphics g, String url, int x, int y, int size, int accent) {
        Thumbs.Thumb t = Thumbs.get(url);
        if (t != null) {
            g.blit(RenderPipelines.GUI_TEXTURED, t.id(), x, y, 0f, 0f, size, size, 64, 64, 64, 64, 0xFFFFFFFF);
            return;
        }
        g.fillGradient(x, y, x + size, y + size, Colors.opaque(Colors.mix(accent, Colors.CARD_DARK, 0.55f)),
                Colors.opaque(Colors.mix(accent, Colors.CARD_DARK, 0.82f)));
        Icons.draw(g, Icons.NOTE, x + (size - Icons.width(Icons.NOTE)) / 2, y + (size - Icons.height(Icons.NOTE)) / 2, 1,
                Colors.alpha(Colors.lighten(accent, 0.3f), 0.75f));
    }

    private void drawEmpty(GuiGraphics g, Font f, int accent) {
        String a;
        String b = "";
        if (tab == Tab.SONGS && MusicLibrary.isEmpty()) {
            if (MusicLibrary.scanning() || !MusicLibrary.ready()) {
                a = "Looking through your music folder...";
            } else {
                a = "Drop your songs into the music folder";
                b = "Press Open folder, copy MP3, OGG, WAV or FLAC files in (subfolders are fine), and they appear here. "
                        + "Folder: " + MusicLibrary.directory();
            }
        } else if (tab == Tab.SONGS) {
            a = "No song matches";
            b = "Try fewer letters. Title, artist, album and file name are searched.";
        } else if (tab == Tab.QUEUE) {
            a = "The queue is empty";
            b = "Use the + button next to a song and choose Play next or Add to queue.";
        } else if (tab == Tab.LISTS) {
            if (openList == null) {
                a = text.isBlank() ? "No playlists yet" : "No playlist matches";
                b = "Type a name in the box above and press New playlist. Add songs with the + button.";
            } else {
                a = text.isBlank() ? "No songs from your folder in this playlist" : "No song matches";
                b = hiddenInList > 0 ? hiddenInList + " Spotify songs in it are only shown in the Spotify search screen." : "Use the + button next to a song.";
            }
        } else {
            a = openAuto == Auto.FAVOURITES ? "No favourites in your folder yet" : "Nothing here yet";
            b = openAuto == Auto.FAVOURITES ? "Press the heart next to a song. Songs you liked in Spotify show up too when you own the file." : "";
        }
        int y = listY + Math.max(8, listH / 2 - 14);
        String fa = Gfx.fit(f, a, pw - 24);
        Gfx.drawStyled(g, f, fa, px + (pw - Gfx.width(f, fa)) / 2, y, Colors.opaque(Theme.soft()), false, false, false);
        if (!b.isEmpty()) {
            for (String line : com.zlorbie788.spotify.util.TextWrap.wrap(b, pw - 40, 4, t -> Gfx.width(f, t))) {
                y += 11;
                Gfx.drawStyled(g, f, line, px + (pw - Gfx.width(f, line)) / 2, y, Colors.opaque(Theme.dim()), false, false, false);
            }
        }
    }

    /** The line that offers the song troubleshooter, or null when everything is fine. */
    private String doctorLine() {
        List<String> p = MusicLibrary.problems();
        if (!p.isEmpty()) {
            // "name: reason" (a file name never holds a colon): the reason comes first so a long name cannot hide it
            String first = p.get(0);
            int cut = first.indexOf(": ");
            String why = cut > 0 ? first.substring(cut + 2) : first;
            String name = cut > 0 ? first.substring(0, cut) : "";
            return "Click to fix: " + p.size() + (p.size() == 1 ? " file could not be read" : " files could not be read") + " (" + why + ")"
                    + (name.isEmpty() ? "" : ": " + name);
        }
        if (tab == Tab.SONGS && MusicLibrary.count() == 0 && !MusicLibrary.scanning() && openList == null) {
            return "No songs found. Click here to see why (Fix my songs).";
        }
        return null;
    }

    private void drawStatus(GuiGraphics g, Font f, List<Row> rows, int accent, long now) {
        int y = py + ph - 15;
        g.fill(px + 8, y - 3, px + pw - 8, y - 2, Colors.alpha(Theme.line(), 0.7f));
        String line;
        int color;
        String err = LocalPlayer.error();
        if (!note.isEmpty() && now - noteAt < 4000) {
            line = note;
            color = accent;
        } else if (!hint.isEmpty()) {
            line = hint;
            color = Theme.soft();
        } else if (!err.isEmpty()) {
            line = err;
            color = Colors.RED;
        } else if (com.zlorbie788.spotify.lyrics.LyricsBulk.running()) {
            line = com.zlorbie788.spotify.lyrics.LyricsBulk.status();
            color = Theme.dim();
        } else if (com.zlorbie788.spotify.local.TagFixer.running()) {
            line = com.zlorbie788.spotify.local.TagFixer.status();
            color = Theme.dim();
        } else if (MusicLibrary.scanning()) {
            line = "Looking through the folder... " + MusicLibrary.count() + " songs so far";
            color = Theme.dim();
        } else if (doctorLine() != null) {
            line = doctorLine();
            color = Colors.GOLD;
        } else if (tab == Tab.SONGS) {
            line = MusicLibrary.count() + " songs · " + MusicLibrary.directory() + " · works without Spotify";
            color = Theme.dim();
        } else {
            line = "Stored on this PC only · Enter plays · Esc goes back";
            color = Theme.dim();
        }
        Gfx.drawStyled(g, f, Gfx.fit(f, line, pw - 24), px + 10, y, Colors.opaque(color), false, false, false);
    }

    // ------------------------------------------------------------ input

    private int rowAtMouse(double mx, double my) {
        if (mx < px + 6 || mx >= px + pw - 8 || my < listY || my >= listY + listH) return -1;
        int idx = scroll + (int) ((my - listY) / ROW);
        return idx < rows().size() ? idx : -1;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (menuFor != null) return event.button() == 0 ? menuClick(event.x(), event.y()) : true;
        if (event.button() == 0 && super.mouseClicked(event, doubleClick)) return true;
        if (event.button() == 0 && doctorLine() != null && event.x() >= px + 8 && event.x() < px + pw - 8
                && event.y() >= py + ph - 19 && event.y() < py + ph - 2) {
            minecraft.setScreen(new SongDoctorScreen(this));
            return true;
        }
        if (event.button() != 0) return false;
        double mx = event.x();
        double my = event.y();
        int idx = rowAtMouse(mx, my);
        if (idx < 0) return false;
        List<Row> rows = rows();
        Row row = rows.get(idx);
        selected = idx;
        int rx = px + 6;
        int rw = pw - 14;
        int heartX = rx + rw - 20;
        int plusX = heartX - 20;
        boolean onHeart = mx >= heartX - 2 && mx < heartX + 14;
        boolean onPlus = mx >= plusX - 2 && mx < plusX + 14;
        if (!row.isSong()) {
            if (tab == Tab.LISTS && onHeart) {
                if (!confirmDelete.equals(row.card())) {
                    confirmDelete = row.card();
                    flash("Click the x again to delete \"" + row.card() + "\"");
                } else {
                    Playlists.delete(row.card());
                    if (PlaylistRunner.active() && PlaylistRunner.name().equals(row.card())) PlaylistRunner.stop();
                    confirmDelete = "";
                    selected = -1;
                }
                return true;
            }
            openCard(row);
            return true;
        }
        Track t = row.track();
        if (onPlus) {
            menuFor = t;
            menuX = (int) mx - 6;
            menuY = (int) my + 4;
            return true;
        }
        if (onHeart) {
            if (tab == Tab.QUEUE) {
                LocalPlayer.removeUpcoming(idx);
                selected = -1;
            } else if (tab == Tab.LISTS && openList != null) {
                Playlists.removeAt(openList, row.listIndex());
                selected = -1;
            } else {
                Library.toggleFavouriteSong(Item.forTrack(t));
            }
            cacheKey = "";
            return true;
        }
        if (tab == Tab.QUEUE) {
            pressRow = idx;
            pressY = my;
            dragging = false;
            dragTo = idx;
            return true;
        }
        playRow(idx);
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (pressRow >= 0 && tab == Tab.QUEUE) {
            if (!dragging && Math.abs(event.y() - pressY) > 4) dragging = true;
            if (dragging) {
                int size = rows().size();
                int idx = scroll + (int) Math.floor((event.y() - listY) / ROW);
                dragTo = Math.max(0, Math.min(size - 1, idx));
                if (event.y() < listY + 6) scroll = Math.max(0, scroll - 1);
                else if (event.y() > listY + listH - 6) scroll++;
            }
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (pressRow >= 0) {
            int from = pressRow;
            int to = dragTo;
            boolean moved = dragging;
            pressRow = -1;
            dragging = false;
            if (moved) {
                if (to != from) {
                    LocalPlayer.moveUpcoming(from, to);
                    selected = to;
                    cacheKey = "";
                }
            } else {
                playRow(from);
            }
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0 && mouseX >= px && mouseX < px + pw && mouseY >= listY && mouseY < listY + listH) {
            scroll -= (int) Math.signum(scrollY) * 2;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        List<Row> rows = rows();
        int perPage = Math.max(1, listH / ROW);
        switch (key) {
            case GLFW.GLFW_KEY_ESCAPE -> {
                back();
                return true;
            }
            case GLFW.GLFW_KEY_DOWN -> {
                if (!rows.isEmpty()) select(Math.min(rows.size() - 1, selected + 1), perPage);
                return true;
            }
            case GLFW.GLFW_KEY_UP -> {
                if (!rows.isEmpty()) select(Math.max(0, selected - 1), perPage);
                return true;
            }
            case GLFW.GLFW_KEY_PAGE_DOWN -> {
                if (!rows.isEmpty()) select(Math.min(rows.size() - 1, Math.max(0, selected) + perPage), perPage);
                return true;
            }
            case GLFW.GLFW_KEY_PAGE_UP -> {
                if (!rows.isEmpty()) select(Math.max(0, selected - perPage), perPage);
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (!rows.isEmpty()) playRow(selected >= 0 && selected < rows.size() ? selected : 0);
                return true;
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (tab == Tab.QUEUE && selected >= 0 && selected < rows.size()) {
                    LocalPlayer.removeUpcoming(selected);
                    cacheKey = "";
                    selected = -1;
                    return true;
                }
            }
            default -> { }
        }
        return super.keyPressed(event);
    }

    private void select(int index, int perPage) {
        selected = index;
        if (index < scroll) scroll = index;
        if (index >= scroll + perPage) scroll = index - perPage + 1;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        ConfigIo.save();
    }
}
