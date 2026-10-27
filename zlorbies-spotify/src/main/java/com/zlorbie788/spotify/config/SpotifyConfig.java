package com.zlorbie788.spotify.config;

/**
 * Every setting of the player. Plain public fields so Gson can read and write them; {@link #sanitize()} puts
 * anything out of range (or missing from an older / hand-edited file) back into a safe state.
 */
public class SpotifyConfig {
    public static final int CONFIG_VERSION = 7;

    public static final int ANCHOR_START = 0;
    public static final int ANCHOR_END = 1;
    public static final int ANCHOR_CENTER = 2;

    /** The six looks. Each one has a fixed size and never grows or shrinks. */
    public enum Style {
        CARD("Card", 248, 78),
        COMPACT("Compact bar", 232, 34),
        VINYL("Vinyl", 248, 78),
        MINIMAL("Minimal", 200, 30),
        TALL("Tall", 124, 176),
        SLEEK("Sleek", 268, 76);

        public final String label;
        public final int width;
        public final int height;

        Style(String label, int width, int height) {
            this.label = label;
            this.width = width;
            this.height = height;
        }

        public Style next(int step) {
            Style[] all = values();
            return all[((ordinal() + step) % all.length + all.length) % all.length];
        }
    }

    public enum Accent {
        ALBUM("From the album cover"), CUSTOM("My colour"), RAINBOW("Rainbow");

        public final String label;

        Accent(String label) {
            this.label = label;
        }

        public Accent next(int step) {
            Accent[] all = values();
            return all[((ordinal() + step) % all.length + all.length) % all.length];
        }
    }

    public enum Background {
        SOLID("Solid"), GLASS("Glass"), TINTED("Album tint"), NONE("None"), FADE("Fade");

        public final String label;

        Background(String label) {
            this.label = label;
        }

        public Background next(int step) {
            Background[] all = values();
            return all[((ordinal() + step) % all.length + all.length) % all.length];
        }
    }

    /** How the full player screen (the one you open with the key) looks. */
    public enum Look {
        CLASSIC("Classic"), SLEEK("Sleek");

        public final String label;

        Look(String label) {
            this.label = label;
        }

        public Look next(int step) {
            Look[] all = values();
            return all[((ordinal() + step) % all.length + all.length) % all.length];
        }
    }

    /** Which public catalogue the search bar asks. */
    public enum SearchSource {
        AUTO("Automatic"), DEEZER("Deezer"), ITUNES("Apple Music");

        public final String label;

        SearchSource(String label) {
            this.label = label;
        }

        public SearchSource next(int step) {
            SearchSource[] all = values();
            return all[((ordinal() + step) % all.length + all.length) % all.length];
        }
    }

    /** The outline of a panel: rounded, cut corners (a HUD look), cut on two corners only, or square. */
    public enum ShapeKind {
        ROUND("Rounded"), CHAMFER("Cut corners"), TECH("Tech cut"), SQUARE("Square");

        public final String label;

        ShapeKind(String label) {
            this.label = label;
        }

        public ShapeKind next(int step) {
            ShapeKind[] all = values();
            return all[((ordinal() + step) % all.length + all.length) % all.length];
        }
    }

    /** How the album cover is cut. AUTO = rounded to match the panel's corners. */
    public enum CoverShape {
        AUTO("Match the panel"), SQUARE("Square"), SOFT("Soft"), ROUND("Rounded"), CIRCLE("Circle");

        public final String label;

        CoverShape(String label) {
            this.label = label;
        }

        public CoverShape next(int step) {
            CoverShape[] all = values();
            return all[((ordinal() + step) % all.length + all.length) % all.length];
        }
    }

    /** The beat display: what moves with the music. */
    public enum VizStyle {
        MIRROR("Mirror bars"), SCOPE("Live scope"), WAVE("Wave"), DOTS("Dot matrix"), BARS("Bars"), OFF("Off"),
        SPECTRUM("Spectrum"), RING("Ring");

        public final String label;

        VizStyle(String label) {
            this.label = label;
        }

        public VizStyle next(int step) {
            VizStyle[] all = values();
            return all[((ordinal() + step) % all.length + all.length) % all.length];
        }
    }

    public enum ProgressStyle {
        LINE("Smooth line"), WAVE("Waveform");

        public final String label;

        ProgressStyle(String label) {
            this.label = label;
        }

        public ProgressStyle next(int step) {
            ProgressStyle[] all = values();
            return all[((ordinal() + step) % all.length + all.length) % all.length];
        }
    }

    /** When the opening animation of the player screen plays. */
    public enum IntroMode {
        FIRST("First time ever"), ALWAYS("Every time I open it"), JOIN("Every time I join a world"),
        SESSION("Once per Minecraft session"), NEVER("Never");

