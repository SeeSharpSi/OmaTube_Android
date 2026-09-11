# Android OmaTube device verification

Independent verification of the Android port at `/home/cassian/projects/android/OmaTube`
on an isolated emulator. No source file was edited for this run. The APKs under
test were already built; no Gradle or build command was run.

## Device and build under test

| Item | Value |
|---|---|
| Device | `emulator-5580`, private headless API 36 AVD |
| Model / ABI | `sdk_gphone64_x86_64` (`emu64xa`) |
| Android SDK | 36 |
| Screen | 1080 x 2400 px, density 420 dpi (scale 2.625) |
| Status bar | 63 px |
| Display cutout | 128 px top (center camera cutout) |
| Navigation bar | 63 px bottom |
| App APK | `app/build/outputs/apk/debug/app-debug.apk`, sha256 `003415e08b9c70ceebf791f281f9766ab902f9b2e68d1818914a2e19cef83f7d` |
| Test APK | `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`, sha256 `b6dece978abad5d1053b022a73a02ff5005a51c8f39806c587b58516a9a4ff87` |
| App package | `dev.omatube.app`, versionName 0.1.0, versionCode 1, minSdk 26, targetSdk 36 |
| Test package | `dev.omatube.app.test` |
| Runner | `androidx.test.runner.AndroidJUnitRunner` |
| Screenshots | 1080 x 2400 portrait, 2400 x 1080 landscape |

All launches used the debug automation extras so the app runs on the in-memory
Room fixture and the fake backend. `MainActivity.onCreate` reads the extras and
installs the automation graph before creating the ViewModel, and
`OmaTubeApplication` builds the production graph lazily, so no production
backend, extractor, WebView or network stack is constructed
(`MainActivity.kt:56-72`, `OmaTubeApplication.kt:26-41`).

## Instrumentation results

Installed both APKs with `adb install -r -t` and ran:

```sh
adb -s emulator-5580 shell am instrument -w -r \
  dev.omatube.app.test/androidx.test.runner.AndroidJUnitRunner
```

Result: **OK (15 tests)**, `Time: 23.171`. All tests passed, no failures, no
errors, no ignored tests.

- `dev.omatube.app.ui.library.LibraryScreenTest` - 6 tests: grid + categories,
  nav callbacks, simple text rows, long-press add, long-press history delete,
  Watch Next move/remove.
- `dev.omatube.app.ui.settings.SettingsScreenTest` - 9 tests: five tabs, tab
  control reveal, immediate simple-UI toggle, theme dropdown, SponsorBlock
  gating, category/channel confirm dialogs, error dismiss + close.

Note: `am instrument -e package dev.omatube.app.test ...` runs zero tests because
the test classes live under `dev.omatube.app.ui.*`. Run without the `package`
filter. No test selector or assertion needed fixing; the existing suite passed
as built.

## Automated route and interaction checks

Driver: `docs/verification/run-device-checks.sh`. It installs nothing, builds
nothing, and drives only the isolated emulator through adb, launch extras and
`uiautomator`. Machine output is in `results.txt`; every line is `PASS` or an
`INFO`/`measure` line. Run with `DEVICE=emulator-5580` (default).

Verified with screenshots and node assertions:

| Area | Result |
|---|---|
| Full feed / history / Watch Next / Config routes | pass |
| Config five tabs incl. Playback (after horizontal scroll) | pass |
| Theme picker open + select (Default, Rose Pine, Nord) | pass |
| Feed rendered under default, rose-pine, nord themes | pass |
| Player fake surface, title, BACK, quality picker, 720p select | pass |
| BACK from player and from history returns to feed | pass |
| Watch Next 2/25 -> move -> remove -> 1/25 | pass |
| Long-press feed card adds to Watch Next (3/25) | pass |
| Simple UI feed / history / Watch Next / Config / player | pass |
| Simple-UI toggle in Appearance, then back to simple feed | pass |
| Player rotation portrait -> landscape -> portrait | pass |
| Category filter (Automation Music) on feed | pass |
| App logcat: no network/extractor tokens, no fatal exceptions | pass |

The `app` logcat audit is scoped to the app PID (`adb logcat -d --pid=`), so the
earlier unfiltered matches from Gboard (`gstatic.com`, `dl.google.com`) do not
implicate the app. With the automation fake backend the app opens no media and
requests no images; thumbnails render the `OMA / TUBE` placeholder
(`VideoCards.kt:59-66`).

