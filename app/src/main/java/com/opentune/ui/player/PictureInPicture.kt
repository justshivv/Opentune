package com.opentune.ui.player

import android.app.Activity
import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Process
import android.util.Rational
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.opentune.data.model.artworkAt
import com.opentune.i18n.Tr
import com.opentune.ui.components.Artwork
import com.opentune.ui.theme.AppIcons
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.roundToInt

/** What can put the app into picture-in-picture with the music video: the main activity. */
interface PipHost {
    /** Goes into picture-in-picture now; false when Android refused or it isn't allowed. */
    fun enterPictureInPicture(): Boolean
}

/**
 * The music video in a small window over other apps (Android's picture-in-picture).
 *
 * Only ever for a song playing as its video: the player's button for it shows only then,
 * and pressing Home goes into it on its own only while a video plays in the open player
 * (Android 12 and up through auto-enter, older ones through the activity's leave hint).
 * The window shows the picture alone, at the video's own shape, with Previous,
 * Play or Pause and Next under it.
 *
 * The state here is shared between the player screen, which knows where the picture is
 * and whether it's on screen, and the activity, which tells Android.
 */
object PictureInPicture {
    /** The broadcast the window's buttons send; only this app's own receiver hears it. */
    const val ACTION = "com.opentune.action.PICTURE_IN_PICTURE_CONTROL"
    const val EXTRA_CONTROL = "control"

    /**
     * The widest (and, turned over, the tallest) shape Android allows a window: 2.39:1. Kept a
     * hair inside, since Android compares the shape as a float against the phone's own limit.
     */
    const val MAX_ASPECT = 2.38f

    private val _active = MutableStateFlow(false)
    /** True while the app is showing as the small window. */
    val active: StateFlow<Boolean> = _active.asStateFlow()
    fun setActive(on: Boolean) { _active.value = on }

    private val _allowed = MutableStateFlow(false)
    /** Whether this phone has picture-in-picture and it isn't turned off for the app. */
    val allowed: StateFlow<Boolean> = _allowed.asStateFlow()
    fun setAllowed(on: Boolean) { _allowed.value = on }

    private val _bounds = MutableStateFlow<android.graphics.Rect?>(null)
    /** Where the picture last showed in the window, for the shrink into the small window to start from. */
    val bounds: StateFlow<android.graphics.Rect?> = _bounds.asStateFlow()

    /** Where the picture shows now, in the window; small moves are let be. */
    fun report(rect: android.graphics.Rect?) {
        val old = _bounds.value
        if (rect == null || rect.isEmpty) return
        if (old != null && abs(old.left - rect.left) < 4 && abs(old.top - rect.top) < 4 &&
            abs(old.right - rect.right) < 4 && abs(old.bottom - rect.bottom) < 4
        ) return
        _bounds.value = rect
    }

    private val shownBy = MutableStateFlow<Set<Any>>(emptySet())
    /** The open players showing the video right now (the sheet's, or the docked one's). */
    val showing: StateFlow<Set<Any>> = shownBy.asStateFlow()

    /** [owner], a player, shows the video on screen ([on]) or no longer does. */
    fun showing(owner: Any, on: Boolean) {
        shownBy.value = if (on) shownBy.value + owner else shownBy.value - owner
    }

    /**
     * The player's video output, for the small window to draw the same picture into. Set
     * while a player is built; the picture is drawn in one place at a time.
     */
    internal var output by mutableStateOf<VideoOutput?>(null)

    /** The player shows its picture-in-picture button: only for a song playing as its video, where the phone allows it. */
    fun buttonShown(videoMode: Boolean, available: Boolean): Boolean = videoMode && available

    /**
     * Pressing Home goes into the small window on its own: only while a song plays as its
     * video in the open player, never for audio, nor while already in the window.
     */
    fun autoEnter(allowed: Boolean, videoMode: Boolean, playerShowsVideo: Boolean, playing: Boolean, inWindow: Boolean): Boolean =
        allowed && videoMode && playerShowsVideo && playing && !inWindow

    /** The window's shape for a picture [ratio] wide over high, held within Android's limits; null while unknown. */
    fun aspect(ratio: Float): Rational? {
        if (!ratio.isFinite() || ratio <= 0f) return null
        val held = ratio.coerceIn(1f / MAX_ASPECT, MAX_ASPECT)
        return Rational((held * ASPECT_SCALE).roundToInt(), ASPECT_SCALE)
    }

    /** The window's buttons. */
    enum class Control(val code: Int, val label: String, val icon: Int) {
        PREVIOUS(1, "Previous", AppIcons.SkipPrevious),
        PLAY(2, "Play", AppIcons.PlayArrow),
        PAUSE(3, "Pause", AppIcons.Pause),
        NEXT(4, "Next", AppIcons.SkipNext);

        companion object {
            fun of(code: Int): Control? = entries.firstOrNull { it.code == code }
        }
    }

    /** A button and whether it can be pressed. */
    data class Button(val control: Control, val enabled: Boolean = true)

    /** Previous, then Play or Pause as the song is, then Next (dimmed with nothing after this song). */
    fun buttons(playing: Boolean, hasNext: Boolean): List<Button> = listOf(
        Button(Control.PREVIOUS),
        Button(if (playing) Control.PAUSE else Control.PLAY),
        Button(Control.NEXT, enabled = hasNext),
    )

    /** What the window is told. */
    data class Spec(
        val aspect: Rational?,
        val sourceRect: android.graphics.Rect?,
        val playing: Boolean,
        val hasNext: Boolean,
        val autoEnter: Boolean,
        val title: String? = null,
        val subtitle: String? = null,
    )

