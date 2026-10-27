package com.zlorbie788.spotify.gui;

import com.zlorbie788.spotify.catalog.Catalog;
import com.zlorbie788.spotify.catalog.Item;
import com.zlorbie788.spotify.catalog.Library;
import com.zlorbie788.spotify.catalog.PlaylistRunner;
import com.zlorbie788.spotify.catalog.Playlists;
import com.zlorbie788.spotify.catalog.Playback;
import com.zlorbie788.spotify.catalog.Verifier;
import com.zlorbie788.spotify.catalog.SpotifyLink;
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
import com.zlorbie788.spotify.lyrics.Lyrics;
import com.zlorbie788.spotify.media.Player;
import com.zlorbie788.spotify.media.Snapshot;
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
import java.util.List;
import java.util.Locale;

/**
 * Search for any song, find it in the public music catalogues and play it in Spotify. No account is needed
 * anywhere: the mod opens the song in the Spotify app on your PC and presses play. Also holds your own little
 * library (liked songs, saved albums, history) and shows the songs of an album.
 *
 * <p>Keys: type to search, Up/Down choose, Enter play, Esc go back. Click a cover row to play, the heart to like.
 */
public class SearchScreen extends Screen implements PlayerHud.HidesHud {
    private enum Tab {
        SONGS("Songs"), ALBUMS("Albums"), LIKED("Liked"), SAVED("Saved"), HISTORY("History"), LISTS("Lists");

        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    private static final int ROW = 28;
    private static final int THUMB = 24;

    private static Tab lastTab = Tab.SONGS;
    private static String lastText = "";

    private final Screen parent;
    private Tab tab = lastTab;
    private EditBox box;
    private String text = lastText;
    private boolean dirty;
    private long typedAt;
    private Catalog.Result listResult;
    private Catalog.Result shownResult;
    private Catalog.Result detailResult;
    /** The search whose results were handed to the Spotify check. */
    private Catalog.Result verifiedFor;
    private Item detail;
    private SpotifyLink link;
    private volatile Item linkItem;
    private String autoOpenAlbum = "";

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
    private String lastMsg = "";
    private long lastMsgAt;
    private boolean started;
    /** The playlist that is open in the Lists tab (null = the list of playlists). */
    private String openList;
    /** The playlist waiting for a second click to be deleted. */
    private String confirmDelete = "";
    /** The song the "add to playlist" menu is open for (null = no menu). */
    private Item menuFor;
    private int menuX;
    private int menuY;
    private String note = "";
    private long noteAt;

    public SearchScreen(Screen parent) {
        super(Component.literal("Search"));
        this.parent = parent;
    }

    /** Opens the songs tab with this text already searched. */
    public static SearchScreen withQuery(Screen parent, String query) {
        SearchScreen sc = new SearchScreen(parent);
        sc.tab = Tab.SONGS;
        sc.text = query == null ? "" : query.strip();
        return sc;
    }

    /** Opens on the Lists tab (your own playlists). */
    public void showLists() {
        tab = Tab.LISTS;
        lastTab = Tab.LISTS;
        openList = null;
    }

    /** Opens on the albums tab and jumps into the album of the song that is playing. */
    public static SearchScreen forAlbumOf(Screen parent, Snapshot s) {
        SearchScreen sc = new SearchScreen(parent);
        sc.tab = Tab.ALBUMS;
        sc.text = (s.album() + " " + Lyrics.firstArtist(s.artist())).strip();
        sc.autoOpenAlbum = Item.norm(s.album());
        return sc;
    }

    /** Opens the songs tab with the artist of the song that is playing. */
    public static SearchScreen forArtist(Screen parent, Snapshot s) {
        SearchScreen sc = new SearchScreen(parent);
        sc.tab = Tab.SONGS;
        sc.text = Lyrics.firstArtist(s.artist());
        return sc;
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
                .accent(accent).tip(detail != null ? "Back to the list (Esc)" : "Back to the player (Esc)"));
        addRenderableWidget(new IconButton(px + pw - 28, py + 8, 20, 16, "Close", () -> Icons.CLOSE, () -> minecraft.setScreen(null))
                .accent(Colors.RED).tip("Close everything"));

        box = new EditBox(font, px + 34, py + 8, pw - 34 - 34, 16, Component.literal("Search"));
        box.setMaxLength(120);
        box.setBordered(false);
        box.setHint(Component.literal(detail != null ? "Album: " + detail.title()
                : tab == Tab.ALBUMS ? "Search albums..."
                : tab == Tab.LISTS ? (openList == null ? "Filter, or type a name for a new playlist..." : "Filter songs, or type a new name...")
                : "Search songs, artists or paste a Spotify link..."));
        box.setValue(text);
        box.setResponder(v -> {
            if (v.equals(text)) return;
            text = v;
            lastText = v;
            dirty = true;
            typedAt = System.currentTimeMillis();
            selected = -1;
            scroll = 0;
        });
        addRenderableWidget(box);
        setInitialFocus(box);