        public final String label;

        IntroMode(String label) {
            this.label = label;
        }

        public IntroMode next(int step) {
            IntroMode[] all = values();
            return all[((ordinal() + step) % all.length + all.length) % all.length];
        }
    }

    /** Colours of the player's own screens. */
    public enum UiTheme {
        MIDNIGHT("Midnight"), GRAPHITE("Graphite"), OCEAN("Ocean"), NEON("Neon"), ROSE("Rose");

        public final String label;

        UiTheme(String label) {
            this.label = label;
        }

        public UiTheme next(int step) {
            UiTheme[] all = values();
            return all[((ordinal() + step) % all.length + all.length) % all.length];
        }
    }

    /** Where the highlight colour of the settings and menus comes from. */
    public enum UiAccent {
        TAB("Colour per tab"), ALBUM("From the album"), CUSTOM("My colour");

        public final String label;

        UiAccent(String label) {
            this.label = label;
        }

        public UiAccent next(int step) {
            UiAccent[] all = values();
            return all[((ordinal() + step) % all.length + all.length) % all.length];
        }
    }

    public enum Visibility {
        ALWAYS("Always"), PLAYING("Only while playing"), ON_CHANGE("Pop up on song change");

        public final String label;

        Visibility(String label) {
            this.label = label;
        }

        public Visibility next(int step) {
            Visibility[] all = values();
            return all[((ordinal() + step) % all.length + all.length) % all.length];
        }
    }

    /** When the song-change toast shows. */
    public enum ToastWhen {
        HIDDEN("Only when the player is hidden"), ALWAYS("On every song change");

        public final String label;

        ToastWhen(String label) {
            this.label = label;
        }

        public ToastWhen next(int step) {
            ToastWhen[] all = values();
            return all[((ordinal() + step) % all.length + all.length) % all.length];
        }
    }

    /** Where the song-change toast slides in. */
    public enum Corner {
        TOP_LEFT("Top left"), TOP_CENTER("Top middle"), TOP_RIGHT("Top right"), BOTTOM_LEFT("Bottom left"),
        BOTTOM_CENTER("Bottom middle"), BOTTOM_RIGHT("Bottom right");

        public final String label;

        Corner(String label) {
            this.label = label;
        }

        public boolean top() {
            return this == TOP_LEFT || this == TOP_CENTER || this == TOP_RIGHT;
        }

        /** -1 = left, 0 = middle, 1 = right. */
        public int side() {
            return this == TOP_LEFT || this == BOTTOM_LEFT ? -1 : this == TOP_RIGHT || this == BOTTOM_RIGHT ? 1 : 0;
        }

        public Corner next(int step) {
            Corner[] all = values();
            return all[((ordinal() + step) % all.length + all.length) % all.length];
        }
    }

    public enum Source {
        SPOTIFY("Spotify only"), ANY("Any player");

        public final String label;

        Source(String label) {
            this.label = label;
        }

        public Source next(int step) {
            Source[] all = values();
            return all[((ordinal() + step) % all.length + all.length) % all.length];
        }
    }

    /** Where the music comes from: decided automatically, always Spotify, or the songs in your own music folder. */
    public enum SourceMode {
        AUTO("Automatic"), SPOTIFY("Spotify"), LOCAL("My music");

        public final String label;

        SourceMode(String label) {
            this.label = label;
        }

        public SourceMode next(int step) {
            SourceMode[] all = values();
            return all[((ordinal() + step) % all.length + all.length) % all.length];
        }
    }

    public int configVersion = CONFIG_VERSION;

    // ------------------------------------------------------------ new in 1.2
    /** Look of the full player screen. */
    public Look playerLook = Look.CLASSIC;
    /** Lyrics settings (their own block in the file). */
    public LyricsConfig lyrics = new LyricsConfig();
    /** Search the online music catalogue (no account or log-in is ever needed). */
    public boolean onlineSearch = true;
    public SearchSource searchSource = SearchSource.AUTO;
    /** Look up the exact Spotify version of a search result (through a public link service). */
    public boolean exactSpotify = true;
    /** The search shows only songs that can really be played in Spotify (each one is checked first). */
    public boolean onlyPlayable = true;
    /** Press play for you when Spotify has opened the chosen song. */
    public boolean autoPlay = true;
    /** Find a cover on the web when Windows does not hand one over. */
    public boolean webCovers = true;
    /** Keep a list of the songs you played from the search. */
    public boolean saveHistory = true;
    public int searchResults = 12;
    /** Two-letter country used by the Apple Music catalogue. */
    public String searchCountry = "US";
    public boolean searchThumbs = true;
    public boolean searchTint = true;

