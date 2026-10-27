package com.zlorbie788.spotify.client;

import com.zlorbie788.spotify.catalog.Playback;
import com.zlorbie788.spotify.catalog.Thumbs;
import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.hud.LyricsHud;
import com.zlorbie788.spotify.hud.PlayerHud;
import com.zlorbie788.spotify.lyrics.Lyrics;
import com.zlorbie788.spotify.media.Covers;
import com.zlorbie788.spotify.media.MediaBridge;
import com.zlorbie788.spotify.media.Player;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/** Spotify Integration Mod: client entry point. */
public class SpotifyClient implements ClientModInitializer {
    public static final String MOD_ID = "zlorbies-spotify";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static String version = "1.0.0";
    private static Supplier<Screen> pendingScreen;

    /** The installed version, read from fabric.mod.json. */
    public static String version() {
        return version;
    }

    /** Screens opened from commands wait one tick, so the chat screen cannot close them again. */
    public static void openLater(Supplier<Screen> screen) {
        pendingScreen = screen;
    }

    @Override
    public void onInitializeClient() {
        FabricLoader.getInstance().getModContainer(MOD_ID)
                .ifPresent(container -> version = container.getMetadata().getVersion().getFriendlyString());
        ConfigIo.load();
        Keybinds.register();
        Commands.init();

        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(MOD_ID, "player"),
                (g, dt) -> com.zlorbie788.spotify.config.SafeMode.guard("The on-screen player", () -> PlayerHud.render(g, dt)));
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(MOD_ID, "lyrics"),
                (g, dt) -> com.zlorbie788.spotify.config.SafeMode.guard("The on-screen lyrics", () -> LyricsHud.render(g, dt)));
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(MOD_ID, "toast"),
                (g, dt) -> com.zlorbie788.spotify.config.SafeMode.guard("The song-change toast", () -> com.zlorbie788.spotify.hud.Toast.render(g, dt)));
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(MOD_ID, "minibar"), com.zlorbie788.spotify.hud.MiniBar::render);
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(MOD_ID, "safe"), com.zlorbie788.spotify.hud.SafeBanner::render);
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> com.zlorbie788.spotify.hud.Intro.onJoin());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            com.zlorbie788.spotify.local.LocalPlayer.flush();
            ConfigIo.save();
            com.zlorbie788.spotify.stats.Stats.save();
            MediaBridge.stop();
            com.zlorbie788.spotify.media.Loopback.stop();
            com.zlorbie788.spotify.local.MediaKeys.stop();
            com.zlorbie788.spotify.audio.Engine.shutdownGame();
        });

        com.zlorbie788.spotify.hud.Intro.prewarmArt(ConfigIo.get());
        if (ConfigIo.get().startBridge) MediaBridge.start();
        LOGGER.info("Spotify Integration Mod " + version() + " loaded. Press Right Alt to open the player.");
    }

    private void onTick(Minecraft mc) {
        if (pendingScreen != null) {
            Supplier<Screen> supplier = pendingScreen;
            pendingScreen = null;
            mc.setScreen(supplier.get());
        }
        com.zlorbie788.spotify.local.Sources.tick();
        com.zlorbie788.spotify.media.Loopback.tick();
        com.zlorbie788.spotify.local.Reactions.tick(mc);
        com.zlorbie788.spotify.local.TagFixer.tick();
        com.zlorbie788.spotify.local.MediaKeys.tick();
        Player.tick();
        Covers.tick(mc);
        Lyrics.tick();
        Playback.tick();
        com.zlorbie788.spotify.catalog.PlaylistRunner.tick();
        com.zlorbie788.spotify.stats.Stats.tick();
        com.zlorbie788.spotify.stats.Achievements.tick();
        String unlocked = com.zlorbie788.spotify.stats.Achievements.takeAnnouncement();
        if (!unlocked.isEmpty()) Msg.feedback(unlocked);
        Thumbs.tick(mc);
        if (mc.screen == null) Keybinds.tick(mc);
    }
}
