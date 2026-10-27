package com.zlorbie788.spotify.config;

/**
 * Every setting of the lyrics. Plain public fields (Gson reads and writes them); {@link #sanitize()} puts
 * anything out of range, or missing from an older config file, back into a safe state.
 */
public class LyricsConfig {

    public enum Align {
        LEFT("Left"), CENTER("Centre"), RIGHT("Right");

        public final String label;

        Align(String label) {
            this.label = label;
        }

        public Align next(int step) {
            Align[] all = values();
            return all[((ordinal() + step) % all.length + all.length) % all.length];
        }
    }

    /** What colour a line of lyrics gets. */
    public enum Tone {
        ACCENT("Player colour"), LIGHT("Soft album light"), WHITE("White"), CUSTOM("My colour"), RAINBOW("Rainbow");

        public final String label;

        Tone(String label) {
            this.label = label;
        }

        public Tone next(int step) {
            Tone[] all = values();
            return all[((ordinal() + step) % all.length + all.length) % all.length];
        }
    }

    /** What the line that is being sung does. */
    public enum Highlight {
        NONE("Just the colour"), FILL("Sweep (karaoke)"), GLOW("Glow"), POP("Pop bigger"),
        UNDERLINE("Underline sweep"), TYPE("Type it out");

        public final String label;

        Highlight(String label) {
            this.label = label;
        }

        public Highlight next(int step) {
            Highlight[] all = values();
            return all[((ordinal() + step) % all.length + all.length) % all.length];
        }
    }

    /** How the list moves from one line to the next. */
    public enum Motion {
        SCROLL("Smooth scroll"), FADE("Fade in place"), JUMP("Instant");

        public final String label;

        Motion(String label) {
            this.label = label;
        }

        public Motion next(int step) {
            Motion[] all = values();
            return all[((ordinal() + step) % all.length + all.length) % all.length];
        }
    }

    /** What happens to a line that is wider than the box. */
    public enum Overflow {
        WRAP("Wrap (2 rows)"), SHRINK("Shrink to fit"), CUT("Cut with ...");

        public final String label;

        Overflow(String label) {
            this.label = label;
        }

        public Overflow next(int step) {
            Overflow[] all = values();
            return all[((ordinal() + step) % all.length + all.length) % all.length];
        }
    }

    /** Upper / lower case for every lyric. */
    public enum Case {
        NORMAL("Normal"), UPPER("UPPERCASE"), LOWER("lowercase");

        public final String label;

        Case(String label) {
            this.label = label;
        }

        public Case next(int step) {
            Case[] all = values();
            return all[((ordinal() + step) % all.length + all.length) % all.length];
        }

        public String apply(String s) {
            return applyTo(s);
        }

        private String applyTo(String s) {
            switch (this) {
                case UPPER:
                    return s.toUpperCase(java.util.Locale.ROOT);
                case LOWER:
                    return s.toLowerCase(java.util.Locale.ROOT);
                default:
                    return s;
            }
        }
    }

    /** If and where the words appear inside the player card on the game screen. */
    public enum CardMode {
        OFF("Off"), ALBUM("Album line"), TWO("Artist + album lines"), STATUS("Top line");

        public final String label;

        CardMode(String label) {
            this.label = label;
        }

        public CardMode next(int step) {
            CardMode[] all = values();
            return all[((ordinal() + step) % all.length + all.length) % all.length];
        }
    }

    /** What a lyric line that is wider than the card does. */
    public enum CardLong {
        WRAP("Wrap to 2 rows"), SCROLL("Scroll while sung"), SHRINK("Shrink to fit"), CUT("Cut with ...");

        public final String label;

        CardLong(String label) {
            this.label = label;
        }

        public CardLong next(int step) {
            CardLong[] all = values();
            return all[((ordinal() + step) % all.length + all.length) % all.length];
        }
    }

    /** If the lyrics are shown inside the full player screen (the one the Open key brings up). */
    public enum InPlayer {
        OFF("Off (Lyrics button)"), REMEMBER("Remember my choice"), ALWAYS("Always open");

        public final String label;

        InPlayer(String label) {
            this.label = label;
        }

        public InPlayer next(int step) {
            InPlayer[] all = values();
            return all[((ordinal() + step) % all.length + all.length) % all.length];
        }
    }

    public enum Back {
        NONE("None"), GLASS("Glass"), SOLID("Solid"), ALBUM("Album tint");

        public final String label;

        Back(String label) {
            this.label = label;
        }

