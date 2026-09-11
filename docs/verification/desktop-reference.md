# Desktop visual reference captures

Deterministic screenshots of the desktop OmaTube Qt Quick UI, captured for
side-by-side comparison while building the Android Compose port. Every image
here comes from the seeded automation fixture on a fake player with no network,
no real videos, no user database, and no desktop compositor capture. Nothing
in this document is a mockup.

## Source and build under test

| Item | Value |
|---|---|
| Desktop source | `/home/cassian/projects/cpp/OmaTube` (read only) |
| Git revision | `6b9e4d5` "Background of a video is more opaque now" |
| Binary | `build/yt-client`, mtime 2026-09-11 15:21:22 -0400 |
| Qt | 6.11.2, style `Basic` |
| Platform | Arch Linux, `QT_QPA_PLATFORM=offscreen` |
| Device pixel ratio | 1 (PNG pixels equal QML logical pixels) |
| Captured | 2026-09-11, runs `omadtr-20260911-01` and `-02` |

The binary mtime is older than the HEAD commit and older than the mtimes of
five player QML files, which normally means a stale build. It is not stale.
`build/qrc_resources.cpp` was generated at 15:21:22 and its embedded bytes for
`qml/VideoPlayerPage.qml` already contain the HEAD-only markers `opacity: 1.0`
and `objectName: "playerOpaqueBackground"`. The working tree carried the
changes before they were committed at 15:27, so the build contains HEAD
content. Verify with:

```sh
grep -n 'playerOpaqueBackground' build/qrc_resources.o
```

No source file was edited and no rebuild was performed.

## Reproducing the captures

Run from the desktop source root. The runner writes PNGs directly to `/tmp`
and refuses to overwrite an existing basename, so reruns need a new prefix.

```sh
QT_QPA_PLATFORM=offscreen ./build/yt-client \
  --automation-sequence /tmp/opencode/run-a-full.json
QT_QPA_PLATFORM=offscreen ./build/yt-client \
  --automation-sequence /tmp/opencode/run-b-simple.json --automation-ui simple
```

Both runs exited 0. Every event executed within 1 or 2 ms of its scheduled
`atMs`. Timing uses absolute milliseconds from startup-ready; gaps are 800 ms
between route changes, 1000 ms after opening settings, and 1000 ms around the
player so the 2000 ms `PointerWatch` chrome timeout does not fire before the
fake player screenshot.

### Run A, full UI (`--automation-ui full`, default)

```json
[
  {"type": "screenshot", "filename": "omadtr-20260911-01-feed.png", "window": "appWindow", "atMs": 600},
  {"type": "click", "target": "historyNavigationButton", "atMs": 1200},
  {"type": "screenshot", "filename": "omadtr-20260911-01-history.png", "window": "appWindow", "atMs": 2200},
  {"type": "click", "target": "feedNavigationButton", "atMs": 3000},
  {"type": "click", "target": "watchNextNavigationButton", "atMs": 3800},
  {"type": "screenshot", "filename": "omadtr-20260911-01-watchnext.png", "window": "appWindow", "atMs": 4800},
  {"type": "click", "target": "feedNavigationButton", "atMs": 5600},
  {"type": "click", "target": "settingsNavigationButton", "atMs": 6400},
  {"type": "screenshot", "filename": "omadtr-20260911-01-settings-channels.png", "window": "settingsWindow", "atMs": 7400},
  {"type": "click", "target": "settingsCategoriesTab", "window": "settingsWindow", "atMs": 8200},
  {"type": "screenshot", "filename": "omadtr-20260911-01-settings-categories.png", "window": "settingsWindow", "atMs": 9200},
  {"type": "click", "target": "settingsFeedTab", "window": "settingsWindow", "atMs": 10000},
  {"type": "screenshot", "filename": "omadtr-20260911-01-settings-feed.png", "window": "settingsWindow", "atMs": 11000},
  {"type": "click", "target": "settingsAppearanceTab", "window": "settingsWindow", "atMs": 11800},
  {"type": "screenshot", "filename": "omadtr-20260911-01-settings-appearance.png", "window": "settingsWindow", "atMs": 12800},
  {"type": "click", "target": "settingsPlaybackTab", "window": "settingsWindow", "atMs": 13600},
  {"type": "screenshot", "filename": "omadtr-20260911-01-settings-playback.png", "window": "settingsWindow", "atMs": 14600},
  {"type": "click", "target": "settingsCloseButton", "window": "settingsWindow", "atMs": 15400},
  {"type": "click", "target": "feedVideo_AUTO0000001", "window": "appWindow", "atMs": 16400},
  {"type": "screenshot", "filename": "omadtr-20260911-01-player.png", "window": "appWindow", "atMs": 17400},
  {"type": "click", "target": "playerBackButton", "window": "appWindow", "atMs": 18400}
]
```

