package com.opentune.ui.cast

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.cast.Cast
import com.opentune.cast.CastTarget
import com.opentune.cast.RemoteStatus
import com.opentune.ui.components.FloatingCard
import com.opentune.ui.components.rememberHaptics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Picks a device to play on: Chromecasts and DLNA renderers (smart TVs,
 * network speakers, Kodi, VLC) on the same Wi-Fi. While casting, it shows
 * where, the device's volume and a way to stop.
 */
@Composable
fun CastSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val devices by Cast.devices.collectAsState()
    val scanning by Cast.scanning.collectAsState()
    val session by Cast.session.collectAsState()
    LaunchedEffect(Unit) { Cast.scan(context) }
    FloatingCard(onDismiss = onDismiss, title = "Play on", icon = Icons.Rounded.Cast) {
        Column(Modifier.verticalScroll(rememberScrollState()).animateContentSize().padding(bottom = 12.dp)) {
            session?.let { s ->
                val status by (s.receiver?.status ?: remember { MutableStateFlow(RemoteStatus()) }).collectAsState()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                        .padding(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                            if (s.receiver == null) {
                                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
                            } else {
                                Icon(Icons.Rounded.CastConnected, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.target.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (s.receiver == null) "Connecting…" else "Playing here",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        FilledTonalButton(onClick = { haptics.press(); Cast.disconnect() }) { Text("Stop") }
                    }
                    val receiver = s.receiver
                    if (receiver != null) {
                        var level by remember(receiver) { mutableFloatStateOf(-1f) }
                        val shown = if (level >= 0f) level else status.volume ?: 0.5f
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Rounded.VolumeDown, null, Modifier.size(20.dp))
                            Slider(
                                value = shown,
                                onValueChange = { level = it },
                                onValueChangeFinished = { val v = level; scope.launch { receiver.setVolume(v) } },
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            )
                            Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, Modifier.size(20.dp))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Devices on your Wi-Fi",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (scanning) {
                    Radar(Modifier.size(28.dp))
                } else {
                    TextButton(onClick = { haptics.tick(); Cast.scan(context) }) {
                        Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Search again")
                    }
                }
            }
            devices.forEach { d ->
                DeviceRow(d, session?.target?.id == d.id) {
                    haptics.press()
                    if (session?.target?.id != d.id) Cast.connect(context, d)
                }
            }
            AnimatedVisibility(devices.isEmpty(), enter = fadeIn(), exit = fadeOut()) {
                Text(
                    if (scanning) "Looking for TVs, speakers and Chromecasts…" else "Nothing found. The device needs to be on and on the same Wi-Fi as this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(device: CastTarget, current: Boolean, onClick: () -> Unit) {
    val tv = device.kind == CastTarget.Kind.DLNA && Regex("tv|bravia|samsung|lg|webos|tizen|android", RegexOption.IGNORE_CASE).containsMatchIn(device.name + " " + device.model.orEmpty())
    val icon = when {
        device.kind == CastTarget.Kind.CHROMECAST && device.model?.contains("speaker", true) != true && device.model?.contains("home", true) != true && device.model?.contains("nest audio", true) != true -> Icons.Rounded.Cast
        tv -> Icons.Rounded.Tv
        else -> Icons.Rounded.Speaker
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(
                if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = if (current) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(device.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(device.model, if (device.kind == CastTarget.Kind.CHROMECAST) "Chromecast" else "DLNA").distinct().joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Rings spreading from a dot while the search runs. */
@Composable
private fun Radar(modifier: Modifier) {
    val t by rememberInfiniteTransition(label = "radar").animateFloat(0f, 1f, infiniteRepeatable(tween(1_600, easing = LinearEasing), RepeatMode.Restart), label = "ring")
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val r = size.minDimension / 2f
        for (k in 0..1) {
            val p = (t + k * 0.5f) % 1f
            drawCircle(color.copy(alpha = (1f - p) * 0.8f), r * (0.25f + 0.75f * p), center, style = Stroke(1.5.dp.toPx()))
        }
        drawCircle(color, r * 0.18f, Offset(center.x, center.y))
    }
}

/** Whether a cast is on, for icons that light up while it is. */
@Composable
fun castingTo(): String? = Cast.session.collectAsState().value?.target?.name
