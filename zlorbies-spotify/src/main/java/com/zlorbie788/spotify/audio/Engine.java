package com.zlorbie788.spotify.audio;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * The audio engine: decodes your songs on its own thread, shapes the sound (equaliser, volume, ducking) and sends it to the
 * sound card. It plays one song after another without gaps, can fade one song into the next, and keeps track of what you are
 * actually hearing (not just what has been decoded) so the position, the lyrics and the visuals line up with the sound.
 *
 * <p>Everything here is polled from the game thread: there are no callbacks.
 */
public final class Engine {
    /** The sample rate the sound card is opened at; every song is converted to it. */
    public static final int RATE = 44100;
    static final int BLOCK = 1024;
    private static final int DEVICE_FRAMES = 6615;
    /** The next song is asked for when the current one has this long left. */
    private static final long PRELOAD_FRAMES = 12L * RATE;

    private static volatile Engine instance;

    /** The engine of the running game (made on first use). */
    public static synchronized Engine get() {
        if (instance == null) instance = new Engine(() -> new JavaSoundSink(com.zlorbie788.spotify.config.ConfigIo.get().audioDevice));
        return instance;
    }

    public static synchronized void shutdownGame() {
        if (instance != null) instance.shutdown();
        instance = null;
    }

    /** What the engine remembers about each stretch of the output so the heard position can be found. */
    private record Seg(long token, long startProduced, long startPos, long length) {}

