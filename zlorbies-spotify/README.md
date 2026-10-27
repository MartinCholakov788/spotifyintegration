# Spotify Integration Mod (`zlorbies-spotify`)

Client-side Fabric mod for **Minecraft 1.21.11** (Fabric Loader 0.19.5, Fabric API 0.141.6+1.21.11, Java 21). License: MIT (see `LICENSE`).
An animated, fully customizable now-playing player for **Spotify** (or any Windows media player) **and a built-in player for your own
MP3, OGG, WAV and FLAC songs**, with **synced lyrics**, a **song search that plays in your Spotify app**, liked songs and saved albums.
**No login anywhere: no Spotify account, no API key, no developer app, nothing to connect.**

## What is new in 1.4.0

- **A new intro** (about 5 seconds, no image files in the mod): you fly at speed through a field of **asteroids** (rocky surface with craters, grain
  and a bright edge facing the star) towards a star while the camera sways and star streaks rush past, you fly into the star (white-out, shockwave
  with red and cyan fringes) and the burst carves the panel open, a pen of light writes the **signature of ZLORBIE788** with sparks and a flourish
  underneath, a metallic shine runs over it, then SPOTIFY INTEGRATION MOD and the version appear, the real beat of your song flashes the light and a
  scan reveals the player. The colours come from the album. Click or press a key to skip; `/music intro` replays it; Settings > Look > Opening
  animation decides when it plays and how fast. With animations switched off it does not play.
- **Light on your computer.** The intro (and the shine, light curtains and dots of the player) are made from small pictures the mod draws once on a
  background thread when the game starts, then just places and tints: about 100 draws per frame instead of thousands of tiny rectangles. Nothing
  ever waits for them; if a picture is not ready yet, that one detail is simply left out of the frame. The spectrum and wave of the player use at most
  120 columns, and long lines use fewer pieces, with the same look.
- **Your own music, no Spotify needed.** Put MP3, OGG, WAV or FLAC files in `config/zlorbies-spotify/music/` (subfolders are fine; the *Open music
  folder* button and `/music folder` open it). The mod has its own decoders and sound engine, so it works whether or not Spotify is open.
  *Smart start*: with nothing playing, the player shows a friendly "drop songs here" card. Settings > My music > Your music folder: **Automatic** (whatever is playing),
  **Spotify** or **My music**; `/music source auto|spotify|local`.
- **My music screen** (button in the player, key `O` inside it, `/music library`): instant search, sort (title, artist, album, newest), the whole
  library with covers, a **Queue** with drag-to-reorder and *Play next*, your own **playlists** and **Favourites** (the heart), and smart lists
  (**Most played**, **Recently added**, **Not played yet**). `/music queue`, `/music playnext <words>`, `/music addqueue <words>`.
- **Sound** (Settings > My music > Sound). A 10-band **equaliser** with presets (Flat, Bass boost, Vocal, Night, Treble, Rock, Electronic, Custom),
  **gapless** playback, **crossfade**, an output device choice, and it remembers the last song, position and volume (*resume*).
- **The game reacts to the music** (Settings > My music): the music lowers ("ducks") while chat arrives, when you take damage or when something loud
  happens, and you can quiet Minecraft's own music while yours plays. Each one has its own switch and a level.
- **Experimental real-audio analysis for Spotify.** When on, the mod listens to what your PC plays (Windows loopback) so the beat display, the mood
  themes and the ring / spectrum visuals follow the real sound of Spotify too. It is a switch (*Settings > My music > Spotify real-audio (experimental)*), it
  shows its status, and when anything goes wrong (it fails, or it connects but only hears silence) it falls back to the loudness meter, and to calm animated bars if that hears nothing either, by itself. Switch it off and it is gone.
- **Beat lock.** The bars, lines, cover and glow now keep the rhythm: the mod works out the tempo from the hits and keeps pulsing on the beat even when
  one hit is missed or late, and it stays quiet for music without a steady beat. Settings > Look > Motion > *Beat lock*; the settings helper card shows
  what is being followed (real audio, loudness meter or calm stand-in motion) and the BPM it found. The cover pops a little harder on each beat.
