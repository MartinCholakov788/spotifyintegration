package com.zlorbie788.spotify.client;

import com.zlorbie788.spotify.media.Player;
import com.zlorbie788.spotify.media.Snapshot;
import net.minecraft.client.Minecraft;

/** "Share this song": copies "Artist - Title" to the clipboard and, when asked, says it in the chat. */
public final class Share {
    private Share() {}

    /** The text that is shared, or "" when nothing is playing. */
    public static String text(Snapshot s) {
        if (s == null || !s.active() || s.isAd() || s.title().isBlank()) return "";
        return "Now listening to: " + (s.artist().isBlank() ? s.title() : s.artist() + " - " + s.title());
    }

    /**
     * Copies the song that is playing to the clipboard. With {@code toChat} it is also sent as a chat message (on a
     * server everyone sees it, so it only happens when the person asked for it).
     */
    public static void send(Minecraft mc, boolean toChat) {
        String t = text(Player.snap());
        if (t.isEmpty()) {
            Msg.feedback("Nothing to share");
            return;
        }
        mc.keyboardHandler.setClipboard(t);
        if (toChat && mc.player != null && mc.getConnection() != null) {
            mc.getConnection().sendChat(t);
            Msg.feedback("Shared in the chat");
        } else {
            Msg.feedback("Copied: " + t.substring("Now listening to: ".length()));
        }
    }
}
