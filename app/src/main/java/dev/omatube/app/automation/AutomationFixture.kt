package dev.omatube.app.automation

import dev.omatube.app.data.LibraryRepository
import dev.omatube.app.model.Channel
import dev.omatube.app.model.Video
import kotlinx.coroutines.flow.first
import java.time.Instant

/**
 * The fixed debug automation fixture, copied exactly from the desktop
 * `src/automationfixture.cpp` and `AUTOMATION.md` section 4.
 *
 * IDs, categories, channel membership, video ordering, the watch-history entry
 * and the Watch Next queue must stay identical so desktop and Android
 * automation drive equivalent state. All values are process-local because the
 * automation graph uses in-memory Room and settings.
 */
object AutomationFixture {

    val PUBLISHED_AT_EPOCH_MILLIS: Long = Instant.parse("2026-01-01T12:00:00.000Z").toEpochMilli()

    const val DURATION_SECONDS: Long = 600L
    const val HISTORY_POSITION_SECONDS: Long = 120L
    const val HISTORY_WATCHED_SECONDS: Long = 120L

    const val PLAYER_VIDEO_ID = "AUTO0000001"
    const val HISTORY_VIDEO_ID = "AUTO0000001"
    const val WATCH_NEXT_FIRST = "AUTO0000002"
    const val WATCH_NEXT_SECOND = "AUTO0000004"

    const val MUSIC_CATEGORY = "Automation Music"
    const val TECH_CATEGORY = "Automation Tech"

    val FIRST_CHANNEL = Channel(
        id = "UCautomation01",
        title = "Automation Channel One",
        originalInput = "UCautomation01",
        handle = "@automation",
        avatarUrl = "",
        uploadsPlaylistId = "UUautomation01",
        metadataFetchedAt = PUBLISHED_AT_EPOCH_MILLIS,
    )

    val SECOND_CHANNEL = Channel(
        id = "UCautomation02",
        title = "Automation Channel Two",
        originalInput = "UCautomation02",
        handle = "@automation",
        avatarUrl = "",
        uploadsPlaylistId = "UUautomation02",
        metadataFetchedAt = PUBLISHED_AT_EPOCH_MILLIS,
    )

    val channels: List<Channel> = listOf(FIRST_CHANNEL, SECOND_CHANNEL)

    val videos: List<Video> = listOf(
        video("AUTO0000001", FIRST_CHANNEL, "Automation Video 1", 0),
        video("AUTO0000002", FIRST_CHANNEL, "Automation Video 2", 60),
        video("AUTO0000003", FIRST_CHANNEL, "Automation Video 3", 120),
        video("AUTO0000004", SECOND_CHANNEL, "Automation Video 4", 180),
        video("AUTO0000005", SECOND_CHANNEL, "Automation Video 5", 240),
    )

    private fun video(
        id: String,
        channel: Channel,
        title: String,
        offsetSeconds: Long,
    ): Video = Video(
        id = id,
        channelId = channel.id,
        title = title,
        channelTitle = channel.title,
        publishedAt = PUBLISHED_AT_EPOCH_MILLIS - offsetSeconds * 1_000L,
        durationSeconds = DURATION_SECONDS,
        isLive = false,
        isUpcoming = false,
        thumbnailUrl = "",
    )

    /**
     * Seeds the fixture into a fresh repository. Safe to call on an already
     * seeded in-memory database: the guard keeps a second call from appending
     * duplicate history or Watch Next rows.
     */
    suspend fun seed(repository: LibraryRepository) {
        if (repository.snapshot.first().channels.isNotEmpty()) return

        val musicId = repository.addCategory(MUSIC_CATEGORY)
        val techId = repository.addCategory(TECH_CATEGORY)

        repository.upsertChannel(FIRST_CHANNEL)
        repository.upsertChannel(SECOND_CHANNEL)
        repository.setChannelCategories(FIRST_CHANNEL.id, setOf(musicId))
        repository.setChannelCategories(SECOND_CHANNEL.id, setOf(techId))

        repository.upsertVideos(videos)

        // The desktop fixture marks both channels' older history as complete so
        // automation never tries to page remote uploads.
        repository.setHistoryCursor(FIRST_CHANNEL.id, null, complete = true)
        repository.setHistoryCursor(SECOND_CHANNEL.id, null, complete = true)

        repository.recordPlayback(
            videoId = HISTORY_VIDEO_ID,
            positionSeconds = HISTORY_POSITION_SECONDS,
            watchedDeltaSeconds = HISTORY_WATCHED_SECONDS,
            newSession = true,
        )

        repository.addWatchNext(WATCH_NEXT_FIRST)
        repository.addWatchNext(WATCH_NEXT_SECOND)
    }
}