- **Song troubleshooter.** If "My music" says a file could not be read, click that line (or Settings > My music > *Fix my songs*, or `/music songs`).
  It checks the folder and says in plain words what is wrong with each file and what to do: a real MP3 vs an M4A/AAC file renamed to .mp3, Opus, empty or
  damaged files, a folder with nothing in it, and so on. The mod now looks at what is *inside* a file, not at its name. OGG files made by common
  encoders that used to fail ("Vorbis exception") are readable now.
- **Stats have their own category** in the settings (Settings > Stats): the numbers right now, achievements, the yearly recap, and where it is saved.
- **Mood themes** (Settings > Look > Mood). The accent colour and the speed of the effects follow the energy of the song (calm songs soft and slow, loud ones bright and quick).
- **Hotkeys that work in the game.** Numpad defaults: 5 play/pause, 6 next, 4 previous, 8 / 2 volume, 0 mute, + like. Also unbound-by-default keys for
  seek, shuffle, repeat, search, lyrics, My music, share and settings (Options > Controls > Spotify Integration Mod). Windows **media keys** work too,
  also when Minecraft is in the background (Settings > My music > *Media keys*, with a status line and a *Try again* button).
- **Mini bar** (Settings > Player > Mini bar). A thin bar with the song and a progress line that shows when you press a hotkey and hides by itself (auto-hide).
- **Lyrics for your own songs.** The mod reads `.lrc` files next to a song, or lyrics inside the song's tags. `/music lyrics fetch` fetches lyrics
  for the whole folder in the background (`/music lyrics stop` to stop). **Fix tags** (`/music fixtags`) fills in missing artists and covers from the
  internet; it never writes into your files.
- **Share a song.** The share key / `/music share` copies "Artist - Title"; `/music share chat` also puts it in the chat box.
- **Stats got achievements and a yearly recap.** Settings > **Stats** > *Open listening stats* (or `/music stats`) now has tabs: Overview, **Achievements**
  (14 goals: first song, 10 / 100 / 1000 plays, 1 / 10 / 100 hours, streaks, ...) and **Recap** (the year in numbers, with *Copy recap*). A message tells
  you when you unlock one.
- **Profiles.** Save your whole look, equaliser and hotkeys under a name and switch with one click (up to 20). Settings > Keys & setup.
- **Safe mode.** If a part of the mod ever fails while drawing, it switches itself off, shows a red strip with the reason, and keeps the game running.
  Settings > Keys & setup > *Reset to safe look* puts the look back to something plain and reliable.
- **A new settings screen.** Six groups (Look, Player, My music, Lyrics, Search, Keys & setup), a search box that finds any option, sections you can fold,
  a live preview, and **a short explanation under every option** (more than 170 of them). New here? An interactive **first-time tour** shows the
  essentials the first time you open the settings (never over the player); Settings > Keys & setup > *Quick tour* or `/music tutorial` starts it again.
- Spotify side: the song-change toast, the "Spotify not found" checklist, lyrics, search and playlists are all still there.
- New files in `config/zlorbies-spotify/`: `music/` (your songs), `profiles.json`, `achievements.json`, plus small caches. Old settings are upgraded.
- **Bundled libraries:** to play MP3 and OGG without installing anything, the mod includes the source of two LGPL decoders (JLayer, JOrbis). See
  `THIRD_PARTY_LIBRARIES.txt`.
- **Nothing overlaps.** The player header measures the real width of its words in your font and switches to shorter ones when space is tight; an automatic
  layout test checks every screen in every font and window size.
- **Honest notes:** the Windows-only parts (media keys, loopback capture, the sound card) were written without a Windows machine to test on; if one of
  them misbehaves, the status line in Settings says why and the rest of the mod keeps working.

## What was new in 1.3.2

- **New name: Spotify Integration Mod.** Every screen, message and log line now carries the new name. Only the intro animation still
  says ZLORBIE788.
