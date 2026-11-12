package com.opentune.ui.components

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.opentune.data.account.AccountStore
import com.opentune.data.settings.AppSettings
import com.opentune.ui.theme.Icon
import com.opentune.ui.theme.Text

/** Where the account menu leads, provided once by the app shell for every page's account button. */
class AccountMenuNav(
    /** Google's sign-in page, to add an account (or sign in). */
    val addAccount: () -> Unit,
    /** Settings, open on its Account & integrations page. */
    val manageAccounts: () -> Unit,
    val settings: () -> Unit,
)

/** Null outside the app shell (a test, a preview): the button then just opens Settings. */
val LocalAccountMenu = staticCompositionLocalOf<AccountMenuNav?> { null }

/**
 * The account button's menu, from the top corner it was opened from: the accounts on this
 * phone with a tick on the one in use (tap another to switch), then Add account, Manage
 * accounts and Settings.
 */
@Composable
fun AccountMenu(nav: AccountMenuNav, onDismiss: () -> Unit) {
    val accounts by AccountStore.accounts.collectAsState()
    val signedIn by AccountStore.signedIn.collectAsState()
    val calm = AppSettings.ui.collectAsState().value.reduceAnimation
    // Grows out of the button's corner, and shrinks back into it.
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    val close = { shown.targetState = false }
    if (!shown.targetState && shown.isIdle) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onDismiss() }
    }
    Popup(alignment = Alignment.TopEnd, onDismissRequest = close, properties = PopupProperties(focusable = true)) {
        AnimatedVisibility(
            shown,
            enter = if (calm) fadeIn(tween(120)) else fadeIn(tween(160)) + scaleIn(tween(220), initialScale = 0.85f, transformOrigin = TransformOrigin(1f, 0f)),
            exit = if (calm) fadeOut(tween(100)) else fadeOut(tween(140)) + scaleOut(tween(160), targetScale = 0.9f, transformOrigin = TransformOrigin(1f, 0f)),
        ) {
            Column(
                Modifier
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = 16.dp, end = 16.dp, top = 6.dp)
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(30.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.97f))
                    .padding(vertical = 18.dp),
            ) {
                val active = accounts.firstOrNull { it.active }
                Text(
                    if (signedIn) "Switch account" else "Account",
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                if (active != null && active.email.isNotBlank()) {
                    Text(
                        active.email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 4.dp),
                    )
                }
                if (signedIn) accounts.forEach { acc ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { if (!acc.active) AccountStore.switchTo(acc.id); close() }
                            .padding(horizontal = 24.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(acc.thumbnailUrl, Modifier.size(48.dp), CircleShape)
                        Column(Modifier.padding(start = 16.dp).weight(1f)) {
                            Text(acc.name.ifBlank { "Signed in" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (acc.email.isNotBlank()) Text(
                                acc.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (acc.active) Icon(Icons.Rounded.Check, "In use", Modifier.size(26.dp))
                    }
                }
                MenuLine(Icons.Rounded.Add, if (signedIn) "Add account" else "Sign in") { close(); nav.addAccount() }
                if (signedIn) MenuLine(Icons.Rounded.ManageAccounts, "Manage accounts") { close(); nav.manageAccounts() }
                MenuLine(Icons.Rounded.Settings, "Settings") { close(); nav.settings() }
            }
        }
    }
}

@Composable
private fun MenuLine(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(28.dp)) }
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp))
    }
}