        int tabs = Tab.values().length;
        int tw = (pw - 16 - (tabs - 1) * 3) / tabs;
        int x = px + 8;
        for (Tab t : Tab.values()) {
            StyledButton b = new StyledButton(x, py + 30, tw, 15, Component.literal(t.label), btn -> switchTab(t))
                    .accent(accent);
            if (t == tab && detail == null) b.primary();
            addRenderableWidget(b);
            x += tw + 3;
        }

        int top = py + 52;
        if (detail != null) {
            top += 38;
            boolean saved = Library.isSaved(detail);
            addRenderableWidget(new StyledButton(px + pw - 8 - 150, py + 56, 46, 15, Component.literal("Play"),
                    b -> playAlbum()).accent(accent).primary().tip("Play the first song of this album in Spotify"));
            addRenderableWidget(new StyledButton(px + pw - 8 - 100, py + 56, 52, 15, Component.literal("Spotify"),
                    b -> Playback.openPage(detail)).accent(accent).tip("Open this album in the Spotify app"));
            IconButton save = new IconButton(px + pw - 8 - 44, py + 56, 22, 15, "Save album", () -> Icons.HEART,
                    () -> {
                        Library.toggleAlbum(detail);
                        rebuildWidgets();
                    }).accent(Colors.PINK).lit(() -> saved).tip(saved ? "Remove from your saved albums" : "Save this album");
            addRenderableWidget(save);
        }
        if (tab == Tab.LISTS && detail == null) {
            top += 22;
            int by = py + 54;
            if (openList == null) {
                addRenderableWidget(new StyledButton(px + pw - 8 - 104, by, 104, 15, Component.literal("New playlist"),
                        b -> createPlaylist()).accent(accent).primary()
                        .tip("Makes a playlist with the name typed in the box above (or \"Playlist\")."));
            } else {
                int x2 = px + pw - 8;
                x2 -= 56;
                addRenderableWidget(new StyledButton(x2, by, 56, 15, Component.literal(confirmDelete.equals(openList) ? "Sure?" : "Delete"),
                        b -> {
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
                        }).accent(Colors.RED).tip("Deletes this playlist (the songs stay wherever else they are). Click twice."));
                x2 -= 4 + 56;
                addRenderableWidget(new StyledButton(x2, by, 56, 15, Component.literal("Rename"), b -> renamePlaylist())
                        .accent(accent).tip("Gives the playlist the name typed in the box above."));
                x2 -= 4 + 58;
                addRenderableWidget(new StyledButton(x2, by, 58, 15, Component.literal("Shuffle"), b -> {
                    if (!PlaylistRunner.start(openList, 0, true)) flash("This playlist is empty");
                }).accent(accent).tip("Plays the playlist in random order."));
                x2 -= 4 + 46;
                addRenderableWidget(new StyledButton(x2, by, 46, 15, Component.literal("Play"), b -> {
                    if (!PlaylistRunner.start(openList, 0, false)) flash("This playlist is empty");
                }).accent(accent).primary().tip("Plays the playlist from the top, one song after the other."));
            }
        }
        listY = top;
        listH = py + ph - 20 - top;
        if (!started) {
            started = true;
            submit();
        }
    }

    private void switchTab(Tab t) {
        if (t == tab && detail == null) return;
        tab = t;
        lastTab = t;
        openList = null;
        menuFor = null;
        detail = null;
        detailResult = null;
        scroll = 0;
        selected = -1;
        rebuildWidgets();
        submit();
    }

    private void back() {
        if (menuFor != null) {
            menuFor = null;
            return;
        }
        if (tab == Tab.LISTS && detail == null && openList != null) {
            openList = null;
            confirmDelete = "";
            scroll = 0;
            selected = -1;
            rebuildWidgets();
            return;
        }
        if (detail != null) {
            detail = null;
            detailResult = null;
            scroll = 0;
            selected = -1;
            rebuildWidgets();
        } else {
            minecraft.setScreen(parent);
        }
    }

    // ------------------------------------------------------------ searching

