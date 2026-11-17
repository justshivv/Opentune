package com.opentune.ui.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.library.YtmSync
import com.opentune.i18n.Tr
import com.opentune.ui.theme.Text
import kotlinx.coroutines.delay

/** What the pill says about [status]; null when there's nothing to say. */
internal fun ytmSyncLine(status: YtmSync.Status, justFinished: Boolean): String? = when {
    status.running && status.checking -> Tr.t("Checking YouTube Music for changes…")
    status.running && status.current != null && status.count > 1 -> Tr.f("Updating {0} · {1} of {2}", status.current, status.index, status.count)
    status.running && status.current != null -> Tr.f("Updating {0}", status.current)
    status.running -> Tr.t("Checking YouTube Music for changes…")
    // A check that failed says why, and stays until the next one.
    status.error != null -> Tr.t(status.error.orEmpty())
    justFinished && status.error == null && status.changed == 0 -> Tr.t("Up to date with YouTube Music")
    justFinished && status.error == null && status.changed == 1 -> Tr.t("1 song updated")
    justFinished && status.error == null -> Tr.f("{0} songs updated", status.changed)
    else -> null
}

/**
 * The Playlists face's sign that it is checking with YouTube Music: a small
 * capsule with YouTube Music's mark breathing while it asks, the part being
 * checked while it works, and for a moment afterwards what it found; a check
 * that failed says why until the next one. Nothing shows when there's
 * nothing to say. Spotify's face carries the same capsule for its own check.
 */
@Composable
fun YtmSyncPill(status: YtmSync.Status, modifier: Modifier = Modifier) {
    var wasRunning by remember { mutableStateOf(status.running) }
    var justFinished by remember { mutableStateOf(false) }
    LaunchedEffect(status.running) {
        if (wasRunning && !status.running) {
            justFinished = true
            delay(2_400)
            justFinished = false
        }
        wasRunning = status.running
    }
    val line = ytmSyncLine(status, justFinished)
    AnimatedVisibility(
        visible = line != null,
        enter = fadeIn(tween(220)) + expandVertically(tween(260)),
        exit = fadeOut(tween(260)) + shrinkVertically(tween(320)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
            Row(
                Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(start = 8.dp, end = 16.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val breathing = rememberInfiniteTransition(label = "ytmCheck")
                val pulse by breathing.animateFloat(
                    0.82f, 1f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse",
                )
                val still = !status.running
                Image(
                    painterResource(com.opentune.R.drawable.ic_youtube_music), null,
                    Modifier.size(20.dp).graphicsLayer {
                        val s = if (still) 1f else pulse
                        scaleX = s; scaleY = s; alpha = if (still) 1f else 0.55f + (pulse - 0.82f) * 2.5f
                    },
                )
                AnimatedContent(line.orEmpty(), transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(140)) }, label = "line") { text ->
                    Text(
                        text, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
