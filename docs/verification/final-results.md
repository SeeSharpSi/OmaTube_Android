# Android OmaTube — final closure verification

Final closure verification of the Android port at
`/home/cassian/projects/android/OmaTube` after the visual-parity corrections
(desktop player chrome, category filter semantics, DataStore shutdown) and the
Media3 `Range` transport fix. This run rebuilt from the current source, ran the
full JVM suite, installed the current APKs on the isolated emulator, ran the
default offline instrumentation suite, exercised the updated touch driver, and
captured fresh screenshots. It is not a summary of earlier agents' output.

## Scope, device and toolchain

| Item | Value |
|---|---|
| Device | `emulator-5580`, private headless AVD, `pixel_6`, API 36 |
| ABI / model | `sdk_gphone64_x86_64` (`emu64xa`), x86_64 |
| Screen | 1080 x 2400 px, density 420 dpi, 128 px top display cutout, 63 px status bar |
| JDK | Eclipse Temurin 21.0.12.1+1 (`.tooling/jdk`) |
| Android SDK | build-tools 36.0.0, platform android-36, platform-tools 37.0.1 |
| Gradle / AGP / Kotlin | 8.13 / 8.13.0 / 2.2.20, KSP 2.2.20-2.0.4 |
| Java tmpdir | `.tooling/tmp` (`bin/test`/`bin/build` now set it automatically), not `/tmp` |
| Second emulator | `emulator-5582` was already shut down and was not touched |

## Changes verified in this closure

- `ui/player/PlayerChrome.kt`: square 10 dp ink/accent handles with a 1 px
  border, correct SponsorBlock overlay paint order (track, progress, segments,
  handle), natural-case `Default`/`Auto`/`720p` labels, an eight-by-five chevron,
  a popup as wide as the dynamically sized selector, DVR seek enabled whenever
  duration is positive, and `playerSeekBar`/`playerVolumeSlider`/
  `playerQualitySelector` test tags.
- `ui/library/CategoryBar.kt`, `LibraryLogic.kt`, `VideoCards.kt`: no visible
  "All" chip; the unfiltered state is the internal `ALL_CATEGORY_ID = 0`
  sentinel, and tapping the active user category toggles back to it. Simple-row
  separator is a middle dot.
- `AppGraph.kt`: bounded (`withTimeoutOrNull`, 2 s) `DataStoreSettingsStore`
  cancellation handshake on `close()`, plus API-key recovery notices surfaced
  through `graph.errors`. Two integration tests cover reopen-after-close and the
  recovery notice.
- `player/PlaybackDataSources.kt`: Media3 `Range` header instead of the `range`
  query parameter for YouTube DASH (regression guards in the unit suite). The
  real playback proof predates this closure and is recorded in
  [playback-smoke.md](playback-smoke.md); it was not rerun because the playback
  engine and backend are unchanged.

## Mechanical fix made during verification

`app/src/test/java/dev/omatube/app/AppIntegrationTest.kt` called the `suspend`
`DataStoreSettingsStore.closeAndJoin()` outside a coroutine at two sites
(`closeReleasesProductionIsolatedSettingsFileForReopen` and
`recoveryNoticeSurfacesThroughGraphErrors`), which failed compilation. Both calls
are now inside `runBlocking` (the second wraps the seed update and the join in
one block). No assertion was changed or weakened.

## Build results

| Command | Result |
|---|---|
| `./bin/build` | `BUILD SUCCESSFUL` (debug up to date with current source) |
| `./bin/test` | `BUILD SUCCESSFUL` — **185 tests, 0 failures, 0 errors, 0 skipped** |
| `./gradlew :app:assembleDebugAndroidTest` | `BUILD SUCCESSFUL` |
| `./gradlew :app:assembleRelease` | `BUILD SUCCESSFUL in 1m 8s` (R8, resource shrink, lintVital) |

Unit test count rose from the previous revision's 182 to 185 after the two
`AppIntegrationTest` shutdown/recovery tests and related additions. The code
compiles cleanly; the only retained warning is the pre-existing
`ApiKeyCrypto.kt:41` supertype parameter-name warning. Lint was not rerun in this
closure because the task scope was build, unit, instrumentation and release.

