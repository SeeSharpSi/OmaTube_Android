# Video tap-to-open fix

Regression evidence for the user-reported bug where tapping a feed card did
nothing. Captured on the private AVD `emulator-5580` (API 36, x86_64) with the
in-memory automation fixture and fake backend. No network, real media, user
database or unrelated device was touched.

The document contains two passes: the implementer pass that landed the fix and
its instrumentation tests, and an independent verification pass that rebuilt the
deliverables and drove the app with real `adb shell input tap` coordinates. The
whole-project closure results stay in
[`final-results.md`](final-results.md); this file only covers the tap fix and
does not restate or rerun that closure.

## Root cause

`app/src/main/java/dev/omatube/app/ui/library/VideoCards.kt` chained two
competing pointer modifiers on the same card node:

1. `clickable(...)` for the normal tap, followed by
2. `pointerInput { detectTapGestures(onLongPress = ...) }` for the long press.

With both modifiers on one node, a real touch sequence no longer produced the
`onOpen` callback, so a tap did nothing. The semantics `performClick()` action
does not go through the pointer pipeline, so earlier tests that used it still
observed a click and missed the defect.

## Fix

Replaced both modifier pairs (in `FullVideoCard` and `SimpleVideoRow`) with a
single `combinedClickable(interactionSource = interaction, indication = null,
onLongClick = onLongPress, onClick = onOpen)`. This preserves the existing
interaction source and pressed state, `Role.Button`, `contentDescription`, the
no-ripple indication and the modifier order. Only the now-unused
`clickable`, `detectTapGestures` and `pointerInput` imports were removed.

## Tests

`app/src/androidTest/java/dev/omatube/app/ui/library/LibraryScreenTest.kt`

- Pointer taps (`performTouchInput { click() }`) on the full feed card, simple
  feed row, full/simple history card and Watch Next card assert the exact
  `Video` and a single `onOpenVideo` call.
- Long press still adds to Watch Next without opening, in both UIs.
- Watch Next queue up/remove controls do not open the video.
- A vertical drag cancels the click; the next tap opens.
- The accessible content description is preserved.

`app/src/androidTest/java/dev/omatube/app/VideoTapNavigationTest.kt`

- End-to-end `MainActivity` run through the `automation=true` extras for both
  `automation_ui=full` and `automation_ui=simple`. A pointer tap on
  `feedVideo_AUTO0000001` must show the fake player (`Automation player
  AUTO0000001`), and Back must return to the feed. It is never launched with
  `automation_route=player`, which would bypass the tap path.

Coverage note: the tap tests call `performTouchInput { click() }`, which injects
a real pointer down/up sequence through the Compose test input dispatcher. They
do not use the semantics `performClick()` action. The independent pass below
additionally drives the app with `adb shell input tap` at coordinates taken from
the uiautomator hierarchy. The load-bearing evidence for this regression is the
pointer sequence, not the semantic action.

## Results

Implementer pass (`LibraryScreenTest` before/after fix) plus the independent
re-run:

| Run | Before fix | After fix (implementer) | Independent re-run |
| --- | --- | --- | --- |
| `LibraryScreenTest` | RED: run 19, failures 6 (incl. full and simple feed tap) | GREEN: OK (19 tests) | GREEN: OK (19 tests) |
| `VideoTapNavigationTest` | n/a (added after fix) | GREEN: OK (2 tests) | GREEN: OK (2 tests) |
| Full offline instrumentation | n/a | OK (41 tests) | OK (41 tests): 39 passed, 2 assumption-skipped |
| `./bin/build` | n/a | BUILD SUCCESSFUL | BUILD SUCCESSFUL |

The full offline run reports `OK (41 tests)`. The breakdown is 39 tests with
status code `0` and 2 tests with status code `-4` (JUnit
`AssumptionViolatedException`): `NewPipeBackendNetworkSmokeTest` (needs
`-e omatubeNetworkSmoke true`) and `NativePlaybackNetworkSmokeTest` (needs
`-e omatubePlaybackSmoke true`). Both were skipped by design.

Raw implementer output is in `.tooling/logs/red-libraryscreen.txt`,
`.tooling/logs/green-libraryscreen.txt`, `.tooling/logs/green-videotapnav.txt`
and `.tooling/logs/full-instrumentation.txt`. Independent output is in
`docs/verification/video-tap-fix/`.

## Independent verification pass

Rebuilt from current source, installed the fresh APKs, and drove the app with
`adb shell input tap` (never a semantic action) at card bounds read from a
`uiautomator dump`.

