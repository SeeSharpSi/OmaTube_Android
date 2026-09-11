# minSdk 26 SQLite compatibility verification

Independent verification of the Android OmaTube debug build on the private
API 26 emulator. The goal was to exercise the real `minSdk 26` SQLite engine
(`emulator-5582`, API 26 ships SQLite `3.18.2`, before `UPSERT`) with the final
APKs, then smoke the automation entry points for startup crashes, missing
platform APIs and cleartext policy.

This run only used `emulator-5582` (`omatube_api26`). `emulator-5580` belongs to
the API 36 final verifier and was never operated. No Gradle build or source edit
was made; the existing final `app-debug.apk` and
`app-debug-androidTest.apk` were installed. `emulator-5582` was shut down after
the run to return RAM.

## Device and toolchain

| Item | Value |
|---|---|
| Device | `emulator-5582`, private headless AVD `omatube_api26` |
| API / release | 26 / Android 8.0.0 |
| ABI / model | `x86_64` / `Android SDK built for x86_64` (`generic_x86_64`) |
| Fingerprint | `Android/sdk_gphone_x86_64/generic_x86_64:8.0.0/OSR1.180418.026/6741039:userdebug/dev-keys` |
| Screen | 1080 x 2400 px, density 420 dpi |
| Accelerator | `/dev/kvm` (host `vmx`), `-gpu swiftshader_indirect`, `-accel on` |
| Boot | cold boot, `sys.boot_completed=1` |
| Host toolchain | Temurin 21.0.12.1+1, SDK build-tools 36.0.0, platform-tools 37.0.1, emulator 37.1.11 |

## APK artifacts under test

| Artifact | Size (bytes) | SHA-256 |
|---|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | 23 260 266 | `49f29cbd331cd8ca512b0f983b557a2c2fb8177be74171d12a64b67536cfc27c` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 1 073 534 | `3c70d6395a989702985053eff18c10b615fa425d6c6b8e5df9d66b13b5bedb8d` |

Both were installed with `adb -s emulator-5582 install -r -t` (streamed install,
`Success` for each). See `.tooling/logs/09-minsdk-install.log`.

## Guest SQLite engine

- `command -v sqlite3` -> `/system/xbin/sqlite3`
- `sqlite3 --version` -> `3.18.2 2017-07-21 ...`
- Probe of the modern syntax: `INSERT ... ON CONFLICT(id) DO UPDATE SET ...`
  returns `Error: near "on": syntax error`. `UPSERT` requires SQLite 3.24, so
  API 26 is a genuine pre-`UPSERT` engine. This is the compatibility target.

The production repository deliberately avoids `UPSERT`:

- `app/src/main/java/dev/omatube/app/data/LibraryDao.kt:59` documents the
  SQLite 3.24 requirement and uses `@Insert(onConflict = OnConflictStrategy.IGNORE)`
  for channels/videos (`LibraryDao.kt:62`, `LibraryDao.kt:94`) plus explicit
  `@Query("UPDATE ...")` statements (`LibraryDao.kt:65`, `LibraryDao.kt:101`).
- `app/src/main/java/dev/omatube/app/data/RoomLibraryRepository.kt:402` holds the
  compatible upsert building blocks; `upsertChannelInternal` (`:407`) does
  insert-or-ignore then update, and `insertVideoIfAbsent` (`:438`) plus the video
  metadata `UPDATE` cover the video path, all inside
  `database.withTransaction`.

## Offline instrumentation suite (API 26)

```sh
adb -s emulator-5582 shell am instrument -w -r \
  dev.omatube.app.test/androidx.test.runner.AndroidJUnitRunner
```

Result: **`OK (22 tests)`, `Time: 7.969`, 21 passed, 1 skipped, 0 failures,
0 errors.** Full raw output: `.tooling/logs/09-minsdk-instrumentation.log`.

| Test class | Tests | Result |
|---|---|---|
| `RoomLibraryRepositoryInstrumentedTest` | 1 | pass |
| `CachePruningInstrumentedTest` | 1 | pass |
| `DataStoreSettingsStoreInstrumentedTest` | 3 | pass |
| `LibraryScreenTest` | 7 | pass |
| `SettingsScreenTest` | 9 | pass |
| `NewPipeBackendNetworkSmokeTest` | 1 | skipped (opt-in gate) |

Coverage of the required areas:

- **SQLite insert + update repository instrumentation**:
  `RoomLibraryRepositoryInstrumentedTest.compatibleUpsertsPreserveStatsQueueAndLiveSemantics`
  passed against the real API 26 SQLite 3.18.2 engine. It inserts channels and
  videos, records playback, adds to the Watch Next queue, sets a history cursor,
  re-upserts metadata, and asserts the portable insert/update path preserves
  watched stats (`15s`), watch count (`1`), queue position, history cursor and
  live semantics (`isLive` only cleared by a successful `replaceLive`).