### Run B, Simple UI (`--automation-ui simple`)

The player is opened first, while the feed route is still active, because the
Simple UI route change after opening settings leaves the feed card offscreen.
The `F` key between history and Watch Next is required for the same reason.

```json
[
  {"type": "screenshot", "filename": "omadtr-20260911-02-simple-feed.png", "window": "appWindow", "atMs": 600},
  {"type": "click", "target": "feedVideo_AUTO0000001", "window": "appWindow", "atMs": 1200},
  {"type": "screenshot", "filename": "omadtr-20260911-02-simple-player.png", "window": "appWindow", "atMs": 2200},
  {"type": "click", "target": "playerBackButton", "window": "appWindow", "atMs": 3200},
  {"type": "key_press", "key": "H", "atMs": 4000},
  {"type": "key_release", "key": "H", "atMs": 4100},
  {"type": "screenshot", "filename": "omadtr-20260911-02-simple-history.png", "window": "appWindow", "atMs": 5100},
  {"type": "key_press", "key": "F", "atMs": 5900},
  {"type": "key_release", "key": "F", "atMs": 6000},
  {"type": "key_press", "key": "W", "atMs": 6600},
  {"type": "key_release", "key": "W", "atMs": 6700},
  {"type": "screenshot", "filename": "omadtr-20260911-02-simple-watchnext.png", "window": "appWindow", "atMs": 7700},
  {"type": "key_press", "key": "C", "atMs": 8500},
  {"type": "key_release", "key": "C", "atMs": 8600},
  {"type": "screenshot", "filename": "omadtr-20260911-02-simple-settings.png", "window": "settingsWindow", "atMs": 9600},
  {"type": "click", "target": "settingsCloseButton", "window": "settingsWindow", "atMs": 10400}
]
```

## Captured files

All files live in `docs/verification/reference/`. The window column is the
actual PNG size in pixels.

| File | View | Window |
|---|---|---|
| `omadtr-20260911-01-feed.png` | Full feed, category buttons, 4-up thumbnail grid | 1180 x 780 |
| `omadtr-20260911-01-history.png` | Full history, single wide row | 1180 x 780 |
| `omadtr-20260911-01-watchnext.png` | Full Watch Next, 2 thumbnail cards | 1180 x 780 |
| `omadtr-20260911-01-settings-channels.png` | Config, Channels tab | 900 x 980 |
| `omadtr-20260911-01-settings-categories.png` | Config, Categories tab | 900 x 980 |
| `omadtr-20260911-01-settings-feed.png` | Config, Feed tab | 900 x 980 |
| `omadtr-20260911-01-settings-appearance.png` | Config, Appearance tab | 900 x 980 |
| `omadtr-20260911-01-settings-playback.png` | Config, Playback tab | 900 x 980 |
| `omadtr-20260911-01-player.png` | Fake player, full UI | 1180 x 780 |
| `omadtr-20260911-02-simple-feed.png` | Simple feed, text rows | 900 x 820 |
| `omadtr-20260911-02-simple-history.png` | Simple history, text row | 900 x 820 |
| `omadtr-20260911-02-simple-watchnext.png` | Simple Watch Next, thumbnail cards | 900 x 820 |
| `omadtr-20260911-02-simple-settings.png` | Config, Channels tab | 780 x 980 |
| `omadtr-20260911-02-simple-player.png` | Fake player, Simple UI | 900 x 820 |

The reference geometry in QML is `1180 x 780` for full (`qml/Main.qml`),
`900 x 820` for Simple (`qml/SimpleMain.qml`), `900 x 980` for full Config
(`qml/SettingsDialog.qml`), and `780 x 980` for Simple Config
(`qml/SimpleSettingsDialog.qml`). Both Config windows title themselves "Config".

