package com.zlorbie788.spotify.local;

import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.media.MediaBridge;
import com.zlorbie788.spotify.media.Player;

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
import java.util.List;

/**
 * Global media keys for your own music: a tiny hidden Windows helper ({@code mediakeys.ps1}) takes the keyboard's
 * play/pause, next and previous keys while your own songs are the active source, so they work even when Minecraft is in the
 * background. While Spotify is the source the keys are given back, so they keep controlling Spotify as usual.
 * Windows only; if the helper cannot start the keys simply do nothing extra.
 */
public final class MediaKeys {
    public enum State { OFF, STARTING, ON, FAILED }

    private static final Object LOCK = new Object();
    private static volatile State state = State.OFF;
    private static volatile String problem = "";
    private static Process process;
    private static BufferedWriter writer;
    private static long generation;
    private static long retryAt;

    private MediaKeys() {}

    public static State state() {
        return state;
    }

    /** Plain words for the settings screen. */
    public static String status() {
        if (!ConfigIo.get().mediaKeys) return "off";
        if (!MediaBridge.isWindows()) return "needs Windows";
        return switch (state) {
            case ON -> "on while your own music plays";
            case STARTING -> "starting";
            case FAILED -> "could not start: " + (problem.isEmpty() ? "unknown reason" : problem);
            default -> Sources.local() ? "starting" : "ready - on when your own music is the source";
        };
    }

    /** Once per client tick. */
    public static void tick() {
        boolean wanted = ConfigIo.get().mediaKeys && MediaBridge.isWindows() && Sources.local();
        synchronized (LOCK) {
            if (wanted) {
                if (process == null && state != State.FAILED && System.nanoTime() >= retryAt) startLocked();
            } else if (process != null) {
                stopLocked();
            } else if (state == State.FAILED && !ConfigIo.get().mediaKeys) {
                state = State.OFF;
            }
        }
    }

    /** Tries again after a failure. */
    public static void retry() {
        synchronized (LOCK) {
            if (state == State.FAILED) state = State.OFF;
            retryAt = 0L;
        }
    }

    public static void stop() {
        synchronized (LOCK) {
            stopLocked();
        }
    }

    private static void startLocked() {
        state = State.STARTING;
        problem = "";
        final long gen = ++generation;
        Thread t = new Thread(() -> run(gen), "spotify-integration-mediakeys");
        t.setDaemon(true);
        t.start();
    }

    private static void stopLocked() {
        generation++;
        BufferedWriter w = writer;
        Process p = process;
        process = null;
        writer = null;
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
    }

    private static void run(long gen) {
        try {
            Path dir = MediaBridge.directory();
            Files.createDirectories(dir);
            Path script = dir.resolve("mediakeys.ps1");
            try (InputStream in = MediaKeys.class.getResourceAsStream("/bridge/mediakeys.ps1")) {
                if (in == null) throw new IOException("mediakeys.ps1 is missing from the mod jar");
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
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            synchronized (LOCK) {
                if (gen != generation) {
                    p.destroy();
                    return;
                }
                process = p;
                writer = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (gen != generation) return;
                    handle(line.trim());
                }
            }
            p.waitFor();
            if (gen != generation) return;
            synchronized (LOCK) {
                process = null;
                writer = null;
                if (state != State.FAILED) {
                    state = State.FAILED;
                    problem = problem.isEmpty() ? "the helper stopped" : problem;
                }
            }
        } catch (Throwable e) {
            if (gen != generation) return;
            synchronized (LOCK) {
                process = null;
                writer = null;
                problem = "could not start the media key helper: " + e.getMessage();
                state = State.FAILED;
            }
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

    /** One line of the helper (public for tests). */
    static void handle(String line) {
        if (line.isEmpty()) return;
        if (line.equals("H ok")) {
            state = State.ON;
        } else if (line.startsWith("E ")) {
            problem = line.substring(2).trim();
            state = State.FAILED;
        } else if (line.startsWith("K ")) {
            String key = line.substring(2).trim();
            LocalPlayer.post(() -> press(key));
        }
    }

    /** A media key arrived (on the game thread). */
    static void press(String key) {
        if (!ConfigIo.get().mediaKeys || !Sources.local()) return;
        switch (key) {
            case "play" -> Player.toggle();
            case "next" -> Player.next();
            case "prev" -> Player.previous();
            default -> { }
        }
    }
}