- **A brand new intro** (about 3.7 seconds): a light streak splits the screen and opens the panel, corner brackets snap in, ZLORBIE788 rises
  letter by letter with a glow and a shine, a rule grows from the middle with sparks, then SPOTIFY INTEGRATION MOD, a version pill, live beat
  bars, and a scan that reveals the player. Click or press a key to skip. Replay it with `/music intro`.
- **Song-change toast.** A slide-in card with the cover, title and artist. Settings > **Extras**: on/off, when to show it (only when the
  on-screen player is hidden, or every song), corner, how long it stays (2-15 s), size, cover on/off, and *Test the toast*. `/music toast` tests it too.
- **Fade out when idle.** The on-screen player fades after N seconds (0 = never) down to a faded opacity you pick, and comes back when
  the song changes, when you press a control or when you point at it. A new **Fade** background (a soft glow with no box) goes with it.
- **"Spotify not found" checklist.** When nothing is playing, the player shows a short list: Windows supported, helper running,
  Spotify open, a song played once. Each line is a tick, a cross or a note, and the first problem says what to do. `/music fix` prints the same list.
  Switch it off in Settings > Extras > *Help card*.
- **Share your look as a text code.** Settings > Look > **Copy code** / **Paste code** (or `/music code`, `/music code paste`). A code starts
  with `SIM1:`, is short, and holds only the look (shapes, colours, fonts, effects and the lyric style), never your positions, keys or switches.
- **Four more quick looks** (nine in all): Vinyl, Mini (one line), Stream (clean for overlays) and **Cover**, where the whole interface takes
  its colours from the album.
- **Lyrics extras.** *Romanize* shows a second line that spells Cyrillic, Greek, Japanese kana and Korean in Latin letters. There is no true
  translation (that needs an online service); kanji and Chinese characters stay as they are. *Word timing* highlights word by word when
  the lyrics carry word timing (rare on lrclib.net); otherwise the smooth estimate is used as before.
- **My playlists.** In Search, the new **Lists** tab holds your own named playlists. Press **+** on any song to add it. Open a list to Play,
  Shuffle, Rename or Delete it; the mod plays the songs one after another in your Spotify and stops when you pick something yourself.
  Up to 40 lists with 300 songs each. They are the mod's own lists, kept in `playlists.json`, not Spotify playlists. `/music lists` opens them.
- **Listening stats.** Settings > Extras > **Listening stats** (or `/music stats`): this game, all time since you first installed it, a
  14-day chart, top songs and top artists. Switch counting off or reset it there.
- New files in `config/zlorbies-spotify/`: `playlists.json` and `stats.json`.

## What was new in 1.3.1

- **Fonts fixed.** The five fonts that came in the OpenType format (Inter, Adventor, Narrow, Script, Terminal) are now plain TrueType files, the
  format Minecraft is happiest with. The two Minecraft "alphabet" fonts (Enchanting table, Illager runes) are gone: they turn every
  letter into symbols, so they looked broken. On top of that the mod now **checks every font when the game has loaded**: a font that
  does not load is hidden from the lists and never shows as boxes (the game log says which one, and `/music status` lists them).
- **Intro: you choose when it plays.** Settings > Menus > Intro: *Every time I open it*, *Every time I join a world*, *Once per Minecraft
  session*, *First time ever* or *Never*. A small "click or press a key to skip" reminder shows during the animation.
- **Quick looks.** Settings > Look had five one-click complete looks: Factory, Neon HUD, Clean, Glass and Retro. They change only how it
  looks (shapes, effects, fonts, theme), never the position, size, lyrics or keys.
- **The Wave seek bar now works** (Settings > Visual > Progress): the played part of the bar is a gently moving wave.
- **Polish.** Sliders show the name on the left and the value on the right (no handle over the text; the handle appears when you point at it),
  a soft fade and colour change when you switch settings tabs, a "Look applied" confirmation, tidier footer hints, a round
  play / pause button that fades in over the cover when you point at it (Classic and Sleek screens), switches on narrow rows keep
  their full label.

## What was new in 1.3.0

- **Much more modern look, everywhere.** Smooth anti-aliased rounded corners (no more pixel staircase), soft shadows, a gliding
  tab highlight in the settings, buttons and switches that fade and glide, click ripples, and five interface themes
  (Midnight, Graphite, Ocean, Neon, Rose). Settings > **Menus** changes the theme, the highlight colour and how round the little controls are.
