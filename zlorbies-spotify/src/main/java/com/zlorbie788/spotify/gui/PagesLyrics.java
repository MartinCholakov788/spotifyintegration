package com.zlorbie788.spotify.gui;

import com.zlorbie788.spotify.catalog.Library;
import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.config.LyricsConfig;
import com.zlorbie788.spotify.config.SpotifyConfig;
import com.zlorbie788.spotify.util.Colors;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The "Lyrics" and "Search" groups of the settings. */
final class PagesLyrics {
    static final String[] HIGHLIGHT_NAMES = {"Colour only", "Sweep", "Glow", "Pop", "Underline", "Type out"};
    static final String[] CASE_NAMES = {"Normal", "UPPERCASE", "lowercase"};
    static final String[] CARD_MODE_NAMES = {"Off", "Album line", "Artist + album", "Top line"};
    static final String[] CARD_LONG_NAMES = {"Wrap", "Scroll", "Shrink", "Cut"};
    static final String[] OUTLINE_NAMES = {"Black", "White", "Navy", "Red", "Gold"};
    static final int[] OUTLINE_COLORS = {0xFF000000, 0xFFFFFFFF, 0xFF101A3A, 0xFF7A1020, 0xFF7A5A10};
    static final String[] LYRIC_MOTION_NAMES = {"Smooth scroll", "Fade", "Instant"};
    static final String[] OVERFLOW_NAMES = {"Wrap", "Shrink", "Cut"};
    static final String[] ALIGN_NAMES = {"Left", "Centre", "Right"};
    static final String[] TONE_NAMES = {"Player colour", "Album light", "White", "My colour", "Rainbow"};
    static final String[] LYRIC_BACK_NAMES = {"None", "Glass", "Solid", "Album tint"};
    static final String[] SEARCH_SOURCE_NAMES = {"Automatic", "Deezer", "Apple Music"};
    static final String[] CORNER_NAMES = {"Square", "Soft", "Round", "Pill"};

    private PagesLyrics() {}

    private static SpotifyConfig cfg() {
        return ConfigIo.get();
    }

    private static String pct(double v) {
        return Math.round(v * 100) + "%";
    }

    private static String px(double v) {
        return (int) v + " px";
    }

    private static String times2(double v) {
        return String.format(Locale.ROOT, "%.2fx", v);
    }

    private static String times(double v) {
        return String.format(Locale.ROOT, "%.1fx", v);
    }

    private static int outlineIndex(LyricsConfig lc) {
        for (int i = 0; i < OUTLINE_COLORS.length; i++) if (OUTLINE_COLORS[i] == lc.outlineColor) return i;
        return 0;
    }

