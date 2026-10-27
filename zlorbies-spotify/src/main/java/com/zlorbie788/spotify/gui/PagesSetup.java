package com.zlorbie788.spotify.gui;

import com.zlorbie788.spotify.client.Keybinds;
import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.config.Profiles;
import com.zlorbie788.spotify.config.SafeMode;
import com.zlorbie788.spotify.config.SpotifyConfig;
import com.zlorbie788.spotify.gui.widgets.StyledButton;
import com.zlorbie788.spotify.hud.Gfx;
import com.zlorbie788.spotify.hud.Theme;
import com.zlorbie788.spotify.util.Colors;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** The "Keys & setup" group: key rebinding, profiles, the tour and safe look. */
final class PagesSetup {
    /** In the order the keys are registered. */
    static final String[] KEY_NAMES = {"Open player", "Settings", "Show / hide", "Play / pause", "Next song",
            "Previous song", "Seek forward", "Seek back", "Volume up", "Volume down", "Mute", "Shuffle", "Repeat",
            "Search songs", "Lyrics on / off", "My music", "Like song", "Share song"};
    static final String[] KEY_HELP = {
            "Opens the big player screen.", "Opens these settings.", "Shows or hides the on-screen player.",
            "Starts or stops the song.", "Skips to the next song.", "Goes back to the previous song.",
            "Jumps ahead in the song.", "Jumps back in the song.", "Turns the music up.", "Turns the music down.",
            "Mutes or unmutes.", "Shuffle on or off.", "Cycles repeat: off, all, one.", "Opens the song search.",
            "Shows or hides the lyrics.", "Opens your own music folder screen.", "Adds the song to your Favourites.",
            "Copies \"Artist - Title\" so you can paste it anywhere."};

    private static String draft = "";
    private static String confirmDelete = "";

    private PagesSetup() {}

    private static SpotifyConfig cfg() {
        return ConfigIo.get();
    }

