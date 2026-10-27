package com.zlorbie788.spotify.media;

import com.zlorbie788.spotify.audio.Analyzer;
import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.config.SpotifyConfig;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Optional "Real audio analysis" for Spotify: a hidden Windows helper ({@code capture.ps1}) listens to the sound Spotify
 * plays and sends it back in small pieces, which are measured with the same {@link Analyzer} that is used for your own songs.
 * That gives Spotify the real spectrum, kick and tempo instead of the loudness-only model.
 *
 * <p>It only runs while Spotify is playing and the option is on, nothing is saved, and if the helper fails for any reason the
 * player simply keeps using the loudness meter. The helper is a separate process so it can never freeze the game.
 */
public final class Loopback {
    public enum State { OFF, WAITING, STARTING, APP, SYSTEM, FAILED }

    private static final long IDLE_STOP_NS = 15_000_000_000L;
    private static final long FRESH_NS = 450_000_000L;
    /** How long the capture may be completely silent while a song plays before it is ignored. */
    private static final long SILENT_NS = 1_800_000_000L;

    private static volatile State state = State.OFF;
    private static volatile String problem = "";
    private static volatile String target = "";

    private static final Object LOCK = new Object();
    private static Process process;
    private static BufferedWriter writer;
    private static long generation;
    private static long unwantedSince;
    private static long retryAt;
    private static int attempts;
    private static String startedFor = "";

    private static volatile Analyzer analyzer;
    private static volatile int rate = 48000;
    private static volatile long framesFed;
    private static volatile long lastChunkNs;
    /** The last time a piece of the capture held real sound (not just digital silence). */
    private static volatile long lastSoundNs;
    private static volatile float lastPeak;
    private static float[] stereo = new float[0];

    private Loopback() {}

    // ------------------------------------------------------------ status

    public static State state() {
        return state;
    }

    /** Plain-words state for the settings screen. */
    public static String status() {
        SpotifyConfig cfg = ConfigIo.get();
        if (!cfg.realAudio) return "off";
        if (!MediaBridge.isWindows()) return "needs Windows";
        return switch (state) {
            case OFF, WAITING -> "ready - starts when Spotify plays";
            case STARTING -> "starting";
            case APP -> hearing() ? "listening to Spotify only"
                    : live() ? "connected but silent: using the loudness meter instead" : "listening to Spotify (waiting for sound)";
            case SYSTEM -> hearing() ? "listening to all system sound (this older Windows cannot isolate Spotify)"
                    : live() ? "connected but silent: using the loudness meter instead"
                    : "listening to all system sound (waiting for sound)";
            case FAILED -> "could not start: " + (problem.isEmpty() ? "unknown reason" : problem);
        };
    }

    /** True while measurements of real audio are arriving. */
    public static boolean live() {
        return analyzer != null && (state == State.APP || state == State.SYSTEM)
                && System.nanoTime() - lastChunkNs < FRESH_NS;
    }

    /**
     * True while the capture arrives AND carries sound. A capture that connects but only ever delivers silence (the
     * wrong program was picked, or Windows hands over empty buffers) is not worth following: the visuals then use the
     * loudness meter instead of drawing flat lines.
     */
    public static boolean hearing() {
        long now = System.nanoTime();
        return live() && now - lastSoundNs < SILENT_NS;
    }

    /** Loudness (0..1) of the newest piece of the capture. */
    public static float peak() {
        return lastPeak;
    }

    public static Analyzer analyzer() {
        return analyzer;
    }

    /** The frame the visuals should show now (shifted by the sync setting). */
    public static long visualFrame(int syncMs) {
        long sinceChunk = Math.max(0L, Math.min(40_000_000L, System.nanoTime() - lastChunkNs));
        long ahead = sinceChunk * rate / 1_000_000_000L;
        return framesFed + ahead - (long) syncMs * rate / 1000L - Analyzer.WINDOW / 2;
    }

    /** Tries again after a failure (the option was switched off and on, or the "Try again" button). */
    public static void retry() {
        synchronized (LOCK) {
            attempts = 0;
            retryAt = 0L;
            if (state == State.FAILED) state = State.OFF;
        }
    }

    // ------------------------------------------------------------ per tick

    /** Once per client tick: starts and stops the helper as needed. */
    public static void tick() {
        SpotifyConfig cfg = ConfigIo.get();
        Snapshot s = MediaBridge.snapshot();
        boolean wanted = cfg.realAudio && MediaBridge.isWindows() && !com.zlorbie788.spotify.local.Sources.local()
                && s.playing() && !s.isAd();
        long now = System.nanoTime();
        if (wanted) {
            unwantedSince = 0L;
            String name = processName(s);
            synchronized (LOCK) {
                if (process != null && !name.equals(startedFor) && !startedFor.isEmpty()) {
                    stopLocked(); // the player app changed
                }
                if (process == null && state != State.FAILED && now >= retryAt) startLocked(name);
                else if (state == State.OFF) state = State.WAITING;
            }
        } else {
            if (unwantedSince == 0L) unwantedSince = now;
            synchronized (LOCK) {
                if (process != null && (!cfg.realAudio || now - unwantedSince > IDLE_STOP_NS)) stopLocked();
                if (!cfg.realAudio && state == State.FAILED) state = State.OFF;
            }
        }
    }

