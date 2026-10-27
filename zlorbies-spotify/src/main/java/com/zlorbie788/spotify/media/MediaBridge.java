package com.zlorbie788.spotify.media;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.stream.Stream;

/**
 * Runs {@code bridge.ps1} (hidden Windows PowerShell) and talks to it over stdin / stdout: it reports what the
 * Windows media session says and carries out the player commands. If the helper dies it is restarted a few times.
 */
public final class MediaBridge {
    public enum State { IDLE, STARTING, RUNNING, FAILED, UNSUPPORTED }

    private static final Object LOCK = new Object();
    private static final Deque<String> LOG = new ArrayDeque<>();

    private static volatile State state = State.IDLE;
    private static volatile Snapshot snapshot = Snapshot.EMPTY;
    private static volatile String problem = "";
    private static volatile boolean smtc;
    private static volatile boolean audio;
    private static volatile String psVersion = "";
    private static volatile long lastLineAt;
    private static volatile long generation;

    private static Process process;
    private static BufferedWriter writer;
    private static int restarts;

    private MediaBridge() {}

    // ------------------------------------------------------------ status

    public static State state() {
        return state;
    }

    public static Snapshot snapshot() {
        return snapshot;
    }

    public static String problem() {
        return problem;
    }

    /** True once the helper said hello and the Windows media session could be opened. */
    public static boolean mediaSessionOk() {
        return smtc;
    }

    /** True when per-app volume control is available. */
    public static boolean volumeOk() {
        return audio;
    }