    static void setup(SettingsKit k) {
        k.group(6);
        SettingsKit.Host host = k.host;

        k.section("Keys", false);
        for (int i = 0; i < Keybinds.ALL.size(); i++) {
            KeyMapping mapping = Keybinds.ALL.get(i);
            String name = i < KEY_NAMES.length ? KEY_NAMES[i] : Component.translatable(mapping.getName()).getString();
            String help = i < KEY_HELP.length ? KEY_HELP[i] : "";
            SettingsRow r = k.row(name + " key", help, "Click the key, then press the one you want. Backspace removes it.");
            r.controlH = 16;
            StyledButton key = Ui.button(0, 0, 50, 16, "", b -> {
                host.listen(mapping);
                host.rebuild();
            }).accent(k.accent());
            key.tip("Click, then press the key you want.");
            key.setMessage(keyLabel(host, mapping));
            StyledButton clear = Ui.button(0, 0, 18, 16, "x", b -> {
                Keybinds.rebind(mapping, GLFW.GLFW_KEY_UNKNOWN);
                host.listen(null);
                host.rebuild();
            }).accent(Colors.RED);
            clear.tip("Remove this key.");
            r.add(key, 0.40f, 0.50f, 0, -2, 0).add(clear, 0.90f, 0.10f, 0, 0, 0);
            final String label = name;
            r.draw = (g, f, x, y, w, mx, my) -> {
                Gfx.drawStyled(g, f, Gfx.fit(f, label, (int) (w * 0.40f) - 6), x + 2, y + 4, Colors.opaque(Theme.text()), false, false, false);
                String clash = Keybinds.conflict(mapping);
                if (clash != null) {
                    Gfx.drawStyled(g, f, "!", x + (int) (w * 0.40f) - 8, y + 4, Colors.opaque(Theme.bad()), false, false, true);
                }
            };
        }
        k.status("Key clashes", "", 2, () -> {
            for (KeyMapping mapping : Keybinds.ALL) {
                String clash = Keybinds.conflict(mapping);
                if (clash != null) {
                    return "! " + Component.translatable(mapping.getName()).getString() + " shares its key with " + clash
                            + ". Change one of them.";
                }
            }
            return "";
        }, Theme::bad).when(() -> {
            for (KeyMapping mapping : Keybinds.ALL) if (Keybinds.conflict(mapping) != null) return true;
            return false;
        });
        k.button("Default keys", "Put every key of the mod back to what it was.", "The number pad is used for the music keys.",
                "Reset keys", false, () -> {
                    Keybinds.resetAll();
                    host.listen(null);
                    host.rebuild();
                });

        k.section("Profiles", false);
        EditBox box = new EditBox(host.font(), 0, 0, 100, 15, Component.literal("Profile name"));
        box.setMaxLength(Profiles.MAX_NAME);
        box.setHint(Component.literal("Name, e.g. Survival"));
        box.setValue(draft);
        box.setResponder(v -> draft = v);
        StyledButton save = Ui.button(0, 0, 50, 15, "Save", b -> {
            String saved = Profiles.save(draft, cfg());
            if (saved == null) {
                host.toast(Profiles.cleanName(draft).isEmpty() ? "Type a name first" : "Could not save (up to " + Profiles.MAX + " profiles)");
                return;
            }
            ConfigIo.save();
            draft = "";
            host.toast("Saved the profile \"" + saved + "\"");
            host.rebuild();
        }).accent(k.accent()).primary();
        save.tip("Saves your whole setup (look, lyrics, music, equaliser and keys) under this name.");
        SettingsRow saveRow = k.row("Save the current setup as a profile", "Type a name and press Save. Same name = replace.",
                "A profile holds the look, lyrics, music options, equaliser and the keys of this mod. It does not hold the tour or intro state.");
        saveRow.add(box, 0, 0.7f, 0, -2, 0).add(save, 0.7f, 0.3f, 2, -2, 0);
        k.status("Current profile", "", 1, () -> cfg().profile.isEmpty() ? "No profile loaded" : "Last loaded: " + cfg().profile,
                Theme::soft);
        for (String name : Profiles.names()) {
            SettingsRow r = k.row("Profile " + name, "Brings back everything saved under this name.", "");
            r.controlH = 16;
            StyledButton load = Ui.button(0, 0, 50, 16, "Load", b -> {
                if (Profiles.load(name)) {
                    host.toast("Loaded \"" + name + "\"");
                    host.rebuild();
                } else {
                    host.toast("Could not load \"" + name + "\"");
                }
            }).accent(k.accent());
            StyledButton del = Ui.button(0, 0, 50, 16, name.equals(confirmDelete) ? "Sure?" : "Delete", b -> {
                if (!name.equals(confirmDelete)) {
                    confirmDelete = name;
                    b.setMessage(Component.literal("Sure?"));
                    return;
                }
                confirmDelete = "";
                Profiles.delete(name);
                host.rebuild();
            }).accent(Colors.RED);
            del.tip("Deletes the saved profile. Your current settings do not change.");
            r.add(load, 0.52f, 0.24f, 0, -2, 0).add(del, 0.76f, 0.24f, 2, -2, 0);
            r.draw = (g, f, x, y, w, mx, my) -> {
                boolean active = name.equals(cfg().profile);
                Gfx.drawStyled(g, f, Gfx.fit(f, name, (int) (w * 0.52f) - 8), x + 2, y + 4,
                        Colors.opaque(active ? Theme.good() : Theme.text()), active, false, false);
            };
        }

        k.section("Tour and safe look", false);
        k.button("Quick tour", "Five short cards that explain the mod, shown right here in the settings.", "You can also type /music tutorial in chat.",
                "Show the quick tour", true, Tour::start);
        k.button("Reset to safe look", "If anything looks broken, this puts the look back to the plain factory look.",
                "Style, colours, fonts and effects go back to normal, and moods, real audio and the intro are switched off. "
                        + "Your positions, music, keys and profiles stay.", "Reset to safe look", false, () -> {
                    SafeMode.resetLook(cfg());
                    ConfigIo.save();
                    com.zlorbie788.spotify.media.Covers.reload();
                    host.toast("Safe look is on");
                    host.rebuild();
                });
        k.status("Last problem", "", 3, () -> {
            String p = SafeMode.problem();
            return p.isEmpty() ? "Nothing has failed." : "Something failed: " + p;
        }, () -> SafeMode.problem().isEmpty() ? Theme.good() : Theme.bad());
    }

    static net.minecraft.network.chat.Component keyLabel(SettingsKit.Host host, KeyMapping mapping) {
        if (mapping == host.listening()) return Component.literal("Press a key...").withStyle(ChatFormatting.YELLOW);
        return mapping.isUnbound()
                ? Component.literal("Not set").withStyle(ChatFormatting.GRAY)
                : mapping.getTranslatedKeyMessage();
    }
}
