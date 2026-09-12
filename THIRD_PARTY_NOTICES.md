# Third-party notices

OmaTube is licensed under GPL-3.0-or-later. Bundled and linked software retains
its own license. A build of this application is not a license grant for every
component under the OmaTube license. The complete GNU General Public License
version 3 text is included in this distribution as `COPYING`.

The OmaTube desktop source is published at
https://git.skrittle.net/andor/OmaTube (verified project remote). This Android
port has no separate configured public remote in this working tree. Anyone
distributing a binary of this application must publish the corresponding
Android source alongside it.

This Android application links the dependencies below. Versions are pinned in
`gradle/libs.versions.toml`.

## AndroidX and Jetpack
- AndroidX Core (`androidx.core:core-ktx`), Activity
  (`androidx.activity:activity-compose`), Lifecycle
  (`androidx.lifecycle:*`), Room (`androidx.room:*`), DataStore
  (`androidx.datastore:datastore-preferences`): Apache License 2.0.
  Source: https://cs.android.com/androidx/platform/frameworks/support
- Jetpack Compose UI, Foundation, Animation, Material 3, Tooling and test
  artifacts (`androidx.compose.*`): Apache License 2.0.
  Source: https://cs.android.com/androidx/platform/frameworks/support
- AndroidX Media3 ExoPlayer, DASH, HLS, OkHttp data source, UI and Session
  (`androidx.media3:*`): Apache License 2.0.
  Source: https://github.com/androidx/media
- AndroidX Test (runner, rules, ext:junit, core, Espresso): Apache License 2.0.
  Source: https://cs.android.com/android/platform/superproject/main/+/main:platform/testing/;bp=/androidx/test

## Kotlin
- Kotlin standard library, Android Gradle plugin tooling, Compose compiler
  plugin: Apache License 2.0. Source: https://github.com/JetBrains/kotlin
- kotlinx.coroutines (`org.jetbrains.kotlinx:kotlinx-coroutines-*`):
  Apache License 2.0. Source: https://github.com/Kotlin/kotlinx.coroutines
- Kotlin Symbol Processing (`com.google.devtools.ksp`): Apache License 2.0.
  Source: https://github.com/google/ksp

## Networking, serialization and images
- OkHttp and MockWebServer (`com.squareup.okhttp3:*`): Apache License 2.0.
  Source: https://github.com/square/okhttp
- Gson (`com.google.code.gson:gson`): Apache License 2.0.
  Source: https://github.com/google/gson
- Coil (`io.coil-kt:coil-compose`): Apache License 2.0.
  Source: https://github.com/coil-kt/coil
- Android Gradle Plugin and desugar_jdk_libs_nio
  (`com.android.tools:desugar_jdk_libs_nio`): Apache License 2.0.
  Source: https://android.googlesource.com/platform/tools/base and
  https://android.googlesource.com/platform/external/desugar

