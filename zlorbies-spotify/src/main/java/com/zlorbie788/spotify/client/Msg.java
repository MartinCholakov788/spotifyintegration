package com.zlorbie788.spotify.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Client-only feedback: a line in the chat window or a short message above the hotbar. */
public final class Msg {
    private Msg() {}

    public static void chat(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null) return;
        mc.gui.getChat().addMessage(Component.literal("[Spotify Integration Mod] ").withStyle(ChatFormatting.GREEN)
                .append(Component.literal(text).withStyle(ChatFormatting.WHITE)));
    }

    /** Feedback for a music key: the mini bar when it is on, otherwise the text above the hotbar. */
    public static void feedback(String text) {
        if (com.zlorbie788.spotify.hud.MiniBar.enabled()) com.zlorbie788.spotify.hud.MiniBar.show(text);
        else actionBar(text);
    }

    public static void actionBar(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.player.displayClientMessage(Component.literal(text), true);
    }
}