### SHA-256

```
2cd2ed3f9a6a7d3a90b1a17caec3e1b3fe18b15c84ea3ff584b8046ac551b5b0  omadtr-20260911-01-feed.png
7b1f3fb4336a33c4067b7810504591e80432b5524538888807af1dd963827442  omadtr-20260911-01-history.png
b0b92b47e91ef299f959f1790d0df890df216a13979971f30dbfb0fa54cab21b  omadtr-20260911-01-player.png
80b0f4e4e08092daec8eb39fcacfeb75fa913f51daac1308f6aeb5bbe3bfcf25  omadtr-20260911-01-settings-appearance.png
0343ac92c2305693637a04d31db8574d94a81b9d470303fbda7d2494e968061c  omadtr-20260911-01-settings-categories.png
1358be3f2bbafa7efcd40b655a00e94b109dca41a3a25862f7c4577a950a3562  omadtr-20260911-01-settings-channels.png
c67412641e908e817f02cf62b66bcd8fc5ca08e0c873b112bab1b36fffac92b4  omadtr-20260911-01-settings-feed.png
6f5a201c823ae3bbf3663a5227600aa0c97b6f6776b22344684c752d91ab28d0  omadtr-20260911-01-settings-playback.png
df0cb198cddb4cbe29a9bf6aef4dc1dd637b872d376728f27c9fdb54d259727f  omadtr-20260911-01-watchnext.png
adbdb8babceece41d8e176cd17b1ea174ace5a4edbf4fd6e99767f71603aba70  omadtr-20260911-02-simple-feed.png
6d1ab38961dd545973a204723fbc43ef1a7964e60bb1f7a77d2ee80c102e9bb0  omadtr-20260911-02-simple-history.png
030155f009bd867b3c2454ed698cb2b67e1348a8a3cdba40049add525161ed15  omadtr-20260911-02-simple-player.png
c8da3077a9ef81e5ea83d1c807e3b98119d45d3dfea29ae6b7419ccd0f627d28  omadtr-20260911-02-simple-settings.png
67534f25b2e641aebae857ad1b494248b453d867af5930d464d34fe2ff1bebad  omadtr-20260911-02-simple-watchnext.png
```

## Fonts Qt resolves on this host

The app calls no `QGuiApplication::setFont` and bundles no fonts, so Qt asks
fontconfig. The QML uses `font.family: "monospace"` on most labels and leaves
other text at the platform default. Resolution from `fc-match`:

| QML family | fc-match result | Style |
|---|---|---|
| `monospace` | JetBrainsMono Nerd Font, JetBrainsMono NF | Regular |
| `sans-serif` (Qt default, not set in QML) | Liberation Sans | Regular |
| `Sans Serif` | Liberation Sans | Regular |

`JetBrainsMonoNerdFont-Regular.ttf` provides the braille spinner glyphs
(`⠋⠙⠹...`) and box-drawing characters in the header and Watch Next buttons. The
captured images preserve whatever fontconfig rendered, so they reflect this
host, not a bundled font. Both families are host-specific. An Android port
must ship a monospace font with braille and box-drawing coverage or replace
the glyphs; Liberation Sans is a desktop substitute and will not exist on a
phone.

No font mismatch against a declared project value was found. There is no
declared font list in the desktop source to compare against.

## Layout observations

Full UI, 1180 wide:
- Feed grid is 4 columns. Cards are about 272 px wide with a 16:9 thumbnail
  area. "Automation Video 1" shows a yellow 20 percent watched bar.
- History is one full-width row. The thumbnail expands to the row, roughly
  1120 by 620, with title, channel, and "last viewed" at the bottom edge and a
  yellow 20 percent bar under the title.
- Watch Next shows two cards in a 4-column grid at about 272 px each, with
  up, down, and REMOVE controls and a `WATCH NEXT (2/25)` counter. The empty
  columns to the right are visible because only two fixture entries exist.
- The header is title on the left, a long keyboard hint line in the center,
  and five icon buttons on the right (feed, history, Watch Next, config,
  refresh). The current route icon is inverted.

