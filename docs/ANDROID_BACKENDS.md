# Android backend research and decisions

This document records the extraction backend OmaTube for Android ships, the
alternatives that were evaluated, and the Android platform facts behind those
decisions. It is factual reference material, not a commitment to add any of the
options below.

## What the app ships

`backend/NewPipeBackend.kt` is the only `VideoBackend` implementation used in
production:

- Metadata and stream extraction use NewPipeExtractor
  `com.github.teamnewpipe:NewPipeExtractor:v0.26.5`, consumed from JitPack.
- `NewPipeDownloader` adapts the service-worker client-version bootstrap from
  [NewPipeExtractor PR #1520](https://github.com/TeamNewPipe/NewPipeExtractor/pull/1520):
  it requests `https://www.youtube.com/sw.js_data` and falls back to the original
  `sw.js` request when data is unavailable or invalid. The v0.26.5 pin remains
  retained until a released extractor includes this fix.
- `resolveStream` always calls NewPipeExtractor. No other component extracts a
  playable stream.
- The public YouTube long-form Atom feed is an optional fast path for recent
  uploads (`ChannelInput.atomFeedUrl`). It is never used to extract a stream.
- The YouTube Data API v3 is an optional metadata path used only when the user
  configured a key. It is never used to extract a stream.
- SponsorBlock is an optional lookup through `SponsorBlockClient`; it returns
  skip segments only and does not affect extraction.
- Transcript acquisition uses `YoutubeTranscriptLoader` after stream resolution;
  it selects an available subtitle URL, requests YouTube JSON3 captions through
  the shared bounded transport, and returns transient cue/word timings. It does
  not perform a second stream extraction or persist transcript data.
- The debug/offline `FakeVideoBackend` replaces all of this under automation.

The app does not bundle `yt-dlp`, Python, ffmpeg, aria2c, or any external
executable.

## PoToken state under v0.26.5

NewPipeExtractor exposes a `PoTokenProvider` interface whose v0.26.5 methods are
`getWebClientPoToken`, `getWebEmbedClientPoToken`, `getAndroidClientPoToken` and
`getIosClientPoToken`. Inspecting the pinned artifact shows that
`YoutubeStreamExtractor` invokes only the Android and iOS getters; the Web and
WebEmbed getters are declared but not called by the stream extractor. The
Android path is the Android-Reel player response
(`YoutubeStreamHelper.getAndroidReelPlayerResponse`), which needs no token for
public VOD playback.

OmaTube therefore registers no `PoTokenProvider`, starts no WebView and contains
no token helper. Earlier design notes that described app-side Web token
generation were speculative and have been corrected. This is a deliberate
limitation: the app stays on the NewPipe-native route and does not claim full
token coverage.

## yt-dlp on Android: options evaluated

`yt-dlp` is the natural companion to the desktop application, where the packaged
binary runs as an ordinary child process. Android is different, so the Android
port deliberately does not ship it. The options below are the factual, currently
maintained approaches; none is used here.

### youtubedl-android

- Upstream: https://github.com/yausername/youtubedl-android (GPL-3.0).
- An Android library that wraps the `yt-dlp` executable and bundles Python.
- It packages the executable and Python into the APK, requires
  `android:extractNativeLibs="true"` and per-ABI filters, and can update yt-dlp
  on device.
- It is the accepted way to run yt-dlp as a subprocess on Android. It is also
  large: it bundles a Python runtime plus the extractor, and it needs its own
  GPL/third-party compliance work.

### Seal

- Upstream: https://github.com/JunkFood02/Seal (GPL-3.0).
- A yt-dlp-based downloader app that uses `youtubedl-android` and demonstrates a
  fuller integration (formats, playlists, on-device updates).
- It is a downloader, not a feed or player, and ships the full yt-dlp stack.

### YTDLnis

- Upstream: https://github.com/deniscerri/ytdlnis.
- Another Android yt-dlp front end with a comparable model: bundled Python and
  yt-dlp, on-device updates, download-oriented.

### Chaquopy

- Upstream: https://github.com/chaquo/chaquopy (MIT).
- The Python SDK for Android. It embeds Python and lets Java/Kotlin call Python
  code. It is a general Python bridge, not a yt-dlp wrapper; using it for
  extraction means shipping yt-dlp's package tree yourself and managing its
  updates.

### Termux (external)

- Upstream: https://github.com/termux/termux-app.
- Termux runs a full userland with its own package manager. It can install
  `yt-dlp`, but it is a separate application, not a library an app can embed,
  and using it from OmaTube would require an external dependency the user must
  install and maintain. Not shipped.

## Android execution policy

The common claim that "Android forbids apps from running processes" is not a
blanket rule, and the decision above does not rest on it.

- Android 10 (API 29) removed the ability for untrusted apps to `execve()` files
  in the writable app home directory, because that is a W^X violation. Google's
  documented guidance is to load only binary code embedded in the APK.
  See https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission
  and the AOSP sepolicy change: https://android-review.googlesource.com/c/platform/system/sepolicy/+/804149
- Execution from the read-only native library directory is still supported, for
  example by packaging a binary under `jniLibs/<abi>/lib*.so` with
  `android:extractNativeLibs="true"` and executing the extracted copy. This is
  exactly how `youtubedl-android` and Termux package executables.
- The restriction targets code executed from app-writable storage, not process
  creation itself. `Runtime.exec`/`ProcessBuilder` remain available and are used
  by the projects above.

So the platform does not forbid a yt-dlp backend. OmaTube omits it for product
and engineering reasons, not because it is impossible.

## Why OmaTube does not ship yt-dlp

- Extraction parity: the desktop feed only needs channel, upload, live and
  stream metadata. NewPipeExtractor covers those directly, in-process, with a
  small pinned dependency and no subprocess protocol.
- Size and runtime: a bundled Python runtime plus yt-dlp is a large APK and a
  second update surface (yt-dlp, Python, and the native libraries) inside an
  application that otherwise has no interpreter.
- Consistency with NewPipe: the NewPipe project itself does not use yt-dlp; it
  extracts streams with NewPipeExtractor. See the project discussions at
  https://github.com/TeamNewPipe/NewPipe/issues/11803 and the closed request at
  https://github.com/TeamNewPipe/NewPipe/issues/4202. The original
  `youtubedl-android` integration pull request was also not merged:
  https://github.com/TeamNewPipe/NewPipe/pull/2131.
- License and notice scope: NewPipeExtractor is already GPL-3.0-or-later, which
  matches this application. Bundling a Python runtime and yt-dlp would add a
  large set of additional notices and corresponding-source obligations.

The desktop application keeps its `yt-dlp`-based keyless path; the Android port
does not claim it.

## Maintenance

Stream extraction is tied to a moving target. YouTube changes its player
frequently enough that any extractor can stop returning playable streams even
when the application code has not changed.

- Dependency versions are locked in `gradle/libs.versions.toml`. Update
  NewPipeExtractor deliberately, one version at a time, and re-run the offline
  suite plus the opt-in network smoke test
  (`docs/verification/README.md`).
- Keep the OmaTube-side playback adapter in step with the extractor. The DASH
  and progressive manifest helpers, the range/`rn` request rewriting and the
  supported-itag list in `player/` track upstream behavior; a pinned extractor
  version is not a license to ignore those changes.
- When a release fails to play, check NewPipeExtractor releases and issue
  reports first. Bumping the pin is usually the correct fix; do not silently
  fall back to a lower-quality or audio-less source to hide an extraction
  failure.

## Source references

- NewPipeExtractor v0.26.5:
  https://github.com/TeamNewPipe/NewPipeExtractor/releases/tag/v0.26.5
- NewPipeExtractor PR #1520 (service-worker data bootstrap):
  https://github.com/TeamNewPipe/NewPipeExtractor/pull/1520
- NewPipe app (does not use yt-dlp):
  https://github.com/TeamNewPipe/NewPipe/issues/11803
- NewPipe yt-dlp backend request (closed):
  https://github.com/TeamNewPipe/NewPipe/issues/4202
- youtubedl-android: https://github.com/yausername/youtubedl-android
- Seal: https://github.com/JunkFood02/Seal
- YTDLnis: https://github.com/deniscerri/ytdlnis
- Chaquopy: https://github.com/chaquo/chaquopy
- Termux: https://github.com/termux/termux-app
- Android 10 execute restriction:
  https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission
- AOSP sepolicy execve restriction:
  https://android-review.googlesource.com/c/platform/system/sepolicy/+/804149