- **DataStore Keystore / cache pruning**:
  `DataStoreSettingsStoreInstrumentedTest` (3) passed, including remembered-key
  encryption/restore and session-only key behavior on API 26 Android Keystore;
  `CachePruningInstrumentedTest` (1) passed on-disk pruning with retention of
  watch stats and history cursor.
- **Compose screens**: `LibraryScreenTest` (7) and `SettingsScreenTest` (9)
  passed, covering full/simple feed, history delete, watch-next controls, route
  callbacks and settings tab/control behavior under Compose on API 26.
- **Gated skip**: `NewPipeBackendNetworkSmokeTest.resolvesPublicChannelAndVideoStream`
  was skipped by `requireOptIn` (`Assume.assumeTrue`,
  `NewPipeBackendNetworkSmokeTest.kt:40`) because `omatubeNetworkSmoke` was not
  supplied. Reported as `INSTRUMENTATION_STATUS_CODE: -4`
  (`AssumptionViolatedException`), not a failure. No network was used.

## Automation entry points

Each route was launched fresh with `am force-stop` first, using only debug
automation extras (in-memory Room fixture, fake backend, no network, no remote
images). Result: `.tooling/logs/09-minsdk-automation.log`. Raw device log:
`.tooling/logs/09-minsdk-logcat.log`.

| Route | Command | Rendered evidence | Result |
|---|---|---|---|
| Full feed | `--es automation_ui full --es automation_route feed --es automation_theme default` | title `OMA / TUBE`, cards `Automation Video 1/2`, `Jan 1, 2026`, `20% watched` | pass |
| Simple feed | `--es automation_ui simple --es automation_route feed` | text rows `Automation Video 1..5`, `20%` | pass |
| Player | `--es automation_ui full --es automation_route player` | fullscreen chrome `Viewing full screen` | pass |
| Config | `--es automation_ui full --es automation_route settings` | `Config`, `CHANNELS`, `CATEGORIES`, `PLAYBACK`, `APPEARANCE` | pass |

All four reported `mResumedActivity: dev.omatube.app/.MainActivity` and dumped a
non-empty UI hierarchy. Screenshots:
`docs/verification/min-sdk/full-feed.png` and
`docs/verification/min-sdk/simple-feed.png` (both 1080 x 2400 RGBA PNG).

Crash and policy scan of the device log over all four launches:

- No `FATAL EXCEPTION`, no `AndroidRuntime` app crash, no empty crash buffer.
- No `NoSuchMethodError`, `NoClassDefFoundError`, `ClassNotFoundException`,
  `AbstractMethodError`, `VerifyError`, `InflateException` or
  `Unable to start activity`.
- No `CLEARTEXT communication ... not permitted`. The manifest sets
  `android:usesCleartextTraffic="false"` (`app/src/main/AndroidManifest.xml:18`),
  and the automation graph performs no network calls.
- Only benign system noise: the `uiautomator` process's own `AndroidRuntime`
  lines, splash-layer SurfaceFlinger notices and the expected
  `Force stopping`/`app died` entries from the force-stop-and-relaunch loop.

## Compatibility gaps and limitations

- No application-level compatibility gap was found on API 26. All 21 runnable
  tests passed on SQLite 3.18.2 and the four automation routes started cleanly.
- Only API 26 was executed for the min-SDK boundary. API 27/28 were not used;
  the portable insert-or-ignore plus update path is API-level agnostic below
  SQLite 3.24 and is the same code exercised here.
- The network smoke test remains intentionally skipped and was not run.
- This run is debug-only, with the fake backend: no real extraction, playback,
  PoToken, SponsorBlock or remote images were exercised. SAF import/export and
  the JVM unit suite are covered by `docs/verification/final-results.md`.
- The player route was verified as fullscreen automation chrome only; no media
  was opened.

## Logs and evidence

| Artifact | Contents |
|---|---|
| `.tooling/logs/09-minsdk-install.log` | APK installs on `emulator-5582` |
| `.tooling/logs/09-minsdk-instrumentation.log` | full offline instrumentation run (OK, 22 tests) |
| `.tooling/logs/09-minsdk-automation.log` | automation intent launches and resumed activities |
| `.tooling/logs/09-minsdk-logcat.log` | device logcat captured across the automation launches |
| `docs/verification/min-sdk/full-feed.png` | full feed automation screenshot |
| `docs/verification/min-sdk/simple-feed.png` | simple feed automation screenshot |

## Device lifecycle

`emulator-5582` was started headless for this run and stopped after verification
with `adb -s emulator-5582 emu kill`; only `emulator-5580` remains attached.
