package com.opentune.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Public
import com.opentune.ui.theme.Icon
import androidx.compose.material3.MaterialTheme
import com.opentune.ui.theme.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.opentune.data.model.PlaylistPrivacy

/** What each privacy means, in a few words. */
val PlaylistPrivacy.meaning: String
    get() = when (this) {
        PlaylistPrivacy.PRIVATE -> "Only you can see it"
        PlaylistPrivacy.UNLISTED -> "Anyone with the link can open it"
        PlaylistPrivacy.PUBLIC -> "Anyone can find and open it"
    }

val PlaylistPrivacy.icon: ImageVector
    get() = when (this) {
        PlaylistPrivacy.PRIVATE -> Icons.Rounded.Lock
        PlaylistPrivacy.UNLISTED -> Icons.Rounded.Link
        PlaylistPrivacy.PUBLIC -> Icons.Rounded.Public
    }

/**
 * For a new playlist while signed in: whether to save it to YouTube Music as
 * well, and who can see it there.
 */
@Composable
fun AccountCopyOptions(
    save: Boolean,
    onSave: (Boolean) -> Unit,
    privacy: PlaylistPrivacy,
    onPrivacy: (PlaylistPrivacy) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { onSave(!save) }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Also save to YouTube Music", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            AppSwitch(save, onSave)
        }
        if (save) {
            PillSegmented(PlaylistPrivacy.entries, privacy, { it.label }, onPrivacy, Modifier.padding(top = 8.dp))
            Text(privacy.meaning, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** Picks who can see a playlist on YouTube Music. */
@Composable
fun PrivacySheet(current: PlaylistPrivacy?, onSelect: (PlaylistPrivacy) -> Unit, onDismiss: () -> Unit) {
    ChoiceSheet(
        title = "Who can see it",
        options = PlaylistPrivacy.entries,
        selected = current ?: PlaylistPrivacy.PRIVATE,
        label = { "${it.label} · ${it.meaning}" },
        onSelect = onSelect,
        onDismiss = onDismiss,
    )
}

/** A playlist's privacy as a pill in its tools row; tapping it changes it. */
@Composable
fun PrivacyButton(privacy: PlaylistPrivacy?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    Row(
        modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable { haptics.tick(); onClick() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon((privacy ?: PlaylistPrivacy.PRIVATE).icon, null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(privacy?.label ?: "Privacy", style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Before sharing a private playlist: its link only opens for its owner. Offers
 * to make it public and share it, or to share the link as it is.
 */
@Composable
fun PrivateShareDialog(onMakePublic: () -> Unit, onShareAnyway: () -> Unit, onDismiss: () -> Unit) {
    FloatingDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Lock, null) },
        title = { Text("This playlist is private") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Its link only opens for you. Make it public and share it, and anyone you send it to can open it and save it.")
                TextButton(onClick = onShareAnyway, modifier = Modifier.padding(top = 4.dp)) { Text("Share the link anyway") }
            }
        },
        confirmButton = { SheetButton("Make public", onClick = onMakePublic) },
        dismissButton = { SheetButton("Cancel", onClick = onDismiss, closes = true) },
    )
}
