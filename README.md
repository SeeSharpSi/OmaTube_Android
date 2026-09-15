# OmaTube for Android

OmaTube for Android is the native Kotlin port of the OmaTube desktop YouTube
feed. It reproduces the desktop Full and Simple interfaces with the same bundled
fonts, palettes and custom controls, then adapts them to a narrow, inset phone
window. The app plays public YouTube content with AndroidX Media3 ExoPlayer and
extracts metadata and streams with NewPipeExtractor.

This repository is the Android application. The desktop C++/Qt application lives
separately; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for source and
license locations.

## What it does

- Full UI with thumbnail feed/history/Watch Next cards and Simple UI with
  title-row presentations for Feed, History and Watch Next, switchable at
  runtime. Both reuse the desktop Default, Rose Pine and Nord
  palettes with no Material or dynamic-color theming.
- Full UI draws edge to edge on phones: its palette background paints behind the
  status and gesture navigation bars, feed/history cards and accent dividers
  reach safe-area edges, while the Watch Next grid and ordinary chrome keep
  their container insets. Simple Feed, History and Watch Next use title rows;
  Settings and bottom navigation use Normal UI chrome, fonts and geometry in
  both modes.
  Watch Next count sits adjacent to its title in Full UI. Refresh status and
  errors appear as transient top-right notices in Full UI; Simple UI keeps its
  bottom-anchored notices.
- Bundled JetBrains Mono Nerd Font and Liberation Sans faces, matching the fonts
  the desktop build resolves on its host.
- Custom player chrome with seek, play/pause, mute, volume, speed, fullscreen,
  a per-video quality override, separate Wi-Fi and Data preferred maximums, and
  a shared Last used height. In
  portrait the top and bottom controls bracket an aspect-fitted video without
  resizing it when the chrome hides; loading stays visible without the chrome,
  a center transport appears in the normal state, and the live scrub timestamp
  is centered above the bottom controls. The controls stay visible while
  touching or seeking.
- Local categories, channels, uploads history and resume positions. Resume and
  watch statistics are stored locally; a Watch Next queue holds up to 25 items.
- SponsorBlock is available but off by default. When enabled, each supported
  category can be skipped manually or automatically.
- Cache-first startup: cached feed and last-known live state render immediately,
  then a refresh runs once per process. Refreshes otherwise happen only on an
  explicit user action. There is no timer, WorkManager job or background
  polling after a refresh completes.
- A local Room library cached up to a hard 10 MiB limit, with deeper history
  fetching allowed below a 9 MiB soft limit. Past the limit the oldest videos
  are pruned first in, first out while watch statistics are kept.

The app has no account login, no subscription sync and no download manager.
Foreground media playback continues with the display off or Activity in the
background. Android exposes a MediaStyle notification with play/pause controls;
notification permission is requested where the platform requires it. Picture-
in-picture is entered only when a real video is already playing.

## Data sources

Stream extraction always uses NewPipeExtractor, pinned to `v0.26.5`. Two
optional metadata paths sit around it:

- Without an API key, recent uploads come from the public YouTube long-form Atom
  feed (`UULF...`). A NewPipe first page adds durations for items that lack
  them. The Atom response is returned before enrichment, so the feed never waits
  on it.
- With a YouTube Data API v3 key, channel, upload, video and live metadata use
  the documented Data API. A key is optional and never required for playback.
  Enter it in Config. When "Remember locally" is enabled, the key is encrypted
  with an Android Keystore AES/GCM key and stored under the app's no-backup
  directory; otherwise it stays in memory for the session only.

Live checks are independent of the feed: an empty successful result clears live
state, while a failure keeps the last-known live state and reports live status
as incomplete instead of silently clearing it.

PoToken handling is intentionally minimal. In the pinned `v0.26.5` extractor the
stream extractor invokes only the Android and iOS client token getters and uses
the Android-Reel path, which needs no token for public VOD playback. OmaTube
registers no token provider and contains no hidden WebView. See
[IMPLEMENTATION.md](IMPLEMENTATION.md) and [docs/ANDROID_BACKENDS.md](docs/ANDROID_BACKENDS.md).

## Requirements

- `bin/bootstrap-android` requires a Linux x86_64 host. It downloads the
  upstream x86_64 Linux JDK, SDK command-line tools and system image, and
  refuses to run on any other OS or architecture.
- JDK 21 (`bin/bootstrap-android` installs Eclipse Temurin 21.0.12.1+1)
- Android SDK Platform 36, Build-Tools 36.0.0 and the API 36 x86_64 emulator
- Gradle 8.13 (provided by the checked-in wrapper)

macOS and other hosts remain usable for development through Android Studio with
a separately installed JDK 21 and Android SDK 36; only the automated
`bin/bootstrap-android` path is Linux x86_64 only.

`bin/bootstrap-android` installs the JDK, SDK, build tools, emulator and a
private headless AVD entirely under the ignored `.tooling/` directory. It uses
no `sudo` and changes no global configuration. Relevant pinned tool versions and
checksums are in `.tooling/TOOLCHAIN.md` and `bin/bootstrap-android`.

Dependency versions are pinned in `gradle/libs.versions.toml`. The application
targets `compileSdk`/`targetSdk` 36 and `minSdk` 26, and is built with Android
Gradle Plugin 8.13.0 and Kotlin 2.2.20.

## Bootstrap and build

```sh
./bin/bootstrap-android          # install/verify the private toolchain
./bin/bootstrap-android --start-emulator   # also boot the private AVD
./bin/build                      # debug APK
./bin/test                       # JVM unit tests
```

`bin/bootstrap-android` prints the environment exports for the private SDK. The
build and test scripts fall back to `.tooling/` automatically, or honor an
existing `JAVA_HOME` and `ANDROID_HOME`.