        public Back next(int step) {
            Back[] all = values();
            return all[((ordinal() + step) % all.length + all.length) % all.length];
        }
    }

    // ------------------------------------------------------------ what and where
    /** Show the lyrics on the screen while you play (the lyrics window of the player works either way). */
    public boolean enabled = false;
    /** Look the words up on the internet (lrclib.net). Off = only lyrics that were saved before. */
    public boolean online = true;
    public int ax = SpotifyConfig.ANCHOR_CENTER;
    public int ay = SpotifyConfig.ANCHOR_END;
    public int x = 0;
    public int y = 56;
    public float scale = 1.0f;
    public float opacity = 1.0f;

    // ------------------------------------------------------------ inside the player card
    /** Show the words inside the on-screen player card (replacing one of its text lines). */
    public CardMode cardMode = CardMode.ALBUM;
    public CardLong cardLong = CardLong.WRAP;

    // ------------------------------------------------------------ inside the full player screen
    /** Whether the player screen opens with the lyrics showing. The Lyrics button (or L) always switches them. */
    public InPlayer inPlayer = InPlayer.REMEMBER;
    /** What "Remember my choice" remembers: lyrics were showing the last time. Starts on so the lyrics are easy to find. */
    public boolean playerOpen = true;

    // ------------------------------------------------------------ lines
    public int linesBefore = 1;
    public int linesAfter = 2;
    public int width = 260;
    public int lineGap = 4;
    public Overflow overflow = Overflow.WRAP;
    public Align align = Align.CENTER;
    /** How many rows a long line may wrap to (1-3). */
    public int wrapRows = 2;
    public int padX = 8;
    public int padY = 7;

    // ------------------------------------------------------------ text
    public boolean boldCurrent = true;
    /** Size of the line being sung compared to the others (1 = same). */
    public float currentScale = 1.2f;
    public Tone currentTone = Tone.ACCENT;
    public int currentColor = 0xFFFFFFFF;
    public Tone otherTone = Tone.WHITE;
    public int otherColor = 0xFFC9D0E2;
    /** 0 = every line equally visible, 1 = lines far from the current one almost vanish. */
    public float fade = 0.6f;
    public boolean shadow = true;
    public boolean outline = false;
    public int outlineColor = 0xFF000000;
    public boolean italic = false;
    public Case textCase = Case.NORMAL;
    /** Write lyrics in Cyrillic, Greek, Japanese kana or Korean in Latin letters (1.3.2). */
    public boolean romanize = false;
    /** Use the real word times when the lyric file has them (the karaoke sweep follows each word). */
    public boolean wordSync = true;
    /** Size of the lines that are not being sung (1 = normal). */
    public float otherScale = 1.0f;
    /** Extra dimming of the lines that were already sung (0 = same as the upcoming ones). */
    public float pastDim = 0.0f;
    /** How fast the karaoke sweep runs compared to the singing estimate. */
    public float sweepSpeed = 1.0f;
    public Highlight highlight = Highlight.FILL;

    // ------------------------------------------------------------ motion
    public Motion motion = Motion.SCROLL;
    public float speed = 1.0f;
    public boolean interlude = true;

    // ------------------------------------------------------------ font
    /** Font of the lyrics; null = the same as the player's text. */
    public UiFont font = null;

    // ------------------------------------------------------------ box
    public Back back = Back.GLASS;
    public float backOpacity = 0.55f;
    public int corner = 2;
    public boolean border = true;
    /** A bar in the player colour along the left edge of the box. */
    public boolean accentBar = false;

    // ------------------------------------------------------------ behaviour
    /** Moves every line later (+) or earlier (-), in milliseconds, if your Spotify is a little out of step. */
    public int offsetMs = 0;
    public boolean showWhenPaused = true;
    public boolean hideWhenNone = true;
    public boolean showInstrumental = true;

