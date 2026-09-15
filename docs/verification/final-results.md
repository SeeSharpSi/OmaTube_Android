# Android OmaTube Final Verification

Fresh offline verification of current branch `feature/background-playback-quality-ui`.
App source and app tests were not modified during verification. Existing
uncommitted feature work was preserved.

## Scope

Verified current background-playback/player quality work, manifest service
declaration, settings parity, full/simple library UI, Watch Next title rows and
count behavior, History edges, navigation geometry, category filtering, player
quality labels, seeking, rotation, and automation network isolation.

## Device and Toolchain

| Item | Value |
|---|---|
| Device | `emulator-5580`, private `omatube_test` AVD, API 36, x86_64 |
| Display | 1080x2400, density 420 dpi, 128 px cutout, 63 px status bar |
| JDK | Private JDK 21.0.12.1 under `.tooling/jdk` |
| Android SDK | Platform 36, Build Tools 36.0.0, platform-tools 37.0.1 |
| Gradle | 8.13 |

Only `emulator-5580` was used. Other devices were not touched.

## Build Results

| Command | Result |
|---|---|
| `./bin/build` | PASS, `BUILD SUCCESSFUL` |
| `./bin/test` | PASS, `BUILD SUCCESSFUL` |
| `./gradlew --console=plain :app:assembleDebugAndroidTest` | PASS with private toolchain environment, `BUILD SUCCESSFUL` |
| `./gradlew --console=plain :app:assembleRelease` | PASS with private toolchain environment, `BUILD SUCCESSFUL` |

Bare direct `gradlew` invocation initially lacked `JAVA_HOME`; rerun with
private `.tooling` JDK/SDK environment passed. No source change was needed.

JVM XML result: **216 tests, 0 skipped, 0 failures, 0 errors**, aggregate
reported test time **10.205 s**.

## Offline Instrumentation

Installed both APKs with `adb -s emulator-5580 install -r -t`, then ran:

```sh
adb -s emulator-5580 shell am instrument -w -r \
  dev.omatube.app.test/androidx.test.runner.AndroidJUnitRunner
```

Result: **OK (78 tests)**, duration **100.208 s**, 0 failures. Two tests were
skipped by design: `NewPipeBackendNetworkSmokeTest` and
`NativePlaybackNetworkSmokeTest`. Both opt-in gates remained disabled; no
network or real playback smoke ran.

## Device UI Driver

Command:

```sh
DEVICE=emulator-5580 docs/verification/run-device-checks.sh \
  docs/verification/android-final
```

Result: **35 PASS, 0 FAIL** in `docs/verification/android-final/results.txt`.
Driver refreshed **31 screenshots**. It used automation extras, in-memory Room,
and fake backend. It did not start `PlaybackService`, fetch network, load media,
or post notifications. App logcat audit passed: no network/extractor tokens and
no fatal exceptions.

Three stale Watch Next count checks were updated in
`docs/verification/run-device-checks.sh`: current full UI exposes count as
separate bottom-bar text `(n/25)`, while simple UI uses title rows.

## Manifest Audit

Installed debug APK inspection confirmed:

- `dev.omatube.app.player.PlaybackService`
- `exported=false`
- `foregroundServiceType=mediaPlayback`
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PLAYBACK` requested and granted
- `POST_NOTIFICATIONS` requested; not granted on fresh emulator

This confirms installed declaration only. Service runtime behavior and
notification controls were not directly exercised.

## Residual Gaps

- Real screen-off playback, foreground notification controls, and background
  Activity transitions were not exercised because network/media smoke is opt-in
  and forbidden for this verification.
- SponsorBlock rendered segments are absent from offline fixture.
- Release APK is unsigned, as expected.

## Verification Artifacts

Changed verification files:

- `docs/verification/run-device-checks.sh`
- `docs/verification/final-results.md`
- `docs/verification/android/library-visual-parity.md`
- `docs/verification/playback-smoke.md`
- `docs/verification/android-final/results.txt`
- 31 refreshed PNGs under `docs/verification/android-final/`

`git diff --check`: PASS.
