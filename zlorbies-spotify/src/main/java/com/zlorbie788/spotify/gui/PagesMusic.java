package com.zlorbie788.spotify.gui;

import com.zlorbie788.spotify.audio.Eq;
import com.zlorbie788.spotify.audio.JavaSoundSink;
import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.config.SpotifyConfig;
import com.zlorbie788.spotify.hud.Gfx;
import com.zlorbie788.spotify.hud.Theme;
import com.zlorbie788.spotify.local.Folder;
import com.zlorbie788.spotify.local.LocalPlayer;
import com.zlorbie788.spotify.local.MediaKeys;
import com.zlorbie788.spotify.local.MusicLibrary;
import com.zlorbie788.spotify.local.TagFixer;
import com.zlorbie788.spotify.lyrics.LyricsBulk;
import com.zlorbie788.spotify.media.Loopback;
import com.zlorbie788.spotify.util.Colors;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The "My music" group of the settings. */
final class PagesMusic {
    private static List<String> devices;

    private PagesMusic() {}

    private static SpotifyConfig cfg() {
        return ConfigIo.get();
    }

    private static List<String> devices() {
        if (devices == null) {
            List<String> list = new ArrayList<>();
            list.add("");
            try {
                list.addAll(JavaSoundSink.devices());
            } catch (Throwable ignored) { }
            devices = list;
        }
        return devices;
    }

    private static String deviceLabel(String name) {
        return name == null || name.isEmpty() ? "System default" : name;
    }