## Stream extraction
- NewPipeExtractor (`com.github.teamnewpipe:NewPipeExtractor`,
  https://github.com/TeamNewPipe/NewPipeExtractor): GNU General Public License
  v3.0 or later. OmaTube's Android application as a whole is distributed under
  GPL-3.0-or-later, which matches this requirement.
  - Mozilla Rhino (`org.mozilla:rhino`, `rhino-engine`): Mozilla Public License
    2.0. Source: https://github.com/mozilla/rhino
  - jsoup (`org.jsoup:jsoup`): MIT License. Source:
    https://github.com/jhy/jsoup
  - nanojson (`com.github.TeamNewPipe:nanojson`): MIT License. Upstream:
    https://github.com/mmastrac/nanojson
  - Protocol Buffers Java Lite (`com.google.protobuf:protobuf-javalite`):
    BSD 3-Clause License. Source:
    https://github.com/protocolbuffers/protobuf
  - JSR-305 annotations (`com.google.code.findbugs:jsr305`): Apache License
    2.0. Source: https://github.com/findbugsproject/jsr305

## JitPack build service
- NewPipeExtractor is consumed from JitPack (`https://jitpack.io`), which
  builds the pinned upstream tag `v0.26.5` from source. See
  https://github.com/TeamNewPipe/NewPipeExtractor/releases/tag/v0.26.5.

## Fonts

The application bundles the same faces the desktop build resolves on its host.
Each family ships only the Regular and Bold weights.

### JetBrains Mono

- Files: `app/src/main/res/font/jetbrains_mono_nerd_regular.ttf`,
  `app/src/main/res/font/jetbrains_mono_nerd_bold.ttf`.
- Base font: JetBrains Mono, Copyright 2020 The JetBrains Mono Project Authors,
  SIL Open Font License 1.1. Included as
  `app/src/main/assets/licenses/jetbrains-mono-OFL.txt`.
- Upstream: https://github.com/JetBrains/JetBrainsMono

### Nerd Fonts patched JetBrains Mono

The bundled JetBrains Mono files are patched with the Nerd Fonts Patcher and
contain added glyphs, so the base JetBrains Mono OFL is not the only applicable
term. The patched font adds glyphs from several icon font projects under their
own licenses.

- Nerd Fonts project: https://github.com/ryanoasis/nerd-fonts
- Patcher and source code: MIT License, Copyright (c) 2014 Ryan L McIntyre.
  Included as `app/src/main/assets/licenses/nerd-fonts-MIT.txt`.
- Added glyph set sources and licenses: included as
  `app/src/main/assets/licenses/nerd-fonts-glyph-sources.txt`. That table is
  copied from https://github.com/ryanoasis/nerd-fonts/blob/master/src/glyphs/README.md;
  the authoritative per-project breakdown is the upstream license audit at
  https://github.com/ryanoasis/nerd-fonts/blob/master/license-audit.md.
  The added sets are licensed CC BY 4.0, MIT, Apache 2.0, or OFL 1.1 RFN, with
  the upstream `Font Logos` set marked by Nerd Fonts as unlicensed. That
  glyph-sources table is copied from an earlier upstream revision, so the
  `Font Logos` finding may be stale; re-check it against the current upstream
  license audit before distribution. Anyone redistributing a product must
  review that finding against their own policy.

### Liberation Sans

- Files: `app/src/main/res/font/liberation_sans_regular.ttf`,
  `app/src/main/res/font/liberation_sans_bold.ttf`.
- Digitized data Copyright (c) 2010 Google Corporation with Reserved Font
  Arimo, Tinos and Cousine; Copyright (c) 2012 Red Hat, Inc. with Reserved
  Font Name Liberation. SIL Open Font License 1.1. Included as
  `app/src/main/assets/licenses/liberation-sans-OFL.txt`.
- Upstream: https://github.com/liberationfonts/liberation-fonts

## Adapted upstream code

Some Android source files are adapted from upstream GPL-3.0-or-later code.
Their upstream copyright and license are retained here.

### NewPipe / NewPipeExtractor (TeamNewPipe, GPL-3.0-or-later)

- `app/src/main/java/dev/omatube/app/player/MediaSourceResolver.kt` follows the
  upstream NewPipe `VideoPlaybackResolver`/`PlaybackResolver` strategy for live
  DASH/HLS preference, separate video and audio merging, and YouTube progressive
  or OTF manifest generation, and copies `ListHelper.SUPPORTED_ITAG_IDS`.
- `app/src/main/java/dev/omatube/app/player/YoutubeDashLiveManifestParser.kt`
  mirrors the upstream NewPipe `YoutubeDashLiveManifestParser` availability
  workaround.
- `app/src/main/java/dev/omatube/app/player/PlaybackDataSources.kt` mirrors the
  upstream NewPipe `PlayerDataSource` split and uses `YoutubeParsingHelper` for
  streaming URL classification and user agents.
- `app/src/main/java/dev/omatube/app/player/StreamSelector.kt` uses the
  desktop/NewPipe codec preference order.
- Upstream: https://github.com/TeamNewPipe/NewPipe and
  https://github.com/TeamNewPipe/NewPipeExtractor

### OmaTube desktop (same project, GPL-3.0-or-later)

The Android port re-implements behavior from the desktop application, including:

- `backend/AtomFeed.kt` from the desktop Atom feed parser.
- `backend/ChannelInput.kt` from the desktop channel reference parsing.
- `backend/SponsorBlockClient.kt` from the desktop SponsorBlock client.
- `automation/AutomationFixture.kt` from the desktop automation fixture.
- `ui/theme/OmaTheme.kt` palettes taken from the desktop `themes/*/colors.toml`; the Android Default accent uses its palette blue.
- Compose screens modeled on the desktop QML Full and Simple interfaces.

## Before public distribution

Publish the exact OmaTube source, build scripts, and required corresponding
source for all GPL/LGPL components, including build recipes, patches, and
applicable relinking materials. Keep the complete GPL text in `COPYING` and the
font notices in `app/src/main/assets/licenses/`. Retain upstream license and
notice files for the lengths their licenses require. This file is an inventory,
not proof of license compliance.
