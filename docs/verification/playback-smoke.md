# Real Media3 playback smoke (opt-in)

Status: **passing** after the player transport fix. This document records an
independent, bounded, opt-in check of real public-video playback on the Android
port. It is not part of the offline suite. It keeps the original failure as
regression history, then records the final verified outcome.

## What the check does

- `app/src/androidTest/java/dev/omatube/app/player/NativePlaybackNetworkSmokeTest.kt`
  - debug-only instrumentation test;
  - gated by `Assume.assumeTrue(omatubePlaybackSmoke == "true")`, so the default
    suite skips it;
  - launches a standalone `androidx.activity.ComponentActivity` (declared
    exported by the Compose test manifest). `MainActivity` and the production
    `AppGraph` are never started;
  - the only backend is `NewPipeBackend(context) { Settings(wifiMaximumVideoHeight = 720, dataMaximumVideoHeight = 720, lastUsedVideoHeight = 720) }`:
    no Room database, no repository, no YouTube Data API key, no login;
  - builds the real `PlayerController` with `automation = false`, which creates
    the real `ExoPlaybackEngine` and `MediaSourceResolver`, and renders through a
    real Media3 `PlayerView` surface;
  - this existing opt-in smoke exercises `PlayerController` directly. It does
    not validate `PlaybackService`, foreground-service behavior, MediaStyle
    notification actions or Activity/background transitions;
  - verifies: buffering then `STATE_READY`/`isPlaying`, the video renderer's
    first frame (`Player.Listener.onRenderedFirstFrame`), simultaneous selected
    video+audio tracks (AV merge), decoded height versus the 720 maximum, a
    >= 3 s position advance, pause stability, seek, resume, and a 720p -> 360p
    -> 720p quality change preserving position and the selected tracks;
  - all waits are deadline-bounded (no loops), and the player, scope, backend
    and activity are released in `finally`;
  - never logs stream URLs, tokens or credentials.

## Command

```sh
. .tooling/env.sh
./bin/build :app:assembleDebugAndroidTest
adb -s emulator-5580 install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5580 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5580 shell am instrument -w -r \
  -e omatubePlaybackSmoke true \
  -e class dev.omatube.app.player.NativePlaybackNetworkSmokeTest \
  dev.omatube.app.test/androidx.test.runner.AndroidJUnitRunner
```

Default offline suite (no argument) skips this test through its opt-in gate;
build, unit tests and the other instrumented tests are unaffected.

## Final outcome

`OK (1 test)`, `Time: 20.995`. The captured run metrics (title only, never URLs):

```
PASS real Media3 playback: videoId=dQw4w9WgXcQ
  title=OmaTube native playback smoke dQw4w9WgXcQ
  states=BUFFERING,READY,BUFFERING,READY,BUFFERING,READY,BUFFERING,READY
  video=1280x720 selectedHeight=720
  tracks=video=true,audio=true
  position=19455 duration=213040
```

What the passing run proves, in order:

1. Real network source buffered (`STATE_BUFFERING`) before `STATE_READY` and
   `isPlaying == true`, with `MediaSourceResolver` + `ExoPlaybackEngine`.
2. The video renderer drew its first frame, and both a video and an audio track
   were selected at the same time (the merged AV source is real, not audio-only).
3. Decoded video `1280x720`, selected source height `720`, at or below the
   requested 720 maximum.
4. The position advanced by at least 3 s of real playback.
5. Pause held the position (drift under 500 ms), seek landed on the requested
   position (within 2 s), and resume advanced the position again.
6. A 360p change re-resolved and reloaded while preserving position (within 3 s)
   and keeping video+audio selected; returning to the 720p maximum then produced
   `selectedHeight == 720` and `videoSize 1280x720` with both tracks still
   selected and the position preserved.
7. The unit transport guard is the 53-test player package (including
   `YoutubeDashDataSourceTest` and `YoutubeRequestInterceptorTest`), part of the
   182-test JVM suite.

## Regression history (original failure, now fixed)

Before the transport fix, the same test failed in ~9.5 s and never reached
`STATE_READY`:

```
ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED: Source error
  caused by androidx.media3.common.ParserException:
  Skipping atom with length > 2147483647 (unsupported). {contentIsMalformed=false, dataType=1}
    at androidx.media3.extractor.mp4.FragmentedMp4Extractor.readAtomHeader
```

Failure state: `states=BUFFERING,IDLE`, `selectedHeight=720`,
`currentTracks video=true,audio=true`, `videoSize=0x0`, `position=0`,
`duration=213040 ms`.

Root cause: `PlaybackDataSources.kt` built `youtubeDash` with a `range` query
parameter and rewrote Media3's `Range` header into it, but sent OkHttp **GET**.
YouTube ignored the `range` parameter on these GET requests, so the
single-file `SegmentBase` DASH sources produced by `MediaSourceResolver` for
progressive video/audio fetched the wrong byte ranges. Evidence was captured
with the app's own factories (itag 136, `init=0-739 index=740-1227`):

| Request | Old `youtubeDash` | `Range`-header transport |
| --- | --- | --- |
| init 0-739 | 200, no `Content-Range`, full-file bytes | 206, `Content-Range: bytes 0-739/26455880` |
| index 740-1227 | `HttpDataSourceException` | 206, `bytes 740-1227/26455880` (`sidx`) |
| media 1228-7999 | 200, wrong bytes (`00000000 000007e0`) | 206, `bytes 1228-7999/26455880` (`moof`) |

The fix (made by the player implementer) removes the query translation and uses
the standard `Range` header for YouTube DASH while keeping `rn`. The transport
change touches no Android API surface: `PlaybackDataSources` now shares one
`createHttpFactory(rnParameter)` path and only appends `rn`.

## Scope and safety

- Only `emulator-5580` was used. `emulator-5582` was shut down and never touched.
- No user database, repository, API key or login is involved; no stream URL or
  credential is logged.
- The check is opt-in, offline-by-default, and never part of `bin/test`.
