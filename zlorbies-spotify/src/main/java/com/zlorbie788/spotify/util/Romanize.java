package com.zlorbie788.spotify.util;

/**
 * Writes lyrics in Latin letters: Cyrillic (Bulgarian, Russian, Ukrainian, Serbian, Macedonian), Greek, Japanese kana
 * and Korean Hangul. Characters from other scripts (including Chinese characters) and everything that is already
 * Latin are left alone. Pure Java, no Minecraft classes, so it can be unit-tested.
 */
public final class Romanize {
    private Romanize() {}

    private static final String[] CYR_LOWER_FROM = {
            "а", "б", "в", "г", "д", "е", "ж", "з", "и", "й", "к", "л", "м", "н", "о", "п", "р", "с", "т", "у", "ф", "х", "ц", "ч",
            "ш", "щ", "ъ", "ь", "ы", "э", "ю", "я", "ё", "є", "і", "ї", "ґ", "ђ", "ј", "љ", "њ", "ћ", "џ", "ѓ", "ќ", "ѕ"};
    private static final String[] CYR_LOWER_TO = {
            "a", "b", "v", "g", "d", "e", "zh", "z", "i", "y", "k", "l", "m", "n", "o", "p", "r", "s", "t", "u", "f", "h", "ts", "ch",
            "sh", "sht", "a", "y", "y", "e", "yu", "ya", "yo", "ye", "i", "yi", "g", "dj", "j", "lj", "nj", "c", "dz", "g", "k", "dz"};

    private static final String[] GREEK_FROM = {
            "α", "β", "γ", "δ", "ε", "ζ", "η", "θ", "ι", "κ", "λ", "μ", "ν", "ξ", "ο", "π", "ρ", "σ", "ς", "τ", "υ", "φ", "χ", "ψ", "ω",
            "ά", "έ", "ή", "ί", "ό", "ύ", "ώ", "ϊ", "ϋ", "ΐ", "ΰ"};
    private static final String[] GREEK_TO = {
            "a", "v", "g", "d", "e", "z", "i", "th", "i", "k", "l", "m", "n", "x", "o", "p", "r", "s", "s", "t", "y", "f", "ch", "ps", "o",
            "a", "e", "i", "i", "o", "y", "o", "i", "y", "i", "y"};

    // Hiragana, in gojuon order starting at U+3041 (small a) is irregular, so the table is by code point name.
    private static final String KANA_PLAIN =
            "ぁa|あa|ぃi|いi|ぅu|うu|ぇe|えe|ぉo|おo|かka|がga|きki|ぎgi|くku|ぐgu|けke|げge|こko|ごgo|さsa|ざza|しshi|じji|すsu|ずzu|せse|ぜze|そso|ぞzo|"
                    + "たta|だda|ちchi|ぢji|っ|つtsu|づzu|てte|でde|とto|どdo|なna|にni|ぬnu|ねne|のno|はha|ばba|ぱpa|ひhi|びbi|ぴpi|ふfu|ぶbu|ぷpu|"
                    + "へhe|べbe|ぺpe|ほho|ぼbo|ぽpo|まma|みmi|むmu|めme|もmo|ゃya|やya|ゅyu|ゆyu|ょyo|よyo|らra|りri|るru|れre|ろro|ゎwa|わwa|ゐi|ゑe|をo|んn|ゔvu";

    private static final java.util.Map<Character, String> CYR_MAP = new java.util.HashMap<>();
    private static final java.util.Map<Character, String> GREEK_MAP = new java.util.HashMap<>();

    private static final java.util.Map<Character, String> KANA = new java.util.HashMap<>();

    static {
        for (int i = 0; i < CYR_LOWER_FROM.length; i++) CYR_MAP.put(CYR_LOWER_FROM[i].charAt(0), CYR_LOWER_TO[i]);
        for (int i = 0; i < GREEK_FROM.length; i++) GREEK_MAP.put(GREEK_FROM[i].charAt(0), GREEK_TO[i]);
        for (String entry : KANA_PLAIN.split("\\|")) {
            if (entry.isEmpty()) continue;
            KANA.put(entry.charAt(0), entry.substring(1));
        }
    }