    // ------------------------------------------------------------ new in 1.4.0: your own music
    public SourceMode sourceMode = SourceMode.AUTO;
    /** Name of the sound output to use for your own songs (empty = the system default). */
    public String audioDevice = "";
    /** Fade one song into the next over this many seconds (0 = gapless, no fade). */
    public int crossfadeSeconds = 0;
    public boolean eqOn = false;
    /** Index into Eq.PRESET_NAMES. */
    public int eqPreset = 0;
    public float[] eqGains = new float[10];
    public int localVolume = 70;
    public boolean localMuted = false;
    /** Remember the last song, position and volume of your own music. */
    public boolean rememberSession = true;
    public boolean duckOnChat = true;
    public boolean duckOnHurt = true;
    /** How loud the music stays while it is lowered (0.1 = very quiet, 0.8 = barely lower). */
    public float duckLevel = 0.35f;
    /** Stop Minecraft's own background music while one of your songs plays. */
    public boolean lowerGameMusic = true;
    /** Take lyrics from a .lrc file next to the song, or from the tags inside it, before looking online. */
    public boolean localLyricsFiles = true;
    /** Look up a missing cover or artist online (nothing is written to your files). */
    public boolean fixTagsOnline = true;
    /** Listen to Spotify's real sound (Windows audio capture) for an exact spectrum and beat. */
    public boolean realAudio = true;
    /** Shifts the visuals against the sound, in ms (positive = later). */
    public int visualSyncMs = 0;
    /** The interface colours and animation speed follow the song's energy. */
    public boolean mood = true;
    public float moodAmount = 0.6f;
    /** A tiny bar shows the song and progress for a moment when you use a key. */
    public boolean miniBar = true;
    public int miniBarSeconds = 3;
    /** Global media keys (play/pause, next, previous) control your own music even when the game is in the background. */
    public boolean mediaKeys = true;
    /** The first-time tour of the settings has been shown. */
    public boolean tourDone = false;
    /** Name of the profile last loaded (shown in the settings). */
    public String profile = "";

    // ------------------------------------------------------------ new in 1.3.2
    /** A small card slides in when the song changes (works even when the on-screen player is hidden). */
    public boolean toast = true;
    public ToastWhen toastWhen = ToastWhen.HIDDEN;
    public Corner toastCorner = Corner.TOP_RIGHT;
    public int toastSeconds = 5;
    public float toastScale = 1.0f;
    public boolean toastCover = true;
    /** The player fades away this many seconds after the last song change or key press (0 = never). */
    public int fadeAfterSeconds = 0;
    /** How visible the player stays after fading (0 = gone, 0.6 = faint). */
    public float fadedOpacity = 0.0f;
    /** Show the "Spotify not found" checklist when the helper cannot see a player. */
    public boolean showHelp = true;
    /** Keep listening statistics (time listened, top songs and artists) on this PC. */
    public boolean trackStats = true;

    // ------------------------------------------------------------ new in 1.3: shape
    /** Outline of the on-screen player. */
    public ShapeKind shape = ShapeKind.ROUND;
    /** Corner size in GUI pixels (0..28). The "Pill" of 1.2 is 10. */
    public float radius = 10f;
    /** Border thickness in GUI pixels (0 = none). */
    public float borderWidth = 1f;
    public CoverShape coverShape = CoverShape.AUTO;
    /** Cut away the dark frame (and the logo) some players put around the cover, so only the picture is left. */
    public boolean trimCover = true;
    /** Zoom into the cover a little more (0..0.3 of each edge). */
    public float coverZoom = 0f;
    /** Little corner brackets on the player (a HUD look). */
    public boolean cornerBrackets = false;

    // ------------------------------------------------------------ new in 1.3: beat display
    public VizStyle viz = VizStyle.MIRROR;
    public ProgressStyle progressStyle = ProgressStyle.LINE;

    // ------------------------------------------------------------ new in 1.3: effects
    /** A light that runs around the edge of the player (faster with the music). */
    public boolean borderTrace = true;
    /** A glint sweeps over the cover when the song changes. */
    public boolean shine = true;
    /** Soft moving colour behind the player screen. */
    public boolean ambient = true;
    /** Dots drift up from the player screen on the beat. */
    public boolean particles = true;
    /** The cover leans a little towards the mouse. */
    public boolean parallax = true;
    /** Buttons, switches, sliders and tabs animate. */
    public boolean uiAnimations = true;
    /** A ring spreads from every click. */
    public boolean ripples = true;

