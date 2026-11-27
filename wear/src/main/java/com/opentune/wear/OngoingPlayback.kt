package com.opentune.wear

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.google.common.collect.ImmutableList

/**
 * The playback notification as an ongoing activity: while music plays, the
 * watch face and the recent apps show OpenTune's icon, which opens the player.
 */
@UnstableApi
class OngoingPlayback(private val context: Context) : MediaNotification.Provider {
    private val base = DefaultMediaNotificationProvider(context)

    override fun createNotification(
        mediaSession: MediaSession,
        mediaButtonPreferences: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback,
    ): MediaNotification {
        val made = base.createNotification(mediaSession, mediaButtonPreferences, actionFactory, onNotificationChangedCallback)
        // Only an ongoing notification can carry one: a paused song's can be swiped away as before.
        if (made.notification.flags and Notification.FLAG_ONGOING_EVENT == 0) return made
        return runCatching {
            val builder = NotificationCompat.Builder(context, made.notification)
            val title = mediaSession.player.mediaMetadata.title?.toString().orEmpty()
            OngoingActivity.Builder(context, made.notificationId, builder)
                .setStaticIcon(R.drawable.ic_launcher_monochrome)
                .setTouchIntent(PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                .setStatus(Status.Builder().addTemplate(title.ifBlank { "OpenTune" }).build())
                .build()
                .apply(context)
            MediaNotification(made.notificationId, builder.build())
        }.getOrDefault(made)
    }

    override fun getNotificationChannelInfo(): MediaNotification.Provider.NotificationChannelInfo = base.notificationChannelInfo

    override fun handleCustomCommand(session: MediaSession, action: String, extras: Bundle): Boolean =
        base.handleCustomCommand(session, action, extras)
}
