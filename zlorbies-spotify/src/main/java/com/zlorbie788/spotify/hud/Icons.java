package com.zlorbie788.spotify.hud;

import net.minecraft.client.gui.GuiGraphics;

/** Tiny pixel-art icons drawn with plain rectangles, so they are crisp at any GUI scale. '#' = a lit pixel. */
public final class Icons {
    private Icons() {}

    public static final String[] PLAY = {
            "#........",
            "###......",
            "#####....",
            "#######..",
            "#########",
            "#######..",
            "#####....",
            "###......",
            "#........",
    };
    public static final String[] PAUSE = {
            ".###.###.",
            ".###.###.",
            ".###.###.",
            ".###.###.",
            ".###.###.",
            ".###.###.",
            ".###.###.",
            ".###.###.",
            ".###.###.",
    };
    public static final String[] NEXT = {
            "#......##",
            "##.....##",
            "###....##",
            "####...##",
            "#####..##",
            "####...##",
            "###....##",
            "##.....##",
            "#......##",
    };
    public static final String[] PREV = {
            "##......#",
            "##.....##",
            "##....###",
            "##...####",
            "##..#####",
            "##...####",
            "##....###",
            "##.....##",
            "##......#",
    };
    public static final String[] SHUFFLE = {
            "........#..",
            "........##.",
            "###..#######",
            "...#.#..##.",
            "....#....#.",
            "...#.#.....",
            "###..#######",
            "........##.",
            "........#..",
    };
    public static final String[] REPEAT = {
            "...........",
            "..#########",
            ".#.......##",
            "#........#.",
            "#........#.",
            "#........#.",
            ".#.......#.",
            "..#########",
            "...........",
    };
    public static final String[] VOLUME = {
            "....#......",
            "...##...#..",
            "####....#.#",
            "####.#..#.#",
            "####.#..#.#",
            "####....#.#",
            "###.....#..",
            "...##......",
            "....#......",
    };
    public static final String[] MUTE = {
            "....#......",
            "...##......",
            "####...#.#.",
            "####....#..",
            "####...#.#.",
            "####.......",
            "####.......",
            "...##......",
            "....#......",
    };
    public static final String[] NOTE = {
            "....######",
            "....######",
            "....#....#",
            "....#....#",
            "....#....#",
            "....#....#",
            "..###..###",
            ".####.####",
            ".####.####",
            "..##...##.",
    };
    public static final String[] GEAR = {
            "...#.#...",
            ".#######.",
            "..##.##..",
            "###...###",
            "#.......#",
            "###...###",
            "..##.##..",
            ".#######.",
            "...#.#...",
    };

    public static final String[] HEART = {
            ".##...##.",
            "####.####",
            "#########",
            "#########",
            ".#######.",
            "..#####..",
            "...###...",
            "....#....",
    };
    public static final String[] SEARCH = {
            "..####...",
            ".#....#..",
            "#......#.",
            "#......#.",
            "#......#.",
            ".#....#..",
            "..####...",
            ".....###.",
            "......###",
    };
    public static final String[] LYRICS = {
            ".#######.",
            "#########",
            "#.......#",
            "#.#####.#",
            "#.......#",
            "#.####..#",
            "#.......#",
            "#########",
            "...##....",
            "..#......",
    };
    public static final String[] BACK = {
            "...#.....",
            "..##.....",
            ".#######.",
            "#########",
            ".#######.",
            "..##.....",
            "...#.....",
    };
    public static final String[] CLOSE = {
            "##.....##",
            "###...###",
            ".##...##.",
            "..#####..",
            "...###...",
            "..#####..",
            ".##...##.",
            "###...###",
            "##.....##",
    };
    public static final String[] ALBUM = {
            "..#####..",
            ".#.....#.",
            "#..###..#",
            "#.#...#.#",
            "#.#.#.#.#",
            "#.#...#.#",
            "#..###..#",
            ".#.....#.",
            "..#####..",
    };
    public static final String[] EXTERNAL = {
            "....#####",
            "....#...#",
            "###.#...#",
            "#.#.#####",
            "#.#.....#",
            "#.#######",
            "###......",
    };

    public static int width(String[] icon) {
        int w = 0;
        for (String row : icon) w = Math.max(w, row.length());
        return w;
    }

    public static int height(String[] icon) {
        return icon.length;
    }

    /** Draws the icon with its top-left corner at (x, y); each lit pixel becomes a scale x scale square. */
    public static void draw(GuiGraphics g, String[] icon, int x, int y, int scale, int color) {
        for (int row = 0; row < icon.length; row++) {
            String line = icon[row];
            int col = 0;
            while (col < line.length()) {
                if (line.charAt(col) != '#') {
                    col++;
                    continue;
                }
                int start = col;
                while (col < line.length() && line.charAt(col) == '#') col++;
                g.fill(x + start * scale, y + row * scale, x + col * scale, y + (row + 1) * scale, color);
            }
        }
    }
}