    static void music(SettingsKit k) {
        k.group(2);
        SettingsKit.Host host = k.host;

        k.section("Your music folder", false);
        k.cycle("Where the music comes from", "Automatic uses Spotify when it is running and your folder when it is not.",
                "Spotify = only Spotify. My music = only the songs in your folder, even if Spotify is open.",
                new String[]{"Automatic", "Spotify", "My music"}, () -> cfg().sourceMode.ordinal(),
                () -> cfg().sourceMode = cfg().sourceMode.next(1));
        List<AbstractWidget> folder = new ArrayList<>();
        folder.add(Ui.button(0, 0, 50, 15, "Open music folder", b -> {
            if (!Folder.openMusic()) host.toast("Could not open the folder: " + MusicLibrary.directory());
        }).accent(k.accent()).primary().tip("Opens the folder in your file explorer. Drop MP3, OGG, WAV or FLAC files in it."));
        folder.add(Ui.button(0, 0, 50, 15, "Rescan", b -> {
            MusicLibrary.rescanAsync();
            host.toast("Looking for new songs...");
        }).accent(k.accent()).tip("Look for songs you added or removed."));
        folder.add(Ui.button(0, 0, 50, 15, "Fix my songs", b -> host.mc().setScreen(new SongDoctorScreen(host.screen())))
                .accent(k.accent()).tip("A song is missing or will not play? This checks every file and says why."));
        k.buttons("Music folder", "Drop songs in the folder, then press Rescan (it also looks by itself).", "", folder);
        k.status("Library", "", 2, () -> {
            if (MusicLibrary.scanning()) return "Looking for songs...";
            int n = MusicLibrary.count();
            String where = MusicLibrary.directory().toString();
            int bad = MusicLibrary.problems().size();
            return (n == 0 ? "No songs yet" : n + (n == 1 ? " song" : " songs")) + " in " + where
                    + (bad > 0 ? "  ·  " + bad + (bad == 1 ? " file" : " files") + " could not be read: press Fix my songs" : "");
        }, Theme::soft);
        k.button("My music screen", "Browse, search, sort, queue and make playlists.", "", "Open My music", false,
                () -> host.mc().setScreen(LibraryScreen.open(host.screen())));
        k.toggle("Remember where I was", "Next time the last song, its position and the volume come back.", "",
                () -> cfg().rememberSession, v -> cfg().rememberSession = v);

        k.section("Sound", false);
        k.choice("Output device", "Which speakers or headphones your own songs play on.",
                "Spotify keeps using its own output. Click to go through the devices Windows offers.",
                () -> deviceLabel(cfg().audioDevice), () -> {
                    List<String> list = devices();
                    int i = list.indexOf(cfg().audioDevice);
                    cfg().audioDevice = list.get((i + 1) % list.size());
                });
        k.slider("Crossfade", "Fade one song into the next. Off = no fade, songs still play gapless.", "Songs always play without gaps between them.",
                0, 12, 1, cfg().crossfadeSeconds, v -> v == 0 ? "off" : (int) v + " s", v -> cfg().crossfadeSeconds = (int) v);
        k.toggle("Equaliser", "Shape the sound of your own songs.", "Presets below, or drag the ten bands yourself.",
                () -> cfg().eqOn, v -> cfg().eqOn = v);
        List<AbstractWidget> chips = new ArrayList<>();
        for (int i = 0; i < Eq.PRESET_NAMES.length; i++) {
            final int index = i;
            chips.add(Ui.button(0, 0, 50, 15, Eq.PRESET_NAMES[i], b -> {
                if (index != Eq.CUSTOM) cfg().eqGains = Eq.preset(index);
                cfg().eqPreset = index;
                if (index != 0) cfg().eqOn = true;
                ConfigIo.save();
                host.rebuild();
            }).accent(k.accent()).tip(Eq.PRESET_TIPS[i]));
        }
        k.grid("Equaliser presets", "Flat, Bass boost, Vocal, Night, Treble, Rock, Electronic, or your own.", "", chips, 4);
        SettingsRow bands = k.row("Equaliser bands", "Click or drag a band. Low sounds on the left, high on the right.", "");
        bands.controlH = 86;
        bands.draw = (g, f, x, y, w, mx, my) -> drawBands(g, f, x, y, w, mx, my, k.accent());
        bands.click = (rx, ry, w) -> setBand(rx, ry, w);
        bands.drag = bands.click;
        k.button("Reset the bands", "Put every band back to 0 dB.", "", "Reset the bands", false, () -> {
            cfg().eqGains = new float[Eq.BANDS];
            cfg().eqPreset = Eq.CUSTOM;
            ConfigIo.save();
        });

        k.section("While you play Minecraft", true);
        k.toggle("Lower the music when I chat", "The music gets quieter while the chat is open.", "", () -> cfg().duckOnChat, v -> cfg().duckOnChat = v);
        k.toggle("Lower the music when I get hurt", "A short dip when you take damage, so you hear the game.", "",
                () -> cfg().duckOnHurt, v -> cfg().duckOnHurt = v);
        k.slider("Lowered volume", "How loud the music stays while it is lowered.", "Low = very quiet, high = barely lower.", 0.1, 0.8, 0.05,
                cfg().duckLevel, v -> Math.round(v * 100) + "%", v -> cfg().duckLevel = (float) v);
        k.toggle("Quiet Minecraft's own music", "Stops the game's background music while one of your songs plays.", "",
                () -> cfg().lowerGameMusic, v -> cfg().lowerGameMusic = v);
        k.toggle("Media keys", "The play/pause, next and previous keys on your keyboard control your own music, even when the game is in the background.",
                "Windows only. Spotify already answers to the media keys by itself.", () -> cfg().mediaKeys, v -> cfg().mediaKeys = v);
        k.status("Media keys status", "", 1, MediaKeys::status, Theme::soft).when(() -> cfg().mediaKeys);
        k.button("Try the media keys again", "Use this if the media keys did not start.", "", "Try again", false, MediaKeys::retry)
                .when(() -> cfg().mediaKeys && MediaKeys.state() == MediaKeys.State.FAILED);

        k.section("Lyrics and tags", true);
        k.toggle("Lyrics from files", "Use a .lrc file next to the song, or the lyrics inside the song, before looking online.", "",
                () -> cfg().localLyricsFiles, v -> cfg().localLyricsFiles = v);
        k.button("Fetch lyrics for the folder", "Looks up lyrics for every song and keeps them on this PC.",
                "Sends titles and artists to lrclib.net, a free lyrics database. It stops by itself if the lookup keeps failing.",
                "Fetch lyrics for my folder", false, () -> {
                    if (LyricsBulk.running()) LyricsBulk.stop();
                    else if (!LyricsBulk.start()) host.toast(LyricsBulk.problem().isEmpty() ? "Already running" : LyricsBulk.problem());
                });
        k.status("Lyrics fetch status", "", 2, LyricsBulk::status, Theme::soft).when(() -> !LyricsBulk.status().isEmpty());
        k.toggle("Fix missing tags online", "If a song has no cover or artist, look them up. Nothing is written to your files.", "",
                () -> cfg().fixTagsOnline, v -> cfg().fixTagsOnline = v);
        k.button("Fix the whole folder", "Fills in missing covers and artists for every song now.", "", "Fix the whole folder", false, () -> {
            if (TagFixer.running()) TagFixer.stop();
            else if (!TagFixer.startAll()) host.toast("Turn on \"Fix missing tags online\" first");
        });
        k.status("Tag fix status", "", 1, TagFixer::status, Theme::soft).when(() -> !TagFixer.status().isEmpty());

        k.section("Spotify real-audio (experimental)", true);
        k.toggle("Real audio analysis", "Listens to the sound Spotify plays so the bars, beat and mood are exact.",
                "A hidden Windows helper reads Spotify's sound. Nothing is recorded or saved. If it fails, the player simply keeps using the loudness meter.",
                () -> cfg().realAudio, v -> {
                    cfg().realAudio = v;
                    if (v) Loopback.retry();
                });
        k.status("Real audio status", "", 2, Loopback::status, () -> Loopback.state() == Loopback.State.FAILED ? Theme.bad() : Theme.soft())
                .when(() -> cfg().realAudio);
        k.button("Try real audio again", "Use this after a failure.", "", "Try again", false, Loopback::retry)
                .when(() -> cfg().realAudio && Loopback.state() == Loopback.State.FAILED);
        k.slider("Visual sync", "Move the bars earlier or later if they do not match the sound.", "", -300, 300, 10, cfg().visualSyncMs,
                v -> String.format(Locale.ROOT, "%+d ms", (int) v), v -> cfg().visualSyncMs = (int) v);
    }

