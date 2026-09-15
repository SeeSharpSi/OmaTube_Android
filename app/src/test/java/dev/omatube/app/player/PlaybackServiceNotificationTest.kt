package dev.omatube.app.player

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.Intent
import android.media.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.omatube.app.model.Settings
import dev.omatube.app.model.Video
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric assertions for the media transport notification, its low-priority
 * channel, the start-intent serialization and the manifest surface that makes
 * background playback possible. Nothing here starts real playback or touches
 * the network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServiceNotificationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun playbackChannelIsLowImportance() {
        PlaybackNotifications.ensureChannel(context)

        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(PlaybackNotifications.CHANNEL_ID)

        assertThat(channel).isNotNull()
        assertThat(channel.importance).isEqualTo(NotificationManager.IMPORTANCE_LOW)
    }

    @Test
    fun transportActionReflectsPlaybackState() {
        val session = MediaSession(context, "test-session")
        try {
            val playing = PlaybackNotifications.build(
                context = context,
                sessionToken = session.sessionToken,
                title = "Title",
                subtitle = "Channel",
                playing = true,
                loading = false,
            )
            assertThat(playing.actions).hasLength(1)
            assertThat(playing.actions[0].title.toString()).isEqualTo("Pause")
            assertThat(playing.flags and Notification.FLAG_ONGOING_EVENT).isEqualTo(Notification.FLAG_ONGOING_EVENT)

            val paused = PlaybackNotifications.build(
                context = context,
                sessionToken = session.sessionToken,
                title = "Title",
                subtitle = "Channel",
                playing = false,
                loading = false,
            )
            assertThat(paused.actions).hasLength(1)
            assertThat(paused.actions[0].title.toString()).isEqualTo("Play")
            // Paused/loading transport must stay ongoing for the owning service.
            assertThat(paused.flags and Notification.FLAG_ONGOING_EVENT).isEqualTo(Notification.FLAG_ONGOING_EVENT)
        } finally {
            session.release()
        }
    }

    @Test
    fun startIntentRoundTripsVideoAndSettings() {
        val video = Video(id = "vid-1", channelId = "chan-1", title = "Title", channelTitle = "Channel")
        val settings = Settings(playbackVolume = 42, lastUsedVideoHeight = 480)

        val intent = PlaybackService.startIntent(context, video, settings)

        assertThat(intent.action).isEqualTo(PlaybackService.ACTION_START)
        assertThat(PlaybackService.decodeVideo(intent)).isEqualTo(video)
        assertThat(PlaybackService.decodeSettings(intent)).isEqualTo(settings)
    }

    @Test
    fun missingSettingsDecodeToNullForStoreFallback() {
        val intent = Intent(context, PlaybackService::class.java)

        assertThat(PlaybackService.decodeVideo(intent)).isNull()
        assertThat(PlaybackService.decodeSettings(intent)).isNull()
    }

    @Test
    fun mediaSessionIsRecreatedAfterRelease() {
        val serviceController = Robolectric.buildService(PlaybackService::class.java).create()
        val service = serviceController.get()
        try {
            val first = service.ensureMediaSession()
            service.releaseMediaSession()
            val second = service.ensureMediaSession()

            assertThat(second).isNotSameInstanceAs(first)
        } finally {
            serviceController.destroy()
        }
    }

    @Test
    fun serviceIsNonExportedMediaPlayback() {
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, PlaybackService::class.java),
            0,
        )

        assertThat(info.exported).isFalse()
        assertThat(info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            .isEqualTo(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    @Test
    fun foregroundNotificationAndWakePermissionsAreDeclared() {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val requested = info.requestedPermissions?.toList().orEmpty()

        assertThat(requested).containsAtLeast(
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.WAKE_LOCK,
        )
    }

    @Test
    fun notificationUsesMediaStyle() {
        val session = MediaSession(context, "test-session")
        try {
            val notification = PlaybackNotifications.build(
                context = context,
                sessionToken = session.sessionToken,
                title = "Title",
                subtitle = "Channel",
                playing = true,
                loading = false,
            )
            // MediaStyle.setMediaSession stores the token in the built extras,
            // which pins the framework MediaStyle usage.
            assertThat(notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)).isTrue()
        } finally {
            session.release()
        }
    }
}
