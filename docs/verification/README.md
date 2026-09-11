# Verification artifacts

This directory holds the deterministic evidence collected for the Android port.
It is reference material for reviewers and future maintenance; it is not part of
the application build.

## Layout

| Path | Contents |
| --- | --- |
| `reference/` | Desktop OmaTube Qt Quick screenshots captured from the seeded fixture for comparison. |
| `desktop-reference.md` | How the desktop references were captured, the fixture and run scripts, and the narrow-phone adaptation notes. |
| `android/` | Android emulator screenshots, `results.txt`, `device-verification.md` and `library-visual-parity.md`. |
| `android/device-verification.md` | Instrumentation run, on-device interaction checks, defects and limitations for one verification pass. |
| `android/library-visual-parity.md` | Targeted follow-up corrections and font bundling decisions. |
| `run-device-checks.sh` | Drives the isolated emulator through the route and interaction matrix. |
| `final-results.md` | Authoritative final build and test counts, written by the final verifier. |
| `min-sdk-results.md` | API 26 SQLite compatibility pass on the private API 26 emulator (predates the player transport fix; its SQLite/API findings are unaffected). |
| `playback-smoke.md` | Opt-in real Media3 playback check: command, original failure, root cause and verified result. |

## Reading the evidence

- Comparison is about color, type scale, spacing, thumbnail proportions and
  control visibility. There is no claim of pixel-identical rendering between Qt
  and Compose; the bundled fonts and custom controls are the parity target.
- `results.txt` is machine output: every line is `PASS`, or an `INFO`/`measure`
  line. The driver prints the device, SDK, cutout and density at the top.
- Automation screenshots use the debug extras, the in-memory fixture and the
  fake backend, so they contain no user data, no real media and no remote
  images.

## Final results

`final-results.md` is the single source of truth for verified counts: the JVM
unit suite, the instrumented suite, and the startup/automation smoke checks for
the revision under test. It is written by the final verifier after running the
build and tests. Do not copy stale counts into other documents; link to it
instead.

## Reproduce

```sh
./bin/bootstrap-android --start-emulator
./bin/build
./bin/test
adb -s emulator-5580 install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5580 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5580 shell am instrument -w -r \
  dev.omatube.app.test/androidx.test.runner.AndroidJUnitRunner
docs/verification/run-device-checks.sh
```

The network smoke check is opt-in and is not part of the commands above; see
`README.md` for its exact invocation. The same applies to the opt-in real
playback check; its command and result are in `playback-smoke.md`.
