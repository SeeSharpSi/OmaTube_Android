# OmaTube Android Repository Notes

## Purpose

Native Kotlin/Compose Android port of the OmaTube desktop C++/Qt YouTube feed.
The desktop application is the visual and behavioral reference. Keep network and
persistence logic out of Compose.

## Build and Verification

- Toolchain is JDK 21, Gradle 8.13 (wrapper), Android SDK Platform 36,
  Build-Tools 36.0.0 and the API 36 x86_64 emulator.
- `./bin/bootstrap-android` installs a private, host-local JDK and Android SDK
  under the ignored `.tooling/` directory and creates the private headless AVD.
  It uses no `sudo` and changes no global state. `--start-emulator` boots the AVD
  on serial `emulator-5580`.
- `./bin/build` runs `:app:assembleDebug`. `./bin/test` runs
  `:app:testDebugUnitTest`. Both fall back to `.tooling/` for `JAVA_HOME` and
  `ANDROID_HOME`, or honor values already set.
- Instrumented tests are installed and run on the private serial with
  `adb -s emulator-5580 shell am instrument`, not through the all-devices
  `:app:connectedDebugAndroidTest` task. They are not part of `./bin/test`.
- No separate lint, formatter, typecheck or codegen task is configured beyond
  the Gradle build and test tasks.
- Read `docs/verification/README.md` before running or extending verification.
  Pending final build and test counts live in `docs/verification/final-results.md`
  (written by the final verifier); do not invent counts.

### Safety boundaries

- Never install system packages, use `sudo`, or write outside the repository.
- Never launch the app against a user's real database or settings. Use the debug
  automation extras, which install an in-memory Room fixture and a fake backend.
  Read `README.md` for the launch and screenshot commands.
- Automated tests and startup smoke checks must not open videos, start playback,
  fetch remote images, or require network, libmpv or yt-dlp.
- The network smoke test is opt-in only, gated by the instrumentation argument
  `omatubeNetworkSmoke=true`, and must never be wired into `./bin/test`.
- Use only the private AVD and its serial. Never touch an unrelated attached
  device, and do not install a test package on a user's system.

## Architecture

- `MainActivity` hosts a single Compose tree; `OmaTubeApplication` owns the
  process-wide `AppGraph`; `AppViewModel` owns routes, selection, settings
  merging, SAF import/export and serialized playback reports.
- `AppGraph` wires either the production graph (Room, DataStore, NewPipeBackend)
  or the automation graph (in-memory Room, in-memory settings, FakeVideoBackend).
  Graph install is identity-aware so Activity recreation preserves state.
- `refresh/RefreshCoordinator` runs the staged refresh: channel metadata, recent
  uploads, optional duration enrichment, per-channel live checks. At most four
  backend calls are in flight. Feed success stays usable when live checks fail;
  report live status as incomplete rather than clearing it.
- `backend/NewPipeBackend` is the `VideoBackend` implementation. Stream
  extraction always uses NewPipeExtractor. The Atom feed is an optional recent
  fast path and the Data API is an optional metadata path when a key exists.
- `data/` owns Room and DataStore persistence behind `LibraryRepository` and
  `SettingsStore`. Room schema is exported to `app/schemas`.
- `player/` owns Media3, stream selection, watch accounting and SponsorBlock;
  `ui/` is presentation only. `ui/theme` holds the bundled palettes and fonts.

## Conventions

- There is no background refresh timer and no periodic polling after a refresh.
  Refresh only at process startup or on explicit user action.
- Room schema versioning uses `app/schemas`. A schema change needs new-database
  creation, a forward migration, and a migration test.
- Add app dependencies and versions to `gradle/libs.versions.toml` first, then
  reference them from `app/build.gradle.kts`. Kotlin sources under
  `app/src/main/java` are discovered automatically; there is no explicit source
  manifest. Assets and resources are packaged from `app/src/main/assets` and
  `app/src/main/res`.
- Theme IDs are hard-coded in `ui/theme/OmaTheme.kt`; a new theme also needs its
  desktop `colors.toml` values copied and theme tests updated.
- Keep the code GPL-3.0-or-later compatible and update
  `THIRD_PARTY_NOTICES.md` for any new dependency, bundled asset or adapted
  upstream code. The full license text is `COPYING`.
- Write code and documentation in professional English, without emojis.

## Ownership

This repository is no longer partitioned by feature agent. Changes are owned by
the module that contains them, and documentation changes are owned by whoever
maintains the affected doc. Update the matching docs in the same change:

- Behavior or architecture: `IMPLEMENTATION.md`, `README.md`, this file.
- Backend or extraction choices: `docs/ANDROID_BACKENDS.md`.
- Dependencies, fonts or adapted code: `THIRD_PARTY_NOTICES.md`.
- Verification tooling or results: `docs/verification/`.