    private final Supplier<AudioSink> sinkFactory;
    private final ConcurrentLinkedQueue<Runnable> commands = new ConcurrentLinkedQueue<>();
    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "spotify-integration-audio-loader");
        t.setDaemon(true);
        return t;
    });
    private final Analyzer analyzer = new Analyzer(RATE);
    private final Eq.Chain eq = new Eq.Chain();
    private Thread thread;
    private volatile boolean running = true;

    // audio-thread state
    private AudioSink sink;
    private boolean sinkRunning;
    private String sinkDevice = "";
    private Deck cur;
    private Deck next;
    private boolean xfActive;
    private long xfLen;
    private long xfDone;
    private boolean userPlay;
    private float gain;
    private boolean fadeIn;
    private boolean snapGain;
    private int fadeInLeft;
    private long errorRetryAt;
    private final float[] a = new float[BLOCK * 2];
    private final float[] b = new float[BLOCK * 2];
    private final float[] mix = new float[BLOCK * 2];
    private final byte[] bytes = new byte[BLOCK * 4];
    private final java.util.Random dither = new java.util.Random(7);

    // shared state
    private volatile long produced;
    private volatile Seg[] segs = new Seg[0];
    private volatile boolean playing;
    private volatile boolean ended;
    private volatile String error = "";
    private volatile long wantedToken = -1;
    private volatile long preloadToken = -1;
    private volatile long preloadingFor = -2;
    private volatile boolean hasCurrent;
    private volatile long curRemaining = Long.MAX_VALUE;

    // settings
    private volatile float volume = 0.8f;
    private volatile boolean muted;
    private volatile float duck = 1f;
    private volatile Eq.Settings eqSettings = Eq.Settings.FLAT;
    private volatile boolean eqDirty = true;
    private volatile int crossfadeFrames;
    private volatile String device = "";
    private volatile int visualDelayFrames;

    public Engine(Supplier<AudioSink> sinkFactory) {
        this.sinkFactory = sinkFactory;
    }

    // ------------------------------------------------------------ lifecycle

    public synchronized void start() {
        if (thread != null) return;
        running = true;
        thread = new Thread(this::loop, "spotify-integration-audio");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY + 1);
        thread.start();
    }

    public synchronized void shutdown() {
        running = false;
        loader.shutdownNow();
        Thread t = thread;
        thread = null;
        if (t != null) {
            try {
                t.join(1500);
            } catch (InterruptedException ignored) { }
        }
    }

    // ------------------------------------------------------------ commands (any thread)

    /**
     * Plays a song right now (the one before it is cut). {@code startFrame} is in engine frames (44.1 kHz). The first sound can
     * take a moment while the file is opened.
     */
    public void play(long token, Path file, long startFrame, boolean autoStart) {
        start();
        wantedToken = token;
        error = "";
        loader.submit(() -> {
            Deck d;
            try {
                d = open(token, file);
                if (startFrame > 0) d.seek(startFrame);
            } catch (Throwable t) {
                if (wantedToken == token) error = describe(file, t);
                commands.add(() -> { if (wantedToken == token) failTrack(token); });
                return;
            }
            final Deck deck = d;
            commands.add(() -> {
                if (wantedToken != token) {
                    deck.close();
                    return;
                }
                switchTo(deck, startFrame, true);
                userPlay = autoStart;
                playing = userPlay;
            });
        });
    }

    /** Opens the song that should follow the current one, so it can start without a gap. */
    public void preload(long token, Path file) {
        start();
        preloadingFor = token;
        loader.submit(() -> {
            Deck d;
            try {
                d = open(token, file);
            } catch (Throwable t) {
                if (preloadingFor == token) preloadingFor = -2;
                return;
            }
            final Deck deck = d;
            commands.add(() -> {
                if (preloadingFor != token || cur == null) {
                    deck.close();
                    return;
                }
                if (next != null) next.close();
                next = deck;
                preloadToken = token;
            });
        });
    }

    public void clearPreload() {
        preloadingFor = -2;
        commands.add(() -> {
            if (next != null && !xfActive) {
                next.close();
                next = null;
            }
            preloadToken = -1;
        });
    }

    public void pause() {
        commands.add(() -> {
            userPlay = false;
            playing = false;
        });
    }

    public void resume() {
        start();
        commands.add(() -> {
            userPlay = true;
            playing = cur != null;
            if (cur == null) playing = false;
        });
    }

    public void seek(long frame) {
        commands.add(() -> {
            if (cur == null) return;
            try {
                long len = cur.outLength();
                long f = Math.max(0, len > 0 ? Math.min(frame, Math.max(0, len - 2000)) : frame);
                cur.seek(f);
                if (next != null && xfActive) {
                    next.close();
                    next = null;
                    preloadToken = -1;
                }
                xfActive = false;
                resetOutput();
                addSeg(cur.token, f, cur.outLength());
                analyzer.reset();
                beginFadeIn();
            } catch (Throwable t) {
                error = describe(cur.path, t);
            }
        });
    }

    /** Stops everything and forgets the current song. */
    public void stop() {
        wantedToken = -1;
        preloadingFor = -2;
        commands.add(() -> {
            userPlay = false;
            playing = false;
            if (cur != null) cur.close();
            if (next != null) next.close();
            cur = null;
            next = null;
            xfActive = false;
            resetOutput();
            segs = new Seg[0];
            hasCurrent = false;
            ended = false;
        });
    }

    public void setVolume(float v) {
        volume = Math.max(0f, Math.min(1f, v));
    }

    public void setMuted(boolean m) {
        muted = m;
    }

    /** 0..1: the music is lowered to this fraction (for ducking); smoothed by the engine. */
    public void setDuck(float d) {
        duck = Math.max(0f, Math.min(1f, d));
    }

    public void setEq(Eq.Settings s) {
        eqSettings = s == null ? Eq.Settings.FLAT : s;
        eqDirty = true;
    }

    public void setCrossfadeMs(int ms) {
        crossfadeFrames = Math.max(0, Math.min(12000, ms)) * RATE / 1000;
    }

    public void setDevice(String name) {
        device = name == null ? "" : name;
    }

    /** Shifts the visuals against the sound (positive = the visuals come later). */
    public void setVisualDelayMs(int ms) {
        visualDelayFrames = ms * RATE / 1000;
    }

    // ------------------------------------------------------------ readings (any thread)

    public boolean playing() {
        return playing;
    }

    public boolean hasTrack() {
        return hasCurrent;
    }

    /** True once the last song has finished and nothing follows. */
    public boolean ended() {
        return ended;
    }

    public String error() {
        return error;
    }

    public void clearError() {
        error = "";
    }

    /** The token of the song you hear now, or -1. */
    public long heardToken() {
        Seg s = heardSeg(heardFrames());
        return s == null ? -1 : s.token;
    }

    /** Position in the song you hear now, in engine frames. */
    public long positionFrames() {
        long heard = heardFrames();
        Seg s = heardSeg(heard);
        if (s == null) return 0;
        long p = s.startPos + Math.max(0, heard - s.startProduced);
        return s.length > 0 ? Math.min(p, s.length) : p;
    }

    public long positionMs() {
        return positionFrames() * 1000L / RATE;
    }

    /** Length of the song you hear now in ms (0 when unknown). */
    public long lengthMs() {
        Seg s = heardSeg(heardFrames());
        return s == null || s.length <= 0 ? 0 : s.length * 1000L / RATE;
    }

    /** Frames that have been handed to the sound card minus those still waiting in it. */
    public long heardFrames() {
        AudioSink s = sink;
        int q = s == null ? 0 : s.queuedFrames();
        return Math.max(0, produced - q);
    }

    /** True while the current song has under about 12 s left and no follower is lined up yet. */
    public boolean wantsPreload() {
        return hasCurrent && next == null && preloadingFor == -2 && !ended && curRemaining < PRELOAD_FRAMES;
    }

    /** True when a follower is ready or being opened. */
    public boolean hasPreload() {
        return preloadingFor != -2;
    }

    public long preloadedToken() {
        return preloadingFor;
    }

    /** What the music looks like right now (matched to what you hear). */
    public Analysis analysisNow() {
        return analyzer.at(heardFrames() - visualDelayFrames + Analyzer.WINDOW / 2);
    }

    public Analyzer analyzer() {
        return analyzer;
    }

    /** The output frame whose measurement describes what you hear right now (shifted by the visual delay). */
    public long visualFrame() {
        return heardFrames() - visualDelayFrames + Analyzer.WINDOW / 2;
    }

    // ------------------------------------------------------------ the audio thread

    private Deck open(long token, Path file) throws Exception {
        PcmSource src = Decoders.open(file);
        return new Deck(token, file, src);
    }

    private static String describe(Path file, Throwable t) {
        String m = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        return "Could not play " + (file.getFileName() == null ? file : file.getFileName()) + ": " + m;
    }

    private void failTrack(long token) {
        // the song could not be opened: let the player move on
        if (cur != null) cur.close();
        cur = null;
        xfActive = false;
        playing = false;
        hasCurrent = false;
        ended = true;
    }

    private void switchTo(Deck deck, long startFrame, boolean flush) {
        if (cur != null) cur.close();
        if (next != null) {
            next.close();
            next = null;
        }
        preloadToken = -1;
        preloadingFor = -2;
        cur = deck;
        xfActive = false;
        if (flush) resetOutput();
        addSeg(deck.token, startFrame, deck.outLength());
        hasCurrent = true;
        ended = false;
        analyzer.reset();
        beginFadeIn();
    }

    private void beginFadeIn() {
        fadeIn = true;
        snapGain = true;
        fadeInLeft = 400;
        eq.reset();
    }

    /** Throws away what is queued in the sound card so a new position is heard at once. */
    private void resetOutput() {
        if (sink != null) sink.flush();
    }

    private void addSeg(long token, long startPos, long length) {
        long start = produced;
        Seg[] old = segs;
        long heard = heardFrames();
        int keepFrom = 0;
        for (int i = 0; i < old.length; i++) if (old[i].startProduced <= heard) keepFrom = i;
        // after a flush nothing older than "now" can still be heard
        if (sink != null && sink.queuedFrames() == 0) keepFrom = old.length;
        Seg[] n = new Seg[Math.max(0, old.length - keepFrom) + 1];
        System.arraycopy(old, keepFrom, n, 0, n.length - 1);
        n[n.length - 1] = new Seg(token, start, startPos, length);
        segs = n;
    }

    private Seg heardSeg(long heard) {
        Seg[] s = segs;
        if (s.length == 0) return null;
        Seg best = s[0];
        for (Seg x : s) if (x.startProduced <= heard) best = x;
        return best;
    }

    private boolean ensureSink() {
        String want = device;
        if (sink != null && want.equals(sinkDevice)) return true;
        if (System.currentTimeMillis() < errorRetryAt) return false;
        if (sink != null) {
            sink.close();
            sink = null;
            sinkRunning = false;
        }
        try {
            AudioSink s = sinkFactory.get();
            s.open(RATE, DEVICE_FRAMES);
            sink = s;
            sinkDevice = want;
            return true;
        } catch (Throwable t) {
            error = "No sound output available (" + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()) + ")";
            errorRetryAt = System.currentTimeMillis() + 3000;
            return false;
        }
    }

    private void loop() {
        while (running) {
            try {
                Runnable c;
                while ((c = commands.poll()) != null) {
                    try {
                        c.run();
                    } catch (Throwable t) {
                        error = "Audio problem: " + t;
                    }
                }
                if (eqDirty) {
                    eqDirty = false;
                    eq.configure(eqSettings, RATE);
                }
                curRemaining = cur == null ? Long.MAX_VALUE : cur.remaining();
                if (cur == null && playing && hasCurrent) {
                    // the last song has been decoded to its end: the card plays out what is queued
                    drainAndFinish();
                    continue;
                }
                if (cur == null || !playing) {
                    if (sink != null && sinkRunning) {
                        sink.stop();
                        sinkRunning = false;
                    }
                    sleep(6);
                    continue;
                }
                if (!ensureSink()) {
                    playing = false;
                    userPlay = false;
                    sleep(30);
                    continue;
                }
                if (!sinkRunning) {
                    sink.start();
                    sinkRunning = true;
                }
                int frames = produceBlock(mix);
                if (frames > 0) finishBlock(mix, frames);
            } catch (Throwable t) {
                error = "Audio problem: " + t;
                sleep(50);
            }
        }
        AudioSink s = sink;
        if (s != null) s.close();
        sink = null;
    }

    private void drainAndFinish() {
        int guard = 0;
        while (running && sink != null && sink.queuedFrames() > 0 && guard++ < 400 && commands.isEmpty()) sleep(5);
        if (commands.isEmpty()) {
            if (sink != null && sinkRunning) {
                sink.stop();
                sinkRunning = false;
            }
            hasCurrent = false;
            ended = true;
            playing = false;
            userPlay = false;
            segs = segs.length == 0 ? segs : new Seg[]{segs[segs.length - 1]};
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) { }
    }

    /** Fills {@code out} with up to BLOCK frames from the current (and next) song. Returns frames, 0 at the very end. */
    private int produceBlock(float[] out) throws java.io.IOException {
        int filled = 0;
        while (filled < BLOCK) {
            if (cur == null) break;
            int want = BLOCK - filled;
            if (!xfActive && crossfadeFrames > 0 && next != null && cur.outLength() > 0 && cur.remaining() <= crossfadeFrames) {
                xfActive = true;
                xfLen = Math.max(1, Math.min(crossfadeFrames, cur.remaining()));
                xfDone = 0;
                addSegAt(next.token, 0, next.outLength(), produced + filled);
            }
            if (xfActive) {
                int n = (int) Math.min(want, xfLen - xfDone);
                int ga = fillFrom(cur, a, n);
                int gb = fillFrom(next, b, n);
                for (int i = 0; i < n; i++) {
                    double t = (xfDone + i + 0.5) / xfLen;
                    float wa = (float) Math.cos(t * Math.PI / 2);
                    float wb = (float) Math.sin(t * Math.PI / 2);
                    out[(filled + i) * 2] = (i < ga ? a[i * 2] : 0f) * wa + (i < gb ? b[i * 2] : 0f) * wb;
                    out[(filled + i) * 2 + 1] = (i < ga ? a[i * 2 + 1] : 0f) * wa + (i < gb ? b[i * 2 + 1] : 0f) * wb;
                }
                filled += n;
                xfDone += n;
                if (xfDone >= xfLen || ga == 0) {
                    cur.close();
                    cur = next;
                    next = null;
                    preloadToken = -1;
                    preloadingFor = -2;
                    xfActive = false;
                }
                continue;
            }
            int n = cur.read(out, filled, want);
            if (n <= 0) {
                if (next != null) {
                    // gapless: the next song starts in the same block
                    cur.close();
                    cur = next;
                    next = null;
                    preloadToken = -1;
                    preloadingFor = -2;
                    addSegAt(cur.token, 0, cur.outLength(), produced + filled);
                    continue;
                }
                cur.close();
                cur = null;
                break;
            }
            filled += n;
        }
        return filled;
    }

    private void addSegAt(long token, long startPos, long length, long startProduced) {
        Seg[] old = segs;
        long heard = heardFrames();
        int keepFrom = 0;
        for (int i = 0; i < old.length; i++) if (old[i].startProduced <= heard) keepFrom = i;
        Seg[] n = new Seg[old.length - keepFrom + 1];
        System.arraycopy(old, keepFrom, n, 0, n.length - 1);
        n[n.length - 1] = new Seg(token, startProduced, startPos, length);
        segs = n;
    }

    private int fillFrom(Deck d, float[] buf, int n) throws java.io.IOException {
        int got = 0;
        while (got < n) {
            float[] tmp = new float[0];
            int r = d.read(buf, got, n - got);
            if (r <= 0) break;
            got += r;
        }
        return got;
    }

    /** Equaliser, analysis, volume, limiter, dither, then out to the card. */
    private void finishBlock(float[] buf, int frames) {
        if (fadeIn && fadeInLeft > 0) {
            int n = Math.min(frames, fadeInLeft);
            for (int i = 0; i < n; i++) {
                float g = (float) (400 - fadeInLeft + i) / 400f;
                buf[2 * i] *= g;
                buf[2 * i + 1] *= g;
            }
            fadeInLeft -= n;
            if (fadeInLeft <= 0) fadeIn = false;
        }
        if (eq.needed()) eq.process(buf, frames);
        long end = produced + frames;
        analyzer.feed(buf, frames, end);

        float target = muted ? 0f : volume * volume * duck;
        if (snapGain) {
            gain = target;
            snapGain = false;
        }
        float g0 = gain;
        float step = (target - g0) / frames;
        // volume changes are smoothed over the block (about 23 ms)
        int o = 0;
        for (int i = 0; i < frames; i++) {
            float g = g0 + step * (i + 1);
            for (int c = 0; c < 2; c++) {
                float x = buf[2 * i + c] * g;
                float ax = Math.abs(x);
                if (ax > 0.9f) x = Math.signum(x) * (0.9f + 0.1f * (float) Math.tanh((ax - 0.9f) / 0.1f));
                float d = (dither.nextFloat() - dither.nextFloat()) / 32768f;
                int v = Math.round((x + d) * 32767f);
                if (v > 32767) v = 32767;
                else if (v < -32768) v = -32768;
                bytes[o++] = (byte) v;
                bytes[o++] = (byte) (v >> 8);
            }
        }
        gain = target;
        int total = frames * 4;
        int off = 0;
        while (off < total && running) {
            int w = sink.write(bytes, off, total - off);
            if (w <= 0) break;
            off += w;
        }
        produced = end;
    }
}
