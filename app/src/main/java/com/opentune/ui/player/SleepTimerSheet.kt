package com.opentune.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.opentune.data.settings.AppSettings
import com.opentune.playback.SleepTimer
import com.opentune.ui.components.FloatingCard
import com.opentune.ui.components.LocalFloatingCard
import com.opentune.ui.components.SheetButton
import com.opentune.ui.components.SheetTone
import com.opentune.ui.components.rememberHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val MINUTES = listOf(15, 30, 45, 60, 90)

/**
 * The sleep timer as a floating card: while a timer runs, a ring counts it
 * down with buttons to add time or turn it off; below, tiles for how long
 * to play on, or until the song ends. Picking one lights its tile and the
 * card closes a moment later.
 */
@Composable
fun SleepTimerDialog(onDismiss: () -> Unit) {
    val state by SleepTimer.state.collectAsState()
    val label = sleepTimerLabel()
    FloatingCard(
        onDismiss = onDismiss,
        title = "Sleep timer",
        icon = Icons.Rounded.Bedtime,
        subtitle = label ?: "Pick when the music stops",
    ) {
        val ui by AppSettings.ui.collectAsState()
        val still = ui.reduceAnimation
        val haptics = rememberHaptics()
        val card = LocalFloatingCard.current
        val scope = rememberCoroutineScope()
        var picked by remember { mutableStateOf<Any?>(null) }
        fun pick(key: Any, start: () -> Unit) {
            if (picked != null) return
            haptics.press()
            picked = key
            start()
            scope.launch {
                if (!still) delay(380)
                card?.close() ?: onDismiss()
            }
        }

        AnimatedVisibility(
            visible = state != SleepTimer.State.Off,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Running(state, still, onAdd = { haptics.tick(); SleepTimer.addMinutes(5) }, onOff = { haptics.tick(); SleepTimer.cancel() })
        }

        val rows = MINUTES.map { m -> m as Any } + EndOfSong
        Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rows.chunked(3).forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEachIndexed { c, key ->
                        val minutes = key as? Int
                        Tile(
                            index = r * 3 + c,
                            still = still,
                            lit = picked == key,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                if (minutes != null) pick(key) { SleepTimer.startMinutes(minutes) } else pick(key) { SleepTimer.endOfTrack() }
                            },
                        ) {
                            if (minutes != null) {
                                Text("$minutes", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                                Text("min", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Icon(Icons.Rounded.MusicNote, null, Modifier.size(26.dp))
                                Spacer(Modifier.height(4.dp))
                                Text("End of song", style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
    }
}

private object EndOfSong

/** The running timer: a ring that empties as time runs out, the time left, and controls. */
@Composable
private fun Running(state: SleepTimer.State, still: Boolean, onAdd: () -> Unit, onOff: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state) {
        while (state is SleepTimer.State.At) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    val at = state as? SleepTimer.State.At
    val target = at?.let { ((it.endsAtMs - now).toFloat() / (it.endsAtMs - it.startedAtMs).coerceAtLeast(1)).coerceIn(0f, 1f) } ?: 1f
    val fraction by animateFloatAsState(target, if (still) snap() else spring(stiffness = 120f), label = "left")
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(24.dp))
            .background(scheme.primary.copy(alpha = 0.14f))
            .border(1.dp, scheme.primary.copy(alpha = 0.35f), RoundedCornerShape(24.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
            val track = scheme.onSurface.copy(alpha = 0.1f)
            val ink = scheme.primary
            Canvas(Modifier.size(72.dp)) {
                val w = 6.dp.toPx()
                val box = Size(size.width - w, size.height - w)
                val tl = Offset(w / 2, w / 2)
                drawArc(track, 0f, 360f, false, tl, box, style = Stroke(w))
                drawArc(ink, -90f, 360f * fraction, false, tl, box, style = Stroke(w, cap = StrokeCap.Round))
            }
            if (at != null) {
                val left = ((at.endsAtMs - now) / 1000).coerceAtLeast(0)
                Text(
                    if (left >= 3600) "%d:%02d".format(left / 3600, left / 60 % 60) else "%d:%02d".format(left / 60, left % 60),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            } else {
                Icon(Icons.Rounded.MusicNote, null, Modifier.size(26.dp), tint = ink)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (at != null) "Music stops when the ring runs out" else "Music stops when this song ends",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (at != null) SheetButton("+5 min", onClick = onAdd, modifier = Modifier.weight(1f), tone = SheetTone.Neutral)
                SheetButton("Turn off", onClick = onOff, modifier = Modifier.weight(1f), tone = SheetTone.Danger)
            }
        }
    }
}

/** A square option tile that rises in with the card and lights up in the accent when chosen. */
@Composable
private fun Tile(index: Int, still: Boolean, lit: Boolean, modifier: Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.94f else if (lit) 1.04f else 1f, spring(dampingRatio = 0.5f, stiffness = 600f), label = "press")
    val glow by animateFloatAsState(if (lit) 1f else 0f, if (still) snap() else spring(stiffness = 500f), label = "lit")
    val appear = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (still) return@LaunchedEffect
        delay(70L + index * 35L)
        appear.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = 320f))
    }
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .aspectRatio(1.05f)
            .graphicsLayer {
                alpha = appear.value.coerceIn(0f, 1f)
                translationY = (1f - appear.value) * 20.dp.toPx()
                scaleX = press * (0.9f + 0.1f * appear.value)
                scaleY = press * (0.9f + 0.1f * appear.value)
            }
            .clip(shape)
            .background(scheme.onSurface.copy(alpha = 0.06f))
            .background(scheme.primary.copy(alpha = 0.22f * glow))
            .border(1.dp, scheme.onSurface.copy(alpha = 0.08f * (1f - glow)), shape)
            .border(1.dp, scheme.primary.copy(alpha = 0.6f * glow), shape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}