    // ------------------------------------------------------------ new in 1.3: fonts
    /** Font of the player's text (card and player screen). */
    public UiFont font = UiFont.MINECRAFT;
    /** Font of the settings and menus. */
    public UiFont uiFont = UiFont.MINECRAFT;
    /** Moves custom-font text up (-) or down (+), in pixels, if it does not sit right on its line. */
    public int fontShift = 0;

    // ------------------------------------------------------------ new in 1.3: the player screen (Right Alt)
    public int panelW = 340;
    public int panelH = 210;
    public ShapeKind panelShape = ShapeKind.ROUND;
    public float panelRadius = 14f;
    public float panelBorder = 1f;
    public Background panelBack = Background.GLASS;
    public float panelOpacity = 0.92f;
    /** Darken the game behind the player screen. */
    public boolean panelDim = true;
    public IntroMode introMode = IntroMode.FIRST;
    /** Set once the opening animation has played (so "First time only" knows). */
    public boolean introSeen = false;
    public float introSpeed = 1.0f;

    // ------------------------------------------------------------ new in 1.3: the settings and menus
    public UiTheme uiTheme = UiTheme.MIDNIGHT;
    public float uiRadius = 3f;
    public UiAccent uiAccent = UiAccent.TAB;
    public int uiColor = 0xFF34D399;

    // ------------------------------------------------------------ placement
    public boolean hudEnabled = true;
    public Style style = Style.SLEEK;
    /** Position: offset from the chosen screen edge (see the ANCHOR constants), in GUI pixels. */
    public int ax = ANCHOR_START;
    public int ay = ANCHOR_START;
    public int x = 8;
    public int y = 8;
    public float scale = 0.5f;
    public float opacity = 1.0f;

    // ------------------------------------------------------------ look
    public Accent accent = Accent.ALBUM;
    public int accentColor = 0xFF34D399;
    public Background background = Background.GLASS;
    /** Old setting (1.2): 0 = square corners, 1..3 = increasingly rounded. Only read once, to set {@link #radius}. */
    public int corner = 3;
    public boolean showCover = true;
    public boolean showAlbum = true;
    public boolean showTime = true;
    public boolean showProgress = true;
    public boolean showEqualizer = true;
    public boolean showAccentLine = true;
    public boolean showGlow = true;
    public boolean showSourceName = true;
    public boolean roundCover = false;
    public boolean textShadow = true;
    public boolean highContrast = false;

    // ------------------------------------------------------------ animation
    /** Master switch: off = nothing moves except the progress bar and the time. */
    public boolean animations = true;
    public float animSpeed = 1.0f;
    public boolean marquee = true;
    public boolean spinCover = true;
    public boolean trackTransition = true;
    public boolean breathingGlow = true;
    /** Bars, lines and glow follow the real loudness and beat of the music (needs the Windows helper). */
    public boolean reactToMusic = true;
    /** How strongly they react (1 = normal). */
    public float reactStrength = 1.0f;
    /** The cover bumps and the glow flashes on every beat. */
    public boolean beatPulse = true;
    /** Keeps the rhythm going when a single beat is missed (see BeatClock). */
    public boolean beatLock = true;
    /** The wide level display in the full player screen. */
    public boolean showVisualizer = true;

    // ------------------------------------------------------------ behaviour
    public Visibility visibility = Visibility.PLAYING;
    public int popupSeconds = 6;
    /** Hide the player this many seconds after pausing (0 = never). */
    public int hidePausedSeconds = 0;
    public Source source = Source.SPOTIFY;
    public int seekStepSeconds = 10;
    public int volumeStep = 5;
    public boolean startBridge = true;

    public static SpotifyConfig defaults() {
        return new SpotifyConfig();
    }

    /** True when motion is allowed (the master switch). */
    public boolean motion() {
        return animations;
    }

