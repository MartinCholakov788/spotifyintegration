package com.zlorbie788.spotify.util;

import com.zlorbie788.spotify.client.SpotifyClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * A very small blocking HTTP client built on {@link HttpURLConnection} (always available, no extra Java modules).
 * Call it from worker threads only, never from the render thread.
 */
public final class Http {
    private Http() {}

    /** A finished request. Error statuses (404, 429...) are normal results, not exceptions. */
    public record Response(int code, String body, String retryAfter) {
        public boolean ok() {
            return code >= 200 && code < 300;
        }
    }

    public static String userAgent() {
        return "Spotify-Integration-Mod/" + SpotifyClient.version() + " (Minecraft Fabric mod)";
    }

    public static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    /** key=value&key=value, both parts percent-encoded. */
    public static String form(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        return sb.toString();
    }

    public static Response get(String url, Map<String, String> headers) throws IOException {
        return request("GET", url, headers, null, null);
    }

    public static Response request(String method, String url, Map<String, String> headers, String body,
                                   String contentType) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(7000);
            c.setReadTimeout(10000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", userAgent());
            c.setRequestProperty("Accept", "application/json");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
            }
            boolean writes = "POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method) && body != null;
            if (writes) {
                byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(bytes.length);
                if (contentType != null) c.setRequestProperty("Content-Type", contentType);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : new String(readLimited(in, 4_000_000), StandardCharsets.UTF_8);
            return new Response(code, text, c.getHeaderField("Retry-After"));
        } finally {
            c.disconnect();
        }
    }

    /** Raw bytes of a picture or other file. Returns null on any HTTP error. */
    public static byte[] bytes(String url, int limit) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            c.setConnectTimeout(7000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", userAgent());
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                return readLimited(in, limit);
            }
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readLimited(InputStream in, int limit) throws IOException {
        try (in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                if (out.size() > limit) throw new IOException("answer too large");
            }
            return out.toByteArray();
        }
    }
}
