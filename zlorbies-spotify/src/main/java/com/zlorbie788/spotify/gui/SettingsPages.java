package com.zlorbie788.spotify.gui;

/** Builds every row of the settings, group by group. */
final class SettingsPages {
    private SettingsPages() {}

    static SettingsKit build(SettingsKit.Host host) {
        SettingsKit k = new SettingsKit(host);
        PagesLook.look(k);
        PagesLook.player(k);
        PagesMusic.music(k);
        PagesLyrics.lyrics(k);
        PagesLyrics.search(k);
        PagesStats.stats(k);
        PagesSetup.setup(k);
        return k;
    }
}
