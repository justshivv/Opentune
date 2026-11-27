package com.opentune.wear

import android.content.Context
import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Button
import androidx.wear.protolayout.material.Colors
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.MultiButtonLayout
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers

/**
 * The tile beside the watch face: what plays (here, or on the device the
 * watch controls), with play/pause and next. Tapping the song opens the app.
 */
class NowPlayingTile : TileService() {
    override fun onCreate() {
        super.onCreate()
        WatchNow.start(this)
    }

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        SuspendToFutureAdapter.launchFuture(Dispatchers.Main.immediate) {
            when (requestParams.currentState.lastClickableId) {
                TOGGLE -> WatchNow.togglePlayPause()
                NEXT -> WatchNow.skipNext()
                PREVIOUS -> WatchNow.skipPrevious()
            }
            val text = TileText.of(WatchNow.shown.value)
            val layout = layout(this@NowPlayingTile, requestParams.deviceConfiguration, text, WatchNow.progress())
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
                // While it plays the ring moves on: the tile asks again now and then while it's shown.
                .apply { if (text.playing) setFreshnessIntervalMillis(PROGRESS_REFRESH_MS) }
                .build()
        }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        SuspendToFutureAdapter.launchFuture(Dispatchers.Main.immediate) {
            ResourceBuilders.Resources.Builder()
                .setVersion(RESOURCES)
                .addIdToImageMapping(PLAY, image(R.drawable.tile_play))
                .addIdToImageMapping(PAUSE, image(R.drawable.tile_pause))
                .addIdToImageMapping(NEXT_ICON, image(R.drawable.tile_next))
                .addIdToImageMapping(PREVIOUS_ICON, image(R.drawable.tile_previous))
                .build()
        }

    private fun image(id: Int) = ResourceBuilders.ImageResource.Builder()
        .setAndroidResourceByResId(ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(id).build())
        .build()

    private fun layout(context: Context, device: DeviceParametersBuilders.DeviceParameters, text: TileText, progress: Float): LayoutElementBuilders.LayoutElement {
        val open = ModifiersBuilders.Clickable.Builder()
            .setId(OPEN)
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(ActionBuilders.AndroidActivity.Builder().setPackageName(context.packageName).setClassName(MainActivity::class.java.name).build())
                    .build(),
            )
            .build()
        fun load(id: String) = ModifiersBuilders.Clickable.Builder().setId(id).setOnClick(ActionBuilders.LoadAction.Builder().build()).build()
        val words = LayoutElementBuilders.Column.Builder()
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(open).build())
            .addContent(
                Text.Builder(context, text.title).setTypography(Typography.TYPOGRAPHY_TITLE3).setColor(androidx.wear.protolayout.ColorBuilders.argb(Colors.DEFAULT.onSurface)).setMaxLines(2).build(),
            )
            .addContent(
                Text.Builder(context, text.line).setTypography(Typography.TYPOGRAPHY_CAPTION1).setColor(androidx.wear.protolayout.ColorBuilders.argb(Colors.DEFAULT.onSurface)).setMaxLines(1).build(),
            )
            .build()
        val content = LayoutElementBuilders.Column.Builder().addContent(words)
        if (text.controls) {
            content.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(androidx.wear.protolayout.DimensionBuilders.dp(8f)).build())
            content.addContent(
                MultiButtonLayout.Builder()
                    .addButtonContent(Button.Builder(context, load(PREVIOUS)).setIconContent(PREVIOUS_ICON).setContentDescription("Previous").build())
                    .addButtonContent(Button.Builder(context, load(TOGGLE)).setIconContent(if (text.playing) PAUSE else PLAY).setContentDescription(if (text.playing) "Pause" else "Play").build())
                    .addButtonContent(Button.Builder(context, load(NEXT)).setIconContent(NEXT_ICON).setContentDescription("Next").build())
                    .build(),
            )
        }
        if (!text.controls) return PrimaryLayout.Builder(device).setResponsiveContentInsetEnabled(true).setContent(content.build()).build()
        // How far into the song, around the edge.
        val ring = androidx.wear.protolayout.material.CircularProgressIndicator.Builder()
            .setProgress(progress.coerceIn(0f, 1f))
            .setStrokeWidth(4f)
            .setContentDescription("Song progress")
            .build()
        return androidx.wear.protolayout.material.layouts.EdgeContentLayout.Builder(device)
            .setResponsiveContentInsetEnabled(true)
            .setEdgeContent(ring)
            .setContent(content.build())
            .build()
    }

    companion object {
        private const val RESOURCES = "2"
        private const val TOGGLE = "toggle"
        private const val NEXT = "next"
        private const val OPEN = "open"
        private const val PLAY = "play"
        private const val PAUSE = "pause"
        private const val NEXT_ICON = "next_icon"
        private const val PREVIOUS = "previous"
        private const val PREVIOUS_ICON = "previous_icon"
        /** How often a playing song's tile is drawn again, for its ring. */
        private const val PROGRESS_REFRESH_MS = 20_000L
    }
}

/** What the tile and the complication say about [Shown]; plain so tests can reach it. */
data class TileText(val title: String, val line: String, val playing: Boolean, val controls: Boolean) {
    companion object {
        fun of(shown: Shown?): TileText = if (shown == null) TileText("Nothing playing", "Tap to open OpenTune", playing = false, controls = false)
        else TileText(shown.song.title, shown.on?.let { "On $it" } ?: shown.song.artist, shown.playing, controls = true)

        /** The complication's short text: the song's title, or the app's name with nothing playing. */
        fun short(shown: Shown?): String = shown?.song?.title ?: "OpenTune"

        /** The complication's long text: "Title · Artist". */
        fun long(shown: Shown?): String = shown?.let { listOf(it.song.title, it.song.artist).filter(String::isNotBlank).joinToString(" · ") } ?: "Nothing playing"
    }
}
