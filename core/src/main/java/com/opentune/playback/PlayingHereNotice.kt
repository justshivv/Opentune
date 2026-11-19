package com.opentune.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.MediaMetadata

/**
 * Says the phone is playing when its media notification can't be shown: Android 12 and later
 * refuse the player's foreground start while the app is in the background, as when another of
 * the person's devices hands its song to this phone (Connect) with the app closed. Tapping it
 * opens the player, and once the app is open the real notification takes over and this one goes.
 */
internal object PlayingHereNotice {
    private const val CHANNEL = "playing_here"
    private const val ID = 7_161
    private const val TAG = "PlayingHereNotice"

    /** Up now: a refused start repeats on each change, and posting again each time made it flicker. */
    @Volatile private var shown: String? = null

    fun show(context: Context, now: MediaMetadata?) {
        val key = "${now?.title}|${now?.artist}"
        if (shown == key) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, com.opentune.i18n.Tr.t("Playing on this phone"), NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            context, CHANNEL.hashCode(),
            com.opentune.data.AppHooks.openApp(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = now?.title?.toString()?.takeIf { it.isNotBlank() }
        val artist = now?.artist?.toString()?.takeIf { it.isNotBlank() }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(com.opentune.core.R.drawable.ic_stat_opentune)
            .setContentTitle(title ?: com.opentune.i18n.Tr.t("Playing on this phone"))
            .setContentText(
                listOfNotNull(artist, com.opentune.i18n.Tr.t("Tap to show the player")).joinToString(" · "),
            )
            .setContentIntent(open)
            .setOngoing(false)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ID, notification)
            shown = key
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed to notify", e)
        }
    }

    fun cancel(context: Context) {
        if (shown == null) return
        shown = null
        runCatching { NotificationManagerCompat.from(context).cancel(ID) }
    }
}
