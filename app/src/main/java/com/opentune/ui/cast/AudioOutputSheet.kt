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
import androidx.compose.ui.res.vectorResource
import com.opentune.ui.theme.AppIcons

/**
 * Where the sound goes, in one place: this phone or the headphones plugged
 * into it, the person's other devices (Connect) to move the music to, a
 * Chromecast or TV to cast to, and the audio pipeline for the full signal
 * path. Cast and the pipeline each open their own sheet; [onDevices] opens
 * the settings for the person's devices.
 */
@Composable
fun AudioOutputSheet(onDismiss: () -> Unit, onCast: () -> Unit, onPipeline: () -> Unit, onDevices: () -> Unit = {}) {
    val route = rememberOutputRoute()
    val haptics = rememberHaptics()
    // The device itself, not the line about it: that line is in the app's language.
    val castSession = com.opentune.cast.Cast.session.collectAsState().value
    val output = rememberOutputDeviceName()
    val deviceName = castSession?.target?.name ?: output.ifBlank { "This phone" }
    val casting = castingTo()
    val connect = com.opentune.data.connect.Connect.view.collectAsState().value
    // Another of the person's devices playing: the music is there, not here.
    val elsewhere = connect?.controlling
    FloatingCard(onDismiss = onDismiss, title = "Audio output", icon = ImageVector.vectorResource(AppIcons.Speaker)) {
        Column(Modifier.padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutputRow(
                icon = if (route == OutputRoute.CAST) ImageVector.vectorResource(AppIcons.Smartphone) else route.icon,
                title = if (route == OutputRoute.CAST) "This phone" else deviceName,
                subtitle = when {
                    elsewhere != null -> "Play it here"
                    casting == null -> "Playing here"
                    else -> "Silent here while casting"
                },
                selected = casting == null && elsewhere == null,
            ) {
                haptics.tick()
                // Brings the music back from the other device.
                if (elsewhere != null) com.opentune.data.connect.Connect.transferTo(connect.myId)
                onDismiss()
            }
            connect?.devices?.sortedBy { it.name }?.forEach { d ->
                OutputRow(
                    icon = com.opentune.ui.connect.formIcon(d.form),
                    title = d.name.ifBlank { com.opentune.i18n.Tr.t("Another device") },
                    subtitle = com.opentune.ui.connect.doing(d) ?: "Ready to play",
                    selected = connect.activeId == d.id && elsewhere != null,
                ) {
                    haptics.tick()
                    com.opentune.data.connect.Connect.transferTo(d.id)
                    onDismiss()
                }
            }
            OutputRow(
                icon = ImageVector.vectorResource(AppIcons.Cast),
                title = "Cast",
                subtitle = casting ?: "Chromecast and Google Cast speakers",
                selected = casting != null,
                chevron = true,
            ) { haptics.tick(); onCast() }
            OutputRow(
                icon = ImageVector.vectorResource(AppIcons.GraphicEq),
                title = "Audio pipeline",
                subtitle = "Live signal path, decoder to output",
                chevron = true,
            ) { haptics.tick(); onPipeline() }
            OutputRow(
                icon = ImageVector.vectorResource(AppIcons.Devices),
                title = "Your devices",
                subtitle = if (connect == null) "Play on your computer from here, and the other way round" else "Add a device or change this one's name",
                chevron = true,
            ) { haptics.tick(); onDevices() }
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
        if (selected) Icon(ImageVector.vectorResource(AppIcons.Check), "Selected", tint = scheme.primary, modifier = Modifier.size(22.dp))
        else if (chevron) Icon(ImageVector.vectorResource(AppIcons.ChevronRight), null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
    }
}
