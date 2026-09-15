package dev.omatube.app.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.session.MediaSession
import android.os.Build
import dev.omatube.app.MainActivity
import dev.omatube.app.R

/**
 * Builds the media transport notification and its playback channel with the
 * framework [Notification.MediaStyle] and [MediaSession] APIs. Kept separate
 * from the service so the channel, style and single compact action can be
 * asserted under Robolectric.
 */
internal object PlaybackNotifications {

    const val CHANNEL_ID = "playback"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.playback_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.playback_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(
        context: Context,
        sessionToken: MediaSession.Token,
        title: String,
        subtitle: String,
        playing: Boolean,
        loading: Boolean,
    ): Notification {
        val action = Notification.Action.Builder(
            Icon.createWithResource(
                context,
                if (playing) R.drawable.ic_notification_pause else R.drawable.ic_notification_play,
            ),
            context.getString(
                if (playing) R.string.notification_action_pause else R.string.notification_action_play,
            ),
            transportIntent(
                context,
                action = if (playing) PlaybackService.ACTION_PAUSE else PlaybackService.ACTION_PLAY,
                requestCode = if (playing) PlaybackService.REQUEST_PAUSE else PlaybackService.REQUEST_PLAY,
            ),
        ).build()

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_play)
            .setContentTitle(title)
            .setContentIntent(contentIntent(context))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(sessionToken)
                    .setShowActionsInCompactView(0),
            )
        when {
            loading -> builder.setContentText(context.getString(R.string.notification_loading))
            subtitle.isNotBlank() -> builder.setContentText(subtitle)
        }
        return builder.build()
    }

    fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            PlaybackService.REQUEST_CONTENT,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun transportIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, PlaybackService::class.java).setAction(action)
        return PendingIntent.getService(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
