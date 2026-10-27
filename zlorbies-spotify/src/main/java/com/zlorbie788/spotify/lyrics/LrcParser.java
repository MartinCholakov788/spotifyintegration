package com.zlorbie788.spotify.lyrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the .lrc format used for time-synced lyrics:
 * <pre>[01:23.45] some words</pre>
 * Several time tags may share one line, tags like [ar:...] are ignored, and [offset:+200] shifts everything.
 * Pure Java, no Minecraft classes, so it can be unit-tested.
 */
public final class LrcParser {
    private static final Pattern TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern OFFSET = Pattern.compile("^\\s*\\[offset:\\s*([+-]?\\d+)\\s*\\]\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    /** Word-level tags of the "enhanced" format: <01:23.45>. They are removed from the text. */
    private static final Pattern WORD_TAG = Pattern.compile("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>");

    private LrcParser() {}

    public static List<LyricLine> parse(String lrc) {
        List<LyricLine> out = new ArrayList<>();
        if (lrc == null || lrc.isBlank()) return out;
        long offset = 0;
        Matcher om = OFFSET.matcher(lrc);
        if (om.find()) {
            try {
                offset = Long.parseLong(om.group(1));
            } catch (NumberFormatException ignored) {
                offset = 0;
            }
        }
        for (String raw : lrc.split("\\r?\\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.charAt(0) != '[') continue;
            Matcher m = TIME.matcher(line);
            List<Long> times = new ArrayList<>();
            int end = 0;
            while (m.find() && m.start() == end) {
                times.add(toMillis(m.group(1), m.group(2), m.group(3)));
                end = m.end();
            }
            if (times.isEmpty()) continue;
            String rest = line.substring(end);
            Matcher wm = WORD_TAG.matcher(rest);
            StringBuilder plain = new StringBuilder();
            List<long[]> tags = new ArrayList<>();
            int last = 0;
            while (wm.find()) {
                plain.append(rest, last, wm.start());
                Matcher tm = TIME.matcher("[" + wm.group().substring(1, wm.group().length() - 1) + "]");
                if (tm.matches()) tags.add(new long[]{toMillis(tm.group(1), tm.group(2), tm.group(3)), plain.length()});
                last = wm.end();
            }
            plain.append(rest.substring(last));
            String joined = plain.toString();
            String text = joined.strip();
            int lead = joined.length() - joined.stripLeading().length();
            long[] wordMs = null;
            int[] wordChar = null;
            if (!tags.isEmpty() && !text.isEmpty()) {
                wordMs = new long[tags.size()];
                wordChar = new int[tags.size()];
                for (int i = 0; i < tags.size(); i++) {
                    wordMs[i] = Math.max(0, tags.get(i)[0] - offset);
                    wordChar[i] = (int) Math.max(0, Math.min(text.length(), tags.get(i)[1] - lead));
                }
            }
            for (long t : times) out.add(new LyricLine(Math.max(0, t - offset), text, wordMs, wordChar));
        }
        out.sort(Comparator.comparingLong(LyricLine::startMs));
        return out;
    }

    private static long toMillis(String minutes, String seconds, String fraction) {
        long ms = Long.parseLong(minutes) * 60_000L + Long.parseLong(seconds) * 1000L;
        if (fraction != null) {
            // ".5" = 500 ms, ".45" = 450 ms, ".456" = 456 ms
            String f = (fraction + "00").substring(0, 3);
            ms += Long.parseLong(f);
        }
        return ms;
    }

    /** Splits plain (unsynced) lyrics into lines. */
    public static List<String> plain(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String raw : text.split("\\r?\\n")) out.add(raw.strip());
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
        return out;
    }

    /**
     * Index of the line that is being sung at {@code posMs}: the last line that has started. -1 before the first
     * line. Binary search.
     */
    public static int indexAt(List<LyricLine> lines, long posMs) {
        int lo = 0;
        int hi = lines.size() - 1;
        int found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).startMs() <= posMs) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }
}
