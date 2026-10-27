package com.zlorbie788.spotify.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.config.SpotifyConfig;
import com.zlorbie788.spotify.gui.PlayerScreen;
import com.zlorbie788.spotify.gui.SearchScreen;
import com.zlorbie788.spotify.gui.SettingsScreen;
import com.zlorbie788.spotify.media.Player;
import com.zlorbie788.spotify.media.Snapshot;
import com.zlorbie788.spotify.util.Ease;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The mod's keys, registered as normal Minecraft key mappings (so they also appear in Options > Controls and
 * are saved with the rest of your keys). The settings screen's Keys tab rebinds the very same mappings.
 */
public final class Keybinds {
    /** Key categories are registered objects since 1.21.9. Translation key: key.category.zlorbies-spotify.main */
    public static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("zlorbies-spotify", "main"));
    public static final List<KeyMapping> ALL = new ArrayList<>();

    public static KeyMapping OPEN;
    public static KeyMapping SETTINGS;
    public static KeyMapping TOGGLE_HUD;
    public static KeyMapping PLAY_PAUSE;
    public static KeyMapping NEXT;
    public static KeyMapping PREVIOUS;
    public static KeyMapping SEEK_FORWARD;
    public static KeyMapping SEEK_BACK;
    public static KeyMapping VOLUME_UP;
    public static KeyMapping VOLUME_DOWN;
    public static KeyMapping MUTE;
    public static KeyMapping SHUFFLE;
    public static KeyMapping REPEAT;
    public static KeyMapping SEARCH;
    public static KeyMapping LYRICS;
    public static KeyMapping MY_MUSIC;
    public static KeyMapping LIKE;
    public static KeyMapping SHARE;

    private Keybinds() {}

    public static void register() {
        OPEN = reg("open", GLFW.GLFW_KEY_RIGHT_ALT);
        SETTINGS = reg("settings", GLFW.GLFW_KEY_UNKNOWN);
        TOGGLE_HUD = reg("toggle_hud", GLFW.GLFW_KEY_UNKNOWN);
        // The number pad is free in Minecraft, so the music keys work out of the box (change them in Settings > Keys).
        PLAY_PAUSE = reg("play_pause", GLFW.GLFW_KEY_KP_5);
        NEXT = reg("next", GLFW.GLFW_KEY_KP_6);
        PREVIOUS = reg("previous", GLFW.GLFW_KEY_KP_4);
        SEEK_FORWARD = reg("seek_forward", GLFW.GLFW_KEY_UNKNOWN);
        SEEK_BACK = reg("seek_back", GLFW.GLFW_KEY_UNKNOWN);
        VOLUME_UP = reg("volume_up", GLFW.GLFW_KEY_KP_8);
        VOLUME_DOWN = reg("volume_down", GLFW.GLFW_KEY_KP_2);
        MUTE = reg("mute", GLFW.GLFW_KEY_KP_0);
        SHUFFLE = reg("shuffle", GLFW.GLFW_KEY_UNKNOWN);
        REPEAT = reg("repeat", GLFW.GLFW_KEY_UNKNOWN);
        SEARCH = reg("search", GLFW.GLFW_KEY_UNKNOWN);
        LYRICS = reg("lyrics", GLFW.GLFW_KEY_UNKNOWN);
        MY_MUSIC = reg("my_music", GLFW.GLFW_KEY_UNKNOWN);
        LIKE = reg("like", GLFW.GLFW_KEY_KP_ADD);
        SHARE = reg("share", GLFW.GLFW_KEY_UNKNOWN);

        // profiles remember the keys too
        com.zlorbie788.spotify.config.Profiles.keySource = () -> {
            java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
            for (KeyMapping mapping : ALL) out.put(mapping.getName(), mapping.saveString());
            return out;
        };
        com.zlorbie788.spotify.config.Profiles.keySink = saved -> {
            for (KeyMapping mapping : ALL) {
                String code = saved.get(mapping.getName());
                if (code != null) mapping.setKey(InputConstants.getKey(code));
            }
            KeyMapping.resetMapping();
            Minecraft.getInstance().options.save();
        };
    }

    private static KeyMapping reg(String name, int key) {
        KeyMapping mapping = KeyBindingHelper.registerKeyBinding(
                new KeyMapping("key.zlorbies-spotify." + name, InputConstants.Type.KEYSYM, key, CATEGORY));
        ALL.add(mapping);
        return mapping;
    }

    // ------------------------------------------------------------ rebinding (used by the settings screen)

    public static void rebind(KeyMapping mapping, int keyCode) {
        mapping.setKey(keyCode == GLFW.GLFW_KEY_UNKNOWN
                ? InputConstants.UNKNOWN
                : InputConstants.Type.KEYSYM.getOrCreate(keyCode));
        KeyMapping.resetMapping();
        Minecraft.getInstance().options.save();
    }

    public static void resetAll() {
        for (KeyMapping mapping : ALL) mapping.setKey(mapping.getDefaultKey());
        KeyMapping.resetMapping();
        Minecraft.getInstance().options.save();
    }

    /** Name of another key mapping bound to the same key, or null if there is no clash. */
    public static String conflict(KeyMapping mapping) {
        if (mapping.isUnbound()) return null;
        for (KeyMapping other : Minecraft.getInstance().options.keyMappings) {
            if (other != mapping && other.same(mapping)) {
                return Component.translatable(other.getName()).getString();
            }
        }
        return null;
    }

    /** Short text for a key ("Right Alt"), or "unbound". */
    public static String keyName(KeyMapping mapping) {
        return mapping == null || mapping.isUnbound() ? "unbound" : mapping.getTranslatedKeyMessage().getString();
    }

    // ------------------------------------------------------------ actions

    /** Called every client tick while no screen is open. */
    public static void tick(Minecraft mc) {
        SpotifyConfig cfg = ConfigIo.get();
        while (OPEN.consumeClick()) mc.setScreen(PlayerScreen.create());
        while (SEARCH.consumeClick()) mc.setScreen(new SearchScreen(null));
        while (MY_MUSIC.consumeClick()) mc.setScreen(com.zlorbie788.spotify.gui.LibraryScreen.open(null));
        while (LIKE.consumeClick()) {
            var item = com.zlorbie788.spotify.catalog.Library.fromSnapshot(Player.snap());
            if (item == null) {
                Msg.feedback("Nothing playing");
            } else {
                boolean now = com.zlorbie788.spotify.catalog.Library.toggleFavouriteSong(item);
                Msg.feedback(now ? "Added to Favourites" : "Removed from Favourites");
            }
        }
        while (SHARE.consumeClick()) Share.send(mc, false);
        while (LYRICS.consumeClick()) {
            cfg.lyrics.enabled = !cfg.lyrics.enabled;
            ConfigIo.save();
            Msg.feedback("Lyrics " + (cfg.lyrics.enabled ? "shown" : "hidden"));
        }
        while (SETTINGS.consumeClick()) mc.setScreen(new SettingsScreen(null));
        while (TOGGLE_HUD.consumeClick()) {
            cfg.hudEnabled = !cfg.hudEnabled;
            ConfigIo.save();
            Msg.actionBar("Spotify Integration Mod " + (cfg.hudEnabled ? "shown" : "hidden"));
        }
        while (PLAY_PAUSE.consumeClick()) {
            Player.toggle();
            Msg.feedback(Player.playing() ? "Playing" : "Paused");
        }
        while (NEXT.consumeClick()) {
            Player.next();
            Msg.feedback("Next song");
        }
        while (PREVIOUS.consumeClick()) {
            Player.previous();
            Msg.feedback("Previous song");
        }
        while (SEEK_FORWARD.consumeClick()) seek(cfg.seekStepSeconds * 1000L);
        while (SEEK_BACK.consumeClick()) seek(-cfg.seekStepSeconds * 1000L);
        while (VOLUME_UP.consumeClick()) volume(cfg.volumeStep);
        while (VOLUME_DOWN.consumeClick()) volume(-cfg.volumeStep);
        while (MUTE.consumeClick()) {
            Player.toggleMute();
            Msg.feedback(Player.muted() ? "Muted" : "Unmuted");
        }
        while (SHUFFLE.consumeClick()) {
            Player.toggleShuffle();
            Msg.feedback("Shuffle " + (Player.shuffle() ? "on" : "off"));
        }
        while (REPEAT.consumeClick()) {
            Player.cycleRepeat();
            Msg.feedback(repeatText(Player.repeat()));
        }
    }

    public static String repeatText(int mode) {
        return switch (mode) {
            case 1 -> "Repeat: this song";
            case 2 -> "Repeat: all";
            default -> "Repeat off";
        };
    }

    private static void seek(long deltaMs) {
        Snapshot s = Player.snap();
        if (!Player.canSeek()) {
            Msg.feedback(s.active() ? "This song cannot be skipped through" : "Nothing playing");
            return;
        }
        Player.seekBy(deltaMs);
        Msg.feedback(Ease.clock(Player.position()) + " / " + Ease.clock(Player.duration()));
    }

    private static void volume(int delta) {
        if (!Player.canVolume()) {
            Msg.feedback("Volume of the player is not available");
            return;
        }
        Player.volumeBy(delta);
        Msg.feedback("Volume " + Player.volume() + "%");
    }
}
