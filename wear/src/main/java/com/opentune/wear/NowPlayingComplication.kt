package com.opentune.wear

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/** A watch-face complication: the song playing (here or on the controlled device); a tap opens the player. */
class NowPlayingComplication : SuspendingComplicationDataSourceService() {
    override fun onCreate() {
        super.onCreate()
        WatchNow.start(this)
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        data(this, request.complicationType, WatchNow.shown.value)

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        data(this, type, null, preview = true)

    companion object {
        fun update(context: Context) {
            ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, NowPlayingComplication::class.java)).requestUpdateAll()
        }

        private fun data(context: Context, type: ComplicationType, shown: Shown?, preview: Boolean = false): ComplicationData? {
            val icon = Icon.createWithResource(context, R.drawable.ic_launcher_monochrome)
            val tap = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val describe = PlainComplicationText.Builder(TileText.long(shown)).build()
            fun text(s: String) = PlainComplicationText.Builder(s).build()
            return when (type) {
                ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text(if (preview) "Song" else TileText.short(shown)), describe)
                    .setMonochromaticImage(MonochromaticImage.Builder(icon).build())
                    .setTapAction(tap)
                    .build()
                ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(text(if (preview) "Song · Artist" else TileText.long(shown)), describe)
                    .setMonochromaticImage(MonochromaticImage.Builder(icon).build())
                    .setTapAction(tap)
                    .build()
                ComplicationType.SMALL_IMAGE -> SmallImageComplicationData.Builder(SmallImage.Builder(icon, SmallImageType.ICON).build(), describe)
                    .setTapAction(tap)
                    .build()
                else -> null
            }
        }
    }
}