    private void submit() {
        dirty = false;
        link = null;
        linkItem = null;
        String q = text.strip();
        if (tab == Tab.LIKED || tab == Tab.SAVED || tab == Tab.HISTORY || tab == Tab.LISTS) {
            listResult = null;
            return;
        }
        link = SpotifyLink.parse(q);
        if (link != null) {
            SpotifyLink l = link;
            Thread t = new Thread(() -> {
                try {
                    linkItem = l.describe();
                } catch (Exception e) {
                    linkItem = new Item(Item.Kind.SONG, Item.Provider.SPOTIFY, l.id(), "Spotify " + l.type(), "Spotify link",
                            "", "", 0, "", "", 0, false, l.uri());
                }
            }, "spotify-integration-link");
            t.setDaemon(true);
            t.start();
            listResult = null;
            return;
        }
        Catalog.Mode mode = tab == Tab.ALBUMS ? Catalog.Mode.ALBUMS : q.isEmpty() ? Catalog.Mode.TRENDING : Catalog.Mode.SONGS;
        if (tab == Tab.ALBUMS && q.isEmpty()) {
            listResult = null;
            return;
        }
        listResult = Catalog.submit(mode, q, null);
    }

    private void openDetail(Item album) {
        detail = album;
        detailResult = Catalog.submit(Catalog.Mode.TRACKS_OF, "", album);
        scroll = 0;
        selected = -1;
        rebuildWidgets();
    }

    private List<Item> rows() {
        List<Item> out = new ArrayList<>();
        if (detail != null) {
            if (detailResult != null && detailResult.done) out.addAll(detailResult.items);
            return out;
        }
        switch (tab) {
            case LIKED -> filter(Library.favourites(), out);
            case SAVED -> filter(Library.albums(), out);
            case HISTORY -> filter(Library.history(), out);
            case LISTS -> {
                if (openList == null) {
                    List<Item> cards = new ArrayList<>();
                    for (String n : Playlists.names()) {
                        List<Item> songs = Playlists.songs(n);
                        String cover = songs.isEmpty() ? "" : songs.get(0).picture(false);
                        cards.add(new Item(Item.Kind.ALBUM, Item.Provider.LOCAL, "pl:" + n, n, "", "", "", 0, cover, "",
                                songs.size(), false, ""));
                    }
                    filter(cards, out);
                } else {
                    filter(Playlists.songs(openList), out);
                }
            }
            default -> {
                if (link != null) {
                    Item li = linkItem;
                    if (li != null) out.add(li);
                } else if (listResult != null && listResult.done) {
                    out.addAll(filtering() ? Verifier.playable(listResult.items) : listResult.items);
                }
            }
        }
        return out;
    }

    /** True when the results of the search are only shown once they are known to play in Spotify. */
    private boolean filtering() {
        SpotifyConfig c = ConfigIo.get();
        return c.onlyPlayable && c.exactSpotify && c.onlineSearch && detail == null && link == null
                && (tab == Tab.SONGS || tab == Tab.ALBUMS);
    }

    /** Hands a new list of results to the Spotify check (once per search). */
    private void kickVerify() {
        if (!filtering() || listResult == null || !listResult.done || listResult == verifiedFor) return;
        verifiedFor = listResult;
        Verifier.want(listResult.items);
    }

    private void filter(List<Item> source, List<Item> out) {
        String q = Item.norm(text);
        for (Item i : source) {
            if (q.isEmpty() || Item.norm(i.title() + i.artist() + i.album()).contains(q)) out.add(i);
        }
    }

    // ------------------------------------------------------------ actions

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

    private void renamePlaylist() {
        if (openList == null) return;
        String wanted = text.strip();
        if (wanted.isEmpty()) {
            flash("Type the new name in the box above first");
            return;
        }
        String now = Playlists.rename(openList, wanted);
        if (now != null) {
            if (PlaylistRunner.active() && PlaylistRunner.name().equals(openList)) PlaylistRunner.stop();
            openList = now;
            text = "";
            lastText = "";
            rebuildWidgets();
            flash("Renamed to \"" + now + "\"");
        }
    }

    private void flash(String message) {
        note = message;
        noteAt = System.currentTimeMillis();
    }

