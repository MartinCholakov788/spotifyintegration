package com.zlorbie788.spotify.local;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zlorbie788.spotify.audio.Analysis;
import com.zlorbie788.spotify.audio.Engine;
import com.zlorbie788.spotify.audio.Eq;
import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.config.SpotifyConfig;
import com.zlorbie788.spotify.media.Snapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Plays the songs of your music folder: the queue ("up next"), shuffle, repeat, previous / next, seeking, the volume
 * and remembering where you were. Everything here runs on the game thread; the sound itself is made by the
 * {@link Engine} on its own thread. The mod's {@code Player} asks this class for a {@link Snapshot} that looks exactly
 * like the one the Windows media session gives for Spotify, so every screen and effect works for both.
 */
public final class LocalPlayer {
    public static final String APP = "Local files";
    public static final int MAX_ORDER = 5000;

    private static final long GRACE_MS = 3000L;
    private static final int MAX_FAILS = 3;

    // ---- the list being played
    private static final List<Track> order = new ArrayList<>();
    private static final List<Track> context = new ArrayList<>();
    private static int index = -1;
    private static int userCount;
    private static boolean shuffle;
    private static int repeat;

    // ---- the song
    private static Track current;
    private static long curToken = -1;
    private static long startPosMs;
    private static boolean wantPlay;
    private static boolean loaded;
    private static long pendingToken = -1;
    private static int pendingIndex = -1;
    private static long nextToken = 1;
    private static final Map<Long, Track> TOKENS = new HashMap<>();
    private static int failures;
    private static long failAt;
    private static String error = "";

    // ---- plumbing
    private static Engine engine;
    private static Snapshot snap = Snapshot.EMPTY;
    private static final ConcurrentLinkedQueue<Runnable> INBOX = new ConcurrentLinkedQueue<>();
    private static Random random = new Random();
    private static boolean restored;
    private static long lastSaveNs;
    private static long cfgDirtyAt;
    private static Path sessionOverride;
    private static long lastDurFix;

    // what was last pushed to the engine
    private static int sentVolume = -1;
    private static Boolean sentMuted;
    private static int sentCross = -1;
    private static int sentEqKey = Integer.MIN_VALUE;
    private static String sentDevice = null;
    private static int sentDelay = Integer.MIN_VALUE;

    private LocalPlayer() {}

    // ------------------------------------------------------------ test hooks

    /** For tests: the engine to use and a fixed random generator. */
    public static void useEngine(Engine e, long seed) {
        engine = e;
        random = new Random(seed);
    }

    /** For tests: forget everything (as after a restart). */
    public static void resetForTests(Path sessionFile) {
        order.clear();
        context.clear();
        index = -1;
        userCount = 0;
        shuffle = false;
        repeat = 0;
        current = null;
        curToken = -1;
        startPosMs = 0;
        wantPlay = false;
        loaded = false;
        pendingToken = -1;
        pendingIndex = -1;
        TOKENS.clear();
        failures = 0;
        error = "";
        snap = Snapshot.EMPTY;
        INBOX.clear();
        restored = false;
        sessionOverride = sessionFile;
        sentVolume = -1;
        sentMuted = null;
        sentCross = -1;
        sentEqKey = Integer.MIN_VALUE;
        sentDevice = null;
        sentDelay = Integer.MIN_VALUE;
    }

    private static Engine engine() {
        if (engine == null) engine = Engine.get();
        return engine;
    }

    private static SpotifyConfig cfg() {
        return ConfigIo.get();
    }

    // ------------------------------------------------------------ readings

    /** What the player looks like now, in the same shape as the Spotify reading. */
    public static Snapshot snapshot() {
        return snap;
    }

    public static Track current() {
        return current;
    }

    public static boolean hasCurrent() {
        return current != null;
    }

    public static boolean playing() {
        return current != null && wantPlay;
    }

    public static boolean shuffle() {
        return shuffle;
    }

    /** 0 = off, 1 = repeat the song, 2 = repeat the list. */
    public static int repeat() {
        return repeat;
    }

    public static String error() {
        return error;
    }

    public static void clearError() {
        error = "";
    }

    /** Songs that will come after the current one, in order (the "up next" list). */
    public static List<Track> upcoming() {
        if (index < 0 || index + 1 >= order.size()) return List.of();
        return List.copyOf(order.subList(index + 1, order.size()));
    }