## APK artifacts (current)

| Artifact | Size (bytes) | SHA-256 |
|---|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | 23 260 266 | `cc0fd21d0296497dad7c574e4b32e3a21f9eb488d80df2e72a4fcd6c2250853e` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 1 105 525 | `d0b93822c0eb37eb2c380e574896ae4fdae1e922c7ac03951461c1da5b891300` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | 7 018 349 | `cd1a82fc9ef43fd087af4329f84b366b7277aacefbf7beffa044f9d3b2a2f174` |

Signing: the debug APK uses the standard Android Debug certificate
(`DN: C=US, O=Android, CN=Android Debug`, SHA-256
`e2f1bb0fc2d1842074450be4ad817fc69ea08a227dadbbba88b020f078dfea38`); the
release APK is unsigned (`DOES NOT VERIFY`, missing `META-INF/MANIFEST.MF`), as
expected. `minSdkVersion` is still `26` and `targetSdkVersion` still `36` for
both debug and release; no manifest, persistence/SQL or API-level change was
introduced, so the earlier minSdk 26 compatibility findings
([min-sdk-results.md](min-sdk-results.md)) remain valid.

## On-device offline instrumentation

Both current APKs were installed with `adb install -r -t` and the default suite
run without any opt-in argument:

```sh
adb -s emulator-5580 shell am instrument -w -r \
  dev.omatube.app.test/androidx.test.runner.AndroidJUnitRunner
```

Result: **`OK (29 tests)`, `Time: 38.378`, 0 failures, 2 skipped.** Breakdown:

- `RoomLibraryRepositoryInstrumentedTest` (1): real device SQLite via Room.
- `CachePruningInstrumentedTest` (1): on-disk cache pruning.
- `DataStoreSettingsStoreInstrumentedTest` (3): production DataStore + Keystore.
- `LibraryScreenTest` (9): UI, including the new `tappingActiveCategoryClearsFilterToSentinel`
  and `tappingOtherCategorySelectsIt`.
- `SettingsScreenTest` (9).
- `PlayerChromeTest` (4): mixed-case selector label, popup `720p` selection and
  reporting, and `playerSeekBar`/`playerVolumeSlider` tags.
- `NewPipeBackendNetworkSmokeTest` (1) and `NativePlaybackNetworkSmokeTest` (1):
  both correctly **skipped** by their `assumeTrue` opt-in gates
  (`AssumptionViolatedException`, `INSTRUMENTATION_STATUS_CODE: -4`). They are
  counted in the 29 and reported OK/ignored, not failed.

The opt-in gates were not flipped in this closure. The playback/extraction
proofs are recorded in [playback-smoke.md](playback-smoke.md).

## Device UI verification

Driver: `docs/verification/run-device-checks.sh`, run as

```sh
DEVICE=emulator-5580 docs/verification/run-device-checks.sh \
  docs/verification/android-final
```

It installs/builds nothing and uses only debug automation extras, the in-memory
fixture and the fake backend. Output: `docs/verification/android-final/results.txt`
and 31 screenshots. **34 `PASS` lines, 0 `FAIL` lines.**

### Driver selectors updated for the current UI

- Category chips are found by their accessible content description
  (`Category N Name`), not by a text label or coordinate.
- The player seek target is derived from the `android.widget.SeekBar`
  accessibility bounds (70% across the track); the old fixed `(750, 2198)` is
  gone. Measured bounds `[244,2169][943,2268]`.
- The quality selector is found by its mixed-case `Default` label; the popup row
  for `720p` is measured from a screenshot (a uiautomator dump dismisses the
  focusable Compose Popup) and selected by index in `PlaybackQuality.OPTIONS`
  order.
- Square handles are asserted by detecting each 10 dp handle in the paused
  screenshot and checking its bounding box is square.
- Config close X, feed date/watched labels, config tab-strip scroll, rotation and
  the app-PID logcat audit remain as before.