    public void sanitize() {
        if (overflow == null) overflow = Overflow.WRAP;
        if (align == null) align = Align.CENTER;
        if (currentTone == null) currentTone = Tone.ACCENT;
        if (otherTone == null) otherTone = Tone.WHITE;
        if (highlight == null) highlight = Highlight.FILL;
        if (motion == null) motion = Motion.SCROLL;
        if (back == null) back = Back.GLASS;
        if (cardMode == null) cardMode = CardMode.ALBUM;
        if (cardLong == null) cardLong = CardLong.WRAP;
        if (inPlayer == null) inPlayer = InPlayer.REMEMBER;
        if (textCase == null) textCase = Case.NORMAL;
        if (ax < SpotifyConfig.ANCHOR_START || ax > SpotifyConfig.ANCHOR_CENTER) ax = SpotifyConfig.ANCHOR_CENTER;
        if (ay < SpotifyConfig.ANCHOR_START || ay > SpotifyConfig.ANCHOR_CENTER) ay = SpotifyConfig.ANCHOR_END;
        scale = clamp(scale, 0.5f, 3.0f);
        opacity = clamp(opacity, 0.1f, 1.0f);
        linesBefore = Math.max(0, Math.min(6, linesBefore));
        linesAfter = Math.max(0, Math.min(6, linesAfter));
        width = Math.max(120, Math.min(560, width));
        wrapRows = Math.max(1, Math.min(3, wrapRows));
        padX = Math.max(2, Math.min(20, padX));
        padY = Math.max(2, Math.min(20, padY));
        otherScale = clamp(otherScale, 0.6f, 1.1f);
        pastDim = clamp(pastDim, 0f, 1f);
        sweepSpeed = clamp(sweepSpeed, 0.5f, 2.0f);
        outlineColor = 0xFF000000 | outlineColor;
        lineGap = Math.max(0, Math.min(12, lineGap));
        currentScale = clamp(currentScale, 1.0f, 1.8f);
        fade = clamp(fade, 0f, 1f);
        speed = clamp(speed, 0.25f, 3.0f);
        backOpacity = clamp(backOpacity, 0f, 1f);
        corner = Math.max(0, Math.min(3, corner));
        offsetMs = Math.max(-5000, Math.min(5000, offsetMs));
        currentColor = 0xFF000000 | currentColor;
        otherColor = 0xFF000000 | otherColor;
    }

    /** The text of a lyric line as it is shown: romanized when that is switched on, then cased. */
    public String shape(String raw) {
        if (raw == null) return "";
        String s = romanize ? com.zlorbie788.spotify.util.Romanize.apply(raw) : raw;
        return textCase.apply(s);
    }

    public static final String[] PRESETS = {"Karaoke", "Subtitles", "Minimal", "Neon"};

    /** Sets the look of the lyrics (not where they are, and not what is switched on) to a ready-made style. */
    public void applyPreset(int index) {
        LyricsConfig d = new LyricsConfig();
        linesBefore = d.linesBefore;
        linesAfter = d.linesAfter;
        width = d.width;
        lineGap = d.lineGap;
        overflow = d.overflow;
        align = d.align;
        wrapRows = d.wrapRows;
        padX = d.padX;
        padY = d.padY;
        boldCurrent = d.boldCurrent;
        currentScale = d.currentScale;
        currentTone = d.currentTone;
        otherTone = d.otherTone;
        fade = d.fade;
        shadow = d.shadow;
        outline = d.outline;
        italic = d.italic;
        textCase = d.textCase;
        otherScale = d.otherScale;
        pastDim = d.pastDim;
        sweepSpeed = d.sweepSpeed;
        highlight = d.highlight;
        motion = d.motion;
        back = d.back;
        backOpacity = d.backOpacity;
        corner = d.corner;
        border = d.border;
        accentBar = d.accentBar;
        switch (index) {
            case 0 -> { // Karaoke: the defaults
            }
            case 1 -> { // Subtitles: one line, white, readable on anything
                linesBefore = 0;
                linesAfter = 0;
                width = 320;
                currentScale = 1.0f;
                currentTone = Tone.WHITE;
                highlight = Highlight.NONE;
                boldCurrent = false;
                back = Back.SOLID;
                backOpacity = 0.7f;
                outline = true;
                shadow = false;
                motion = Motion.FADE;
            }
            case 2 -> { // Minimal: just words
                linesBefore = 1;
                linesAfter = 1;
                back = Back.NONE;
                highlight = Highlight.NONE;
                currentScale = 1.1f;
                fade = 0.85f;
                border = false;
                otherTone = Tone.WHITE;
            }
            default -> { // Neon: big glowing album colours
                linesBefore = 2;
                linesAfter = 2;
                width = 300;
                currentScale = 1.35f;
                currentTone = Tone.LIGHT;
                highlight = Highlight.GLOW;
                back = Back.ALBUM;
                backOpacity = 0.6f;
                fade = 0.75f;
                accentBar = true;
            }
        }
    }

    private static float clamp(float v, float lo, float hi) {
        if (Float.isNaN(v)) return lo;
        return Math.max(lo, Math.min(hi, v));
    }
}
