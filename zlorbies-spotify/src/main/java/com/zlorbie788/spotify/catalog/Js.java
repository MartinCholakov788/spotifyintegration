package com.zlorbie788.spotify.catalog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;

/** Tolerant JSON reading: a missing or odd field is an empty value, never an exception. */
final class Js {
    private Js() {}

    static JsonObject parse(String text) throws IOException {
        try {
            JsonElement e = JsonParser.parseString(text);
            if (e.isJsonObject()) return e.getAsJsonObject();
        } catch (RuntimeException ignored) { }
        throw new IOException("the answer was not understood");
    }

    static String str(JsonObject o, String key) {
        if (o == null || !o.has(key)) return "";
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return "";
        return e.getAsString();
    }

    static long num(JsonObject o, String key) {
        if (o == null || !o.has(key)) return 0;
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return 0;
        try {
            return e.getAsLong();
        } catch (RuntimeException ex) {
            try {
                return (long) e.getAsDouble();
            } catch (RuntimeException ex2) {
                return 0;
            }
        }
    }

    static JsonObject obj(JsonObject o, String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonObject()) return null;
        return o.getAsJsonObject(key);
    }

    static JsonArray arr(JsonObject o, String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonArray()) return new JsonArray();
        return o.getAsJsonArray(key);
    }
}