- **Fonts.** Settings > **Fonts**: 9 new fonts (Inter, Poppins, Adventor, Narrow, Mono, Terminal, Serif, Script, Humanist). Choose one for the player, another for the lyrics ("Same as player" by default) and another for
  the menus. Click any font in the list to use it. If a font sits a little too high or low, the *Vertical shift* slider fixes it.
  Licences of the bundled fonts: `THIRD_PARTY_FONTS.txt`.
- **The Right-Alt panel is yours to shape.** Settings > **Panel**: width (280-560), height (176-340), shape (Rounded / Cut corners / Tech cut / Square), corner size, border thickness, background (Solid / Glass / Album-tinted / None), opacity, and darkening the game
  behind it, with a live preview. The Sleek on-screen player and the whole Sleek screen get the same shape and corner settings.
- **A better beat display.** The little bars that looked like "-------" when the music was quiet are gone. Settings > **Visual** > *Beat*:
  **Mirror bars** (the standard, they stay as calm dots when silent and grow from the middle on every beat), **Scope** (a live line that
  scrolls), **Wave**, **Dot matrix**, **Bars** or **Off**. Bigger and better placed: top right of the card, under the cover in the Sleek screen.
- **The cover is finally inside its box.** Spotify's Windows picture comes with empty margins and a "Spotify" logo strip; the mod now trims
  that away automatically (Settings > Visual > *Trim cover*) and draws the cover with corners that match the card (concentric), so it
  never pokes out of the rounded corners. Choose Match the panel / Square / Soft / Round / Circle, and a zoom, in Settings > Visual.
- **Many more animations:** a light that runs around the border while a song plays, a shine that crosses the player now and then, soft
  coloured aurora light behind the player screen that pulses with the beat, floating light specks, HUD corner brackets, parallax (the cover
  and the light drift with your mouse), a pop on the cover when the song changes, and the panel sliding in. Each one has its own switch.
- **An intro!** The first time you open the player it plays a short boot sequence: a laser splits the screen and opens the panel, corner
  brackets snap in, the title "ZLORBIE788" decodes letter by letter, boot bars and shock rings, then a scan reveals the player
  (about 3 seconds). Any click or key skips it. Settings > **Menus** > *Intro*: First time only / Every time / Once per game / Never,
  speed, and **Replay intro** (or type `/music intro`).
- **Factory settings = the ones you sent in the screenshots** (Sleek, Colour album, Glass, ... Lyrics in the card on the album line, wrap,
  Sweep, size 1.20 ...). Note: the on-screen player **Size is 0.5x** because that is what the screenshot showed: raise it in Settings > Place
  or with *Move on screen* if that is too small. "Reset all" puts every setting back to these.
- Old settings files keep working (they are upgraded and every new setting starts at its factory value).

## What was new in 1.2.3