Full Config, 900 x 980:
- "Config" title bar with a close button.
- Five equal tabs across the top: CHANNELS, CATEGORIES, FEED, APPEARANCE,
  PLAYBACK. The active tab is bold with an underline.
- Channels: add-channel field plus Import JSON and Export JSON, then two
  subscribed-channel cards with per-category checkboxes.
- Categories: new-category field, then rows with Save and Delete.
- Feed: short-video filter with a number stepper, a YouTube Data API key
  field, a "Remember locally" checkbox, and a "Use key" button.
- Appearance: theme dropdown showing "Default", and a "Use simple UI" checkbox.
- Playback: backend dropdown showing "Embedded mpv", preferred maximum quality
  showing "Auto", and a SponsorBlock section with a category row per segment
  type.

Simple UI, 900 x 820:
- Feed is a text list, no thumbnails. Each row has a large title and a muted
  channel and date line; watched items show a yellow percentage on the right.
- History is one text row with title, channel, "last viewed", and percentage.
- Watch Next is the same thumbnail-card grid as full UI, two cards at about
  272 px in a 3-column grid at this width.
- No header navigation icons. The title sits left and the hint line center.
- Config is 780 x 980, a narrower copy of the full Config with smaller tab
  labels.

Player, both UIs:
- The automation fake player fills the window with black (the `opacity: 1.0`
  and background rectangle added in `6b9e4d5`), centers
  `Automation player AUTO0000001`, and shows a single Back button. See
  `qml/AutomationPlayer.qml`.
- `qml/PlayerControls.qml`, the real overlay with Back, title, quality
  selector, seek bar, play, and SponsorBlock segment markers, is loaded only
  by `qml/MpvPlayer.qml` and is never mounted in automation. So the fake
  player screenshots show the automation backend's own Back control, not the
  full desktop chrome. Capturing the real chrome needs libmpv and a media
  load, which the automation contract forbids.

## Narrow-phone adaptation

Aspect-to-aspect notes for a portrait phone around 360 to 430 dp wide.

- Feed grid. Four columns at 1180 and two at 620 minimum. A phone needs one
  column with full-width 16:9 thumbnails, or two columns only above about
  600 dp. The desktop page already holds at two columns down to 620 px, so
  reuse that breakpoint.
- History. The full-UI row expands its thumbnail to the full window width.
  On a phone that thumbnail would be taller than the viewport. Constrain it
  to a fixed 16:9 width and stack title, channel, and progress below.
- Watch Next. Card width is a fraction of the grid, so at 3 or 4 columns the
  cards are about 272 px. On a phone, either one column or cards sized to the
  available width with the reorder controls on their own row. The up, down,
  and REMOVE targets are small for touch.
- Config. Five tabs across 900 px become about 180 px each. At phone width
  that is under 80 px per tab, too narrow. Use a scrollable tab strip or a
  top-level settings list with the five sections as rows. The content in each
  tab is single-column and scrolls, so the inner layout already fits.
- Header. The centered hint line is long and is desktop-only keyboard help.
  The Android contract already calls for removing keyboard hints. Keep the
  title and replace the five icon buttons with touch navigation.
- Player. Full-bleed black already works on a phone. The Back control in the
  fake player is centered and small; the real `PlayerControls.qml` top bar is
  52 px tall and should map to a touch bar with safe-area insets.
- Shared. Simple UI text rows map to a phone more directly than the full
  thumbnail grid, but the contract keeps both, so the grid path needs the
  responsive work described above.

## Limitations

- Theme combinations were not scripted. The theme control is a ComboBox; the
  runner has no wheel or text entry, and the popup list items carry no
  `objectName`, so selecting rose-pine, nord, or omarchy would need a fragile
  coordinate click. All captures use the default theme.
- No live fixture exists, so live cards are absent in every image.
- The real player chrome and the mpv, WebEngine, and macOS backends are not
  captured. Automation always uses the fake player.
- Screenshots are app window contents only. No desktop, cursor, decorations,
  or compositor effects are included.
- Timestamps inside history images ("last viewed Sep 11, 2026") come
  from the fixture watch time and are not stable across days. Image hashes for
  those two files may change if recaptured on a later date.
