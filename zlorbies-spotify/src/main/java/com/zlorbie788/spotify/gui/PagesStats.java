package com.zlorbie788.spotify.gui;

import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.stats.Achievements;
import com.zlorbie788.spotify.stats.Stats;

import java.util.List;

/** The Stats group of the settings: counting, the stats screen, achievements, the recap and wiping it all. */
final class PagesStats {
    private static boolean armed;
    private static long armedAt;

    private PagesStats() {}

    /** A few live numbers for the preview card (also handy for tests). */
    static String[] preview() {
        List<Stats.SongStat> songs = Stats.topSongs(1);
        String top = songs.isEmpty() ? "nothing yet" : songs.get(0).title() + " (" + songs.get(0).plays() + " plays)";
        return new String[]{
                "Today: " + Stats.duration(Stats.todayMs()) + ", this session: " + Stats.duration(Stats.sessionMs()),
                "All time: " + Stats.duration(Stats.totalMs()) + " over " + Stats.daysListened() + " days, best streak " + Stats.bestStreak() + " days",
                "Top song: " + top,
                "Achievements: " + Achievements.unlockedCount() + " of " + Achievements.goals().size(),
                "Everything stays on this PC and is never sent anywhere."};
    }

    static void stats(SettingsKit k) {
        k.group(5);
        SettingsKit.Host host = k.host;

        k.section("Counting", false);
        k.toggle("Count my listening", "Keeps listening time, top songs and artists on this PC (never sent anywhere).",
                "Off = nothing is counted from now on. What is already counted stays until you reset it.",
                () -> ConfigIo.get().trackStats, v -> ConfigIo.get().trackStats = v);
        k.status("Right now", "", 2, () -> {
            String[] p = preview();
            return p[0] + "  ·  " + p[1];
        }, SettingsKit::soft);
        k.button("Open listening stats", "This session, all time, the last two weeks, top songs and artists.", "", "Open", true, () -> {
            StatsScreen.showTab(0);
            host.mc().setScreen(new StatsScreen(host.screen()));
        });

        k.section("Achievements and recap", false);
        k.status("Achievements", "", 1, () -> Achievements.unlockedCount() + " of " + Achievements.goals().size() + " unlocked",
                SettingsKit::soft);
        k.pair("Look at them", "Achievements and your yearly recap each have a page in the stats screen.", "",
                k.buttonWidget("Achievements", "See what you unlocked and what is next.", false, () -> {
                    StatsScreen.showTab(1);
                    host.mc().setScreen(new StatsScreen(host.screen()));
                }),
                k.buttonWidget("Yearly recap", "Your year in music: months, best day, favourites.", false, () -> {
                    StatsScreen.showTab(2);
                    host.mc().setScreen(new StatsScreen(host.screen()));
                }));
        k.button("My playlists", "Your own playlists (saved on this PC, no Spotify login needed).", "", "Open playlists", false, () -> {
            SearchScreen s = new SearchScreen(host.screen());
            s.showLists();
            host.mc().setScreen(s);
        });

        k.section("Your data", true);
        k.status("Where it lives", "", 2, () -> "Saved in the mod's config folder on this PC. It is never uploaded; nothing here needs a log-in.",
                SettingsKit::soft);
        k.button("Reset all stats", "Forgets listening time, tops, streaks and achievements. Click twice to confirm.", "", "Reset stats", false, () -> {
            long now = System.currentTimeMillis();
            if (armed && now - armedAt < 6000L) {
                armed = false;
                Stats.reset();
                Achievements.reset();
                host.toast("All stats were reset");
                return;
            }
            armed = true;
            armedAt = now;
            host.toast("Click Reset stats again within 6 seconds to forget everything");
        });
    }
}
