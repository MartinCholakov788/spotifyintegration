package com.zlorbie788.spotify.config;

import com.zlorbie788.spotify.client.SpotifyClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

/**
 * The fonts the player can write its text in. The first is Minecraft's own; the others are fonts that come with the mod
 * (see {@code assets/zlorbies-spotify/font}). A font that has no letter for a character falls back to Minecraft's font for
 * that character. A font that fails to load in the game is detected ({@link #works()}) and simply not offered, so a broken
 * font can never end up as rows of boxes.
 */
public enum UiFont {
    MINECRAFT("Minecraft", null, null),
    INTER("Inter", "zlorbies-spotify", "inter"),
    POPPINS("Poppins", "zlorbies-spotify", "poppins"),
    ADVENTOR("Adventor", "zlorbies-spotify", "adventor"),
    NARROW("Narrow", "zlorbies-spotify", "narrow"),
    MONO("Mono", "zlorbies-spotify", "mono"),
    TERMINAL("Terminal", "zlorbies-spotify", "terminal"),
    LORA("Serif", "zlorbies-spotify", "lora"),
    SCRIPT("Script", "zlorbies-spotify", "script"),
    CARLITO("Humanist", "zlorbies-spotify", "carlito");

    public final String label;
    private final String namespace;
    private final String path;
    private Style style;
    /** 1 = loaded fine, -1 = failed (re-checked now and then), 0 = not checked yet. */
    private int state;
    private long checkedAt;

    UiFont(String label, String namespace, String path) {
        this.label = label;
        this.namespace = namespace;
        this.path = path;
    }

    /** True for Minecraft's normal font (no custom style needed). */
    public boolean isDefault() {
        return path == null;
    }

    /** The text style that selects this font (never called for {@link #MINECRAFT}). */
    public Style style() {
        if (style == null) style = styleOf(namespace, path);
        return style;
    }

    private static Style styleOf(String namespace, String path) {
        Identifier id = Identifier.fromNamespaceAndPath(namespace, path);
        return Style.EMPTY.withFont(new FontDescription.Resource(id));
    }

    /**
     * Did this font load? Minecraft draws text in a font that does not exist (or failed to load) as identical boxes, so
     * "every letter has the same width as the box" means it is broken. Checked once the game has loaded its resources, and
     * checked again every few seconds while it looks broken (a resource reload may have been under way).
     */
    public boolean works() {
        if (isDefault()) return true;
        if (state == 1) return true;
        long now = System.currentTimeMillis();
        if (state == -1 && now - checkedAt < 4000L) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.font == null) return true;
        checkedAt = now;
        boolean ok;
        try {
            ok = !looksMissing(mc.font);
        } catch (Throwable t) {
            ok = true;
        }
        if (!ok && state != -1) {
            SpotifyClient.LOGGER.warn("[Spotify Integration Mod] font \"{}\" did not load; it is hidden from the font lists", label);
        }
        state = ok ? 1 : -1;
        return ok;
    }

    private boolean looksMissing(Font f) {
        Style none = styleOf("zlorbies-spotify", "no_such_font");
        String probe = "iWm.";
        boolean allBoxes = true;
        boolean sameAsBox = true;
        int first = -1;
        for (int i = 0; i < probe.length(); i++) {
            String ch = String.valueOf(probe.charAt(i));
            int mine = f.width(Component.literal(ch).withStyle(style()));
            int box = f.width(Component.literal(ch).withStyle(none));
            if (mine != box) sameAsBox = false;
            if (first < 0) first = mine;
            else if (mine != first) allBoxes = false;
        }
        return sameAsBox && allBoxes;
    }

    /** Every font that works, in menu order. */
    public static java.util.List<UiFont> available() {
        java.util.List<UiFont> list = new java.util.ArrayList<>();
        for (UiFont f : values()) if (f.works()) list.add(f);
        return list;
    }

    /** The next font that works (or the same one when it is the only one). */
    public UiFont next(int step) {
        UiFont[] all = values();
        int i = ordinal();
        for (int n = 0; n < all.length; n++) {
            i = ((i + step) % all.length + all.length) % all.length;
            if (all[i].works()) return all[i];
        }
        return MINECRAFT;
    }
}