- The category-reset assertion scrolls the grid to the top before checking,
  because the grid preserves the previous first-visible item across the filter
  change; the first attempt failed for that reason and the assertion was
  corrected (the toggle itself is correct).

### Parity assertions

- `PASS no visible All category chip`; `PASS user category chip exposed by
  content description`; `PASS category filter shows Tech channel` (Tech channel
  videos 4 and 5); `PASS active category tap resets to unfiltered sentinel`.
- `PASS full feed shows published date`; `PASS watched card shows progress`;
  `PASS unwatched card hides 0% label`.
- `PASS quality selector shows mixed-case Default`, `PASS no uppercase DEFAULT
  label`; after selection `PASS quality selector selects mixed-case 720p`,
  `PASS no uppercase 720P label` (see `full-player-quality-open.png`,
  `full-player-quality-720p.png`).
- `PASS seek handle square 24x23px`, `PASS volume handle square 24x24px`; the
  visual crops confirm the 10 dp square handles and the selector chevron.
- `PASS player seek moves position` (`2:00` -> `6:59`), `PASS player rotation
  portrait -> landscape` (2400x1080) and `PASS player rotation landscape ->
  portrait` (1080x2400).
- `PASS no network/extractor tokens in app logcat`, `PASS no fatal exceptions in
  app logcat`.

### Fonts

The debug APK still packages `res/font/jetbrains_mono_nerd_regular.ttf`,
`jetbrains_mono_nerd_bold.ttf`, `liberation_sans_regular.ttf` and
`liberation_sans_bold.ttf`, with the OFL/MIT licenses under `assets/licenses/`.
No font resource changed in this closure.

## Failures, skips and genuine limitations

- Final state: no build, unit, release, offline instrumentation or device-driver
  failures.
- Two opt-in instrumentation tests are skipped by design in the default suite
  and were not re-enabled in this closure; the playback/extraction proof is the
  earlier authorized run recorded in [playback-smoke.md](playback-smoke.md).
- SponsorBlock overlay bars cannot be screenshot-tested here: the automation
  fixture has no sponsor segments. Paint-order changes are only covered by code
  review and the player chrome Compose tests, not by a rendered-segment test.
- No pixel-identical claim against desktop. Parity is asserted for casing,
  geometry class (square handles, selector/popup width relationship, chevron),
  colors, labels and behavior.
- Only `emulator-5580` (API 36, 1080x2400, density 420) was exercised. Landscape
  was exercised on the player only; other routes are portrait in this run.
- minSdk 26 compatibility evidence is from the earlier revision and is unaffected
  by this closure (no SQLite/Room/manifest change; the transport fix is an HTTP
  data-source change).
- The release APK is unsigned; no signing credentials exist beyond the debug key.
- SAF import/export remains covered at the callback level only.

## Logs

| Log | Contents |
|---|---|
| `.tooling/logs/11-build.log` | closure debug build |
| `.tooling/logs/12-unit-tests.log` | closure unit suite (185 tests) |
| `.tooling/logs/13-androidtest-release.log` | instrumentation + release build |
| `.tooling/logs/14-instrumentation-offline.log` | closure offline suite (OK, 29 tests, 2 skipped) |
| `.tooling/logs/15-device-checks.log` | driver run before the category-assertion correction |
| `.tooling/logs/16-device-checks.log` | final driver run (34 PASS, 0 FAIL) |
| `docs/verification/android-final/results.txt` | final driver PASS/measure lines |
| `docs/verification/android-final/*.png` | 31 current screenshots (sources overwritten intentionally) |

## Reproduction

```sh
cd /home/cassian/projects/android/OmaTube
. .tooling/env.sh
./bin/build
./bin/test
./gradlew --console=plain :app:assembleDebugAndroidTest :app:assembleRelease
adb -s emulator-5580 install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5580 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5580 shell am instrument -w -r \
  dev.omatube.app.test/androidx.test.runner.AndroidJUnitRunner
DEVICE=emulator-5580 docs/verification/run-device-checks.sh \
  docs/verification/android-final
```