    public static String powerShellVersion() {
        return psVersion;
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    /** The last few lines the helper printed on its error stream or reported as errors. */
    public static List<String> recentLog() {
        synchronized (LOCK) {
            return new ArrayList<>(LOG);
        }
    }

    private static void log(String line) {
        synchronized (LOCK) {
            LOG.addLast(line);
            while (LOG.size() > 12) LOG.removeFirst();
        }
    }

    // ------------------------------------------------------------ lifecycle

    public static Path directory() {
        return ConfigIo.directory();
    }

    private static volatile String coverNote = "";
    private static volatile String coverType = "";

    /** What the helper says about the cover picture: "ok", or the reason it could not get one. */
    public static String coverNote() {
        return coverNote;
    }

    /** The picture format the player gave (for example "image/jpeg"), if the helper knows it. */
    public static String coverType() {
        return coverType;
    }

    public static Path coverDirectory() {
        return ConfigIo.directory().resolve("covers");
    }

    public static void start() {
        synchronized (LOCK) {
            if (state == State.STARTING || state == State.RUNNING) return;
            if (!isWindows()) {
                state = State.UNSUPPORTED;
                problem = "This player reads the Windows media session, so it needs Windows.";
                return;
            }
            state = State.STARTING;
            problem = "";
            restarts = 0;
        }
        launch();
    }

    public static void stop() {
        synchronized (LOCK) {
            generation++;
            sendUnsafe("quit");
            destroy();
            if (state != State.UNSUPPORTED) state = State.IDLE;
            snapshot = Snapshot.EMPTY;
        }
        Beat.reset();
    }

    public static void restart() {
        stop();
        start();
    }

    private static void destroy() {
        Process p = process;
        process = null;
        writer = null;
        if (p != null) {
            try {
                p.destroy();
            } catch (Throwable ignored) { }
        }
    }

    private static void launch() {
        Beat.reset();
        final long myGeneration;
        synchronized (LOCK) {
            myGeneration = ++generation;
        }
        Thread t = new Thread(() -> run(myGeneration), "spotify-integration-bridge");
        t.setDaemon(true);
        t.start();
    }

    private static void run(long myGeneration) {
        try {
            Path dir = directory();
            Path covers = coverDirectory();
            Files.createDirectories(covers);
            cleanCovers(covers);
            Path script = dir.resolve("bridge.ps1");
            try (InputStream in = MediaBridge.class.getResourceAsStream("/bridge/bridge.ps1")) {
                if (in == null) throw new IOException("bridge.ps1 is missing from the mod jar");
                Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
            }

            SpotifyConfig cfg = ConfigIo.get();
            List<String> cmd = new ArrayList<>();
            cmd.add(powershellExe());
            cmd.add("-NoLogo");
            cmd.add("-NoProfile");
            cmd.add("-NonInteractive");
            cmd.add("-ExecutionPolicy");
            cmd.add("Bypass");
            cmd.add("-WindowStyle");
            cmd.add("Hidden");
            cmd.add("-File");
            cmd.add(script.toString());
            cmd.add("-OutDir");
            cmd.add(covers.toString());
            cmd.add("-Source");
            cmd.add(cfg.source == SpotifyConfig.Source.ANY ? "any" : "spotify");
            cmd.add("-PollMs");
            cmd.add("500");

            Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            synchronized (LOCK) {
                if (myGeneration != generation) {
                    p.destroy();
                    return;
                }
                process = p;
                writer = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
            }

            Thread err = new Thread(() -> drainErrors(p), "spotify-integration-bridge-err");
            err.setDaemon(true);
            err.start();

            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (myGeneration != generation) return;
                    handle(line);
                }
            }
            int code = p.waitFor();
            if (myGeneration != generation) return;
            onExit(code);
        } catch (Throwable t) {
            if (myGeneration != generation) return;
            synchronized (LOCK) {
                state = State.FAILED;
                problem = "Could not start the Windows helper: " + t.getMessage();
                log(problem);
            }
        }
    }

    private static String powershellExe() {
        String root = System.getenv("SystemRoot");
        if (root != null) {
            Path p = Path.of(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
            if (Files.exists(p)) return p.toString();
        }
        return "powershell.exe";
    }

    private static void cleanCovers(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            files.forEach(f -> {
                try {
                    Files.deleteIfExists(f);
                } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private static void drainErrors(Process p) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isBlank()) log("PowerShell: " + line.trim());
            }
        } catch (IOException ignored) { }
    }

    private static void onExit(int code) {
        synchronized (LOCK) {
            process = null;
            writer = null;
            if (restarts < 3) {
                restarts++;
                state = State.STARTING;
                log("The helper stopped (code " + code + "), restarting.");
            } else {
                state = State.FAILED;
                problem = "The Windows helper keeps stopping (code " + code + ").";
                snapshot = Snapshot.EMPTY;
                return;
            }
        }
        try {
            Thread.sleep(1500);
        } catch (InterruptedException ignored) { }
        launch();
    }

    // ------------------------------------------------------------ messages from the helper

    private static void handle(String line) {
        lastLineAt = System.nanoTime();
        // Loudness readings arrive about 40 times a second: {"t":"lv","v":0.1234}
        if (line.startsWith("{\"t\":\"lv\"")) {
            int at = line.indexOf("\"v\":");
            if (at > 0) {
                int end = line.indexOf('}', at);
                try {
                    Beat.feed(Float.parseFloat(line.substring(at + 4, end < 0 ? line.length() : end).trim()), System.nanoTime());
                } catch (RuntimeException ignored) { }
            }
            return;
        }
        JsonObject o;
        try {
            o = JsonParser.parseString(line).getAsJsonObject();
        } catch (Exception e) {
            log("Unreadable line: " + (line.length() > 80 ? line.substring(0, 80) : line));
            return;
        }
        String type = str(o, "t");
        switch (type) {
            case "hello" -> {
                smtc = bool(o, "smtc");
                audio = bool(o, "audio");
                psVersion = str(o, "ps");
                synchronized (LOCK) {
                    state = State.RUNNING;
                    restarts = 0;
                }
            }
            case "error" -> log(str(o, "msg"));
            case "state" -> {
                synchronized (LOCK) {
                    if (state != State.RUNNING) state = State.RUNNING;
                }
                coverNote = str(o, "cs");
                coverType = str(o, "ct");
                snapshot = parse(o);
            }
            default -> { }
        }
    }

    private static Snapshot parse(JsonObject o) {
        Snapshot.Status status = switch (str(o, "status")) {
            case "playing" -> Snapshot.Status.PLAYING;
            case "paused" -> Snapshot.Status.PAUSED;
            case "stopped" -> Snapshot.Status.STOPPED;
            default -> Snapshot.Status.NONE;
        };
        if (status == Snapshot.Status.NONE) {
            return new Snapshot(Snapshot.Status.NONE, str(o, "app"), "", "", "", 0, 0, false, 0,
                    false, false, false, false, false, false, -1, false, "", "", false, System.nanoTime());
        }
        return new Snapshot(status, str(o, "app"), str(o, "title"), str(o, "artist"), str(o, "album"),
                num(o, "pos"), num(o, "dur"), bool(o, "shuffle"), (int) num(o, "repeat"),
                bool(o, "canSeek"), bool(o, "canNext"), bool(o, "canPrev"), bool(o, "canToggle"),
                bool(o, "canShuffle"), bool(o, "canRepeat"),
                o.has("vol") ? (int) num(o, "vol") : -1, bool(o, "mute"),
                str(o, "cover"), str(o, "key"), bool(o, "basic"), System.nanoTime());
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static boolean bool(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() && o.get(key).getAsBoolean();
    }

    private static long num(JsonObject o, String key) {
        try {
            return o.has(key) && !o.get(key).isJsonNull() ? (long) o.get(key).getAsDouble() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    // ------------------------------------------------------------ commands to the helper

    /** Sends one command line ("toggle", "seek 61000", ...). Does nothing when the helper is not running. */
    public static void send(String command) {
        synchronized (LOCK) {
            sendUnsafe(command);
        }
    }

    private static void sendUnsafe(String command) {
        BufferedWriter w = writer;
        if (w == null) return;
        try {
            w.write(command);
            w.write('\n');
            w.flush();
        } catch (IOException e) {
            log("Could not send '" + command + "': " + e.getMessage());
        }
    }

    /** Seconds since the helper last sent something (large while it is silent). */
    public static double silentSeconds() {
        long last = lastLineAt;
        if (last == 0L) return Double.MAX_VALUE;
        return (System.nanoTime() - last) / 1_000_000_000.0;
    }
}
