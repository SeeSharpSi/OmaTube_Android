package dev.omatube.app.player

import android.os.SystemClock

/**
 * Accounts for the time a viewer actually spends watching, independent of the
 * media position, so seeks and buffering gaps are never credited.
 *
 * Behavior mirrors the desktop `WatchTracker` semantics that the report
 * callback expects:
 *
 * - Reports are produced at most every five seconds of credited watch time.
 * - A forced collection happens on pause, seek and close so the final position
 *   and remaining delta are not lost.
 * - The first report of a session that reaches the minimum watch time carries
 *   `newSession = true` exactly once, so the repository bumps the watch count a
 *   single time per session.
 * - A seek resets the elapsed baseline; the jump itself is not credited.
 *
 * The clock is injected for deterministic tests.
 */
class WatchAccounting(
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val minimumSessionSeconds: Long = 5,
    private val reportIntervalMs: Long = 5_000,
    private val maxTickCreditMs: Long = 10_000,
) {
    /**
     * A single `onReportPlayback` payload. [elapsedWatchedDeltaSeconds] is the
     * credited watch time since the previous report for this session.
     */
    data class Report(
        val videoId: String,
        val positionSeconds: Long,
        val elapsedWatchedDeltaSeconds: Long,
        val newSession: Boolean,
    )

    private var videoId: String? = null
    private var positionSeconds: Long = 0
    private var lastTickMs: Long = NO_TICK
    private var accruedMs: Long = 0
    private var pendingMs: Long = 0
    private var sessionCounted = false
    private var pendingNewSession = false

    /** Begins a fresh session. Any previous session must be collected first. */
    fun start(videoId: String, initialPositionSeconds: Long) {
        this.videoId = videoId
        positionSeconds = initialPositionSeconds.coerceAtLeast(0)
        lastTickMs = NO_TICK
        accruedMs = 0
        pendingMs = 0
        sessionCounted = false
        pendingNewSession = false
    }

    /**
     * Feeds the latest media position and whether playback is progressing.
     * Call this on every position tick (roughly once per second is enough).
     */
    fun onPosition(positionSeconds: Long, playing: Boolean) {
        this.positionSeconds = positionSeconds.coerceAtLeast(0)
        if (!playing) {
            lastTickMs = NO_TICK
            return
        }
        val now = clockMs()
        if (lastTickMs != NO_TICK) {
            val delta = (now - lastTickMs).coerceIn(0L, maxTickCreditMs)
            if (delta > 0L) {
                credit(delta)
            }
        }
        lastTickMs = now
    }

    /** Records a user or automatic seek. The jump is not credited. */
    fun onSeek(positionSeconds: Long) {
        this.positionSeconds = positionSeconds.coerceAtLeast(0)
        lastTickMs = NO_TICK
    }

    /**
     * Returns a report when at least one report interval of watch time accrued,
     * or unconditionally when [force] is set (pause, seek, close). Returns null
     * when there is nothing meaningful to report.
     */
    fun collect(force: Boolean = false): Report? {
        val id = videoId ?: return null
        val due = force || pendingMs >= reportIntervalMs
        if (!due) return null
        // A forced flush still must not create history for a zero-watch close.
        // Sub-second credit is dropped, matching the desktop integer-second
        // accounting.
        if (pendingMs < MINIMUM_REPORTABLE_MS && !pendingNewSession) return null
        val report = Report(
            videoId = id,
            positionSeconds = positionSeconds,
            elapsedWatchedDeltaSeconds = pendingMs / 1000L,
            newSession = pendingNewSession,
        )
        pendingMs = 0
        pendingNewSession = false
        return report
    }

    /** Flushes the active session and clears it. */
    fun stop(): Report? {
        val report = collect(force = true)
        videoId = null
        positionSeconds = 0
        lastTickMs = NO_TICK
        accruedMs = 0
        pendingMs = 0
        sessionCounted = false
        pendingNewSession = false
        return report
    }

    private fun credit(deltaMs: Long) {
        accruedMs += deltaMs
        pendingMs += deltaMs
        if (!sessionCounted && accruedMs >= minimumSessionSeconds * 1000L) {
            sessionCounted = true
            pendingNewSession = true
        }
    }

    private companion object {
        const val NO_TICK = -1L
        const val MINIMUM_REPORTABLE_MS = 1_000L
    }
}