    /** How many of {@link #upcoming()} were put there by you (play next / add to queue). */
    public static int queuedCount() {
        return Math.max(0, Math.min(userCount, upcoming().size()));
    }

    /** The songs already played in this list (oldest first). */
    public static List<Track> history() {
        if (index <= 0) return List.of();
        return List.copyOf(order.subList(0, Math.min(index, order.size())));
    }

    public static int indexInOrder() {
        return index;
    }

    /** Position in ms (what you hear), valid while a song is loaded. */
    public static long positionMs() {
        if (current == null) return 0L;
        if (loaded && engine().heardToken() == curToken) return Math.max(0L, engine().positionMs());
        return startPosMs;
    }

    /** The loudness picture of what is playing right now (silence when nothing plays). */
    public static Analysis analysis() {
        if (!loaded || !wantPlay) return Analysis.SILENCE;
        return engine().analysisNow();
    }

    public static Engine audio() {
        return engine();
    }

    // ------------------------------------------------------------ per tick

    /** Once per client tick. */
    public static void tick() {
        Runnable r;
        while ((r = INBOX.poll()) != null) {
            try {
                r.run();
            } catch (RuntimeException e) {
                error = "Something went wrong: " + e.getMessage();
            }
        }
        if (!restored && MusicLibrary.ready()) restore();
        syncSettings();
        follow();
        build();
        saveLater();
    }

    /** Runs a command on the game thread (for helpers on other threads, like the media keys). */
    public static void post(Runnable r) {
        INBOX.add(r);
    }

    private static long lastEndedToken = -2;

    private static void follow() {
        Engine e = engine();
        failureStep(e);
        if (!loaded) return;
        long heard = e.heardToken();
        if (heard >= 0 && heard != curToken) {
            if (heard == pendingToken && pendingIndex >= 0 && pendingIndex < order.size()) {
                advanceTo(pendingIndex, heard);
            } else if (TOKENS.containsKey(heard) && heard > curToken) {
                // a song we did not expect (queue changed while crossfading): adopt it
                Track t = TOKENS.get(heard);
                int at = -1;
                for (int i = index + 1; i < order.size(); i++) {
                    if (order.get(i).id.equals(t.id)) {
                        at = i;
                        break;
                    }
                }
                if (at < 0) {
                    at = Math.min(order.size(), index + 1);
                    order.add(at, t);
                }
                advanceTo(at, heard);
            }
        }
        if (heard == curToken && failures > 0 && e.positionMs() > 2000) failures = 0;
        if (wantPlay && e.wantsPreload() && pendingToken < 0) {
            int n = peekNext(true);
            if (n >= 0 && n < order.size()) {
                Track t = order.get(n);
                long token = nextToken++;
                TOKENS.put(token, t);
                pendingToken = token;
                pendingIndex = n;
                e.preload(token, file(t));
            }
        }
        if (e.ended() && heard != lastEndedToken) {
            lastEndedToken = heard;
            songEnded();
        }
        if (current != null && loaded && heard == curToken) {
            long len = e.lengthMs();
            long now = System.currentTimeMillis();
            if (len > 0 && Math.abs(len - current.durMs) > 400 && now - lastDurFix > 2000) {
                lastDurFix = now;
                Track fresh = MusicLibrary.byId(current.id);
                if (fresh != null) MusicLibrary.updateDuration(fresh.id, len);
                Track updated = MusicLibrary.byId(current.id);
                if (updated != null) current = updated;
            }
        }
    }

    /** An engine error (damaged file...) or a missing file: say so, then move on to the next song. */
    private static void failureStep(Engine e) {
        String err = loaded ? e.error() : "";
        if (!err.isEmpty() && failAt == 0L) {
            error = err;
            failAt = System.currentTimeMillis();
        }
        if (failAt != 0L && System.currentTimeMillis() - failAt > 1200L) {
            failAt = 0L;
            if (loaded) e.clearError();
            if (wantPlay) {
                failures++;
                int n = peekNext(false);
                if (failures < MAX_FAILS && n >= 0 && n != index) {
                    index = n;
                    consumeUser();
                    startCurrent(0L, true, false);
                } else {
                    wantPlay = false;
                    loaded = false;
                    e.stop();
                }
            }
        }
    }

