package dev.omatube.app.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.dash.manifest.Period
import androidx.media3.exoplayer.dash.manifest.ProgramInformation
import androidx.media3.exoplayer.dash.manifest.ServiceDescriptionElement
import androidx.media3.exoplayer.dash.manifest.UtcTimingElement

/**
 * Fixes YouTube live DASH manifests so playback can start from the newest
 * available period. Mirrors the upstream NewPipe `YoutubeDashLiveManifestParser`
 * (GPL-3.0-or-later, TeamNewPipe) by forcing `availabilityStartTime` to the Unix
 * epoch, which is what the upstream workaround uses.
 */
@OptIn(UnstableApi::class)
class YoutubeDashLiveManifestParser : DashManifestParser() {
    override fun buildMediaPresentationDescription(
        availabilityStartTime: Long,
        durationMs: Long,
        minBufferTimeMs: Long,
        dynamic: Boolean,
        minUpdateTimeMs: Long,
        timeShiftBufferDepthMs: Long,
        suggestedPresentationDelayMs: Long,
        publishTimeMs: Long,
        programInformation: ProgramInformation?,
        utcTiming: UtcTimingElement?,
        serviceDescription: ServiceDescriptionElement?,
        location: Uri?,
        periods: List<Period>,
    ): DashManifest {
        return super.buildMediaPresentationDescription(
            AVAILABILITY_START_TIME,
            durationMs,
            minBufferTimeMs,
            dynamic,
            minUpdateTimeMs,
            timeShiftBufferDepthMs,
            suggestedPresentationDelayMs,
            publishTimeMs,
            programInformation,
            utcTiming,
            serviceDescription,
            location,
            periods,
        )
    }

    private companion object {
        /** `Util.parseXsDateTime("1970-01-01T00:00:00Z")`. */
        const val AVAILABILITY_START_TIME = 0L
    }
}
