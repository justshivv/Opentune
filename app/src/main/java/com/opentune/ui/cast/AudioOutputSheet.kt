package com.opentune.ui.cast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Speaker
import com.opentune.ui.theme.Icon
import androidx.compose.material3.MaterialTheme
import com.opentune.ui.theme.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.ui.components.FloatingCard
import com.opentune.ui.components.rememberHaptics
import com.opentune.ui.player.OutputRoute
import com.opentune.ui.player.icon
import com.opentune.ui.player.isHeadset
import com.opentune.ui.player.rememberOutputDeviceName
import com.opentune.ui.player.rememberOutputRoute

/**
 * Where the sound goes, in one place: this phone or the headphones plugged
 * into it, a Chromecast or TV to cast to, and the audio pipeline for the
 * full signal path. Cast and the pipeline each open their own sheet.
 */
@Composable
fun AudioOutputSheet(onDismiss: () -> Unit, onCast: () -> Unit, onPipeline: () -> Unit) {
    val route = rememberOutputRoute()
    val haptics = rememberHaptics()
    // The device itself, not the line about it: that line is in the app's language.
    val castSession = com.opentune.cast.Cast.session.collectAsState().value
    val output = rememberOutputDeviceName()
    val deviceName = castSession?.target?.name ?: output.ifBlank { "This phone" }
    val casting = castingTo()
    FloatingCard(onDismiss = onDismiss, title = "Audio output", icon = Icons.Rounded.Speaker) {
        Column(Modifier.padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutputRow(
                icon = if (route == OutputRoute.CAST) Icons.Rounded.Smartphone else route.icon,
                title = if (route == OutputRoute.CAST) "This phone" else deviceName,
                subtitle = if (casting == null) "Playing here" else "Silent here while casting",
                selected = casting == null,
            ) { haptics.tick(); onDismiss() }
            OutputRow(
                icon = Icons.Rounded.Cast,
                title = "Cast",
                subtitle = casting ?: "Chromecast and Google Cast speakers",
                selected = casting != null,
                chevron = true,
            ) { haptics.tick(); onCast() }
            OutputRow(
                icon = Icons.Rounded.GraphicEq,
                title = "Audio pipeline",
                subtitle = "Live signal path, decoder to output",
                chevron = true,
            ) { haptics.tick(); onPipeline() }
        }
    }
}

@Composable
private fun OutputRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean = false,
    chevron: Boolean = false,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) scheme.primary.copy(alpha = 0.12f) else scheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(if (selected) scheme.primary else scheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = if (selected) scheme.onPrimary else scheme.onSurface, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Icon(Icons.Rounded.Check, "Selected", tint = scheme.primary, modifier = Modifier.size(22.dp))
        else if (chevron) Icon(Icons.Rounded.ChevronRight, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
    }
}
