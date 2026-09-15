# Android implementation contract

Status: final for the current implementation. The decisions below describe the
shipped architecture and supersede any earlier speculative notes.

## Decisions

- Native Kotlin and Compose Foundation UI, styled from the desktop QML. Media3 ExoPlayer renders video under custom controls. NewPipeExtractor v0.26.5 is the primary extractor.
- The desktop source at `/home/cassian/projects/cpp/OmaTube` is the visual and behavior reference. Full UI has thumbnail cards. Simple UI has text feed/history/Watch Next title rows. Settings and bottom navigation use shared Normal UI chrome, fonts and geometry in both modes. Preserve both. Remove keyboard hints and bindings. Provide touch navigation for routes previously reachable only by keyboard.
- Reuse the three bundled theme palettes. Do not apply Material defaults or dynamic colors. The Android Default palette intentionally uses its palette blue as the global accent instead of the desktop near-black accent so seek progress and accent chrome stay visible. Small screens need constrained wrapping and scrolling, not an unrelated mobile redesign. Player chrome follows `qml/PlayerControls.qml`.
- Refresh on process startup and explicit user action only. No periodic WorkManager or refresh on every Activity recreation. Preserve cached feed and last-known live status if live extraction fails.
- Android Room schema starts at version 1. Desktop SQLite schema versions are not Room migration versions. Desktop interoperability uses its existing version-1 channels/categories JSON formats, not copying desktop SQLite files.
- Package `dev.omatube.app`, minSdk 26, compile/targetSdk 36. GPL-3.0-or-later. Dependencies pinned. Tools installed privately under `.tooling`, ignored by version control. Do not modify desktop source or install system packages.
- Media3 must merge separate video and audio sources using `MergingMediaSource`, as NewPipe actually does. Prefer live DASH/HLS manifests for live content. Never silently play a video-only source without audio.
- PoToken: there is no app-side token provider and no hidden WebView. The pinned NewPipeExtractor v0.26.5 stream extractor invokes only the Android and iOS client token getters and uses the Android-Reel player response for public VOD playback; its Web/WebEmbed getters are declared but not called. Earlier notes that called token generation a required integration were speculative and are superseded. See docs/ANDROID_BACKENDS.md.
- Fonts: bundle the exact desktop faces (JetBrains Mono Nerd Font and Liberation Sans, Regular and Bold) and declare them in OmaTypography; do not rely on platform Monospace/Default. Ship the base OFL plus the Nerd Fonts patcher MIT and added-glyph notices; see THIRD_PARTY_NOTICES.md.
- Adapted upstream code (NewPipe `MediaSourceResolver`/`PlaybackDataSources`/live DASH parser and itag list; desktop Atom, channel, SponsorBlock, fixture and palette sources) keeps its upstream copyright and GPL-3.0-or-later attribution. The full GPL text is COPYING.
- Automation must use in-memory Room, isolated settings, seeded desktop fixture IDs, fake extraction/playback, and no remote images or network. Exposed only in debug builds.

## Initial work split (historical)

All modules below are now implemented; this list records the original
partitioning and is no longer a live ownership map. Repository-level ownership
and conventions are in AGENTS.md.

- Foundation agent: Gradle, manifest, resources, `model/Models.kt`, `data/Contracts.kt`, `backend/VideoBackend.kt`. Publish these contracts before other implementation.
- Persistence agent: `data/` implementations, excluding `Contracts.kt`.
- Backend agent: `backend/` implementations, excluding `VideoBackend.kt`, and backend test files.
- Feed UI agent: `ui/` excluding `ui/settings/` and `ui/player/`; theme and reusable controls included. Do not own root Activity or ViewModel.
- Settings UI agent: `ui/settings/` only.
- Player agent: `player/`, `ui/player/`.
- Integration agent: `AppViewModel.kt`, `OmaTubeApplication.kt`, `MainActivity.kt`, debug fixture and root UI wiring.
- Build coordinator owns tool bootstrap, integration fixes and final review through delegated tasks.

## Public Kotlin models

All models in `dev.omatube.app.model`, immutable data classes with defaults where shown. Millisecond timestamps use UTC epoch values; video positions/durations use seconds.