    /** True when the text has anything this class can romanize (so callers can skip the work). */
    public static boolean needed(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            if (script(s.charAt(i)) != 0) return true;
        }
        return false;
    }

    /** 0 = not handled, 1 = Cyrillic, 2 = Greek, 3 = kana, 4 = Hangul. */
    private static int script(char c) {
        if (c >= 0x0400 && c <= 0x04FF) return 1;
        if (c >= 0x0370 && c <= 0x03FF && c != 0x037E) return 2;
        if ((c >= 0x3041 && c <= 0x3096) || (c >= 0x30A1 && c <= 0x30FA) || c == 0x30FC) return 3;
        if (c >= 0xAC00 && c <= 0xD7A3) return 4;
        return 0;
    }

    public static String apply(String s) {
        if (s == null || s.isEmpty() || !needed(s)) return s == null ? "" : s;
        StringBuilder out = new StringBuilder(s.length() + 8);
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            switch (script(c)) {
                case 1 -> out.append(cyrillic(c));
                case 2 -> out.append(greek(c));
                case 3 -> i = kana(s, i, out);
                case 4 -> hangul(c, out);
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static String keepCase(char original, String latin) {
        if (latin.isEmpty()) return latin;
        return Character.isUpperCase(original) ? Character.toUpperCase(latin.charAt(0)) + latin.substring(1) : latin;
    }

    private static String cyrillic(char c) {
        char lower = Character.toLowerCase(c);
        String t = CYR_MAP.get(lower);
        return t == null ? String.valueOf(c) : keepCase(c, t);
    }

    private static String greek(char c) {
        char lower = Character.toLowerCase(c);
        String t = GREEK_MAP.get(lower);
        return t == null ? String.valueOf(c) : keepCase(c, t);
    }

    // ------------------------------------------------------------ kana

    private static char toHiragana(char c) {
        if (c >= 0x30A1 && c <= 0x30F6) return (char) (c - 0x60);
        return c;
    }

    /** Handles one kana (or a kana plus a small ya / yu / yo, and the doubling small tsu). Returns the last index used. */
    private static int kana(String s, int i, StringBuilder out) {
        char raw = s.charAt(i);
        if (raw == 0x30FC) { // long vowel mark: repeat the last vowel
            for (int k = out.length() - 1; k >= 0; k--) {
                char v = out.charAt(k);
                if ("aiueo".indexOf(v) >= 0) {
                    out.append(v);
                    break;
                }
                if (Character.isLetter(v)) break;
            }
            return i;
        }
        char c = toHiragana(raw);
        if (c == 'っ') {
            // doubles the next consonant
            if (i + 1 < s.length()) {
                char nx = toHiragana(s.charAt(i + 1));
                String r = KANA.get(nx);
                if (r != null && !r.isEmpty() && "aiueo".indexOf(r.charAt(0)) < 0 && r.charAt(0) != 'n') {
                    out.append(r.charAt(0) == 'c' ? 't' : r.charAt(0));
                }
            }
            return i;
        }
        String r = KANA.get(c);
        if (r == null) {
            out.append(raw);
            return i;
        }
        // combination with a small ya / yu / yo: きゃ -> kya, しゃ -> sha, ちゅ -> chu
        if (i + 1 < s.length() && r.length() >= 2 && r.charAt(r.length() - 1) == 'i') {
            char nx = toHiragana(s.charAt(i + 1));
            if (nx == 'ゃ' || nx == 'ゅ' || nx == 'ょ') {
                String vowel = nx == 'ゃ' ? "a" : nx == 'ゅ' ? "u" : "o";
                String base = r.substring(0, r.length() - 1);
                if (base.equals("sh") || base.equals("ch") || base.equals("j")) out.append(base).append(vowel);
                else out.append(base).append('y').append(vowel);
                return i + 1;
            }
        }
        out.append(r);
        return i;
    }

    // ------------------------------------------------------------ hangul

    private static final String[] INITIAL = {"g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h"};
    private static final String[] MEDIAL = {"a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we",
            "wi", "yu", "eu", "ui", "i"};
    private static final String[] FINAL = {"", "k", "k", "ks", "n", "nj", "nh", "t", "l", "lk", "lm", "lb", "ls", "lt", "lp", "lh", "m", "p",
            "ps", "t", "t", "ng", "t", "t", "k", "t", "p", "t"};

    private static void hangul(char c, StringBuilder out) {
        int code = c - 0xAC00;
        int initial = code / (21 * 28);
        int medial = (code % (21 * 28)) / 28;
        int last = code % 28;
        out.append(INITIAL[initial]).append(MEDIAL[medial]).append(FINAL[last]);
    }
}