    private int indexInList(Item it) {
        if (openList == null) return 0;
        List<Item> all = Playlists.songs(openList);
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).key().equals(it.key())) return i;
        }
        return 0;
    }

    private void addTo(String list, Item it) {
        if (Playlists.contains(list, it)) {
            flash("Already in \"" + list + "\"");
        } else if (Playlists.add(list, it)) {
            flash("Added to \"" + list + "\"");
        } else {
            flash("Could not add (the playlist is full)");
        }
    }

    private void activate(Item it) {
        if (tab == Tab.LISTS && detail == null) {
            if (openList == null) {
                openList = it.title();
                text = "";
                lastText = "";
                scroll = 0;
                selected = -1;
                confirmDelete = "";
                rebuildWidgets();
            } else {
                PlaylistRunner.start(openList, indexInList(it), false);
            }
            return;
        }
        if (it.isAlbum()) {
            openDetail(it);
            return;
        }
        if (it.provider() == Item.Provider.SPOTIFY && link != null && !link.playable()) {
            Playback.openPage(it);
            return;
        }
        PlaylistRunner.stop();
        Library.played(it);
        if (link != null && it.provider() == Item.Provider.SPOTIFY) Playback.playLink(link, it.title());
        else Playback.play(it);
    }

    private void playAlbum() {
        if (detailResult != null && detailResult.done && !detailResult.items.isEmpty()) {
            Library.played(detailResult.items.get(0));
            Playback.play(detailResult.items.get(0));
        } else if (detail != null) {
            Playback.play(detail);
        }
    }

    private boolean liked(Item it) {
        return it.isAlbum() ? Library.isSaved(it) : Library.isFavouriteSong(it.title(), it.artist());
    }

    private void toggleLike(Item it) {
        if (it.isAlbum()) Library.toggleAlbum(it);
        else Library.toggleFavouriteSong(it);
    }

    private static boolean isPlaying(Item it) {
        Snapshot s = Player.snap();
        if (!s.active() || !it.isSong()) return false;
        return Item.norm(Lyrics.cleanTitle(s.title())).equals(Item.norm(Lyrics.cleanTitle(it.title())))
                && Item.norm(Lyrics.firstArtist(s.artist())).equals(Item.norm(Lyrics.firstArtist(it.artist())));
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
        int topC = Colors.opaque(Colors.mix(Colors.CARD_DARK, c, 0.55f));
        int botC = Colors.opaque(Colors.mix(0xFF05060A, c, 0.06f));
        g.fillGradient(0, 0, width, height, topC, botC);
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

        if (dirty && now - typedAt > 380) submit();
        if (autoOpenAlbum.length() > 0 && listResult != null && listResult.done && detail == null) {
            String want = autoOpenAlbum;
            autoOpenAlbum = "";
            for (Item it : listResult.items) {
                String n = Item.norm(it.title());
                if (n.equals(want) || n.startsWith(want) || want.startsWith(n)) {
                    openDetail(it);
                    break;
                }
            }
        }
        kickVerify();
        List<Item> rows = rows();
        Catalog.Result active = detail != null ? detailResult : listResult;
        if (active != shownResult) {
            shownResult = active;
            scroll = 0;
            selected = rows.isEmpty() ? -1 : (selected >= rows.size() ? -1 : selected);
        }
        int perPage = Math.max(1, listH / ROW);
        scroll = Math.max(0, Math.min(Math.max(0, rows.size() - perPage), scroll));

        // tint follows the first cover
        int tintTarget = PlayerHud.smoothAccent();
        if (!rows.isEmpty() && cfg.searchThumbs) {
            Item pick = detail != null ? detail : rows.get(Math.max(0, Math.min(rows.size() - 1, selected < 0 ? 0 : selected)));
            tintTarget = Thumbs.colorOf(pick.picture(false), tintTarget);
        }
        tint = cfg.motion() ? Colors.mix(tint, tintTarget, 0.08f) : tintTarget;

        // panel
        Gfx.box(g, px, py, pw, ph, Math.max(1, cfg.corner),
                Colors.alpha(Colors.mix(Colors.CARD, tint, 0.18f), cfg.highContrast ? 1f : 0.92f),
                Colors.alpha(Colors.CARD_DARK, cfg.highContrast ? 1f : 0.94f),
                Colors.opaque(Colors.mix(Colors.LINE, tint, 0.35f)));
        // search field backdrop
        Chrome.body(g, px + 32, py + 6, pw - 32 - 32, 20, Colors.opaque(Theme.well()),
                Colors.opaque(box != null && box.isFocused() ? accent : Theme.line()));

        super.render(g, mouseX, mouseY, partialTick);
        hint = "";

        // album header
        if (detail != null) drawHeader(g, f, accent);

        // list
        g.enableScissor(px + 4, listY, px + pw - 4, listY + listH);
        for (int i = 0; i < perPage + 1 && scroll + i < rows.size(); i++) {
            int idx = scroll + i;
            int ry = listY + i * ROW;
            if (ry + ROW > listY + listH + ROW / 2) break;
            drawRow(g, f, rows.get(idx), idx, ry, mouseX, mouseY, accent);
        }
        g.disableScissor();
        if (rows.size() > perPage) {
            int trackH = listH;
            int thumbH = Math.max(10, trackH * perPage / rows.size());
            int ty = listY + (trackH - thumbH) * scroll / Math.max(1, rows.size() - perPage);
            g.fill(px + pw - 6, listY, px + pw - 4, listY + trackH, Colors.alpha(Theme.line(), 0.6f));
            g.fill(px + pw - 6, ty, px + pw - 4, ty + thumbH, Colors.opaque(Colors.mix(Theme.soft(), accent, 0.4f)));
        }
        if (rows.isEmpty()) drawEmpty(g, f, accent);

        if (tab == Tab.LISTS && detail == null) drawListBar(g, f, accent);

        // status line
        drawStatus(g, f, rows, accent, now);
        if (menuFor != null) drawMenu(g, f, mouseX, mouseY, accent);
    }

    private void drawListBar(GuiGraphics g, Font f, int accent) {
        int y = py + 54;
        String title;
        String sub;
        if (openList == null) {
            title = "Your playlists";
            int n = Playlists.count();
            sub = n + (n == 1 ? " playlist" : " playlists");
        } else {
            title = openList;
            int n = Playlists.songs(openList).size();
            sub = n + (n == 1 ? " song" : " songs");
        }
        int room = openList == null ? pw - 16 - 104 - 10 : pw - 16 - 56 - 56 - 58 - 46 - 24;
        String t = Gfx.fitBold(f, title, Math.max(30, room - 10 - Gfx.width(f, sub)));
        Gfx.textBold(g, f, t, px + 10, y + 3, Theme.text(), 1f, true);
        Gfx.text(g, f, sub, px + 10 + Gfx.width(f, t, true) + 8, y + 3, Theme.dim(), 1f, false);
    }

    private List<String> menuEntries() {
        List<String> names = new ArrayList<>(Playlists.names());
        if (names.size() > 7) names = new ArrayList<>(names.subList(0, 7));
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
            boolean isNew = i == entries.size() - 1;
            boolean has = !isNew && menuFor != null && Playlists.contains(entries.get(i), menuFor);
            String label = Gfx.fit(f, entries.get(i), w - 26);
            Gfx.text(g, f, label, x + 8, ry + 3, isNew ? accent : Theme.text(), 1f, false);
            if (has) {
                int tc = Colors.opaque(accent);
                int tx = x + w - 15;
                int[][] px2 = {{0, 4}, {1, 5}, {2, 6}, {3, 5}, {4, 4}, {5, 3}, {6, 2}};
                for (int[] q : px2) g.fill(tx + q[0], ry + q[1], tx + q[0] + 1, ry + q[1] + 1, tc);
            }
        }
    }

    /** A click while the menu is open. Returns true (the click is always used up by the menu). */
    private boolean menuClick(double mx, double my) {
        List<String> entries = menuEntries();
        int w = 150;
        int h = 6 + entries.size() * 13;
        Item it = menuFor;
        menuFor = null;
        if (it == null || mx < menuX || mx >= menuX + w || my < menuY || my >= menuY + h) return true;
        int i = (int) ((my - menuY - 3) / 13);
        if (i < 0 || i >= entries.size()) return true;
        if (i == entries.size() - 1) {
            String made = Playlists.create(text.strip().isEmpty() || tab == Tab.LISTS ? "" : text.strip());
            if (made == null) {
                flash("You have the most playlists allowed (" + Playlists.MAX_LISTS + ")");
                return true;
            }
            addTo(made, it);
        } else {
            addTo(entries.get(i), it);
        }
        return true;
    }

    private void drawHeader(GuiGraphics g, Font f, int accent) {
        int y = py + 52;
        drawThumb(g, detail.picture(false), px + 10, y + 3, 30, accent, true);
        String t = Gfx.fitBold(f, detail.title(), pw - 20 - 40 - 160);
        Gfx.textBold(g, f, t, px + 46, y + 6, Theme.text(), 1f, true);
        Gfx.text(g, f, Gfx.fit(f, detail.subtitle(), pw - 20 - 40 - 160), px + 46, y + 19, Theme.soft(), 1f, false);
        g.fill(px + 8, y + 36, px + pw - 8, y + 37, Colors.alpha(Theme.line(), 0.8f));
    }

    private void drawRow(GuiGraphics g, Font f, Item it, int idx, int ry, int mx, int my, int accent) {
        int rx = px + 6;
        int rw = pw - 14;
        boolean over = mx >= rx && mx < rx + rw && my >= ry && my < ry + ROW && my >= listY && my < listY + listH;
        boolean sel = idx == selected;
        if (over || sel) {
            g.fill(rx, ry, rx + rw, ry + ROW - 1, Colors.alpha(accent, sel ? 0.26f : 0.14f));
            if (sel) g.renderOutline(rx, ry, rw, ROW - 1, Colors.alpha(accent, 0.7f));
        }
        boolean now = isPlaying(it);
        if (detail != null && it.isSong()) {
            String n = String.valueOf(idx + 1);
            Gfx.drawStyled(g, f, n, rx + 4 + (THUMB - Gfx.width(f, n)) / 2, ry + 10, Colors.opaque(now ? accent : Theme.dim()), false, false, false);
        } else {
            drawThumb(g, it.picture(false), rx + 4, ry + 2, THUMB, accent, it.isAlbum());
        }
        int heartX = rx + rw - 20;
        int extX = heartX - 20;
        int right = (it.isAlbum() ? extX : heartX) - 4;
        String dur = it.durationMs() > 0 ? Ease.clock(it.durationMs()) : "";
        int durW = dur.isEmpty() ? 0 : Gfx.width(f, dur) + 6;
        if (!dur.isEmpty() && pw > 260) {
            Gfx.drawStyled(g, f, dur, right - Gfx.width(f, dur), ry + 10, Colors.opaque(Theme.dim()), false, false, false);
        } else {
            durW = 0;
        }
        int tx = rx + 4 + THUMB + 8;
        int tw = right - durW - tx - 2;
        String title = it.explicit() ? it.title() + "  E" : it.title();
        Gfx.line(g, f, Gfx.fit(f, title, tw), tx, ry + 5, tw, now ? accent : Theme.text(), 1f, false, false, false, 0L);
        Gfx.text(g, f, Gfx.fit(f, detail != null && it.isSong() ? it.artist() : it.subtitle(), tw), tx, ry + 16, Theme.dim(), 1f, false);

        if (tab == Tab.LISTS && detail == null) {
            boolean overDel = over && mx >= heartX - 2 && mx < heartX + 14;
            Icons.draw(g, Icons.CLOSE, heartX, ry + 9, 1, Colors.alpha(overDel ? Colors.RED : Theme.dim(), overDel || over ? 0.95f : 0.5f));
            if (openList == null) {
                boolean overPlay = over && mx >= extX - 2 && mx < extX + 14;
                Icons.draw(g, Icons.PLAY, extX, ry + 9, 1, Colors.alpha(overPlay ? accent : Theme.dim(), over ? 0.95f : 0.5f));
                if (overPlay) hint = "Play this playlist";
                if (overDel) hint = confirmDelete.equals(it.title()) ? "Click again to delete the playlist" : "Delete this playlist";
                if (over && hint.isEmpty()) hint = "Open this playlist";
            } else {
                if (overDel) hint = "Take this song out of the playlist";
                if (over && hint.isEmpty()) hint = "Play the playlist from this song";
            }
            return;
        }
        // heart
        boolean liked = liked(it);
        boolean overHeart = over && mx >= heartX - 2 && mx < heartX + 14;
        Icons.draw(g, Icons.HEART, heartX, ry + 9, 1, liked ? Colors.opaque(Colors.PINK)
                : Colors.alpha(overHeart ? Theme.text() : Theme.dim(), overHeart || over ? 0.9f : 0.5f));
        if (overHeart) hint = liked ? (it.isAlbum() ? "Remove from saved albums" : "Remove from liked songs")
                : (it.isAlbum() ? "Save this album" : "Add to liked songs");
        if (it.isAlbum()) {
            boolean overExt = over && mx >= extX - 2 && mx < extX + 14;
            Icons.draw(g, Icons.EXTERNAL, extX, ry + 10, 1, Colors.alpha(overExt ? Theme.text() : Theme.dim(), over ? 0.9f : 0.5f));
            if (overExt) hint = "Open this album in Spotify";
        } else if (it.isSong()) {
            boolean overAdd = over && mx >= extX - 2 && mx < extX + 14;
            int pc = Colors.alpha(overAdd ? accent : Theme.dim(), overAdd ? 1f : over ? 0.9f : 0.5f);
            g.fill(extX + 2, ry + 13, extX + 11, ry + 15, pc);
            g.fill(extX + 5, ry + 10, extX + 8, ry + 18, pc);
            if (overAdd) hint = "Add to a playlist";
        }
        if (over && hint.isEmpty()) hint = it.isAlbum() ? "See the songs of this album" : "Play in Spotify";
    }

    private void drawThumb(GuiGraphics g, String url, int x, int y, int size, int accent, boolean album) {
        Thumbs.Thumb t = Thumbs.get(url);
        if (t != null) {
            g.blit(RenderPipelines.GUI_TEXTURED, t.id(), x, y, 0f, 0f, size, size, 64, 64, 64, 64, 0xFFFFFFFF);
            return;
        }
        g.fillGradient(x, y, x + size, y + size, Colors.opaque(Colors.mix(accent, Colors.CARD_DARK, 0.55f)),
                Colors.opaque(Colors.mix(accent, Colors.CARD_DARK, 0.82f)));
        String[] icon = album ? Icons.ALBUM : Icons.NOTE;
        Icons.draw(g, icon, x + (size - Icons.width(icon)) / 2, y + (size - Icons.height(icon)) / 2, 1,
                Colors.alpha(Colors.lighten(accent, 0.3f), 0.75f));
    }

    private void drawEmpty(GuiGraphics g, Font f, int accent) {
        String a;
        String b = "";
        boolean loading = (detail != null ? detailResult : listResult) != null
                && !(detail != null ? detailResult : listResult).done;
        if (loading || (link != null && linkItem == null)) {
            a = "Searching...";
        } else if (!ConfigIo.get().onlineSearch && (tab == Tab.SONGS || tab == Tab.ALBUMS)) {
            a = "Online search is off";
            b = "Turn it on in Settings > Search";
        } else if (filtering() && listResult != null && listResult.done && !listResult.items.isEmpty()) {
            if (Verifier.pending()) {
                a = Verifier.waiting() ? "Waiting for the link service..." : "Checking which songs play in Spotify...";
                b = Verifier.waiting() ? "It asked for a short break (" + Verifier.waitSeconds() + " s). Results appear as they are checked."
                        : "Only songs that play directly are shown.";
            } else {
                a = "None of these can be played directly";
                b = "They are not on Spotify. Turn off \"Only songs that play directly\" in Settings > Search to see them anyway.";
            }
        } else if (detail != null) {
            Catalog.Result r = detailResult;
            a = r != null && !r.error.isEmpty() ? "Could not load this album" : "No songs found for this album";
            b = r != null ? r.error : "";
        } else {
            switch (tab) {
                case LIKED -> {
                    a = text.isBlank() ? "No liked songs yet" : "No liked songs match";
                    b = "Press the heart next to a song, or F in the player";
                }
                case SAVED -> {
                    a = text.isBlank() ? "No saved albums yet" : "No saved albums match";
                    b = "Open an album and press the heart";
                }
                case LISTS -> {
                    if (openList == null) {
                        a = text.isBlank() ? "No playlists yet" : "No playlist matches";
                        b = "Type a name in the box above and press New playlist. Add songs with the + button on any song.";
                    } else {
                        a = text.isBlank() ? "This playlist is empty" : "No song matches";
                        b = "Use the + button next to a song in Songs, Liked or History.";
                    }
                }
                case HISTORY -> {
                    a = text.isBlank() ? "Nothing played from here yet" : "No history matches";
                    b = ConfigIo.get().saveHistory ? "" : "History is turned off in Settings > Search";
                }
                case ALBUMS -> {
                    a = text.isBlank() ? "Type the name of an album" : "No albums found";
                    b = listResult != null ? listResult.error : "";
                }
                default -> {
                    a = listResult != null && !listResult.error.isEmpty() ? "Search did not work" : "No songs found";
                    b = listResult != null ? listResult.error : "";
                }
            }
        }
        int y = listY + Math.max(8, listH / 2 - 14);
        String fa = Gfx.fit(f, a, pw - 24);
        Gfx.drawStyled(g, f, fa, px + (pw - Gfx.width(f, fa)) / 2, y, Colors.opaque(Theme.soft()), false, false, false);
        if (!b.isEmpty()) {
            for (String line : com.zlorbie788.spotify.util.TextWrap.wrap(b, pw - 40, 3, t -> Gfx.width(f, t))) {
                y += 11;
                Gfx.drawStyled(g, f, line, px + (pw - Gfx.width(f, line)) / 2, y, Colors.opaque(Theme.dim()), false, false, false);
            }
        }
    }

    private void drawStatus(GuiGraphics g, Font f, List<Item> rows, int accent, long now) {
        int y = py + ph - 15;
        g.fill(px + 8, y - 3, px + pw - 8, y - 2, Colors.alpha(Theme.line(), 0.7f));
        String msg = Playback.message();
        if (!msg.equals(lastMsg)) {
            lastMsg = msg;
            lastMsgAt = now;
        }
        String line;
        int color;
        if (!note.isEmpty() && now - noteAt < 4000) {
            line = note;
            color = accent;
        } else if (!hint.isEmpty()) {
            line = hint;
            color = Theme.soft();
        } else if (PlaylistRunner.active() && tab == Tab.LISTS) {
            line = "Playing \"" + PlaylistRunner.name() + "\" · song " + (PlaylistRunner.index() + 1) + " of " + PlaylistRunner.size();
            color = accent;
        } else if (!msg.isEmpty() && Playback.phase() != Playback.Phase.IDLE && now - lastMsgAt < 9000) {
            line = msg;
            color = Playback.phase() == Playback.Phase.FAILED ? Colors.RED : accent;
        } else {
            Catalog.Result r = detail != null ? detailResult : listResult;
            if (r != null && !r.done) {
                line = "Searching...";
                color = Theme.dim();
            } else if (r != null && !r.error.isEmpty() && rows.isEmpty()) {
                line = r.error;
                color = Colors.RED;
            } else if (tab == Tab.SONGS && detail == null && text.isBlank()) {
                line = "Popular right now" + (r != null && !r.source.isEmpty() ? " · " + r.source : "");
                color = Theme.dim();
            } else if (filtering() && r != null && r.done && Verifier.serviceDown() && !r.items.isEmpty()) {
                line = "Could not check Spotify right now, so every result is shown";
                color = Colors.GOLD;
            } else if (filtering() && r != null && r.done && Verifier.pending() && !r.items.isEmpty()) {
                line = Verifier.waiting()
                        ? "Waiting for the link service (" + Verifier.waitSeconds() + " s) · " + rows.size() + " ready"
                        : "Checking Spotify... " + Verifier.answered() + " of " + Verifier.total() + " · " + rows.size() + " ready";
                color = Theme.dim();
            } else if (filtering() && r != null && r.done && !r.source.isEmpty() && !rows.isEmpty()) {
                int hidden = Math.max(0, r.items.size() - rows.size());
                line = rows.size() + (rows.size() == 1 ? " song" : " songs") + " play in your Spotify app"
                        + (hidden > 0 ? " · " + hidden + " hidden (not on Spotify)" : "") + " · " + r.source;
                color = Theme.dim();
            } else if (r != null && !r.source.isEmpty() && !rows.isEmpty()) {
                line = rows.size() + " results · " + r.source + " · plays in your Spotify app";
                color = Theme.dim();
            } else if (tab == Tab.LIKED || tab == Tab.SAVED || tab == Tab.HISTORY || tab == Tab.LISTS) {
                line = rows.size() + (rows.size() == 1 ? " item" : " items") + " · stored on this PC only";
                color = Theme.dim();
            } else {
                line = "No account needed · Enter plays · Esc goes back";
                color = Theme.dim();
            }
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
        if (event.button() != 0) return false;
        double mx = event.x();
        double my = event.y();
        int idx = rowAtMouse(mx, my);
        if (idx < 0) return false;
        List<Item> rows = rows();
        Item it = rows.get(idx);
        selected = idx;
        int rx = px + 6;
        int rw = pw - 14;
        int heartX = rx + rw - 20;
        int extX = heartX - 20;
        if (tab == Tab.LISTS && detail == null) {
            if (mx >= heartX - 2 && mx < heartX + 14) {
                if (openList == null) {
                    if (!confirmDelete.equals(it.title())) {
                        confirmDelete = it.title();
                        flash("Click the x again to delete \"" + it.title() + "\"");
                    } else {
                        Playlists.delete(it.title());
                        if (PlaylistRunner.active() && PlaylistRunner.name().equals(it.title())) PlaylistRunner.stop();
                        confirmDelete = "";
                        selected = -1;
                    }
                } else {
                    Playlists.removeAt(openList, indexInList(it));
                    selected = -1;
                }
                return true;
            }
            if (openList == null && mx >= extX - 2 && mx < extX + 14) {
                if (!PlaylistRunner.start(it.title(), 0, false)) flash("This playlist is empty");
                return true;
            }
            activate(it);
            return true;
        }
        if (it.isSong() && mx >= extX - 2 && mx < extX + 14) {
            menuFor = it;
            menuX = (int) mx - 6;
            menuY = (int) my + 4;
            return true;
        }
        if (mx >= heartX - 2 && mx < heartX + 14) {
            toggleLike(it);
            if (tab == Tab.LIKED || tab == Tab.SAVED) selected = -1;
            return true;
        }
        if (it.isAlbum() && mx >= extX - 2 && mx < extX + 14) {
            Playback.openPage(it);
            return true;
        }
        activate(it);
        return true;
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
        List<Item> rows = rows();
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
                if (dirty) {
                    submit();
                } else if (!rows.isEmpty()) {
                    activate(rows.get(selected >= 0 && selected < rows.size() ? selected : 0));
                }
                return true;
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
