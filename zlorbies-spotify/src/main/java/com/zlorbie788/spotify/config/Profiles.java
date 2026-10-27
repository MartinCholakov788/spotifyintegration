package com.zlorbie788.spotify.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Profiles: the whole setup (look, lyrics, music, keys of the mod, sounds) saved under a name and brought back with one
 * click. They live in {@code profiles.json} next to the settings. Nothing is sent anywhere.
 */
public final class Profiles {
    public static final int MAX = 20;
    public static final int MAX_NAME = 24;

    /** Things that describe the installation rather than the setup, so a profile never changes them. */
    private static final String[] KEEP = {"configVersion", "tourDone", "introSeen", "profile"};

    /** Where the keys of the mod are read from when saving / put back when loading (set by the game side; null in tests). */
    public static Supplier<Map<String, String>> keySource;
    public static Consumer<Map<String, String>> keySink;

    private static Path override;

    private Profiles() {}

    private static Path file() {
        return override != null ? override : ConfigIo.directory().resolve("profiles.json");
    }

    /** For tests. */
    static void useFile(Path p) {
        override = p;
    }

    public static String cleanName(String raw) {
        String s = raw == null ? "" : raw.replaceAll("\\p{Cntrl}", "").strip();
        if (s.length() > MAX_NAME) s = s.substring(0, MAX_NAME).strip();
        return s;
    }

    private static JsonObject read() {
        try {
            Path f = file();
            if (!Files.exists(f)) return new JsonObject();
            JsonElement e = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
            return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private static boolean write(JsonObject o) {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, o.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static synchronized List<String> names() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : read().entrySet()) out.add(e.getKey());
        return out;
    }

    public static synchronized boolean exists(String name) {
        return read().has(cleanName(name));
    }

    /** Saves the current setup under the name (replacing a profile with the same name). Returns the name, or null when not possible. */
    public static synchronized String save(String wanted, SpotifyConfig cfg) {
        String name = cleanName(wanted);
        if (name.isEmpty()) return null;
        JsonObject all = read();
        if (!all.has(name) && all.size() >= MAX) return null;
        JsonObject body = JsonParser.parseString(ConfigIo.toJson(cfg)).getAsJsonObject();
        for (String k : KEEP) body.remove(k);
        if (keySource != null) {
            JsonObject keys = new JsonObject();
            try {
                for (Map.Entry<String, String> e : keySource.get().entrySet()) keys.addProperty(e.getKey(), e.getValue());
            } catch (RuntimeException ignored) { }
            body.add("keys", keys);
        }
        all.add(name, body);
        if (!write(all)) return null;
        cfg.profile = name;
        return name;
    }

    /** Applies a saved profile on top of the active settings. Returns false when there is no such profile. */
    public static synchronized boolean load(String name) {
        JsonObject all = read();
        if (!all.has(name) || !all.get(name).isJsonObject()) return false;
        SpotifyConfig now = ConfigIo.get();
        JsonObject merged = JsonParser.parseString(ConfigIo.toJson(now)).getAsJsonObject();
        Map<String, String> keys = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : all.getAsJsonObject(name).entrySet()) {
            if (e.getKey().equals("keys")) {
                if (e.getValue().isJsonObject()) {
                    for (Map.Entry<String, JsonElement> k : e.getValue().getAsJsonObject().entrySet()) {
                        if (k.getValue().isJsonPrimitive()) keys.put(k.getKey(), k.getValue().getAsString());
                    }
                }
                continue;
            }
            if (isKept(e.getKey())) continue;
            merged.add(e.getKey(), e.getValue());
        }
        SpotifyConfig next = ConfigIo.fromJson(merged.toString());
        if (next == null) return false;
        next.profile = name;
        ConfigIo.replace(next);
        if (keySink != null && !keys.isEmpty()) {
            try {
                keySink.accept(keys);
            } catch (RuntimeException ignored) { }
        }
        return true;
    }

    public static synchronized boolean delete(String name) {
        JsonObject all = read();
        if (all.remove(name) == null) return false;
        write(all);
        SpotifyConfig c = ConfigIo.get();
        if (name.equals(c.profile)) c.profile = "";
        return true;
    }

    private static boolean isKept(String key) {
        for (String k : KEEP) if (k.equals(key)) return true;
        return false;
    }
}