```kotlin
data class Category(val id: Long, val name: String, val sortOrder: Int = 0)
data class Channel(val id: String, val title: String, val originalInput: String = "", val handle: String = "", val avatarUrl: String = "", val uploadsPlaylistId: String = "", val metadataFetchedAt: Long = 0, val categoryIds: Set<Long> = emptySet())
data class Video(val id: String, val channelId: String, val title: String, val channelTitle: String = "", val publishedAt: Long = 0, val durationSeconds: Long = -1, val isLive: Boolean = false, val isUpcoming: Boolean = false, val thumbnailUrl: String = "", val watchedSeconds: Long = 0, val lastPositionSeconds: Long = 0, val watchCount: Int = 0, val lastWatchedAt: Long = 0, val queuePosition: Int = -1)
data class HistoryEntry(val id: Long, val video: Video, val watchedAt: Long)
data class LibrarySnapshot(val categories: List<Category> = emptyList(), val channels: List<Channel> = emptyList(), val videos: List<Video> = emptyList(), val history: List<HistoryEntry> = emptyList(), val watchNext: List<Video> = emptyList())
enum class SponsorAction { NONE, MANUAL, AUTO }
data class SponsorSegment(val startSeconds: Double, val endSeconds: Double, val category: String)
data class Settings(val themeId: String = "default", val simpleUi: Boolean = false, val shortVideoCutoffMinutes: Int = 3, val wifiMaximumVideoHeight: Int = 0, val dataMaximumVideoHeight: Int = 0, val lastUsedVideoHeight: Int = 0, val playbackVolume: Int = 100, val sponsorBlockEnabled: Boolean = false, val sponsorActions: Map<String, SponsorAction> = emptyMap(), val apiKey: String = "", val rememberApiKey: Boolean = false, val videoQualityOverrides: Map<String, Int> = emptyMap())
data class VideoPage(val videos: List<Video>, val nextPage: String? = null)
```

## Persistence contract

`dev.omatube.app.data.LibraryRepository` interface:

```kotlin
val snapshot: Flow<LibrarySnapshot>
suspend fun addCategory(name: String): Long
suspend fun renameCategory(id: Long, name: String)
suspend fun removeCategory(id: Long)
suspend fun moveCategory(id: Long, toIndex: Int)
suspend fun upsertChannel(channel: Channel)
suspend fun removeChannel(id: String)
suspend fun setChannelCategories(channelId: String, categoryIds: Set<Long>)
suspend fun upsertVideos(videos: List<Video>)
suspend fun replaceLive(channelId: String, videos: List<Video>)
suspend fun recordPlayback(videoId: String, positionSeconds: Long, watchedDeltaSeconds: Long, newSession: Boolean)
suspend fun deleteHistory(id: Long)
suspend fun clearHistory()
suspend fun addWatchNext(videoId: String)
suspend fun removeWatchNext(videoId: String)
suspend fun moveWatchNext(videoId: String, toIndex: Int)
suspend fun historyCursor(channelId: String): String?
suspend fun setHistoryCursor(channelId: String, cursor: String?, complete: Boolean)
suspend fun historyComplete(channelId: String): Boolean
suspend fun canFetchHistory(): Boolean
suspend fun pruneCache()
suspend fun exportChannels(): String
suspend fun exportCategories(): String
suspend fun importJson(json: String): Int
```

`SettingsStore` exposes `val settings: Flow<Settings>` and `suspend fun update(transform: (Settings) -> Settings)`. Implementations named `RoomLibraryRepository(context: Context, inMemory: Boolean = false)` and `DataStoreSettingsStore(context: Context, inMemory: Boolean = false)`. No destructive migration fallback. Repository validation throws actionable exceptions, caught by ViewModel.

## Backend contract

`dev.omatube.app.backend.VideoBackend` interface:

```kotlin
suspend fun resolveChannel(input: String): Channel
suspend fun recentVideos(channel: Channel): VideoPage
suspend fun olderVideos(channel: Channel, cursor: String?): VideoPage
suspend fun liveVideos(channel: Channel): List<Video>
suspend fun resolveStream(videoId: String): org.schabi.newpipe.extractor.stream.StreamInfo
suspend fun sponsorSegments(videoId: String): List<SponsorSegment>
```

Implementation `NewPipeBackend(context: Context, settings: () -> Settings)`. Bounded coroutine I/O, cancelable requests, Atom fast path, optional Data API metadata path when key supplied. Stream extraction always NewPipe. Successful empty live results clear live state; exceptions never clear it. Cursor strings must survive process restart or explicitly cause safe first-page re-extraction.

Playback quality resolves in this order: per-video override, active-connection
preferred maximum (`wifiMaximumVideoHeight` or `dataMaximumVideoHeight`), then
`lastUsedVideoHeight` when that preference is `LAST_USED`, with `AUTO` allowing
the best available track. Player resolution samples connectivity immediately
before each resolution. Wi-Fi uses the Wi-Fi preference and cellular uses Data;
other transports use Data when metered and Wi-Fi when unmetered, while no active
network uses Data. Automation uses synthetic Data classification and never
touches Android connectivity services.

## UI entry points

Theme/common UI agent exposes:

```kotlin
@Composable fun OmaTheme(themeId: String, content: @Composable () -> Unit)
@Composable fun LibraryScreen(library: LibrarySnapshot, settings: Settings, route: String, selectedCategoryId: Long, refreshing: Boolean, loadingMore: Boolean, hasMore: Boolean, status: String, error: String?, automation: Boolean, onRoute: (String) -> Unit, onCategory: (Long) -> Unit, onMoveCategory: (Long, Int) -> Unit, onRefresh: () -> Unit, onLoadMore: () -> Unit, onOpenVideo: (Video) -> Unit, onAddWatchNext: (String) -> Unit, onRemoveWatchNext: (String) -> Unit, onMoveWatchNext: (String, Int) -> Unit, onDeleteHistory: (Long) -> Unit, onDismissError: () -> Unit)
```

Routes: `feed`, `history`, `watchnext`, `settings`. UI filters local data by selected category and cutoff, never performs network. Feed shows at most page-sized items as controlled by root/library snapshot. Layout should use custom colors, borders, fonts and glyphs; stable test tags mirror QML objectNames. Settings and player may define private matching styled controls to avoid common-component dependency races.

Full UI renders edge to edge: the Activity root paints `LocalOmaColors.current.background` behind the status and gesture navigation bars while non-player content still respects `safeDrawingPadding`, and system-bar icon appearance follows the background luminance. On older releases that still honor opaque resource bars, the non-player Full UI also themes the status, navigation and navigation-divider window colors to the palette background, while Simple UI and the player restore the window theme colors. Phone-width feed and History cards plus accent dividers run to safe-area edges; Watch Next keeps its internal grid inset, and Full History keeps vertical grid padding only. Simple Feed, History and Watch Next use title rows with the prior 56 dp side margin. Settings and the full-width shared bottom navigation use Normal UI chrome, fonts and geometry in both modes. Full UI shows refresh status and error banners as transient top-right notices that auto-hide four seconds after work is inactive and dismiss on tap, while Simple UI keeps its persisted bottom-anchored notices. Full UI drops Watch Next and Watch History top headers; its Watch Next count sits adjacent to the bottom-bar title in a weighted group that keeps five 38 dp navigation controls fixed. Simple Watch Next keeps its title-row count and inline queue controls. The full refresh button swaps its glyph for the existing braille spinner while refreshing at the same 38 dp size.

Settings UI exposes `SettingsScreen(library: LibrarySnapshot, settings: Settings, addingChannel: Boolean, error: String?, onClose: () -> Unit, onSettingsChange: (Settings) -> Unit, onAddChannel: (String, Set<Long>) -> Unit, onRemoveChannel: (String) -> Unit, onSetChannelCategories: (String, Set<Long>) -> Unit, onAddCategory: (String) -> Unit, onRenameCategory: (Long, String) -> Unit, onRemoveCategory: (Long) -> Unit, onImport: (Uri) -> Unit, onExport: (Uri, Boolean) -> Unit, onDismissError: () -> Unit)`. Export Boolean true means channels, false means categories. SAF launchers belong in SettingsScreen.

Player UI exposes `PlayerScreen(video: Video, settings: Settings, backend: VideoBackend, automation: Boolean, onClose: () -> Unit, onSettingsChange: (Settings) -> Unit, onReportPlayback: (String, Long, Long, Boolean) -> Unit)`. Report arguments: video ID, current position seconds, actual elapsed playing seconds, first report for new watch session. Avoid seek jumps counting as watched time. `PlaybackService` owns real `PlayerController`, ExoPlayer, source selection, MediaSession, MediaStyle notification and watch accounting; local fake automation owns its controller and `FakePlaybackEngine` without service, network or real media. The real service uses foreground media-playback permissions, wake mode, audio focus and audio-becoming-noisy handling. Playback continues when Activity stops, the screen locks or the user goes home. Explicit close releases controller, MediaSession, foreground state and notification; controller pause/seek/release flushes watch accounting, which persists through the service repository callback. Player module otherwise owns controls, lifecycle, SponsorBlock, volume, quality, fullscreen and playback errors. Seek-bar contact suppresses the chrome auto-hide while held, and releasing it restarts the full idle delay. The scrub timestamp is centered above the bottom controls rather than following the handle. Portrait layout is keyed on physical orientation: the display-cutout-safe container reserves a 52 dp top slot and the bottom-bar slot, brackets an aspect-fitted Media3 surface whose ratio tracks `VideoSize`, and keeps the video stable when the chrome hides. Loading renders without the chrome and a center transport appears in the normal state. The fullscreen icon derives its state from the current orientation rather than a stored flag.

Root application owns repository/backend instances, process-once startup, ViewModel and collected state. Activity recreation must preserve state, avoid duplicated refresh and resume playback correctly. Debug automation intent extras: `automation=true`, `automation_ui=full|simple`, optional `automation_route=feed|history|watchnext|settings|player`, `automation_theme=default|rose-pine|nord`. Never allow extras to activate fake mode in release.