- Physical card taps, six route/UI combinations, each locating the visible card
  by its content description, tapping the center of the thumbnail/title region,
  asserting `Automation player AUTO000...`, then `keyevent 4` (Back) returning
  to the route: `full feed`, `simple feed`, `full history`, `simple history`,
  `full watchnext`, `simple watchnext`. 6/6 passed.
- Physical Watch Next queue controls (siblings outside the clickable card area):
  move down, move up and remove each changed the queue without opening the
  player. 3/3 passed.
- Long press on a feed card added to Watch Next without opening, full and
  simple. 2/2 passed.
- A vertical swipe in the feed did not open the player, full and simple. 2/2
  passed.

Total 13/13 physical checks. Evidence:
`docs/verification/video-tap-fix/physical-results.txt` (machine output),
`docs/verification/video-tap-fix/full-feed-tap-player.png` (screen after a
physical full-feed tap), and the three instrumentation transcripts in the same
directory.

### Independent findings

- The fix is confirmed under real pointer input on every route and UI, not only
  under Compose test input.
- No production defect was found in the fix; no production source was changed
  during this pass.
- The debug and instrumentation APKs that were present when this pass began
  predated the 18:03 source edits (debug 23,260,266 bytes, androidTest
  1,121,069 bytes) even though Gradle reported the tasks up to date. Both, and
  the release APK, were regenerated from current source before verification;
  the hashes below are for the regenerated files.

## Build deliverables

Built from the current working tree on the private toolchain.

| Artifact | Bytes | SHA-256 |
| --- | --- | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | 22,800,487 | `a195871bfd2821bc0a3180c5b174849d0365969cbb5b34f0b6c197935e2e6a7d` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 1,088,954 | `8a7bc9c5fa2c8d8c600fd47c1513f84599a049f5c7f4ba71aaea5e273ba92940` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | 7,018,349 | `2de61bc9d7da3f0a13c262c28e1637f228a154de4e9c5245be69c89d4f10929e` |

## Commands

Build:

```sh
./bin/build
. .tooling/env.sh && ./gradlew --console=plain :app:assembleDebugAndroidTest
. .tooling/env.sh && ./gradlew --console=plain :app:assembleRelease
```

Install and instrument on the private serial:

```sh
adb -s emulator-5580 install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5580 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

adb -s emulator-5580 shell am instrument -w -r \
  -e class dev.omatube.app.ui.library.LibraryScreenTest \
  dev.omatube.app.test/androidx.test.runner.AndroidJUnitRunner

adb -s emulator-5580 shell am instrument -w -r \
  -e class dev.omatube.app.VideoTapNavigationTest \
  dev.omatube.app.test/androidx.test.runner.AndroidJUnitRunner

adb -s emulator-5580 shell am instrument -w -r \
  dev.omatube.app.test/androidx.test.runner.AndroidJUnitRunner
```

Physical tap pattern (bounded to the private serial):

```sh
adb -s emulator-5580 shell am start -n dev.omatube.app/.MainActivity \
  --ez automation true --es automation_ui full \
  --es automation_route feed --es automation_theme default
adb -s emulator-5580 shell uiautomator dump /sdcard/window_dump.xml
adb -s emulator-5580 exec-out cat /sdcard/window_dump.xml   # read card bounds
adb -s emulator-5580 shell input tap <centerX> <centerY>
adb -s emulator-5580 shell input keyevent 4
```

A long press uses `input swipe x y x y 1000`; a scroll uses
`input swipe x y x <y-700> 300`.

## Known pre-existing environment issue

`./bin/test` (the JVM unit suite) aborts with a native `SIGBUS` in Robolectric's
native runtime while `AppIntegrationTest` runs Room disk IO. It was observed
twice and reproduced by the implementer on a pristine `HEAD` with the working
changes stashed, so it is unrelated to this fix. An earlier closure run had
passed the same suite (see `final-results.md`), so this is a regression in the
current machine state, not in the change under test.

The unit suite was not rerun in this pass, for two reasons. The change is
UI-only (`VideoCards.kt` plus instrumentation sources), so the JVM unit suite
does not exercise the changed behavior and a rerun would not change the result.
Inspection of the private temp root also showed no simple fix: `.tooling/tmp`
sits on `btrfs` with 841 GiB free and 434 MiB used, the shared `/tmp` tmpfs is
at 77% (1.8 GiB free) but the JVM temp dir is the private `.tooling/tmp`, and
both Robolectric native-runtime copies contain a full-size
`libandroid_runtime.so` (16,186,528 bytes) with no truncated or zero-length
native library. There is therefore no evidence of disk or temp exhaustion to
correct, and the scripts were not altered. The unit-suite limitation is recorded
here rather than hidden.