- **Lyrics are never cut off with "..." any more.** A long line now wraps onto as many rows as it needs (the card and the lyrics box
  both), gets a little smaller if it is very long, and in the one-row spots (the card's album line, the *Top line*) it shrinks and then
  scrolls along with the singing, so you can read all of it. Only the *Cut* choice still shortens a line, and only because you pick it.
  Songs with plain (untimed) lyrics wrap too.
- **Lyrics inside the main player (Right Alt).** The Classic player now has the lyrics built in: beside the player when the window is wide
  enough, or under the song details when it is tall enough (on a small window the Lyrics button still opens the Sleek player). The
  Lyrics button, the **L** key and `/music lyrics` switch them. **Settings > Player > Lyrics in the player**: *Off* (only when you press the
  button), *Remember* (opens the way you left it, the standard) or *Always* (opens with the lyrics showing). The Sleek player follows the same choice.
- **The cover always matches its frame.** The soft shadow, the glow and the hover outline now grow and shrink together with the cover
  (the pop when a song changes and the beat bump), so they hug it exactly instead of the picture sticking out over them.
- Settings > Player is tidier (seek and volume steps share a row) to make room for the new option.

## What was new in 1.2.2

- **Long lyric lines wrap onto a second row inside the card.** The card keeps its size: a line that is too wide for one row uses the artist
  row as well (two rows: the sweep runs through the first row and on into the second). Short lines stay on one row. Settings >
  Lyrics > *Long lines in the card*: **Wrap** (new standard), Scroll, Shrink or Cut. Looks with a single free row (Compact, Minimal,
  and the *Top line* mode) shrink the line instead.

## What was new in 1.2.1

- **Lyrics inside the player card.** Settings > Lyrics > *Inside the player card*: **Album line** (the line being sung takes the place of
  the album name), **Artist + album** (the current line and the next one), or **Top line** (instead of NOW PLAYING). Works in every
  on-screen look. A long line stays still until the singing reaches the edge, then follows it (or shrinks / is cut: *Long lines in the card*).
  When a song has no timed lyrics the card shows its normal text.
- **The old look is back as the standard.** The player screen (Right Alt) opens the *Classic* screen again, and the on-screen player is the
  Card. Sleek is still there if you want it (Settings > Player > Player screen, Settings > Look). Older settings files are switched back once.
- **Lyrics are much more customizable.** New *Text* tab and more in *Lines* / *Colour*: 4 ready-made looks (Karaoke, Subtitles, Minimal, Neon),
  two new line effects (Underline sweep, Type it out) and a sweep speed, UPPERCASE / lowercase, italic, outline colour, size of the
  other lines, extra dimming of lines already sung, 1-3 wrap rows, box padding, up to 6 lines above / below, width up to 560,
  border on / off and a colour bar.
- **The search only shows songs that play directly.** Every result is checked first and only songs that really exist on Spotify are
  listed (so a click always plays); results appear one by one while they are checked. Turn it off in Settings > Search >
  *Only songs that play directly*. `/music find` also skips songs that are not on Spotify.

## What was new in 1.2.0

- **Lyrics**, time-synced and very customizable (position, width, lines above / below, size, colours, glow / sweep / pop highlight,
  fade, motion, box style, timing offset...). On the screen while you play, and inside the player screen (click a line to jump to it).
- **Sleek player screen**: the whole screen takes the colours of the album, with a big cover, a wide seek bar and round controls.
  Choose between *Classic* (the standard) and *Sleek* in Settings > Player > Player screen. There is also a **Sleek** on-screen style.
- **Search**: type a song, artist or album, press Enter or click a result, and it plays in your Spotify app. You can also paste a
  Spotify link. No Spotify log-in is needed (see "How the search works").
- **Albums**: click the album name in the player to see that album's songs; save albums, like songs, and see your history.
- **Cover fallback**: when Windows does not hand over a cover (it sometimes does not), the mod finds the album picture online.
- New keys / commands: `/music search`, `/music find <words>`, `/music like`, `/music lyrics`.

## How it works

Spotify (and Chrome, Apple Music, VLC, ...) publish what they are playing to Windows (the media overlay you see when you press a
volume key). The mod starts a small hidden Windows PowerShell helper (`bridge.ps1`, bundled inside the jar) that reads that
information and sends your commands back. Nothing is installed.

- **Windows 10 / 11 only.** The helper uses Windows PowerShell 5.1, which ships with Windows.
- Spotify (desktop app or Microsoft Store version) must be installed and running. Play something once so Windows sees it.

## Step by step: build, install, first run

1. **Build.** Extract the zip to a normal folder (for example `C:\mods\zlorbies-spotify`) so that `build.bat` sits next to `gradlew.bat`.
   Double-click **`build.bat`** (not as administrator). It finds Java 21 (and offers to install it) and uses `C:\gradle-home`.
2. **Take the jar** from `dist` (a copy of the one in `build\libs`; never the one with `-sources` in its name). Delete any older `zlorbies-spotify-*.jar` from your mods folder first. Put it in your mods folder
   (Modrinth app: your profile > Mods > Add > From file; or `.minecraft\mods`). Fabric API must be installed.
3. **Start Spotify** and press play on any song.
4. **Start Minecraft 1.21.11** (Fabric). Press **Right Alt** to open the player.
5. Click **Lyrics** (or press **L**) to see the words next to the player, **Search** to find a song. That is all: there is nothing to log in to.

`build.bat` now says **BUILD SUCCESSFUL** only when the jar really exists (it opens the `dist` folder with it) and **BUILD FAILED** otherwise.

If the build fails, copy the lines containing `error:` or `What went wrong:`.

## Using it

| What | How |
|---|---|
| Open the full player | **Right Alt** (change it in Settings > Keys), or `/music` |
| Search songs | the **Search** button, `/` in the player, or `/music search` (you can bind a key in Settings > Keys) |
| Lyrics | the **Lyrics** button (or `L`) in the player; on the game screen: Settings > Lyrics > *Show lyrics on screen*, or `/music lyrics toggle` |
| Like the current song | the heart in the player, `F`, or `/music like` |
| Settings | the gear in the player, or `/music settings` |
| Your own songs | the **My music** button (or `O`) in the player, or `/music library`; songs go in `config/zlorbies-spotify/music/` (`/music folder`) |
| The quick tour | Settings > Keys & setup > *Quick tour*, or `/music tutorial` |
| Hotkeys in the game | numpad 5 / 6 / 4 play-next-previous, 8 / 2 volume, 0 mute, + like; the rest in Options > Controls |
| Move / resize the HUD and the lyrics | Settings > *Move on screen* (drag, scroll = size, Shift+scroll = opacity, arrows nudge, `L` switches between player and lyrics), or `/music layout` |

**Sleek player screen:** the cover reacts to the music (glow and beat), seek bar you can click or drag (hover shows the time, scroll wheel
seeks), shuffle / previous / play / next / repeat, mute and a volume slider. Click the **artist** to search them, click the **album**
to see its songs. Keys: Space, Left/Right, Up/Down, M, S, R, N, B, L (lyrics), F (like), / (search).

**Lyrics panel:** the current line is highlighted; click any line to jump there; the **- / +** chips at the bottom shift the timing
by 0.1 s if the lyrics are early or late for your Spotify. Lyrics come from lrclib.net (free, community made). The song title,
artist, album and length are sent to look them up, and found lyrics are saved on your PC so each song is only looked up once.
Songs without timed lyrics show plain text, or a note when none exist. Turn the online lookup off in Settings > Lyrics.

**On-screen player (HUD):** six styles (Card, Compact, Vinyl, Minimal, Tall, Sleek), always / pop-up on song change / only while playing,
fixed-size boxes (long titles scroll or are cut with an ellipsis), colours from the album art, your own colour or rainbow,
solid / glass / tinted / no background, round or square corners, glow, equalizer, spinning round cover, song-change slide,
text shadow, **high contrast** mode, and an **Animations** master switch for reduced motion.

**Commands:** `/music` or `/spotify` followed by `open`, `settings`, `layout`, `hud`, `play`, `pause`, `toggle`, `next`, `prev`,
`mute`, `shuffle`, `repeat`, `seek 1:23` / `seek +15` / `seek -15`, `forward`, `back`, `volume 40` / `volume +5`, `status`,
`reconnect`, `help`, `intro` (replays the opening animation), `fix`, `stats`, `lists`, `toast`, `code`, `code paste`, and `search [words]`, `find <words>` (plays the first match), `like`, `lyrics [on|off|toggle]`.
1.4.0 adds `tutorial`, `library`, `folder`, `rescan`, `source auto|spotify|local`, `queue`, `queue clear`, `playnext <words>`, `addqueue <words>`, `share`, `share chat`, `fixtags`, `fixtags stop`, `lyrics fetch`, `lyrics stop` and `songs` (the song troubleshooter).

## How the search works (no log-in)

1. The search looks your words up in a **public music catalogue**: Deezer, with Apple Music's public search as the backup.
   Both are open to everyone and need no key.
2. When you pick a song, the mod asks the public **song.link** service which Spotify song matches it (this is the only
   "translation" step, and the answer is saved on your PC so it is only asked once per song).
3. The mod hands that Spotify link to **your own Spotify app** (the same as clicking a Spotify link in a browser), watches the Windows
   media session until the song shows up, and presses play for you (Settings > Search > *Press play for me*).

So the music always plays in Spotify, with your account, your Premium and your volume. The mod never sees your account.

**Be aware of these limits** (they are the price of having no log-in):

- It **cannot read your real Spotify library**: your Spotify playlists, saved songs and albums are not shown. *Liked*, *Saved* albums and
  *History* in the search are the mod's own lists, stored in `config/zlorbies-spotify/library.json` on your PC.
- It cannot add songs to the Spotify queue or to Spotify playlists.
- Only songs that exist on Spotify are listed (the checking is done by song.link, which allows about 10 lookups a minute). The first results
  show at once, later ones can take a moment, and songs you looked up before are instant. If song.link asks for a break you see a countdown;
  if it cannot be reached at all, every result is shown again (and a song that cannot be matched then opens a Spotify **search page** instead).
  Want to see everything immediately? Turn off *Only songs that play directly* in Settings > Search.
- Songs that exist only on Spotify (not on Deezer / Apple Music) cannot be found by name; paste their Spotify link instead.
- If the Spotify app opens a song but does not start it, the mod presses play for you up to 3 times, then tells you.
- Searching, covers from the web and lyrics need an internet connection. Each can be switched off in Settings.

## Follows the music

The bars, the top line, the glow and the cover react to the **real loudness and beat** of the song. The helper reads the same
live level meter that the Windows volume mixer shows for Spotify, about 40 times a second, so nothing is faked and nothing is
recorded. Kicks push the low bars, busy hi-hats ripple the high ones, bars fall with gravity and keep a falling peak cap, the
animated line speeds up with the energy and flashes on every beat, and the cover gives a small bump.
Settings > Motion: *Follow the music*, *Reaction* strength, *Beat pulse*, *Level display* (the wide bars in the full player).
It is a loudness and rhythm follower, not a full frequency analysis, so the spread across the bars is a shaped model.
If the level is not available the bars use a smooth stand-in motion instead.

## Limits

- Volume changes the Spotify app's volume in the Windows mixer; it needs Spotify to be playing audio at least once.
- Seeking and shuffle / repeat depend on the player; during ads Spotify blocks most controls.
- Spotify playlists, your saved Spotify songs and the queue need a Spotify login (the Web API). They are not included on purpose: this mod needs no login.
- Not for Mac / Linux (the search, lyrics and library parts would work, but the player reading is Windows only).

## Troubleshooting

- **No cover art:** with *Cover from the web* on (Settings > Search) the mod finds the cover online when Windows gives none.
  Open Settings > Player and read the *Cover art* line (or type `/music status`) for the reason otherwise.
- **Lyrics say "No lyrics found":** the song may not be in lrclib.net yet; the **Try again** button looks again. If they are early or
  late, use the - / + chips in the lyrics panel (or Settings > Lyrics > Timing).
- **The search finds nothing / "Could not reach the music catalogues":** check your internet connection; try Settings > Search > *Search in*
  (Deezer or Apple Music).
- **The search list is short or empty:** only songs that exist on Spotify are shown (Settings > Search > *Only songs that play directly*), and
  the check can take a moment. A countdown means song.link asked for a break. Turn the option off to see every result at once.
- **Spotify opens a search page instead of the song** (only with that option off, or when song.link cannot be reached): there was no
  exact match; click the song in Spotify.
- **Shows "Waiting for Spotify":** start Spotify and press play. Settings > Player > **Reconnect**, or `/music reconnect`.
- `/music status` prints what the helper reports. Settings > Player shows the helper state.
- To read other players too: Settings > Player > Read from: *Any player*.
- Files: `config/zlorbies-spotify/config.json` (a `.bak` is kept), `lyrics/` (saved lyrics), `library.json` (likes, saved albums, history), `playlists.json` (your lists), `stats.json` (listening stats),
  `profiles.json`, `achievements.json`, `music/` (your own songs), `spotify-links.json`, `thumbs/` and `webcovers/` (pictures). You can delete any of them; they are re-made when needed.
