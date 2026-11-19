package com.opentune.ui.player

import android.graphics.Matrix
import android.view.TextureView
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import com.opentune.i18n.Tr
import com.opentune.playback.PlayerConnection
import com.opentune.ui.theme.Text

/**
 * The playing song's music-video picture, drawn by the playback player itself
 * into a texture view filling its box, the whole frame fitted inside it.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun VideoSurface(connection: PlayerConnection, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = remember { TextureView(context) }
    val size by connection.videoSize.collectAsState()
    DisposableEffect(connection, view) {
        connection.setVideoTextureView(view)
        val layout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            fitVideo(view, connection.videoSize.value)
        }
        view.addOnLayoutChangeListener(layout)
        onDispose {
            view.removeOnLayoutChangeListener(layout)
            connection.setVideoTextureView(null)
        }
    }
    Box(modifier.background(Color.Black)) {
        AndroidView(
            factory = { view },
            modifier = Modifier.matchParentSize(),
            update = { fitVideo(it, size) },
        )
    }
}

/** Fits the whole video frame inside the view, centred, with black around it. */
private fun fitVideo(view: TextureView, video: VideoSize) {
    val vw = view.width.toFloat()
    val vh = view.height.toFloat()
    if (vw == 0f || vh == 0f || video.width == 0 || video.height == 0) return
    val frameW = video.width * video.pixelWidthHeightRatio
    val frameH = video.height.toFloat()
    val scale = minOf(vw / frameW, vh / frameH)
    val matrix = Matrix()
    matrix.setScale(frameW * scale / vw, frameH * scale / vh, vw / 2f, vh / 2f)
    view.setTransform(matrix)
}

/** The player's Song | Video switch, shown only for songs that have a video. */
@Composable
internal fun SongVideoToggle(videoMode: Boolean, onSelect: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Segment(Tr.f("Song"), selected = !videoMode) { onSelect(false) }
        Segment(Tr.f("Video"), selected = videoMode) { onSelect(true) }
    }
}

@Composable
private fun Segment(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent)
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
