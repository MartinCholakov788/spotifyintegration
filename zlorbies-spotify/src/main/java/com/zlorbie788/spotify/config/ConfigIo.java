package com.zlorbie788.spotify.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Loads and saves {@link SpotifyConfig} next to the other Fabric configs. */
public final class ConfigIo {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static SpotifyConfig instance;

    private ConfigIo() { }

    public static SpotifyConfig get() {
        if (instance == null) load();
        return instance;
    }

    public static Path directory() {
        return FabricLoader.getInstance().getConfigDir().resolve("zlorbies-spotify");
    }

    public static Path file() {
        return directory().resolve("config.json");
    }

    public static void load() {
        SpotifyConfig loaded = null;
        Path file = file();
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                loaded = GSON.fromJson(r, SpotifyConfig.class);
            } catch (Exception e) {
                // Damaged file: keep a copy and start fresh instead of crashing the game.
                try {
                    Files.copy(file, directory().resolve("config.json.bak"), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) { }
            }
        }
        instance = loaded != null ? loaded : new SpotifyConfig();
        instance.sanitize();
        save();
    }

    public static void save() {
        if (instance == null) return;
        try {
            Files.createDirectories(directory());
            try (Writer w = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
                GSON.toJson(instance, w);
            }
        } catch (IOException ignored) {
            // best effort
        }
    }

    /** Writes the settings as text (used by the profiles). */
    public static String toJson(SpotifyConfig c) {
        return GSON.toJson(c);
    }

    /** Reads settings written by {@link #toJson}; null when the text is not usable. */
    public static SpotifyConfig fromJson(String json) {
        try {
            SpotifyConfig c = GSON.fromJson(json, SpotifyConfig.class);
            if (c == null) return null;
            c.sanitize();
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    /** Makes these settings the active ones (the file is rewritten). */
    public static void replace(SpotifyConfig c) {
        instance = c;
        instance.sanitize();
        save();
    }

    /** Back to factory settings (the file is rewritten). */
    public static void reset() {
        instance = new SpotifyConfig();
        instance.sanitize();
        save();
    }
}
