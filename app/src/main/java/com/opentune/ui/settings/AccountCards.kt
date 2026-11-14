package com.opentune.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentune.R
import com.opentune.ui.components.Artwork
import com.opentune.ui.theme.Icon
import com.opentune.ui.theme.Text

/*
 * The pieces of the Account & integrations page: the YouTube Music card at the top, and one
 * row per connected service, each with its own mark, whether it's connected, and what you
 * can do with it right there.
 */

private val Connected = Color(0xFF2FA35B)

/** A service's mark: its own logo where the app has one, else its colour with a short monogram or icon. */
@Composable
internal fun ServiceMark(kind: ServiceKind, size: androidx.compose.ui.unit.Dp = 40.dp) {
    when (kind) {
        ServiceKind.YouTubeMusic -> Image(painterResource(R.drawable.ic_youtube_music), "YouTube Music", Modifier.size(size))
        ServiceKind.Spotify -> Image(painterResource(R.drawable.ic_spotify), "Spotify", Modifier.size(size))
        ServiceKind.Discord -> Image(painterResource(R.drawable.ic_discord), "Discord", Modifier.size(size))
        ServiceKind.LastFm -> Monogram("fm", Color(0xFFD51007), Color.White, size)
        ServiceKind.ListenBrainz -> Monogram("LB", Color(0xFFEB743B), Color(0xFF353070), size)
        ServiceKind.Server -> IconMark(Icons.Rounded.Dns, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer, size)
        ServiceKind.Devices -> IconMark(Icons.Rounded.Devices, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, size)
    }
}

internal enum class ServiceKind { YouTubeMusic, Spotify, Discord, LastFm, ListenBrainz, Server, Devices }

@Composable
private fun Monogram(text: String, background: Color, foreground: Color, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(background), contentAlignment = Alignment.Center) {
        // Not translated: it's the service's own short name.
        androidx.compose.material3.Text(text, color = foreground, fontWeight = FontWeight.Black, fontSize = (size.value * 0.36f).sp, letterSpacing = (-0.5).sp)
    }
}

@Composable
private fun IconMark(icon: ImageVector, background: Color, foreground: Color, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(background), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = foreground, modifier = Modifier.size(size * 0.55f))
    }
}

/**
 * The YouTube Music card's top. Signed in: the account in use, large, with the YouTube Music
 * mark on its photo. Signed out: what signing in gives you, and one clear button.
 */
@Composable
internal fun YouTubeMusicHero(name: String?, email: String?, photo: String?, onSignIn: () -> Unit) {
    if (name != null) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                Artwork(photo, Modifier.size(72.dp), CircleShape)
                Box(
                    Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 4.dp)
                        .clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer).padding(3.dp),
                ) { ServiceMark(ServiceKind.YouTubeMusic, 22.dp) }
            }
            Column(Modifier.padding(start = 18.dp).weight(1f)) {
                Text(name.ifBlank { "Signed in" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(email?.ifBlank { null } ?: "YouTube Music", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                StatusLabel("Signed in to YouTube Music", Modifier.padding(top = 6.dp))
            }
        }
    } else {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            ServiceMark(ServiceKind.YouTubeMusic, 56.dp)
            Spacer(Modifier.height(14.dp))
            Text("YouTube Music", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "Sign in for your playlists, liked songs, history and recommendations. You can listen without an account too.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onSignIn,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.surface),
            ) { Text("Sign in", style = MaterialTheme.typography.titleMedium) }
        }
    }
}

/** A small green dot and a word: connected, and what as. */
@Composable
private fun StatusLabel(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Connected))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = Connected, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A small rounded button under a connected service: Sync now, Disconnect. */
internal data class ServiceAction(val label: String, val danger: Boolean = false, val onClick: () -> Unit)

/**
 * One service. Not connected: its mark, what it adds, and a Connect button. Connected: who
 * it's connected as, how it's doing, and its actions as small buttons underneath, so nothing
 * about a service is in a row of its own somewhere else.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ServiceRow(
    kind: ServiceKind,
    name: String,
    connectedAs: String?,
    detail: String?,
    onClick: () -> Unit,
    actions: List<ServiceAction> = emptyList(),
    enabled: Boolean = true,
    /** Replaces the Connect button or arrow: Discord's on/off switch. */
    trailing: (@Composable () -> Unit)? = null,
    /** Off where tapping a connected row does something other than open a page. */
    chevron: Boolean = true,
) {
    val connected = connectedAs != null
    Column(
        Modifier.fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ServiceMark(kind)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (connectedAs != null) StatusLabel(connectedAs, Modifier.padding(top = 3.dp))
                if (!detail.isNullOrBlank()) {
                    Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            when {
                trailing != null -> trailing()
                !connected -> ConnectPill(enabled, onClick)
                chevron -> Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (connected && actions.isNotEmpty()) {
            FlowRow(Modifier.padding(start = 56.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.forEach { ActionPill(it) }
            }
        }
    }
}

@Composable
private fun ConnectPill(enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onSurface)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) { Text("Connect", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.surface) }
}

@Composable
private fun ActionPill(action: ServiceAction) {
    val color = if (action.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
            .clickable(role = Role.Button, onClick = action.onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) { Text(action.label, style = MaterialTheme.typography.labelLarge, color = color) }
}
