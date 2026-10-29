package com.opentune.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opentune.data.account.AccountStore

/**
 * The button at the top of a tab's page that opens Settings: the account's
 * photo when signed in, a cog otherwise, with the dot for an update waiting.
 */
@Composable
fun AccountButton(
    onClick: () -> Unit,
    /** Inside a glass pill of its own (with other buttons beside it): no glass of its own. */
    plain: Boolean = false,
) {
    val account by AccountStore.account.collectAsState()
    if (plain) {
        WithUpdateDot {
            androidx.compose.foundation.layout.Box(
                Modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = "Settings", role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                val photo = account?.thumbnailUrl
                if (photo != null) Artwork(photo, Modifier.size(40.dp), CircleShape)
                else Icon(Icons.Rounded.Settings, "Settings", Modifier.size(24.dp))
            }
        }
        return
    }
    WithUpdateDot {
        GlassIconButton(Icons.Rounded.Settings, "Settings", onClick) {
            val photo = account?.thumbnailUrl
            if (photo != null) Artwork(photo, Modifier.size(44.dp), CircleShape)
            else Icon(Icons.Rounded.Settings, "Settings", Modifier.size(26.dp))
        }
    }
}