    private static void advanceTo(int newIndex, long token) {
        int delta = newIndex - index;
        index = newIndex;
        if (delta > 0) userCount = Math.max(0, userCount - delta);
        current = TOKENS.get(token);
        curToken = token;
        pendingToken = -1;
        pendingIndex = -1;
        startPosMs = 0L;
        failures = 0;
        error = "";
        prune();
        save();
    }

    private static void songEnded() {
        pendingToken = -1;
        pendingIndex = -1;
        int n = peekNext(true);
        if (n >= 0 && wantPlay) {
            index = n;
            consumeUser();
            startCurrent(0L, true, false);
        } else {
            // the list is over: stay on the last song, ready to play it again
            wantPlay = false;
            loaded = false;
            startPosMs = 0L;
            engine().stop();
            save();
        }
    }

    private static void consumeUser() {
        if (userCount > 0) userCount--;
    }

    private static void prune() {
        if (TOKENS.size() < 12) return;
        TOKENS.keySet().removeIf(t -> t != curToken && t != pendingToken && t < curToken - 6);
    }

    /** The index of the song that follows, or -1 at the end of the list. */
    private static int peekNext(boolean auto) {
        if (order.isEmpty() || index < 0) return -1;
        if (auto && repeat == 1) return index;
        int n = index + 1;
        if (n < order.size()) return n;
        if (repeat == 2) return 0;
        return -1;
    }

    private static Path file(Track t) {
        return MusicLibrary.directory().resolve(t.id);
    }

    // ------------------------------------------------------------ starting songs

    /**
     * Starts the song at {@code index} of the current list.
     *
     * @param user true when a person asked for it (the other source is then paused)
     */
    private static void startCurrent(long fromMs, boolean autoStart, boolean user) {
        if (index < 0 || index >= order.size()) return;
        Track t = order.get(index);
        if (!java.nio.file.Files.exists(file(t))) {
            error = "\"" + t.fileName() + "\" is not in the music folder any more.";
            failAt = System.currentTimeMillis();
            current = t;
            curToken = -1;
            wantPlay = autoStart;
            loaded = false;
            build();
            return;
        }
        Engine e = engine();
        current = t;
        curToken = nextToken++;
        TOKENS.put(curToken, t);
        startPosMs = Math.max(0L, fromMs);
        pendingToken = -1;
        pendingIndex = -1;
        wantPlay = autoStart;
        loaded = true;
        error = "";
        syncSettings();
        e.play(curToken, file(t), startPosMs * Engine.RATE / 1000L, autoStart);
        if (autoStart && user) Sources.localStarted();
        prune();
        save();
        build();
    }

    /** Plays {@code list} starting with the song at {@code start}. The list becomes the new queue. */
    public static void playFrom(List<Track> list, int start) {
        if (list == null || list.isEmpty()) return;
        int s = Math.max(0, Math.min(start, list.size() - 1));
        context.clear();
        context.addAll(list.size() > MAX_ORDER ? list.subList(0, MAX_ORDER) : list);
        if (list.size() > MAX_ORDER) s = Math.min(s, MAX_ORDER - 1);
        userCount = 0;
        Track first = context.get(Math.min(s, context.size() - 1));
        buildOrder(first);
        startCurrent(0L, true, true);
    }

    /** Plays the whole list in a random order. */
    public static void playShuffled(List<Track> list) {
        if (list == null || list.isEmpty()) return;
        shuffle = true;
        playFrom(list, random.nextInt(list.size()));
    }