    public void sanitize() {
        // 1.2.1 brought the classic look back as the standard one: older files get it once (they can pick Sleek again).
        if (configVersion < 3) {
            playerLook = Look.CLASSIC;
            if (style == Style.SLEEK) style = Style.CARD;
        }
        // 1.2.2: long lyric lines wrap onto a second row inside the card by default
        if (configVersion < 4 && lyrics != null) lyrics.cardLong = LyricsConfig.CardLong.WRAP;
        // 1.3: the corner level becomes a size in pixels
        if (configVersion < 5) {
            int[] levels = {0, 3, 6, 10};
            radius = levels[Math.max(0, Math.min(3, corner))];
            panelRadius = Math.max(8f, radius + 2f);
        }
        if (style == null) style = Style.CARD;
        if (shape == null) shape = ShapeKind.ROUND;
        if (coverShape == null) coverShape = CoverShape.AUTO;
        if (viz == null) viz = VizStyle.MIRROR;
        if (progressStyle == null) progressStyle = ProgressStyle.LINE;
        if (font == null) font = UiFont.MINECRAFT;
        if (uiFont == null) uiFont = UiFont.MINECRAFT;
        if (panelShape == null) panelShape = ShapeKind.ROUND;
        if (panelBack == null) panelBack = Background.GLASS;
        if (introMode == null) introMode = IntroMode.FIRST;
        if (uiTheme == null) uiTheme = UiTheme.MIDNIGHT;
        if (uiAccent == null) uiAccent = UiAccent.TAB;
        radius = clamp(radius, 0f, 28f);
        borderWidth = clamp(borderWidth, 0f, 3f);
        coverZoom = clamp(coverZoom, 0f, 0.3f);
        fontShift = Math.max(-4, Math.min(4, fontShift));
        panelW = Math.max(280, Math.min(560, panelW));
        panelH = Math.max(176, Math.min(340, panelH));
        panelRadius = clamp(panelRadius, 0f, 32f);
        panelBorder = clamp(panelBorder, 0f, 3f);
        panelOpacity = clamp(panelOpacity, 0.3f, 1f);
        introSpeed = clamp(introSpeed, 0.5f, 2f);
        uiRadius = clamp(uiRadius, 0f, 8f);
        uiColor = 0xFF000000 | uiColor;
        if (playerLook == null) playerLook = Look.CLASSIC;
        if (lyrics == null) lyrics = new LyricsConfig();
        lyrics.sanitize();
        if (searchSource == null) searchSource = SearchSource.AUTO;
        searchResults = Math.max(5, Math.min(25, searchResults));
        if (searchCountry == null || !searchCountry.matches("[A-Za-z]{2}")) searchCountry = "US";
        searchCountry = searchCountry.toUpperCase(java.util.Locale.ROOT);
        if (accent == null) accent = Accent.ALBUM;
        if (background == null) background = Background.SOLID;
        if (visibility == null) visibility = Visibility.ALWAYS;
        if (source == null) source = Source.SPOTIFY;
        if (sourceMode == null) sourceMode = SourceMode.AUTO;
        if (audioDevice == null) audioDevice = "";
        crossfadeSeconds = Math.max(0, Math.min(12, crossfadeSeconds));
        eqPreset = Math.max(0, Math.min(7, eqPreset));
        if (eqGains == null || eqGains.length != 10) eqGains = new float[10];
        for (int i = 0; i < 10; i++) eqGains[i] = clamp(eqGains[i], -12f, 12f);
        localVolume = Math.max(0, Math.min(100, localVolume));
        duckLevel = clamp(duckLevel, 0.05f, 0.9f);
        visualSyncMs = Math.max(-300, Math.min(300, visualSyncMs));
        moodAmount = clamp(moodAmount, 0f, 1f);
        miniBarSeconds = Math.max(1, Math.min(10, miniBarSeconds));
        if (profile == null) profile = "";
        if (toastWhen == null) toastWhen = ToastWhen.HIDDEN;
        if (toastCorner == null) toastCorner = Corner.TOP_RIGHT;
        toastSeconds = Math.max(2, Math.min(20, toastSeconds));
        toastScale = clamp(toastScale, 0.5f, 2.0f);
        fadeAfterSeconds = Math.max(0, Math.min(600, fadeAfterSeconds));
        fadedOpacity = clamp(fadedOpacity, 0f, 0.7f);
        if (ax < ANCHOR_START || ax > ANCHOR_CENTER) ax = ANCHOR_START;
        if (ay < ANCHOR_START || ay > ANCHOR_CENTER) ay = ANCHOR_START;
        scale = clamp(scale, 0.5f, 3.0f);
        opacity = clamp(opacity, 0.1f, 1.0f);
        animSpeed = clamp(animSpeed, 0.25f, 3.0f);
        reactStrength = clamp(reactStrength, 0.3f, 2.0f);
        corner = Math.max(0, Math.min(3, corner));
        popupSeconds = Math.max(2, Math.min(60, popupSeconds));
        hidePausedSeconds = Math.max(0, Math.min(600, hidePausedSeconds));
        seekStepSeconds = Math.max(1, Math.min(120, seekStepSeconds));
        volumeStep = Math.max(1, Math.min(25, volumeStep));
        accentColor = 0xFF000000 | accentColor;
        configVersion = CONFIG_VERSION;
    }

    private static float clamp(float v, float lo, float hi) {
        if (Float.isNaN(v)) return lo;
        return Math.max(lo, Math.min(hi, v));
    }
}
