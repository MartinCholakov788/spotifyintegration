package com.zlorbie788.spotify.client;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.config.SpotifyConfig;
import com.zlorbie788.spotify.gui.LayoutEditorScreen;
import com.zlorbie788.spotify.catalog.Library;
import com.zlorbie788.spotify.catalog.Playback;
import com.zlorbie788.spotify.gui.PlayerScreen;
import com.zlorbie788.spotify.gui.SearchScreen;
import com.zlorbie788.spotify.gui.SettingsScreen;
import com.zlorbie788.spotify.media.Beat;
import com.zlorbie788.spotify.media.Covers;
import com.zlorbie788.spotify.media.MediaBridge;
import com.zlorbie788.spotify.media.Player;
import com.zlorbie788.spotify.media.Snapshot;
import com.zlorbie788.spotify.util.Ease;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.commands.CommandBuildContext;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** /music (also /spotify): control the player from the chat box. */
public final class Commands {
    private Commands() {}

    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register(Commands::register);
    }

    private static Command<FabricClientCommandSource> run(Runnable action) {
        return ctx -> {
            action.run();
            return 1;
        };
    }

    private static void register(CommandDispatcher<FabricClientCommandSource> d, CommandBuildContext ctx) {
        d.register(build("music"));
        d.register(build("spotify"));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> build(String name) {
        return literal(name)
                .executes(run(() -> SpotifyClient.openLater(PlayerScreen::create)))
                .then(literal("open").executes(run(() -> SpotifyClient.openLater(PlayerScreen::create))))
                .then(literal("intro").executes(run(() -> {
                    com.zlorbie788.spotify.hud.Intro.replayNext();
                    SpotifyClient.openLater(PlayerScreen::create);
                })))
                .then(literal("search").executes(run(() -> SpotifyClient.openLater(() -> new SearchScreen(null))))
                        .then(argument("words", StringArgumentType.greedyString()).executes(c -> {
                            String words = StringArgumentType.getString(c, "words");
                            SpotifyClient.openLater(() -> SearchScreen.withQuery(null, words));
                            return 1;
                        })))
                .then(literal("find").then(argument("words", StringArgumentType.greedyString()).executes(c -> {
                    String words = StringArgumentType.getString(c, "words");
                    Msg.chat("Searching for \"" + words + "\" and opening it in Spotify...");
                    Playback.playQuery(words);
                    return 1;
                })))
                .then(literal("lyrics").executes(run(() -> SpotifyClient.openLater(PlayerScreen::createWithLyrics)))
                        .then(literal("on").executes(run(() -> setLyrics(true))))
                        .then(literal("off").executes(run(() -> setLyrics(false))))
                        .then(literal("toggle").executes(run(() -> setLyrics(!ConfigIo.get().lyrics.enabled))))
                        .then(literal("fetch").executes(run(() -> {
                            if (com.zlorbie788.spotify.lyrics.LyricsBulk.start()) {
                                Msg.chat("Fetching lyrics for your music folder in the background. /music lyrics stop cancels it.");
                            } else {
                                String why = com.zlorbie788.spotify.lyrics.LyricsBulk.problem();
                                Msg.chat(com.zlorbie788.spotify.lyrics.LyricsBulk.running() ? com.zlorbie788.spotify.lyrics.LyricsBulk.status()
                                        : "Could not start: " + (why.isEmpty() ? "unknown" : why) + ".");
                            }
                        })))
                        .then(literal("stop").executes(run(() -> {
                            com.zlorbie788.spotify.lyrics.LyricsBulk.stop();
                            Msg.chat("Stopping the lyrics fetch.");
                        }))))
                .then(literal("like").executes(run(() -> {
                    var item = Library.fromSnapshot(Player.snap());
                    if (item == null) {
                        Msg.chat("Nothing is playing.");
                        return;
                    }
                    boolean now = Library.toggleFavouriteSong(item);
                    Msg.chat(now ? "Added \"" + item.title() + "\" to your liked songs." : "Removed from your liked songs.");
                })))
                .then(literal("share").executes(run(() -> Share.send(net.minecraft.client.Minecraft.getInstance(), false)))
                        .then(literal("chat").executes(run(() -> Share.send(net.minecraft.client.Minecraft.getInstance(), true)))))
                .then(literal("library").executes(run(() -> SpotifyClient.openLater(() -> com.zlorbie788.spotify.gui.LibraryScreen.open(null)))))
                .then(literal("folder").executes(run(() -> {
                    boolean ok = com.zlorbie788.spotify.local.Folder.openMusic();
                    Msg.chat(ok ? "Opened your music folder: " + com.zlorbie788.spotify.local.MusicLibrary.directory()
                            : "Could not open it. Your music folder is: " + com.zlorbie788.spotify.local.MusicLibrary.directory());
                })))
                .then(literal("fixtags").executes(run(() -> {
                    if (com.zlorbie788.spotify.local.TagFixer.startAll()) {
                        Msg.chat("Filling in missing artists, albums and covers from the internet. Your song files are not changed. "
                                + "/music fixtags stop cancels it.");
                    } else {
                        Msg.chat(com.zlorbie788.spotify.local.TagFixer.running() ? com.zlorbie788.spotify.local.TagFixer.status()
                                : "Fixing tags is switched off (Settings > My music) or online search is off.");
                    }
                }))
                        .then(literal("stop").executes(run(() -> {
                            com.zlorbie788.spotify.local.TagFixer.stop();
                            Msg.chat("Stopping.");
                        }))))
                .then(literal("rescan").executes(run(() -> {
                    com.zlorbie788.spotify.local.MusicLibrary.rescanAsync();
                    Msg.chat("Looking through your music folder...");
                })))
                .then(literal("source")
                        .then(literal("auto").executes(run(() -> source(SpotifyConfig.SourceMode.AUTO))))
                        .then(literal("spotify").executes(run(() -> source(SpotifyConfig.SourceMode.SPOTIFY))))
                        .then(literal("local").executes(run(() -> source(SpotifyConfig.SourceMode.LOCAL))))
                        .then(literal("mymusic").executes(run(() -> source(SpotifyConfig.SourceMode.LOCAL)))))
                .then(literal("queue")
                        .executes(run(() -> queueInfo()))
                        .then(literal("clear").executes(run(() -> {
                            com.zlorbie788.spotify.local.LocalPlayer.clearUpcoming();
                            Msg.chat("The queue is empty now.");
                        }))))
                .then(literal("playnext").then(argument("words", StringArgumentType.greedyString()).executes(c -> {
                    queueSong(StringArgumentType.getString(c, "words"), true);
                    return 1;
                })))
                .then(literal("addqueue").then(argument("words", StringArgumentType.greedyString()).executes(c -> {
                    queueSong(StringArgumentType.getString(c, "words"), false);
                    return 1;
                })))
                .then(literal("tutorial").executes(run(() -> SpotifyClient.openLater(() -> {
                    com.zlorbie788.spotify.gui.Tour.start();
                    return new SettingsScreen(null);
                }))))
                .then(literal("settings").executes(run(() -> SpotifyClient.openLater(() -> new SettingsScreen(null)))))
                .then(literal("layout").executes(run(() -> SpotifyClient.openLater(() -> new LayoutEditorScreen(null)))))
                .then(literal("hud").executes(run(() -> {
                    SpotifyConfig cfg = ConfigIo.get();
                    cfg.hudEnabled = !cfg.hudEnabled;
                    ConfigIo.save();
                    Msg.chat("Player " + (cfg.hudEnabled ? "shown" : "hidden") + ".");
                })))
                .then(literal("play").executes(run(Player::play)))
                .then(literal("pause").executes(run(Player::pause)))
                .then(literal("toggle").executes(run(Player::toggle)))
                .then(literal("next").executes(run(Player::next)))
                .then(literal("prev").executes(run(Player::previous)))
                .then(literal("previous").executes(run(Player::previous)))
                .then(literal("mute").executes(run(Player::toggleMute)))
                .then(literal("shuffle").executes(run(() -> {
                    Player.toggleShuffle();
                    Msg.chat("Shuffle " + (Player.shuffle() ? "on" : "off") + ".");
                })))
                .then(literal("repeat").executes(run(() -> {
                    Player.cycleRepeat();
                    Msg.chat(Keybinds.repeatText(Player.repeat()) + ".");
                })))
                .then(literal("seek").then(argument("time", StringArgumentType.greedyString()).executes(c -> {
                    seek(StringArgumentType.getString(c, "time"));
                    return 1;
                })))
                .then(literal("forward").executes(run(() -> Player.seekBy(ConfigIo.get().seekStepSeconds * 1000L))))
                .then(literal("back").executes(run(() -> Player.seekBy(-ConfigIo.get().seekStepSeconds * 1000L))))
                .then(literal("volume").then(argument("percent", StringArgumentType.word()).executes(c -> {
                    volume(StringArgumentType.getString(c, "percent"));
                    return 1;
                })))
                .then(literal("status").executes(run(Commands::status)))
                .then(literal("reconnect").executes(run(() -> {
                    MediaBridge.restart();
                    Msg.chat("Restarting the Windows helper...");
                })))
                .then(literal("fix").executes(run(Commands::fix)))
                .then(literal("songs").executes(run(() -> {
                    Msg.chat("Checking the music folder...");
                    Thread t = new Thread(() -> {
                        java.util.List<String> lines = com.zlorbie788.spotify.local.SongDoctor.lines(
                                com.zlorbie788.spotify.local.SongDoctor.check(com.zlorbie788.spotify.local.MusicLibrary.directory()));
                        net.minecraft.client.Minecraft.getInstance().execute(() -> {
                            for (int i = 0; i < lines.size() && i < 8; i++) Msg.chat(lines.get(i));
                            if (lines.size() > 8) Msg.chat("... and " + (lines.size() - 8) + " more. See all of them in Settings > My music > Fix my songs.");
                        });
                    }, "Spotify-Song-Doctor-Command");
                    t.setDaemon(true);
                    t.start();
                })))
                .then(literal("stats").executes(run(() -> SpotifyClient.openLater(() -> new com.zlorbie788.spotify.gui.StatsScreen(null)))))
                .then(literal("lists").executes(run(() -> SpotifyClient.openLater(() -> {
                    SearchScreen s = new SearchScreen(null);
                    s.showLists();
                    return s;
                }))))
                .then(literal("code").executes(run(() -> {
                    net.minecraft.client.Minecraft.getInstance().keyboardHandler.setClipboard(
                            com.zlorbie788.spotify.hud.LookCode.export(ConfigIo.get()));
                    Msg.chat("Your look code is on the clipboard. Paste it anywhere to share it.");
                }))
                        .then(literal("paste").executes(run(() -> {
                            int n = com.zlorbie788.spotify.hud.LookCode.apply(ConfigIo.get(),
                                    net.minecraft.client.Minecraft.getInstance().keyboardHandler.getClipboard());
                            if (n < 0) {
                                Msg.chat("The clipboard has no look code.");
                                return;
                            }
                            ConfigIo.save();
                            Covers.reload();
                            Msg.chat("Look applied (" + n + " settings).");
                        }))))
                .then(literal("toast").executes(run(com.zlorbie788.spotify.hud.Toast::test)))
                .then(literal("help").executes(run(Commands::help)));
    }

    /** /music fix: the "why do I see no song?" checklist in the chat. */
    private static void fix() {
        SpotifyConfig cfg = ConfigIo.get();
        for (var c : com.zlorbie788.spotify.media.Diagnosis.checks(cfg)) {
            String mark = switch (c.level()) {
                case OK -> "[ok] ";
                case BAD -> "[!!] ";
                default -> "[..] ";
            };
            Msg.chat(mark + c.label() + (c.hint().isEmpty() ? "" : " - " + c.hint()));
        }
    }

    private static void source(SpotifyConfig.SourceMode mode) {
        ConfigIo.get().sourceMode = mode;
        ConfigIo.save();
        Msg.chat(switch (mode) {
            case SPOTIFY -> "The player now only follows Spotify.";
            case LOCAL -> "The player now only plays your own music folder.";
            default -> "The player follows Spotify when it is running and your music folder when it is not.";
        });
    }

    private static void queueInfo() {
        var up = com.zlorbie788.spotify.local.LocalPlayer.upcoming();
        if (up.isEmpty()) {
            Msg.chat("The queue is empty. Use /music playnext <words> or /music addqueue <words>.");
            return;
        }
        Msg.chat("Up next (" + up.size() + "):");
        for (int i = 0; i < Math.min(5, up.size()); i++) Msg.chat((i + 1) + ". " + up.get(i).label());
        if (up.size() > 5) Msg.chat("...and " + (up.size() - 5) + " more.");
    }

    private static void queueSong(String words, boolean next) {
        var found = com.zlorbie788.spotify.local.MusicLibrary.search(words, com.zlorbie788.spotify.local.MusicLibrary.Sort.TITLE);
        if (found.isEmpty()) {
            Msg.chat("No song in your music folder matches \"" + words + "\".");
            return;
        }
        var t = found.get(0);
        if (next) com.zlorbie788.spotify.local.LocalPlayer.playNext(t);
        else com.zlorbie788.spotify.local.LocalPlayer.addToQueue(t);
        Msg.chat((next ? "Plays next: " : "Added to the queue: ") + t.label());
    }

    private static void setLyrics(boolean on) {
        ConfigIo.get().lyrics.enabled = on;
        ConfigIo.save();
        Msg.chat("Lyrics on screen " + (on ? "shown" : "hidden") + ".");
    }

    private static void seek(String text) {
        if (!Player.canSeek()) {
            Msg.chat("Nothing to seek in right now.");
            return;
        }
        String t = text.trim();
        if (t.startsWith("+") || t.startsWith("-")) {
            long delta = Ease.parseTime(t.substring(1));
            if (delta < 0) {
                Msg.chat("Use a time like +10, -30, +1:00.");
                return;
            }
            Player.seekBy(t.startsWith("-") ? -delta : delta);
        } else {
            long target = Ease.parseTime(t);
            if (target < 0) {
                Msg.chat("Use a time like 1:23, 83, 1m23s, or +10 / -10 to move from here.");
                return;
            }
            Player.seekTo(target);
        }
        Msg.chat("Jumped to " + Ease.clock(Player.position()) + " / " + Ease.clock(Player.duration()) + ".");
    }

    private static void volume(String text) {
        if (!Player.canVolume()) {
            Msg.chat("The volume of the player is not available.");
            return;
        }
        String t = text.trim();
        try {
            if (t.startsWith("+") || t.startsWith("-")) {
                Player.volumeBy(Integer.parseInt(t.startsWith("+") ? t.substring(1) : t));
            } else {
                Player.setVolume(Integer.parseInt(t));
            }
            Msg.chat("Volume " + Player.volume() + "%.");
        } catch (NumberFormatException e) {
            Msg.chat("Use a number from 0 to 100, or +10 / -10.");
        }
    }

    private static void status() {
        Snapshot s = Player.snap();
        StringBuilder bad = new StringBuilder();
        for (com.zlorbie788.spotify.config.UiFont f : com.zlorbie788.spotify.config.UiFont.values()) {
            if (!f.works()) bad.append(bad.length() == 0 ? "" : ", ").append(f.label);
        }
        Msg.chat("Fonts: " + (bad.length() == 0 ? "all loaded" : "did not load: " + bad + " (hidden from the lists)"));
        Msg.chat("Helper: " + MediaBridge.state().name().toLowerCase(java.util.Locale.ROOT)
                + "  ·  cover: " + Covers.status() + "  ·  music level: " + (Beat.hearing(System.nanoTime()) ? "live" : Beat.live() ? "reads silence (not hearing the player)" : "not available"));
        if (!s.active()) {
            Msg.chat("Nothing playing. Helper: " + MediaBridge.state().name().toLowerCase(java.util.Locale.ROOT)
                    + (MediaBridge.problem().isEmpty() ? "" : " (" + MediaBridge.problem() + ")") + ".");
            return;
        }
        Msg.chat((Player.playing() ? "Playing" : "Paused") + ": " + s.title() + " - " + s.artist()
                + (s.album().isEmpty() ? "" : " (" + s.album() + ")"));
        Msg.chat(Ease.clock(Player.position()) + " / " + Ease.clock(Player.duration())
                + (Player.volume() >= 0 ? "  ·  volume " + Player.volume() + "%" : "")
                + "  ·  " + s.appName());
    }

    private static void help() {
        Msg.chat("/music [open, settings, layout, hud, play, pause, toggle, next, prev, mute, shuffle, repeat]");
        Msg.chat("/music seek <1:23 | 83 | +10 | -10>   /music volume <0-100 | +5 | -5>   /music forward | back");
        Msg.chat("/music search [words]   /music find <words> (plays the first match in Spotify)   /music like");
        Msg.chat("/music stats (listening stats)   /music lists (your playlists)   /music fix (why no song?)   /music toast (test)");
        Msg.chat("/music code (copy your look as text)   /music code paste (apply a look from the clipboard)");
        Msg.chat("/music library (your own songs)   /music folder   /music rescan   /music source <auto | spotify | local>");
        Msg.chat("/music lyrics fetch (lyrics for the whole folder)   /music fixtags (fill in missing artists and covers)");
        Msg.chat("/music queue   /music playnext <words>   /music addqueue <words>   /music share [chat]");
        Msg.chat("/music tutorial (the quick tour)   /music intro (replays the opening animation)   /music lyrics [on | off | toggle]   /music status   /music reconnect   (also works as /spotify)");
    }
}