    private static void buildOrder(Track first) {
        order.clear();
        if (shuffle) {
            List<Track> rest = new ArrayList<>(context);
            int at = -1;
            for (int i = 0; i < rest.size(); i++) {
                if (rest.get(i).id.equals(first.id)) {
                    at = i;
                    break;
                }
            }
            if (at >= 0) rest.remove(at);
            Collections.shuffle(rest, random);
            order.add(first);
            order.addAll(rest);
            index = 0;
        } else {
            order.addAll(context);
            index = 0;
            for (int i = 0; i < order.size(); i++) {
                if (order.get(i).id.equals(first.id)) {
                    index = i;
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------ commands

    public static void toggle() {
        if (wantPlay) pause();
        else play();
    }

    public static void play() {
        if (current == null) {
            restoreOrStart();
            return;
        }
        if (!loaded) {
            startCurrent(startPosMs, true, true);
            return;
        }
        wantPlay = true;
        engine().resume();
        Sources.localStarted();
        save();
        build();
    }

    private static boolean restoreOrStart() {
        if (MusicLibrary.isEmpty()) {
            error = "Your music folder is empty. Put MP3, OGG, WAV or FLAC files in it.";
            return false;
        }
        List<Track> all = MusicLibrary.search("", MusicLibrary.Sort.ARTIST);
        playFrom(all, 0);
        return true;
    }

    public static void pause() {
        if (!wantPlay) return;
        pauseQuiet();
    }

    /** Pauses without telling the other source (it took over, or the mode changed). */
    public static void pauseQuiet() {
        if (!wantPlay) return;
        wantPlay = false;
        if (loaded) {
            if (engine().heardToken() == curToken) engine().pause();
            else startCurrent(startPosMs, false, false); // still opening: open it paused instead
        }
        save();
        build();
    }

    public static void next() {
        if (order.isEmpty()) return;
        int n = peekNext(false);
        if (n < 0) n = 0; // the end of the list: next goes back to the top
        index = n;
        consumeUser();
        startCurrent(0L, true, true);
    }

    public static void previous() {
        if (order.isEmpty() || current == null) return;
        if (positionMs() > 3000L) {
            restartCurrent();
        } else if (index > 0) {
            index--;
            userCount = 0;
            startCurrent(0L, true, true);
        } else if (repeat == 2 && order.size() > 1) {
            index = order.size() - 1;
            userCount = 0;
            startCurrent(0L, true, true);
        } else {
            restartCurrent();
        }
    }

    private static void restartCurrent() {
        seekTo(0L);
        if (!wantPlay) play();
    }

    public static void seekTo(long ms) {
        if (current == null) return;
        long target = Math.max(0L, ms);
        long dur = current.durMs;
        if (dur > 0) target = Math.min(target, Math.max(0L, dur - 300L));
        if (loaded && engine().heardToken() == curToken) {
            engine().seek(target * Engine.RATE / 1000L);
        } else if (loaded) {
            // the song is still opening: start it from there
            startCurrent(target, wantPlay, false);
            return;
        }
        startPosMs = target;
        save();
        build();
    }

    public static int volume() {
        return cfg().localVolume;
    }

    public static boolean muted() {
        return cfg().localMuted;
    }

    public static void setVolume(int percent) {
        SpotifyConfig c = cfg();
        c.localVolume = Math.max(0, Math.min(100, percent));
        if (c.localVolume > 0) c.localMuted = false;
        cfgDirtyAt = System.nanoTime();
        syncSettings();
    }

    public static void setMuted(boolean m) {
        cfg().localMuted = m;
        cfgDirtyAt = System.nanoTime();
        syncSettings();
    }

    public static void toggleShuffle() {
        setShuffle(!shuffle);
    }

    public static void setShuffle(boolean on) {
        if (on == shuffle) return;
        shuffle = on;
        if (current == null || order.isEmpty()) return;
        invalidatePreload();
        // keep what the person queued by hand, re-order the rest
        List<Track> queued = new ArrayList<>(upcoming().subList(0, queuedCount()));
        Track cur = current;
        List<Track> rest;
        if (context.isEmpty()) context.addAll(order);
        if (on) {
            rest = new ArrayList<>(context);
            rest.removeIf(t -> t.id.equals(cur.id));
            Collections.shuffle(rest, random);
            order.clear();
            order.add(cur);
            order.addAll(queued);
            order.addAll(rest);
            index = 0;
        } else {
            order.clear();
            order.addAll(context);
            index = 0;
            for (int i = 0; i < order.size(); i++) {
                if (order.get(i).id.equals(cur.id)) {
                    index = i;
                    break;
                }
            }
            order.addAll(index + 1, queued);
        }
        userCount = queued.size();
        save();
        build();
    }

    public static void setRepeat(int mode) {
        int m = Math.floorMod(mode, 3);
        if (m == repeat) return;
        repeat = m;
        invalidatePreload();
        save();
        build();
    }

    public static void cycleRepeat() {
        setRepeat(repeat + 1);
    }

    // ------------------------------------------------------------ the queue

    private static void invalidatePreload() {
        if (pendingToken >= 0) {
            pendingToken = -1;
            pendingIndex = -1;
            if (loaded) engine().clearPreload();
        }
    }

    /** "Play next": the song comes right after the current one. Starts it when nothing is playing. */
    public static void playNext(Track t) {
        if (t == null) return;
        if (current == null || order.isEmpty()) {
            playFrom(List.of(t), 0);
            return;
        }
        invalidatePreload();
        order.add(Math.min(order.size(), index + 1), t);
        userCount++;
        save();
    }

    /** "Add to queue": after the songs you queued before, before the rest of the list. */
    public static void addToQueue(Track t) {
        if (t == null) return;
        if (current == null || order.isEmpty()) {
            playFrom(List.of(t), 0);
            return;
        }
        invalidatePreload();
        int at = Math.min(order.size(), index + 1 + queuedCount());
        order.add(at, t);
        userCount++;
        save();
    }

    /** Removes the song at {@code upcomingIndex} of {@link #upcoming()}. */
    public static void removeUpcoming(int upcomingIndex) {
        int at = index + 1 + upcomingIndex;
        if (upcomingIndex < 0 || at >= order.size()) return;
        invalidatePreload();
        order.remove(at);
        if (upcomingIndex < userCount) userCount--;
        save();
    }

    /** Moves a song inside the up-next list (drag and drop). */
    public static void moveUpcoming(int from, int to) {
        int size = upcoming().size();
        if (from < 0 || from >= size || to < 0 || to >= size || from == to) return;
        invalidatePreload();
        Track t = order.remove(index + 1 + from);
        order.add(index + 1 + to, t);
        if (from < userCount && to >= userCount) userCount--;
        else if (from >= userCount && to < userCount) userCount++;
        save();
    }

    /** Jumps to a song of the up-next list and plays it. */
    public static void playUpcoming(int upcomingIndex) {
        int at = index + 1 + upcomingIndex;
        if (upcomingIndex < 0 || at >= order.size()) return;
        int skipped = Math.min(userCount, upcomingIndex + 1);
        index = at;
        userCount = Math.max(0, userCount - skipped);
        startCurrent(0L, true, true);
    }

    /** Empties the up-next list (the song playing stays). */
    public static void clearUpcoming() {
        if (index < 0) return;
        invalidatePreload();
        while (order.size() > index + 1) order.remove(order.size() - 1);
        userCount = 0;
        save();
    }

    // ------------------------------------------------------------ engine settings

    private static void syncSettings() {
        SpotifyConfig c = cfg();
        Engine e = engine();
        if (sentVolume != c.localVolume) {
            sentVolume = c.localVolume;
            e.setVolume(c.localVolume / 100f);
        }
        if (sentMuted == null || sentMuted != c.localMuted) {
            sentMuted = c.localMuted;
            e.setMuted(c.localMuted);
        }
        if (sentCross != c.crossfadeSeconds) {
            sentCross = c.crossfadeSeconds;
            e.setCrossfadeMs(c.crossfadeSeconds * 1000);
        }
        int eqKey = eqKey(c);
        if (eqKey != sentEqKey) {
            sentEqKey = eqKey;
            e.setEq(eqSettings(c));
        }
        if (sentDevice == null || !sentDevice.equals(c.audioDevice)) {
            sentDevice = c.audioDevice;
            e.setDevice(c.audioDevice);
        }
        if (sentDelay != c.visualSyncMs) {
            sentDelay = c.visualSyncMs;
            e.setVisualDelayMs(c.visualSyncMs);
        }
    }

    private static int eqKey(SpotifyConfig c) {
        int h = (c.eqOn ? 1 : 0) + 31 * c.eqPreset;
        if (c.eqPreset == Eq.CUSTOM) for (float g : c.eqGains) h = h * 31 + Float.floatToIntBits(g);
        return h;
    }

    /** The equaliser the settings ask for. */
    public static Eq.Settings eqSettings(SpotifyConfig c) {
        if (!c.eqOn) return Eq.Settings.FLAT;
        float[] gains = c.eqPreset == Eq.CUSTOM ? c.eqGains.clone() : Eq.preset(c.eqPreset);
        return new Eq.Settings(true, gains, c.eqPreset == Eq.NIGHT);
    }

    // ------------------------------------------------------------ the snapshot

    private static void build() {
        if (current == null) {
            snap = Snapshot.EMPTY;
            return;
        }
        Track t = current;
        Snapshot.Status st = wantPlay ? Snapshot.Status.PLAYING : Snapshot.Status.PAUSED;
        long pos = positionMs();
        String cover = t.cover.isEmpty() ? "" : "local:" + t.cover;
        snap = new Snapshot(st, APP, t.title, t.artist, t.album, pos, t.durMs, shuffle, repeat,
                true, true, true, true, true, true, cfg().localVolume, cfg().localMuted,
                cover, "local:" + t.id, false, System.nanoTime());
    }

    /** Rebuilds the snapshot right now (after a command, so the screen reacts at once). */
    public static void refreshNow() {
        build();
    }

    // ------------------------------------------------------------ remembering

    private static Path sessionFile() {
        return sessionOverride != null ? sessionOverride : ConfigIo.directory().resolve("session.json");
    }

    private static boolean dirty;

    private static void save() {
        dirty = true;
    }

    private static void saveLater() {
        long now = System.nanoTime();
        if (cfgDirtyAt != 0L && now - cfgDirtyAt > 1_500_000_000L) {
            cfgDirtyAt = 0L;
            ConfigIo.save();
        }
        boolean periodic = wantPlay && now - lastSaveNs > 5_000_000_000L;
        if ((dirty && now - lastSaveNs > 400_000_000L) || periodic) {
            dirty = false;
            lastSaveNs = now;
            writeSession();
        }
    }

    /** Saves right away (the game is closing). */
    public static void flush() {
        if (cfgDirtyAt != 0L) {
            cfgDirtyAt = 0L;
            ConfigIo.save();
        }
        writeSession();
    }

    private static void writeSession() {
        if (!cfg().rememberSession || current == null) return;
        try {
            JsonObject o = new JsonObject();
            o.addProperty("current", current.id);
            o.addProperty("positionMs", positionMs());
            o.addProperty("shuffle", shuffle);
            o.addProperty("repeat", repeat);
            o.addProperty("index", index);
            o.addProperty("userCount", userCount);
            JsonArray ids = new JsonArray();
            int from = Math.max(0, index - 50);
            int to = Math.min(order.size(), index + 1000);
            for (int i = from; i < to; i++) ids.add(order.get(i).id);
            o.add("order", ids);
            o.addProperty("orderFrom", from);
            JsonArray ctx = new JsonArray();
            for (Track t : context) ctx.add(t.id);
            o.add("context", ctx);
            Path f = sessionFile();
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling("session.json.tmp");
            Files.writeString(tmp, o.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException ignored) {
            // remembering is a convenience
        }
    }

    private static void restore() {
        restored = true;
        if (!cfg().rememberSession || current != null) return;
        Path f = sessionFile();
        if (!Files.exists(f)) return;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            Track cur = MusicLibrary.byId(o.get("current").getAsString());
            if (cur == null) return;
            shuffle = o.has("shuffle") && o.get("shuffle").getAsBoolean();
            repeat = o.has("repeat") ? Math.floorMod(o.get("repeat").getAsInt(), 3) : 0;
            context.clear();
            if (o.has("context")) {
                for (JsonElement e : o.getAsJsonArray("context")) {
                    Track t = MusicLibrary.byId(e.getAsString());
                    if (t != null) context.add(t);
                }
            }
            order.clear();
            if (o.has("order")) {
                for (JsonElement e : o.getAsJsonArray("order")) {
                    Track t = MusicLibrary.byId(e.getAsString());
                    if (t != null) order.add(t);
                }
            }
            index = -1;
            for (int i = 0; i < order.size(); i++) {
                if (order.get(i).id.equals(cur.id)) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                order.add(0, cur);
                index = 0;
            }
            userCount = o.has("userCount") ? Math.max(0, o.get("userCount").getAsInt()) : 0;
            current = cur;
            curToken = -1;
            loaded = false;
            wantPlay = false;
            startPosMs = o.has("positionMs") ? Math.max(0L, o.get("positionMs").getAsLong()) : 0L;
            if (cur.durMs > 0 && startPosMs > cur.durMs - 1500L) startPosMs = 0L;
        } catch (IOException | RuntimeException e) {
            order.clear();
            context.clear();
            index = -1;
            current = null;
        }
    }

    // ------------------------------------------------------------ helpers for screens

    /** Fresh random order of a list (used by "shuffle play"). */
    public static List<Track> shuffled(List<Track> list) {
        List<Track> copy = new ArrayList<>(list);
        Collections.shuffle(copy, random);
        return copy;
    }
}
