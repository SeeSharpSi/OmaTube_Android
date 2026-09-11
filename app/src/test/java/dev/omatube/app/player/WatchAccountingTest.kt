package dev.omatube.app.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WatchAccountingTest {
    private var now = 1_000L
    private val watch = WatchAccounting(
        clockMs = { now },
        minimumSessionSeconds = 5,
        reportIntervalMs = 5_000,
        maxTickCreditMs = 10_000,
    )

    @Test
    fun creditsMonotonicWatchTimeEveryFiveSeconds() {
        watch.start("video", 0L)
        watch.onPosition(0L, playing = true)
        repeat(5) { second ->
            now += 1_000L
            watch.onPosition(second + 1L, playing = true)
        }

        val report = watch.collect()
        assertThat(report).isNotNull()
        assertThat(report!!.videoId).isEqualTo("video")
        assertThat(report.positionSeconds).isEqualTo(5L)
        assertThat(report.elapsedWatchedDeltaSeconds).isEqualTo(5L)
        assertThat(report.newSession).isTrue()

        assertThat(watch.collect()).isNull()
    }

    @Test
    fun newSessionIsReportedOncePerSession() {
        watch.start("video", 0L)
        watch.onPosition(0L, playing = true)
        repeat(5) {
            now += 1_000L
            watch.onPosition(it + 1L, playing = true)
        }
        assertThat(watch.collect()!!.newSession).isTrue()

        repeat(5) {
            now += 1_000L
            watch.onPosition(it + 6L, playing = true)
        }
        val second = watch.collect()
        assertThat(second).isNotNull()
        assertThat(second!!.newSession).isFalse()
    }

    @Test
    fun seekJumpIsNotCredited() {
        watch.start("video", 0L)
        watch.onPosition(0L, playing = true)
        now += 1_000L
        watch.onPosition(1L, playing = true)

        // Seeking forward resets the baseline; the jump itself is not watched.
        watch.onSeek(120L)
        now += 5_000L
        watch.onPosition(120L, playing = true)

        val report = watch.collect(force = true)
        assertThat(report).isNotNull()
        assertThat(report!!.elapsedWatchedDeltaSeconds).isEqualTo(1L)
        assertThat(report.positionSeconds).isEqualTo(120L)
    }

    @Test
    fun pauseFlushesPendingWatchTime() {
        watch.start("video", 0L)
        watch.onPosition(0L, playing = true)
        now += 2_000L
        watch.onPosition(2L, playing = true)
        watch.onPosition(2L, playing = false)

        val report = watch.collect(force = true)
        assertThat(report).isNotNull()
        assertThat(report!!.elapsedWatchedDeltaSeconds).isEqualTo(2L)
    }

    @Test
    fun shortSessionDoesNotCount() {
        watch.start("video", 0L)
        watch.onPosition(0L, playing = true)
        now += 1_500L
        watch.onPosition(1L, playing = true)

        val report = watch.collect(force = true)
        assertThat(report).isNotNull()
        assertThat(report!!.newSession).isFalse()
        assertThat(report.elapsedWatchedDeltaSeconds).isEqualTo(1L)
    }

    @Test
    fun stopFlushesAndClearsSession() {
        watch.start("video", 0L)
        watch.onPosition(0L, playing = true)
        now += 3_000L
        watch.onPosition(3L, playing = true)

        val report = watch.stop()
        assertThat(report).isNotNull()
        assertThat(report!!.elapsedWatchedDeltaSeconds).isEqualTo(3L)
        assertThat(watch.collect(force = true)).isNull()
    }

    @Test
    fun zeroWatchCloseEmitsNothing() {
        watch.start("video", 0L)

        assertThat(watch.collect(force = true)).isNull()
        assertThat(watch.stop()).isNull()
    }

    @Test
    fun subSecondWatchIsNotReported() {
        watch.start("video", 0L)
        watch.onPosition(0L, playing = true)
        now += 500L
        watch.onPosition(0L, playing = true)

        assertThat(watch.collect(force = true)).isNull()
    }

    @Test
    fun oneAndAHalfSecondsReportsOneSecond() {
        watch.start("video", 0L)
        watch.onPosition(0L, playing = true)
        now += 1_500L
        watch.onPosition(1L, playing = true)

        val report = watch.collect(force = true)
        assertThat(report).isNotNull()
        assertThat(report!!.elapsedWatchedDeltaSeconds).isEqualTo(1L)
        assertThat(report.newSession).isFalse()
    }
}
