# Library visual parity follow-up

Targeted corrections for the defects raised in `device-verification.md`
(D2, D3) plus the font decision. Scope is the owned library UI only; the
root window-inset defect (D1) is handled separately, and `ui/settings` /
`ui/player` are untouched.

## D2 - Full feed omitted the published date

`FeedContent.FullFeedGrid` now passes `metaText = relativeTime(video.publishedAt)`
to `FullVideoCard`, the same formatter the simple feed and the desktop QML use.
The card already right-aligns the channel/meta pair, matching the desktop feed
card ("Automation Channel One" left, "Jan 1, 2026" right).

## D3 - Unwatched cards rendered "0% watched"

`watchProgressPercent` now returns `-1` when the video has no watch session,
mirroring the desktop SQL join on `video_watch_time`: a missing row yields the
sentinel and the card hides both the label and the 3 px bar. The session test is
`watchCount > 0 || watchedSeconds > 0`, so a real session that stopped at the
start still renders a legitimate `0%`. The same rule feeds the feed, history,
and Watch Next cards.

## Feed / history / Watch Next layout assessment

- Feed and Watch Next use the same `FullVideoCard` (16:9 thumbnail, title,
  channel/meta, optional watched label). On the 411 dp phone this is one column,
  which is the expected phone adaptation of the desktop 4-up grid.
- The desktop full-history page renders a single card that stretches to the
  window because each QML delegate sets `Layout.fillWidth`/`Layout.fillHeight`
  while the grid only has one row. That produced a very tall thumbnail in the
  reference capture. Android keeps the intrinsic card height (thumbnail plus
  metadata), which is the intended card content without the empty stretched
  region. No arbitrary grid normalization was applied; feed/history/Watch Next
  each keep the delegate behavior their QML source defines.
- Watch Next keeps the grid in both UIs and the `(n/25)` header.

## Fonts

The desktop declared `monospace` (JetBrainsMono Nerd Font, including the
braille spinner glyphs) and a default sans face (Liberation Sans). Android
platform `Monospace`/`Default` differ, so the exact installed faces are bundled
unmodified.

Bundled resources:

| Resource | Source | SHA-256 |
|---|---|---|
| `res/font/jetbrains_mono_nerd_regular.ttf` | `/usr/share/fonts/TTF/JetBrainsMonoNerdFont-Regular.ttf` | `1c680e8cde9fcf8b88a5605ce8d1fb94dd3fb15841f7ca7bf4c55664855e5611` |
| `res/font/jetbrains_mono_nerd_bold.ttf` | `/usr/share/fonts/TTF/JetBrainsMonoNerdFont-Bold.ttf` | `e490660ad75e0b152c93b1604c2ea1a4ea675f1b8a3fe0d005b1998568f99f1f` |
| `res/font/liberation_sans_regular.ttf` | `/usr/share/fonts/liberation/LiberationSans-Regular.ttf` | `baccc64becc3eb7d104b7c84d99f5314a0a1f896e2b3ea6c2f22fc08d2003bee` |
| `res/font/liberation_sans_bold.ttf` | `/usr/share/fonts/liberation/LiberationSans-Bold.ttf` | `769673c4355020b1e28a14c366a152da410ab6b16239fe883ebc35b73624835b` |

Only Regular and Bold are bundled for each family (about 5.9 MB total); the UI
uses no italic face. The Nerd Font copy retains JetBrains Mono's OFL metadata
and covers U+2800-U+28FF (braille) and U+2500 box drawing, which the refresh
spinner and any future chrome glyphs rely on.

Licenses are shipped at:

- `app/src/main/assets/licenses/jetbrains-mono-OFL.txt` (SIL OFL 1.1,
  Copyright 2020 The JetBrains Mono Project Authors)
- `app/src/main/assets/licenses/liberation-sans-OFL.txt` (SIL OFL 1.1,
  Copyright 2010 Google Corporation / 2012 Red Hat, Inc.)

Public entry point: `dev.omatube.app.ui.theme.OmaTypography` exposes
`mono`, `sans`, and `family(chrome)`. The library `OmaText` now resolves
through it. `ui/settings` and `ui/player` keep their own text helpers until the
coordinator asks their owners to switch to `OmaTypography`.

## Category bar (supersedes the earlier visible-All decision)

The desktop category bar contains only user categories, and
`AppController::selectCategory` (`cpp/src/appcontroller.cpp:630-637`) toggles a
tapped active category back to unfiltered. Android now matches: there is no
visible "All" chip. `ALL_CATEGORY_ID = 0` remains an internal unfiltered
sentinel, and the category chip callback is
`toggledCategorySelection(selectedCategoryId, tappedId)` so a repeat tap on the
active category emits `0`. Drag reorder already excludes the sentinel because
it no longer exists as a chip. This supersedes the earlier implementation
contract decision to show a local All chip; the desktop source wins.

Test tags are unchanged except `categoryButton_0` no longer exists.

## Simple row middot

`SimpleVideoRow` now renders the desktop meta line as
`channel` + 6 dp gap + `·` (12 sp, muted) + 6 dp gap + meta text. The simple
history row passes `"last viewed <timestamp>"` without an embedded dot so it
uses the same row; the full-UI history card keeps its inline `" · last viewed"`
prefix. No other styling changed.

## Tests

- `LibraryLogicTest.watchProgressPercent_hidesUnwatchedButShowsRealZeroSession`
  covers the unwatched sentinel and a real 0% session.
- `LibraryLogicTest.watchProgressPercent_matchesDesktopQuery` covers unknown
  duration and the clamp.
- `LibraryScreenTest.feedCardsShowPublishedDateAndHideUnwatchedProgress`
  asserts the full feed shows the published date and "20% watched" while an
  unwatched card renders no "0% watched".
