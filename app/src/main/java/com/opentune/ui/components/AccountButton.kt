package com.opentune.ui.components

import androidx.compose.foundation.layout.size
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
fun AccountButton(onClick: () -> Unit) {
    val account by AccountStore.account.collectAsState()
    WithUpdateDot {
        GlassIconButton(Icons.Rounded.Settings, "Settings", onClick) {
            val photo = account?.thumbnailUrl
            if (photo != null) Artwork(photo, Modifier.size(44.dp), CircleShape)
            else Icon(Icons.Rounded.Settings, "Settings", Modifier.size(26.dp))
        }
    }
}