## Screenshots

All in `docs/verification/android/` (1080 x 2400 unless noted).

| File | View |
|---|---|
| `full-feed-default.png` | Full feed, All/Music/Tech categories |
| `full-feed-rose-pine.png` | Feed, rose-pine |
| `full-feed-nord.png` | Feed, nord |
| `full-feed-category-music.png` | Feed filtered to Automation Music |
| `full-history-default.png` | History card |
| `full-history-back-feed.png` | Feed after BACK from history |
| `full-watchnext-default.png` | Watch Next 2/25 |
| `full-watchnext-moved.png` | After Move down (Video 4 now #1) |
| `full-watchnext-removed.png` | After Remove (1/25) |
| `full-watchnext-after-add.png` | Long-press add result (3/25) |
| `full-settings-channels.png` | Config, Channels |
| `full-settings-categories.png` | Config, Categories |
| `full-settings-feed.png` | Config, Feed |
| `full-settings-appearance.png` | Config, Appearance |
| `full-settings-playback.png` | Config, Playback (tab strip scrolled) |
| `full-settings-theme-open.png` | Theme dropdown open |
| `full-settings-theme-nord.png` | After selecting Nord |
| `full-settings-simple-toggle-on.png` | Use simple UI checked |
| `full-toggle-back-simple-feed.png` | Simple feed after toggle + back |
| `full-player-chrome.png` | Player chrome (paused) |
| `full-player-quality-open.png` | Quality popup open |
| `full-player-quality-720p.png` | After selecting 720p |
| `full-player-landscape.png` | Player landscape (2400 x 1080) |
| `full-player-portrait-again.png` | Player back to portrait |
| `full-player-back-feed.png` | Feed after BACK |
| `simple-feed-default.png` | Simple text feed |
| `simple-history-default.png` | Simple history row |
| `simple-watchnext-default.png` | Simple Watch Next cards |
| `simple-settings-channels.png` | Simple Config |
| `simple-player-chrome.png` | Simple player chrome (paused) |

## Defects

Severity is relative to a normal user on this class of device.

### D1 (load-bearing) - App ignores window insets; header controls sit under the 128 px cutout/status-bar region

The Activity is edge-to-edge under targetSdk 36 but nothing consumes
`WindowInsets`, and no `enableEdgeToEdge`/`statusBarsPadding` call exists
(`MainActivity.kt:56-72`; `LibraryScreen.kt:72-99`; `SettingsControls.kt:536-540`).
This device has a 128 px top display cutout and a status-bar window that occupies
the full top 128 px. The system bar window takes touches there and draws over the
app.

Observed consequences:

- Feed navigation buttons are laid out at y 45-171 (`Show Watch Next` bounds
  `[552,45][673,171]`), so their centers (y 108) are inside the dead region.
  - Repro: `--automation_route feed`; `adb shell input tap 612 108` -> stays on
    Feed; `adb shell input tap 612 150` -> navigates to Watch Next.
- Config close button bounds `[930,19][1056,145]`, center y 82. Tapping its
  center does not close; tapping `1006 130` closes. The visible X is largely
  non-functional.
  - Repro: `--automation_route settings`; `adb shell input tap 993 82` -> still
    Config; `adb shell input tap 1006 130` -> feed.
- Titles draw under the status bar: Config title bounds `[63,56][239,108]`,
  Feed title `[53,77][177,139]`. The clock overprints "Config" and the battery
  icon overprints the close button (see `full-settings-theme-open.png`,
  `full-feed-nord.png`).

Impact: top-row touch targets are reduced to a ~17-43 px sliver, and the header
is visually colliding with system UI. Fix by applying the safe top inset to the
library header, the Config header, and by consuming `WindowInsets` in the
Activity.

### D2 (visual parity) - Full-UI feed cards omit the published date

`FeedContent.kt:169-179` builds `FullVideoCard` without `metaText`, while the
simple feed passes `relativeTime(video.publishedAt)` (`FeedContent.kt:230`).
The desktop full feed shows the date at the right of the channel line
(`docs/verification/reference/omadtr-20260911-01-feed.png`). Android full feed
shows title / channel / watched label only (`full-feed-default.png`). The card
already supports `metaText` (`VideoCards.kt:162-170`).

### D3 (visual parity) - Unwatched cards render a "0% watched" label

`watchProgressPercent` returns 0 for any positive-duration video with zero
position, and only -1 when duration is unknown (`LibraryLogic.kt:40-44`). The
card prints the label whenever `percent >= 0` (`VideoCards.kt:173-180`), so every
unwatched video shows "0% watched" in the feed and Watch Next. The desktop
reference shows the watched label only on watched items. This affects every
unwatched card.

### D4 (minor visual) - Status-bar icon color is not theme-aware

`themes.xml` hardcodes `android:windowLightStatusBar=true` and a cream status
bar; nothing updates it per theme. Nord/rose-pine use dark backgrounds while the
status-bar icons stay dark, so the clock and icons are dark-on-dark and hard to
read (`full-feed-nord.png`, `full-settings-theme-nord.png`). The player hides the
bars, so it is unaffected.

### D5 (minor UX) - Config tab strip clips PLAYBACK with no scroll affordance

`SettingsTabs` uses a horizontal scroll with `minTab = 96.dp`
(`SettingsControls.kt:397-457`), so the fifth tab is initially offscreen (only
~31 px of `PLAYBACK` visible; `full-settings-channels.png`). It works once
swiped, but there is no indicator that the row scrolls. The desktop-narrowing
notes in `desktop-reference.md` anticipated a scrollable strip or section list;
this is the scrollable variant without a cue.

### D6 (informational) - First player open shows Android's immersive-mode education overlay

On the first immersive entry per app process the platform "Viewing full screen"
card covers the top chrome for a few seconds. Platform behavior, not app code;
the driver dismisses it before capturing. Not a defect, recorded for
reproducibility (it appeared on the first exploratory player launch and on the
first run of each fresh process during testing).

## Differences that are intentional or not defects

- History thumbnail: Android uses a fixed 16:9 card and stacks metadata
  (`VideoCards.kt:117-121`), matching the phone-adaptation guidance in
  `desktop-reference.md`; the desktop history row expands its thumbnail to a very
  tall full-window image. Not a regression.
- Simple UI still exposes the five touch navigation buttons
  (`LibraryScreen.kt:185-264`). The desktop Simple UI had no header icons because
  desktop used keyboard shortcuts; the Android contract requires touch navigation,
  so this is an intentional divergence.
- Player quality button shows `DEFAULT` with a `Default` entry and a separate
  `Auto` entry (`PlaybackQuality.kt:21-31`, `PlayerChrome.kt:296`). The desktop
  `PlayerControls.qml` is never mounted in desktop automation, so there is no
  captured desktop chrome to compare against; recorded as unverified rather than
  a mismatch.
- Full-UI cards place title/channel/watched below a 16:9 thumbnail at one column
  on this 411 dp-wide screen (`full-feed-default.png`); the desktop 4-up grid is
  not expected on a phone.
- Fonts: titles use Compose's default sans face for library text while desktop
  declared `/usr` monospace (JetBrainsMono). No pixel-parity claim is made; the
  monospace/box-drawing glyph concerns from `desktop-reference.md` do not apply
  because the Android placeholders use plain text `OMA / TUBE`.

## Limitations

- Only `emulator-5580` (default phone AVD) was used. No tablet, foldable, or
  alternate density was exercised.
- Automation only: the fake backend and in-memory fixture were used. Real
  NewPipeExtractor, PoToken, ExoPlayer media loading, live streams, and SponsorBlock
  network fetches were not exercised, per the automation contract.
- SAF import/export was not driven end-to-end; it was left alone to avoid scope
  changes. The Settings screen wires the launchers, and `SettingsScreenTest`
  verifies the stored callbacks.
- No pixel-parity claim against desktop; comparisons are color, type scale,
  spacing, thumbnail proportions, control visibility and layout only.
- Screenshot timestamps in history text come from the fixture watch time and may
  differ on another day.

## Reproducing

```sh
export PATH="$PATH:/home/cassian/projects/android/OmaTube/.tooling/android-sdk/platform-tools"
adb -s emulator-5580 install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5580 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5580 shell am instrument -w -r \
  dev.omatube.app.test/androidx.test.runner.AndroidJUnitRunner
docs/verification/run-device-checks.sh
```