`bin/build` and `bin/test` also keep Gradle and every forked JVM off the shared
`/tmp`, which can be small or full. They default `TMPDIR` and a
`-Djava.io.tmpdir` entry in `JAVA_TOOL_OPTIONS` to the ignored `.tooling/tmp`,
and preserve an existing `TMPDIR`, `JAVA_TOOL_OPTIONS` or other
`java.io.tmpdir` flag.

Direct Gradle equivalents, for an already configured environment:

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Instrumented tests are not run through the all-devices
`:app:connectedDebugAndroidTest` task; install and run them on the private
serial with `adb shell am instrument` as described under
[Automation and verification](#automation-and-verification).

No continuous-integration workflow is committed to this repository. A CI job
that mirrors local verification should bootstrap the toolchain, then run
`bin/build` and `bin/test`; any instrumented or automation check needs an
emulator and is opt-in.

Build outputs:

| Artifact | Path |
| --- | --- |
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| Instrumentation APK | `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` |
| Release APK (unsigned) | `app/build/outputs/apk/release/app-release-unsigned.apk` |

## Install on a device or emulator

The private AVD runs headless on serial `emulator-5580`. Install and launch the
debug build with:

```sh
adb -s emulator-5580 install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5580 shell am start -n dev.omatube.app/.MainActivity
```

Use `adb -s emulator-5580` for every command so an unrelated attached device is
never touched. A normal launch opens `omatube.sqlite3` under the app's private
data directory and may consume YouTube API quota, so use the automation mode
below for smoke checks and manual exploration.

### Android Studio

Open this repository root as a Gradle project and point Android Studio at the
private SDK (`.tooling/android-sdk`) and the bundled JDK (`.tooling/jdk`), or at
your own JDK 21 and SDK 36 installation. `local.properties` and
`gradle-local.properties` are ignored and are the intended place for a local
`sdk.dir`. On macOS, install JDK 21 and Android SDK 36 yourself and let Android
Studio resolve them; `bin/bootstrap-android` is not used there.

## Automation and verification

Debug builds accept deterministic automation extras. They install an in-memory
Room fixture and a fake backend, so no real media is opened, no remote images
are fetched and no user data is read. The extras are ignored in release builds.

```sh
adb -s emulator-5580 shell am start -n dev.omatube.app/.MainActivity \
  --ez automation true \
  --es automation_ui full \
  --es automation_route feed \
  --es automation_theme default

adb -s emulator-5580 exec-out screencap -p > /tmp/omatube-feed.png
```

`automation_ui` is `full` or `simple`; `automation_route` is `feed`, `history`,
`watchnext`, `settings` or `player`; `automation_theme` is `default`, `rose-pine`
or `nord`. `docs/verification/run-device-checks.sh` drives a full route and
interaction matrix on the isolated emulator and writes screenshots plus a
machine-readable summary. See [docs/verification/README.md](docs/verification/README.md)
for the artifact layout and where the final build and test counts are recorded.

JVM unit tests never require the network. Instrumented tests are installed and
run explicitly on the private serial. Never use the all-devices
`:app:connectedDebugAndroidTest` task, and always pass `-s emulator-5580` so an
unrelated attached device is never touched. To run a single class, add
`-e class <fully.qualified.TestClass>`:

```sh
adb -s emulator-5580 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5580 shell am instrument -w -r \
  dev.omatube.app.test/androidx.test.runner.AndroidJUnitRunner
```

Two checks are network-dependent and skipped by default. Both run only on the
private emulator, only for their own class, and must not be added to `bin/test`.

The metadata and stream extraction check needs `omatubeNetworkSmoke=true`:

```sh
adb -s emulator-5580 shell am instrument -w -r \
  -e omatubeNetworkSmoke true \
  -e class dev.omatube.app.backend.NewPipeBackendNetworkSmokeTest \
  dev.omatube.app.test/androidx.test.runner.AndroidJUnitRunner
```

The native playback check exercises real Media3 playback through the production
`PlayerController`, `MediaSourceResolver` and `ExoPlaybackEngine` for one public
video, using a standalone test activity and no user data, repository or API key.
It needs `omatubePlaybackSmoke=true`:

```sh
adb -s emulator-5580 shell am instrument -w -r \
  -e omatubePlaybackSmoke true \
  -e class dev.omatube.app.player.NativePlaybackNetworkSmokeTest \
  dev.omatube.app.test/androidx.test.runner.AndroidJUnitRunner
```

See [docs/verification/playback-smoke.md](docs/verification/playback-smoke.md)
for the verified result, regression history and evidence.

## Distribution

The Gradle build currently defines no signing configuration. `assembleRelease`
produces `app-release-unsigned.apk` with R8 and resource shrinking enabled.

To produce a distributable APK, build the release variant and sign it outside
Gradle, for example with Android Studio's **Build > Generate Signed App
Bundle / APK** flow or with `apksigner`. Never commit a keystore,
`keystore.properties` or a signing password to the repository; keep them outside
the working tree. After signing, side-load the APK with `adb -s <serial>
install -r <signed.apk>`.

## UI parity notes

The port targets the desktop look: the same bundled font files, the same three
palettes and custom controls rather than platform widgets. It is not a claim of
pixel-identical rendering. The desktop grid and window chrome reflow to a single
column and safe-area insets, and Android text metrics differ from Qt's, so the
verification screenshots are a comparison of color, type scale, spacing,
thumbnail proportions and control visibility, not an exact renderer match.

## License

OmaTube is licensed under GPL-3.0-or-later. The full license text is in
[COPYING](COPYING). Bundled and linked components keep their own terms; see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
