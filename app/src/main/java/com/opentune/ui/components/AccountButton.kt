package com.opentune.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import com.opentune.ui.theme.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.unit.dp
import com.opentune.data.account.AccountStore
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.opentune.ui.theme.AppIcons

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
    // In the app shell the button opens the account menu (switch, add, manage, Settings); elsewhere it opens Settings.
    val menu = LocalAccountMenu.current
    val menuOpenState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var menuOpen by menuOpenState
    // The menu opens from this button: the button's place on the window is kept as
    // it is laid out, and the menu sets its card down on that very spot.
    var anchor by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val track = Modifier.onGloballyPositioned { anchor = it.boundsInWindow() }
    if (menuOpen && menu != null) AccountMenu(menu, anchor = anchor, onDismiss = { menuOpen = false })
    @Suppress("NAME_SHADOWING")
    val onClick: () -> Unit = if (menu != null) ({ menuOpen = true }) else onClick
    if (plain) {
        WithUpdateDot {
            // Named for a screen reader whether it shows the account's photo or the cog.
            val said = com.opentune.i18n.Tr.t("Settings")
            androidx.compose.foundation.layout.Box(
                Modifier.size(44.dp).clip(CircleShape).then(track).clickable(onClickLabel = "Settings", role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
                    .clearAndSetSemantics { contentDescription = said; role = androidx.compose.ui.semantics.Role.Button },
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                val photo = account?.thumbnailUrl
                if (photo != null) Artwork(photo, Modifier.size(40.dp), CircleShape)
                else Icon(ImageVector.vectorResource(AppIcons.Settings), "Settings", Modifier.size(24.dp))
            }
        }
        return
    }
    WithUpdateDot {
        GlassIconButton(ImageVector.vectorResource(AppIcons.Settings), "Settings", onClick, track) {
            val photo = account?.thumbnailUrl
            if (photo != null) Artwork(photo, Modifier.size(44.dp), CircleShape)
            else Icon(ImageVector.vectorResource(AppIcons.Settings), "Settings", Modifier.size(26.dp))
        }
    }
}
