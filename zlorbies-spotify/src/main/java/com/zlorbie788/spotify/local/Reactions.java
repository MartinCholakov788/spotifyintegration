package com.zlorbie788.spotify.local;

import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.config.SpotifyConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;

/**
 * Game reactions for your own songs: the music gets quieter while the chat is open or right after you take damage, and
 * the game's own music can stay silent while a song of yours plays. (Spotify's volume belongs to Spotify, so none of this
 * touches it.) Runs once per client tick.
 */
public final class Reactions {
    private static final long HURT_HOLD_MS = 1400L;

    private static float duck = 1f;
    private static long hurtUntil;
    private static int lastHurtTime;
    private static int musicTick;
    private static float applied = 1f;

    private Reactions() {}

    /** The factor the music is lowered by right now (1 = not lowered). */
    public static float duck() {
        return duck;
    }

    public static void tick(Minecraft mc) {
        SpotifyConfig cfg = ConfigIo.get();
        boolean mine = Sources.local() && LocalPlayer.playing();
        long now = System.currentTimeMillis();

        float target = 1f;
        if (mine) {
            if (cfg.duckOnChat && mc.screen instanceof ChatScreen) target = cfg.duckLevel;
            if (cfg.duckOnHurt && mc.player != null) {
                int h = mc.player.hurtTime;
                if (h > 0 && lastHurtTime == 0) hurtUntil = now + HURT_HOLD_MS;
                lastHurtTime = h;
                if (now < hurtUntil) target = Math.min(target, cfg.duckLevel);
            }
        } else {
            lastHurtTime = 0;
        }
        // ease towards the target: quick down, a little slower back up
        float k = target < duck ? 0.35f : 0.12f;
        duck += (target - duck) * k;
        if (Math.abs(duck - target) < 0.004f) duck = target;
        if (duck != applied) {
            applied = duck;
            LocalPlayer.audio().setDuck(duck);
        }

        if (mine && cfg.lowerGameMusic && mc.getMusicManager() != null && ++musicTick >= 10) {
            musicTick = 0;
            mc.getMusicManager().stopPlaying();
        }
    }

    /** For tests. */
    static void reset() {
        duck = 1f;
        hurtUntil = 0L;
        lastHurtTime = 0;
        musicTick = 0;
        applied = 1f;
    }
}