    static void lyrics(SettingsKit k) {
        k.group(3);
        LyricsConfig lc = cfg().lyrics;
        SettingsKit.Host host = k.host;

        k.section("Basics", false);
        k.toggle("Show lyrics on screen", "Lyrics of the song you are playing, drawn over the game.",
                "They are also in the player screen (Lyrics button).", () -> lc.enabled, v -> lc.enabled = v);
        k.slider("Timing", "Lyrics too early? Move this up. Too late? Move it down.", "Also in the player screen.", -3000, 3000, 100, lc.offsetMs,
                v -> String.format(Locale.ROOT, "%+.1f s", v / 1000.0), v -> lc.offsetMs = (int) v);
        k.toggle("Look up lyrics online", "Sends the song title, artist, album and length to lrclib.net, a free lyrics database.",
                "Off = only lyrics from files and the ones already saved.", () -> lc.online, v -> lc.online = v);
        k.toggle("Show while paused", "Keep the lyrics on screen when the music is paused.", "", () -> lc.showWhenPaused, v -> lc.showWhenPaused = v);
        k.toggle("Hide when there are none", "Off = a small note tells you when a song has no lyrics.", "",
                () -> lc.hideWhenNone, v -> lc.hideWhenNone = v);
        k.toggle("Gap dots", "Three little dots during long instrumental parts.", "", () -> lc.interlude, v -> lc.interlude = v);
        k.toggle("Instrumental note", "Show \"Instrumental\" for songs without words.", "", () -> lc.showInstrumental, v -> lc.showInstrumental = v);
        k.toggle("Romanize", "Write Cyrillic, Greek, Japanese kana or Korean lyrics in Latin letters.",
                "Under the same timing. Kanji and Chinese characters stay as they are. (This is not a translation.)",
                () -> lc.romanize, v -> lc.romanize = v);
        k.toggle("Word timing", "If the lyric file has the time of every word, the karaoke sweep follows each word exactly.",
                "Otherwise the sweep is estimated.", () -> lc.wordSync, v -> lc.wordSync = v);

        k.section("In the player card", true);
        k.cycle("Lyrics inside the card", "Show the line being sung inside the small player card.",
                "Album line = instead of the album name; Artist + album = the current and the next line; Top line = instead of NOW PLAYING.",
                CARD_MODE_NAMES, () -> lc.cardMode.ordinal(), () -> lc.cardMode = lc.cardMode.next(1));
        k.cycle("Long lines in the card", "What happens to a lyric line wider than the card.",
                "Wrap = onto a second row. Scroll = normal size, moving along while it is sung. Shrink = smaller. A line that still does not "
                        + "fit gets smaller and then scrolls, so no word is lost. Only Cut shortens it with ...",
                CARD_LONG_NAMES, () -> lc.cardLong.ordinal(), () -> lc.cardLong = lc.cardLong.next(1));

        k.section("Lines and box", true);
        k.slider("Lines before", "Lines shown above the current one.", "", 0, 6, 1, lc.linesBefore, v -> String.valueOf((int) v),
                v -> lc.linesBefore = (int) v);
        k.slider("Lines after", "Lines shown below the current one.", "", 0, 6, 1, lc.linesAfter, v -> String.valueOf((int) v),
                v -> lc.linesAfter = (int) v);
        k.slider("Width", "How wide the lyrics box is.", "", 120, 560, 10, lc.width, PagesLyrics::px, v -> lc.width = (int) v);
        k.slider("Line gap", "Space between two lines.", "", 0, 12, 1, lc.lineGap, PagesLyrics::px, v -> lc.lineGap = (int) v);
        k.slider("Padding", "Space between the words and the edge of the box.", "", 2, 20, 1, lc.padX, PagesLyrics::px, v -> {
            lc.padX = (int) v;
            lc.padY = Math.max(2, (int) v - 1);
        });
        k.cycle("Long lines", "A line wider than the box.", "Wrap it onto more rows, shrink it, or cut it with ... (nothing is cut unless you pick Cut).",
                OVERFLOW_NAMES, () -> lc.overflow.ordinal(), () -> lc.overflow = lc.overflow.next(1));
        k.slider("Wrap up to", "How many rows a long line may take.", "A longer line gets smaller instead of being cut.", 1, 3, 1, lc.wrapRows,
                v -> (int) v + (v == 1 ? " row" : " rows"), v -> lc.wrapRows = (int) v);
        k.cycle("Align", "Left, centre or right.", "", ALIGN_NAMES, () -> lc.align.ordinal(), () -> lc.align = lc.align.next(1));
        k.cycle("Movement", "How the lyrics move from one line to the next.", "", LYRIC_MOTION_NAMES, () -> lc.motion.ordinal(),
                () -> lc.motion = lc.motion.next(1));
        k.slider("Animation speed", "How fast the lyrics animate.", "", 0.25, 3.0, 0.05, lc.speed, PagesLyrics::times2, v -> lc.speed = (float) v);
        k.slider("Size", "Make the whole lyrics box bigger or smaller.", "", 0.5, 3.0, 0.1, lc.scale, PagesLyrics::times, v -> lc.scale = (float) v);

        k.section("Text", true);
        k.cycle("Current line", "What the line being sung does.", "Colour only, a karaoke sweep, a glow, a bigger pop, an underline that fills, "
                + "or the words typing out.", HIGHLIGHT_NAMES, () -> lc.highlight.ordinal(), () -> lc.highlight = lc.highlight.next(1));
        k.slider("Sweep speed", "How fast the sweep, underline or typing runs.", "Lower = slower than the singer.", 0.5, 2.0, 0.05, lc.sweepSpeed,
                PagesLyrics::times2, v -> lc.sweepSpeed = (float) v);
        k.slider("Current line size", "How much bigger the line being sung is.", "", 1.0, 1.8, 0.05, lc.currentScale, PagesLyrics::times2,
                v -> lc.currentScale = (float) v);
        k.slider("Other lines size", "The size of the lines that are not being sung.", "", 0.6, 1.1, 0.05, lc.otherScale, PagesLyrics::times2,
                v -> lc.otherScale = (float) v);
        k.slider("Fade", "How much the lines far from the current one fade away.", "", 0, 1, 0.05, lc.fade, PagesLyrics::pct, v -> lc.fade = (float) v);
        k.slider("Past lines", "Extra dimming of the lines that were already sung.", "", 0, 1, 0.05, lc.pastDim, PagesLyrics::pct,
                v -> lc.pastDim = (float) v);
        k.cycle("Letters", "Show the words as written, in CAPITALS or in lowercase.", "", CASE_NAMES, () -> lc.textCase.ordinal(),
                () -> lc.textCase = lc.textCase.next(1));
        k.toggle("Bold line", "The current line is written in bold.", "", () -> lc.boldCurrent, v -> lc.boldCurrent = v);
        k.toggle("Italic", "All the lyrics in slanted letters.", "", () -> lc.italic, v -> lc.italic = v);
        k.toggle("Shadow", "A soft dark shadow behind the words.", "", () -> lc.shadow, v -> lc.shadow = v);
        k.toggle("Outline", "A thin outline: easiest to read on bright scenes.", "", () -> lc.outline, v -> lc.outline = v);
        k.cycle("Outline colour", "The colour of the outline (when Outline is on).", "", OUTLINE_NAMES, () -> outlineIndex(lc),
                () -> lc.outlineColor = OUTLINE_COLORS[(outlineIndex(lc) + 1) % OUTLINE_COLORS.length]).enabled(() -> lc.outline);

        k.section("Colours", true);
        k.cycle("Current line colour", "Player colour, soft album light, white, your own colour or rainbow.", "", TONE_NAMES,
                () -> lc.currentTone.ordinal(), () -> lc.currentTone = lc.currentTone.next(1));
        k.swatches("Pick the current line colour", "Choosing one switches the current line to \"My colour\".", "",
                () -> lc.currentTone == LyricsConfig.Tone.CUSTOM ? lc.currentColor : 0, c -> {
                    lc.currentColor = c;
                    lc.currentTone = LyricsConfig.Tone.CUSTOM;
                });
        k.cycle("Other lines colour", "The colour of the lines that are not being sung.", "", TONE_NAMES, () -> lc.otherTone.ordinal(),
                () -> lc.otherTone = lc.otherTone.next(1));
        k.swatches("Pick the other lines colour", "Choosing one switches the other lines to \"My colour\".", "",
                () -> lc.otherTone == LyricsConfig.Tone.CUSTOM ? lc.otherColor : 0, c -> {
                    lc.otherColor = c;
                    lc.otherTone = LyricsConfig.Tone.CUSTOM;
                });
        k.cycle("Box", "Nothing behind the words, glass, solid or tinted with the album colour.", "", LYRIC_BACK_NAMES, () -> lc.back.ordinal(),
                () -> lc.back = lc.back.next(1));
        k.slider("Box strength", "How solid the box behind the lyrics is.", "", 0, 1, 0.05, lc.backOpacity, PagesLyrics::pct,
                v -> lc.backOpacity = (float) v);
        k.slider("Box corners", "How round the corners of the box are.", "", 0, 3, 1, lc.corner, v -> CORNER_NAMES[(int) v], v -> lc.corner = (int) v);
        k.slider("Opacity", "How see-through everything is.", "", 0.1, 1.0, 0.05, lc.opacity, PagesLyrics::pct, v -> lc.opacity = (float) v);
        k.toggle("Border", "A thin line around the lyrics box.", "", () -> lc.border, v -> lc.border = v);
        k.toggle("Colour bar", "A bar in the player colour along the left edge of the box.", "", () -> lc.accentBar, v -> lc.accentBar = v);

        k.section("Quick looks and place", true);
        String[] presets = LyricsConfig.PRESETS;
        List<AbstractWidget> chips = new ArrayList<>();
        for (int i = 0; i < presets.length; i++) {
            final int index = i;
            chips.add(Ui.button(0, 0, 50, 15, presets[i], b -> {
                lc.applyPreset(index);
                ConfigIo.save();
                host.rebuild();
            }).accent(k.accent()).tip("Ready-made lyrics look: " + presets[i] + ". It changes the look only, not where the lyrics are."));
        }
        k.grid("Lyrics looks", "Ready-made looks for the lyrics. Fine-tune them in Lines, Text and Colours.", "", chips, 4);
        String[] names = {"Top", "Middle", "Bottom"};
        int[] ay = {SpotifyConfig.ANCHOR_START, SpotifyConfig.ANCHOR_CENTER, SpotifyConfig.ANCHOR_END};
        List<AbstractWidget> place = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final int index = i;
            place.add(Ui.button(0, 0, 50, 15, names[i], b -> {
                lc.ax = SpotifyConfig.ANCHOR_CENTER;
                lc.ay = ay[index];
                lc.x = 0;
                lc.y = index == 1 ? 0 : 56;
                ConfigIo.save();
            }).accent(k.accent()).tip("Put the lyrics here. \"Move on screen\" places them exactly."));
        }
        k.buttons("Quick place", "Put the lyrics at the top, middle or bottom of the screen.", "", place);
    }

    static void search(SettingsKit k) {
        k.group(4);
        SpotifyConfig c = cfg();
        SettingsKit.Host host = k.host;

        k.section("Online search", false);
        k.toggle("Online song search", "The search bar looks songs up in public catalogues (Deezer / Apple Music).",
                "No account or log-in is needed.", () -> c.onlineSearch, v -> c.onlineSearch = v);
        k.cycle("Search in", "Which catalogue to ask.", "Automatic uses Deezer and falls back to Apple Music.", SEARCH_SOURCE_NAMES,
                () -> c.searchSource.ordinal(), () -> c.searchSource = c.searchSource.next(1));
        k.toggle("Find the exact Spotify song", "Asks song.link for the Spotify version of the song.", "Off = Spotify only opens a search page.",
                () -> c.exactSpotify, v -> c.exactSpotify = v);
        k.toggle("Only songs that play directly", "Every result is checked first and only songs that really exist on Spotify are shown.",
                "Results appear one by one as they are checked. Off = show everything.", () -> c.onlyPlayable, v -> c.onlyPlayable = v);
        k.toggle("Auto-play", "When Spotify has opened the song, press play so it starts at once.", "", () -> c.autoPlay, v -> c.autoPlay = v);
        k.slider("Results", "How many songs a search looks at.", "", 5, 25, 1, c.searchResults, v -> String.valueOf((int) v),
                v -> c.searchResults = (int) v);

        k.section("Pictures", true);
        k.toggle("Cover lookup", "When Windows gives no cover, look the album picture up online.", "", () -> c.webCovers, v -> c.webCovers = v);
        k.toggle("Result pictures", "Small covers next to the search results.", "", () -> c.searchThumbs, v -> c.searchThumbs = v);
        k.toggle("Search tint", "Colour the search screen like the first cover.", "", () -> c.searchTint, v -> c.searchTint = v);

        k.section("History", true);
        k.toggle("Remember played songs", "Keeps the History list. Stored on this PC only.", "", () -> c.saveHistory, v -> c.saveHistory = v);
        List<AbstractWidget> tools = new ArrayList<>();
        tools.add(Ui.button(0, 0, 50, 15, "Open search", b -> host.mc().setScreen(new SearchScreen(host.screen())))
                .accent(k.accent()).primary().tip("Try the search now."));
        tools.add(Ui.button(0, 0, 50, 15, "Clear history", b -> {
            Library.clearHistory();
            host.toast("History cleared");
        }).accent(Colors.RED).tip("Forget the songs you played from the search."));
        k.buttons("Search tools", "Try the search, or forget the songs you played from it.", "", tools);
        k.status("No log-in", "", 3, () -> "Nothing here asks for your Spotify account. Songs are found in public catalogues, then your own "
                + "Spotify app opens the song. Liked songs, saved albums and history stay on this PC.", com.zlorbie788.spotify.hud.Theme::dim);
    }
}
