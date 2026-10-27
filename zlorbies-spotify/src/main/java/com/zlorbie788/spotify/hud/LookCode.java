package com.zlorbie788.spotify.hud;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zlorbie788.spotify.config.LyricsConfig;
import com.zlorbie788.spotify.config.SpotifyConfig;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Your whole look as one short line of text, so you can paste it into Discord or a Modrinth comment and others can
 * paste it back in. A code holds only how things LOOK (style, shapes, colours, fonts, the lyrics style). It never
 * holds where the player is, what is switched on, your keys or anything about playback or your files.
 * Only what differs from the factory look is written, so codes stay short; importing first puts every look setting back
 * to the factory value and then applies the code.
 * Format: {@code SIM1:} + URL-safe base64 of gzip-compressed JSON.
 */
public final class LookCode {
    public static final String PREFIX = "SIM1:";

    private static final Gson GSON = new Gson();

    /** Settings of {@link SpotifyConfig} that are part of a look. */
    static final String[] PLAYER_FIELDS = {
            "playerLook", "style", "accent", "accentColor", "background", "shape", "radius", "borderWidth",
            "coverShape", "trimCover", "coverZoom", "roundCover", "cornerBrackets", "viz", "progressStyle",
            "borderTrace", "shine", "ambient", "particles", "parallax", "uiAnimations", "ripples", "font", "uiFont",
            "fontShift", "panelShape", "panelRadius", "panelBorder", "panelBack", "panelOpacity", "uiTheme",
            "uiRadius", "uiAccent", "uiColor", "showCover", "showAlbum", "showTime", "showProgress", "showEqualizer",
            "showAccentLine", "showGlow", "textShadow", "highContrast", "spinCover", "breathingGlow", "beatPulse",
    };

    /** Settings of {@link LyricsConfig} that are part of a look. */
    static final String[] LYRICS_FIELDS = {
            "align", "boldCurrent", "currentScale", "currentTone", "currentColor", "otherTone", "otherColor", "fade",
            "shadow", "outline", "outlineColor", "italic", "textCase", "otherScale", "pastDim", "highlight", "motion",
            "font", "back", "backOpacity", "corner", "border", "accentBar",
    };

    private LookCode() {}

    /** The code for the look in {@code cfg}. */
    public static String export(SpotifyConfig cfg) {
        JsonObject root = new JsonObject();
        root.addProperty("v", 1);
        SpotifyConfig base = new SpotifyConfig();
        JsonObject p = new JsonObject();
        for (String name : PLAYER_FIELDS) put(p, SpotifyConfig.class, cfg, base, name);
        root.add("p", p);
        JsonObject l = new JsonObject();
        if (cfg.lyrics != null) for (String name : LYRICS_FIELDS) put(l, LyricsConfig.class, cfg.lyrics, base.lyrics, name);
        root.add("l", l);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
                gz.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** True when the text looks like one of our codes (spaces and line breaks around it are fine). */
    public static boolean looksLikeCode(String text) {
        return text != null && text.strip().startsWith(PREFIX) && text.strip().length() > PREFIX.length() + 8;
    }

    /**
     * Applies a code to {@code cfg}. Returns the number of settings that were set, or -1 when the text is not a
     * usable code (then nothing is changed).
     */
    public static int apply(SpotifyConfig cfg, String text) {
        if (!looksLikeCode(text)) return -1;
        JsonObject root;
        try {
            String body = text.strip().substring(PREFIX.length()).replaceAll("\\s+", "");
            byte[] zipped = Base64.getUrlDecoder().decode(body);
            try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(zipped))) {
                String json = new String(gz.readNBytes(256 * 1024), StandardCharsets.UTF_8);
                JsonElement e = JsonParser.parseString(json);
                if (!e.isJsonObject()) return -1;
                root = e.getAsJsonObject();
            }
        } catch (Exception e) {
            return -1;
        }
        if (!root.has("p") || !root.get("p").isJsonObject()) return -1;
        int n = 0;
        JsonObject p = root.getAsJsonObject("p");
        SpotifyConfig base = new SpotifyConfig();
        if (cfg.lyrics == null) cfg.lyrics = new LyricsConfig();
        // a code is a difference from the factory look: start from the factory look
        for (String name : PLAYER_FIELDS) copy(SpotifyConfig.class, base, cfg, name);
        for (String name : LYRICS_FIELDS) copy(LyricsConfig.class, base.lyrics, cfg.lyrics, name);
        for (String name : PLAYER_FIELDS) n += take(p, SpotifyConfig.class, cfg, name, false);
        if (root.has("l") && root.get("l").isJsonObject()) {
            JsonObject l = root.getAsJsonObject("l");
            for (String name : LYRICS_FIELDS) n += take(l, LyricsConfig.class, cfg.lyrics, name, true);
        }
        cfg.lyrics.sanitize();
        cfg.sanitize();
        return n;
    }

    private static void copy(Class<?> type, Object from, Object into, String name) {
        try {
            Field f = type.getField(name);
            f.set(into, f.get(from));
        } catch (ReflectiveOperationException ignored) { }
    }

    private static void put(JsonObject into, Class<?> type, Object from, Object base, String name) {
        try {
            Field f = type.getField(name);
            Object v = f.get(from);
            if (java.util.Objects.equals(v, f.get(base))) return;
            if (v == null) into.add(name, com.google.gson.JsonNull.INSTANCE);
            else into.add(name, GSON.toJsonTree(v, f.getGenericType()));
        } catch (ReflectiveOperationException ignored) { }
    }

    private static int take(JsonObject from, Class<?> type, Object into, String name, boolean nullAllowed) {
        if (!from.has(name)) return 0;
        try {
            Field f = type.getField(name);
            JsonElement e = from.get(name);
            Object v = e.isJsonNull() ? null : GSON.fromJson(e, f.getGenericType());
            if (v == null && (!nullAllowed || !name.equals("font"))) return 0; // unknown enum names come back null
            if (v instanceof Number num && Double.isNaN(num.doubleValue())) return 0;
            f.set(into, v);
            return 1;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return 0;
        }
    }
}