    /** The process to listen to: "spotify" for Spotify, otherwise the name of the app that plays. */
    static String processName(Snapshot s) {
        String app = s.appName();
        if (app.isEmpty() || app.equalsIgnoreCase("Spotify")) return "spotify";
        return app.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9._-]", "");
    }

    // ------------------------------------------------------------ the helper process

    private static void startLocked(String name) {
        state = State.STARTING;
        problem = "";
        startedFor = name;
        target = name;
        final long myGen = ++generation;
        Thread t = new Thread(() -> run(myGen, name), "spotify-integration-capture");
        t.setDaemon(true);
        t.start();
    }

    private static void stopLocked() {
        generation++;
        BufferedWriter w = writer;
        Process p = process;
        process = null;
        writer = null;
        startedFor = "";
        if (w != null) {
            try {
                w.write("quit\n");
                w.flush();
            } catch (IOException ignored) { }
        }
        if (p != null) {
            try {
                p.destroy();
            } catch (Throwable ignored) { }
        }
        if (state != State.FAILED) state = State.OFF;
        lastChunkNs = 0L;
    }

    public static void stop() {
        synchronized (LOCK) {
            stopLocked();
        }
    }

    private static void run(long myGen, String name) {
        try {
            Path dir = MediaBridge.directory();
            Files.createDirectories(dir);
            Path script = dir.resolve("capture.ps1");
            try (InputStream in = Loopback.class.getResourceAsStream("/bridge/capture.ps1")) {
                if (in == null) throw new IOException("capture.ps1 is missing from the mod jar");
                Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
            }
            List<String> cmd = new ArrayList<>();
            cmd.add(powershell());
            cmd.add("-NoLogo");
            cmd.add("-NoProfile");
            cmd.add("-NonInteractive");
            cmd.add("-ExecutionPolicy");
            cmd.add("Bypass");
            cmd.add("-WindowStyle");
            cmd.add("Hidden");
            cmd.add("-File");
            cmd.add(script.toString());
            cmd.add("-Name");
            cmd.add(name);
            Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            synchronized (LOCK) {
                if (myGen != generation) {
                    p.destroy();
                    return;
                }
                process = p;
                writer = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
            }
            Thread err = new Thread(() -> drain(p), "spotify-integration-capture-err");
            err.setDaemon(true);
            err.start();

            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.US_ASCII), 1 << 16)) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (myGen != generation) return;
                    handle(line);
                }
            }
            p.waitFor();
            if (myGen != generation) return;
            onExit();
        } catch (Throwable e) {
            if (myGen != generation) return;
            fail("could not start the capture helper: " + e.getMessage());
        }
    }

    private static String powershell() {
        String root = System.getenv("SystemRoot");
        if (root != null) {
            Path p = Path.of(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
            if (Files.exists(p)) return p.toString();
        }
        return "powershell.exe";
    }

    private static void drain(Process p) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isBlank() && state == State.STARTING) problem = line.trim();
            }
        } catch (IOException ignored) { }
    }

    private static void handle(String line) {
        if (line.isEmpty()) return;
        char kind = line.charAt(0);
        if (kind == 'P') {
            chunk(line);
        } else if (kind == 'H') {
            String[] parts = line.split(" ");
            try {
                int r = Integer.parseInt(parts[2]);
                if (r < 8000 || r > 192000) throw new NumberFormatException();
                rate = r;
                analyzer = new Analyzer(r);
                framesFed = 0L;
                lastChunkNs = 0L;
                lastSoundNs = System.nanoTime();
                attempts = 0;
                state = "app".equals(parts[1]) ? State.APP : State.SYSTEM;
            } catch (RuntimeException e) {
                fail("the capture helper said something unexpected: " + line);
            }
        } else if (kind == 'E') {
            problem = line.length() > 2 ? line.substring(2).trim() : "";
        }
    }

    private static void chunk(String line) {
        Analyzer a = analyzer;
        if (a == null || line.length() < 3) return;
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(line.substring(2));
        } catch (IllegalArgumentException e) {
            return;
        }
        int n = raw.length / 2;
        if (n <= 0) return;
        if (stereo.length < n * 2) stereo = new float[n * 2];
        float peak = 0f;
        for (int i = 0; i < n; i++) {
            short v = (short) ((raw[2 * i] & 0xFF) | (raw[2 * i + 1] << 8));
            float f = v / 32768f;
            stereo[2 * i] = f;
            stereo[2 * i + 1] = f;
            float m = f < 0f ? -f : f;
            if (m > peak) peak = m;
        }
        lastPeak = peak;
        if (peak > 0.0015f) lastSoundNs = System.nanoTime();
        long end = framesFed + n;
        a.feed(stereo, n, end);
        framesFed = end;
        lastChunkNs = System.nanoTime();
    }

    private static void onExit() {
        synchronized (LOCK) {
            process = null;
            writer = null;
            startedFor = "";
            lastChunkNs = 0L;
            State was = state;
            attempts++;
            if (was == State.STARTING || attempts >= 4) {
                // never got going, or keeps dying: give up until the person tries again
                fail(problem.isEmpty() ? "the capture helper stopped" : problem);
            } else {
                state = State.OFF;
                retryAt = System.nanoTime() + 3_000_000_000L * attempts;
            }
        }
    }

    private static void fail(String why) {
        synchronized (LOCK) {
            problem = why;
            state = State.FAILED;
            lastChunkNs = 0L;
            Process p = process;
            process = null;
            writer = null;
            startedFor = "";
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) { }
            }
        }
    }

    /** For tests: feed a chunk as if the helper had sent it. */
    static void testHello(String mode, int sampleRate) {
        handle("H " + mode + " " + sampleRate);
    }

    static void testChunk(short[] mono) {
        byte[] raw = new byte[mono.length * 2];
        for (int i = 0; i < mono.length; i++) {
            raw[2 * i] = (byte) mono[i];
            raw[2 * i + 1] = (byte) (mono[i] >> 8);
        }
        chunk("P " + Base64.getEncoder().encodeToString(raw));
    }
}
