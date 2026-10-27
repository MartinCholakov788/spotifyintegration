package com.zlorbie788.spotify.gui;

import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.config.SpotifyConfig;
import com.zlorbie788.spotify.gui.widgets.ColorSwatch;
import com.zlorbie788.spotify.gui.widgets.ConfigSlider;
import com.zlorbie788.spotify.gui.widgets.StyledButton;
import com.zlorbie788.spotify.gui.widgets.StyledToggle;
import com.zlorbie788.spotify.hud.Gfx;
import com.zlorbie788.spotify.hud.Theme;
import com.zlorbie788.spotify.util.Colors;
import com.zlorbie788.spotify.util.TextWrap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** The small factories every settings page uses, so a page reads as a plain list of options. */
final class SettingsKit {
    /** What the pages need from the screen they live in. */
    interface Host {
        Minecraft mc();

        Screen screen();

        Font font();

        /** Rebuild the controls (keeps the scroll position). */
        void rebuild();

        void toast(String text);

        net.minecraft.client.KeyMapping listening();

        void listen(net.minecraft.client.KeyMapping mapping);
    }

    static final String[] GROUPS = {"Look", "Player", "My music", "Lyrics", "Search", "Stats", "Keys & setup"};
    static final String[] BLURBS = {
            "How the player looks and moves",
            "Where it is, what it does, how it connects",
            "Your own songs, sound and the game",
            "Lyrics: what they show and how they look",
            "Finding songs online",
            "What you listened to, achievements and your recap",
            "Keys, profiles, the tour and safe look"};
    static final int[] COLORS = {Colors.MINT, Colors.CYAN, Colors.PINK, Colors.PURPLE, Colors.GOLD, Colors.RED, Colors.BLUE};

    final Host host;
    final List<SettingsRow> rows = new ArrayList<>();
    /** Sections that start folded. */
    final java.util.Set<String> foldedByDefault = new java.util.HashSet<>();
    /** Sections in the order they were declared (group, name). */
    final List<String[]> sections = new ArrayList<>();
    int group;
    String section = "";

    SettingsKit(Host host) {
        this.host = host;
    }

    static SpotifyConfig cfg() {
        return ConfigIo.get();
    }

    int accent() {
        return COLORS[group];
    }

    void group(int g) {
        group = g;
    }

    /** Starts a section. Sections are open unless {@code folded}. */
    void section(String name, boolean folded) {
        section = name;
        sections.add(new String[]{String.valueOf(group), name});
        if (folded) foldedByDefault.add(group + "/" + name);
    }

    SettingsRow row(String label, String help, String more) {
        SettingsRow r = new SettingsRow(group, section, label, help, more, GROUPS[group]);
        rows.add(r);
        return r;
    }

    private static <T extends AbstractWidget> T tip(T w, String more) {
        // the widgets share the same Tooltip idea; only set it when there is something extra to say
        return w;
    }

    // ------------------------------------------------------------ plain controls

    SettingsRow toggle(String label, String help, String more, BooleanSupplier get, Consumer<Boolean> set) {
        StyledToggle t = Ui.toggle(0, 0, 100, 15, label, get, v -> {
            set.accept(v);
            ConfigIo.save();
        }).accent(accent());
        if (!more.isEmpty()) t.tip(more);
        return row(label, help, more).add(t, 0, 1, 0, 0, 0);
    }

    SettingsRow slider(String label, String help, String more, double min, double max, double step, double cur,
                       DoubleFunction<String> fmt, DoubleConsumer set) {
        ConfigSlider s = new ConfigSlider(0, 0, 100, 15, label + ": ", min, max, step, cur, fmt, set).accent(accent());
        if (!more.isEmpty()) s.tip(more);
        return row(label, help, more).add(s, 0, 1, 0, 0, 0);
    }

    ConfigSlider sliderWidget(String label, String more, double min, double max, double step, double cur,
                              DoubleFunction<String> fmt, DoubleConsumer set) {
        ConfigSlider s = new ConfigSlider(0, 0, 100, 15, label + ": ", min, max, step, cur, fmt, set).accent(accent());
        if (!more.isEmpty()) s.tip(more);
        return s;
    }

    SettingsRow cycle(String label, String help, String more, String[] names, IntSupplier get, Runnable advance) {
        return row(label, help, more).add(cycleWidget(label, more, names, get, advance), 0, 1, 0, 0, 0);
    }