    /** Android's description of the window: its shape, where it grows out of, its buttons. */
    fun params(context: Context, spec: Spec): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setActions(buttons(spec.playing, spec.hasNext).map { remoteAction(context, it) })
        spec.aspect?.let(builder::setAspectRatio)
        // A hint off the shape the window takes makes Android's shrink jump at the end.
        spec.sourceRect?.takeIf { !it.isEmpty }?.let(builder::setSourceRectHint)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(spec.autoEnter)
            // A video: no seamless resize, so the picture isn't stretched while the window changes size.
            builder.setSeamlessResizeEnabled(false)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            spec.title?.takeIf { it.isNotBlank() }?.let(builder::setTitle)
            spec.subtitle?.takeIf { it.isNotBlank() }?.let(builder::setSubtitle)
        }
        return builder.build()
    }

    private fun remoteAction(context: Context, button: Button): RemoteAction {
        val control = button.control
        // Package-explicit and immutable; heard only by the activity's receiver, which isn't exported.
        val intent = Intent(ACTION).setPackage(context.packageName).putExtra(EXTRA_CONTROL, control.code)
        val pending = PendingIntent.getBroadcast(
            context, control.code, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val label = Tr.t(control.label)
        return RemoteAction(Icon.createWithResource(context, control.icon), label, label, pending).apply {
            isEnabled = button.enabled
        }
    }

    /** Whether this phone has picture-in-picture and the person hasn't turned it off for the app. */
    fun isAllowed(context: Context): Boolean {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return true
        return runCatching {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, Process.myUid(), context.packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED || mode == AppOpsManager.MODE_DEFAULT
        }.getOrDefault(true)
    }

    private const val ASPECT_SCALE = 10_000
}

/** The activity under a Compose context, through any wrappers (the language one, say). */
internal tailrec fun Context.hostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.hostActivity()
    else -> null
}

/**
 * What the small window shows: the picture alone, filling it. A song with no video
 * playing (the next one in the queue, say) shows its cover large instead, so the
 * window stays useful, its buttons still there, rather than going black.
 */
@Composable
internal fun PictureInPictureContent(
    artworkUrl: String?,
    videoMode: Boolean,
    picture: (@Composable BoxScope.() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(Color.Black).testTag("pip-window"), contentAlignment = Alignment.Center) {
        // The cover underneath: shown for a song without video, and until a video's first frame.
        Artwork(
            artworkUrl.artworkAt(com.opentune.data.model.PLAYER_ART_PX),
            Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true).testTag("pip-cover"),
            RectangleShape,
        )
        // Over it the picture, clear until its first frame so the cover shows while it loads.
        if (videoMode && picture != null) Box(Modifier.matchParentSize().testTag("pip-picture")) { picture() }
    }
}

/**
 * The app as it was, kept exactly so while [held] (the small window shows instead): laid
 * out at the full window's last size with its last configuration, and not drawn. The
 * small window is a tiny, wide window; laid out in it, the app would take it for a turned
 * phone or a small screen (opening the sideways video page, folding a docked player away)
 * and come back changed. Kept as it was, coming back lands on the player as it was left.
 * [inWindow] says whether the activity is in the small window already, for the frame or
 * two between Android changing the window and saying so.
 */
@Composable
internal fun HeldInPictureInPicture(held: Boolean, inWindow: () -> Boolean, content: @Composable () -> Unit) {
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val window = androidx.compose.ui.platform.LocalWindowInfo.current
    val frozen = held || inWindow()
    // The last full window's, kept in plain holders: nothing has to be composed again when they change.
    val keptConfig = remember { arrayOf(android.content.res.Configuration(config)) }
    val keptSize = remember { arrayOf(androidx.compose.ui.unit.IntSize.Zero) }
    if (!frozen) keptConfig[0] = config
    val shownConfig = if (frozen) keptConfig[0] else config
    val shownWindow = remember(window, frozen) {
        if (!frozen || keptSize[0] == androidx.compose.ui.unit.IntSize.Zero) window
        else object : androidx.compose.ui.platform.WindowInfo by window {
            override val containerSize: androidx.compose.ui.unit.IntSize get() = keptSize[0]
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalConfiguration provides shownConfig,
        androidx.compose.ui.platform.LocalWindowInfo provides shownWindow,
    ) {
        androidx.compose.ui.layout.Layout(
            content = content,
            modifier = Modifier
                .graphicsLayer { alpha = if (held) 0f else 1f }
                .then(if (held) Modifier.clearAndSetSemantics { } else Modifier),
        ) { measurables, constraints ->
            val full = androidx.compose.ui.unit.IntSize(constraints.maxWidth, constraints.maxHeight)
            if (!frozen && constraints.hasBoundedWidth && constraints.hasBoundedHeight) keptSize[0] = full
            // Held, at the full window's size; otherwise just as it's given.
            val inner = if (frozen && keptSize[0] != androidx.compose.ui.unit.IntSize.Zero) {
                androidx.compose.ui.unit.Constraints.fixed(keptSize[0].width, keptSize[0].height)
            } else constraints
            val placed = measurables.map { it.measure(inner) }
            layout(constraints.maxWidth, constraints.maxHeight) { placed.forEach { it.place(0, 0) } }
        }
    }
}

/** The button that sends the music video into a small window over other apps. */
@Composable
internal fun PictureInPictureButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
) {
    Box(
        modifier.size(40.dp).clip(CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = com.opentune.ui.components.FocusRing(CircleShape), onClick = onClick)
            .testTag("video-pip-button"),
        contentAlignment = Alignment.Center,
    ) {
        com.opentune.ui.theme.Icon(ImageVector.vectorResource(AppIcons.PictureInPicture), "Picture in picture", Modifier.size(24.dp), tint = tint)
    }
}