    // ------------------------------------------------------------ equaliser bands

    private static float gainOf(int i) {
        if (cfg().eqPreset == Eq.CUSTOM) return cfg().eqGains[i];
        return Eq.preset(cfg().eqPreset)[i];
    }

    private static void drawBands(net.minecraft.client.gui.GuiGraphics g, net.minecraft.client.gui.Font f, int x, int y, int w,
                                  int mx, int my, int accent) {
        int col = w / Eq.BANDS;
        int left = x + (w - col * Eq.BANDS) / 2;
        int top = y + 4;
        int track = 62;
        int zero = top + track / 2;
        boolean on = cfg().eqOn;
        int ac = Theme.accent(accent);
        for (int i = 0; i < Eq.BANDS; i++) {
            int cx = left + i * col + col / 2;
            float gain = gainOf(i);
            int knob = zero - Math.round(gain / Eq.MAX_DB * (track / 2f));
            boolean hover = mx >= left + i * col && mx < left + (i + 1) * col && my >= y && my < y + 86;
            g.fill(cx - 1, top, cx + 1, top + track, Colors.alpha(Theme.line(), 0.9f));
            g.fill(cx - 5, zero, cx + 5, zero + 1, Colors.alpha(Theme.dim(), 0.7f));
            int c = on ? ac : Theme.dim();
            g.fill(cx - 1, Math.min(zero, knob), cx + 1, Math.max(zero, knob) + 1, Colors.alpha(c, 0.9f));
            g.fill(cx - 4, knob - 2, cx + 4, knob + 3, Colors.opaque(hover ? Colors.lighten(c, 0.3f) : c));
            String name = Eq.BAND_NAMES[i];
            Gfx.drawStyled(g, f, name, cx - Gfx.width(f, name) / 2, top + track + 4, Colors.opaque(Theme.soft()), false, false, false);
            if (hover) {
                String t = String.format(Locale.ROOT, "%+.1f", gain);
                Gfx.drawStyled(g, f, t, cx - Gfx.width(f, t) / 2, top + track + 14, Colors.opaque(Theme.text()), false, false, false);
            }
        }
    }

    private static boolean setBand(int rx, int ry, int w) {
        int col = w / Eq.BANDS;
        int left = (w - col * Eq.BANDS) / 2;
        int i = (rx - left) / Math.max(1, col);
        if (rx < left || i < 0 || i >= Eq.BANDS) return false;
        int track = 62;
        float t = 1f - (ry - 4) / (float) track;
        float gain = (t * 2f - 1f) * Eq.MAX_DB;
        gain = Math.max(-Eq.MAX_DB, Math.min(Eq.MAX_DB, Math.round(gain * 2f) / 2f));
        if (Math.abs(gain) < 0.5f) gain = 0f;
        if (cfg().eqPreset != Eq.CUSTOM) cfg().eqGains = Eq.preset(cfg().eqPreset);
        cfg().eqGains[i] = gain;
        cfg().eqPreset = Eq.CUSTOM;
        return true;
    }
}