    StyledButton cycleWidget(String label, String more, String[] names, IntSupplier get, Runnable advance) {
        String prefix = label.isEmpty() ? "" : label + ": ";
        StyledButton b = Ui.button(0, 0, 100, 15, prefix + names[get.getAsInt()], x -> {
            advance.run();
            ConfigIo.save();
            x.setMessage(Component.literal(prefix + names[get.getAsInt()]));
        }).accent(accent());
        if (!more.isEmpty()) b.tip(more);
        return b;
    }

    SettingsRow choice(String label, String help, String more, Supplier<String> text, Runnable advance) {
        return row(label, help, more).add(choiceWidget(label, more, text, advance), 0, 1, 0, 0, 0);
    }

    StyledButton choiceWidget(String label, String more, Supplier<String> text, Runnable advance) {
        String prefix = label.isEmpty() ? "" : label + ": ";
        StyledButton b = Ui.button(0, 0, 100, 15, prefix + text.get(), x -> {
            advance.run();
            ConfigIo.save();
            x.setMessage(Component.literal(prefix + text.get()));
        }).accent(accent());
        if (!more.isEmpty()) b.tip(more);
        return b;
    }

    StyledButton buttonWidget(String text, String more, boolean primary, Runnable run) {
        StyledButton b = Ui.button(0, 0, 100, 15, text, x -> run.run()).accent(accent());
        if (primary) b.primary();
        if (!more.isEmpty()) b.tip(more);
        return b;
    }

    SettingsRow button(String label, String help, String more, String text, boolean primary, Runnable run) {
        return row(label, help, more).add(buttonWidget(text, more, primary, run), 0, 1, 0, 0, 0);
    }

    /** Two controls side by side. */
    SettingsRow pair(String label, String help, String more, AbstractWidget a, AbstractWidget b) {
        return row(label, help, more).add(a, 0, 0.5f, 0, -2, 0).add(b, 0.5f, 0.5f, 2, -2, 0);
    }

    /** A line of buttons that share the width. */
    SettingsRow buttons(String label, String help, String more, List<AbstractWidget> list) {
        SettingsRow r = row(label, help, more);
        int n = list.size();
        for (int i = 0; i < n; i++) r.add(list.get(i), (float) i / n, 1f / n, i == 0 ? 0 : 2, i == 0 || i == n - 1 ? -2 : -4, 0);
        return r;
    }

    /** A grid of buttons, {@code cols} per line, 17 px per line. */
    SettingsRow grid(String label, String help, String more, List<AbstractWidget> list, int cols) {
        SettingsRow r = row(label, help, more);
        int n = list.size();
        for (int i = 0; i < n; i++) {
            int c = i % cols;
            r.add(list.get(i), (float) c / cols, 1f / cols, c == 0 ? 0 : 2, c == 0 || c == cols - 1 ? -2 : -4, (i / cols) * 17);
        }
        r.controlH = ((n + cols - 1) / cols) * 17 - 2;
        return r;
    }

    /** The row of eight colours. {@code pick} gets the colour; the row rebuilds so the ring moves. */
    SettingsRow swatches(String label, String help, String more, java.util.function.IntSupplier current,
                         java.util.function.IntConsumer pick) {
        SettingsRow r = row(label, help, more);
        int n = Colors.PALETTE.length;
        for (int i = 0; i < n; i++) {
            final int index = i;
            ColorSwatch sw = new ColorSwatch(0, 0, 24, 15, Colors.PALETTE_NAMES[i], Colors.PALETTE[i],
                    () -> current.getAsInt() != 0 && Colors.opaque(current.getAsInt()) == Colors.PALETTE[index],
                    () -> {
                        pick.accept(Colors.PALETTE[index]);
                        ConfigIo.save();
                        host.rebuild();
                    });
            r.add(sw, (float) i / n, 1f / n, 0, -3, 0);
        }
        return r;
    }

    /** A block of text that is worked out every frame (a status). */
    SettingsRow status(String label, String help, int lines, Supplier<String> text, IntSupplier color) {
        SettingsRow r = row(label, help, "");
        r.controlH = lines * 10 + 2;
        r.draw = (g, f, x, y, w, mx, my) -> {
            int yy = y + 1;
            String t = text.get();
            for (String s : TextWrap.wrap(t, w - 4, lines, q -> Gfx.width(f, q))) {
                Gfx.drawStyled(g, f, s, x + 2, yy, Colors.opaque(color.getAsInt()), false, false, false);
                yy += 10;
            }
        };
        return r;
    }

    static int soft() {
        return Theme.soft();
    }
}
