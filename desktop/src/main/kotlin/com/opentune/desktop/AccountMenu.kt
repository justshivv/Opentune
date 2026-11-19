package com.opentune.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentune.data.account.AccountStore
import com.opentune.data.account.SavedAccount
import com.opentune.data.settings.AppSettings
import com.opentune.ui.components.FocusRing
import com.opentune.ui.components.LocalHazeState
import androidx.compose.ui.draw.clip
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.HazeState
import kotlin.math.roundToInt

/** Accounts to show in the menu in place of the saved ones, for the offscreen pictures; null in the app. */
internal object ChromeAccountsPreview {
    var accounts: List<SavedAccount>? = null
}

/**
 * The account menu, opened from the picture at the top bar's right end: a 470dp popover of
 * frosted glass under a dark film, its right edge under the picture's, over the window dimmed
 * to half black. "Switch account" with the active account's address, each account signed in
 * here (a tick on the active one; a click switches to another), then Add account, Manage
 * accounts and Settings; signed out, Sign in and Settings. It fades and grows in from its top
 * right corner over 160ms; a click outside or Escape closes it.
 *
 * [onAccounts] opens the Accounts section of Settings, where signing in with the phone lives.
 */
@Composable
internal fun ChromeAccountMenu(
    open: Boolean,
    haze: HazeState?,
    onDismiss: () -> Unit,
    onSettings: () -> Unit,
    onAccounts: () -> Unit,
) {
    val still = AppSettings.ui.collectAsState().value.reduceAnimation
    AnimatedVisibility(open, enter = fadeIn(tween(if (still) 100 else 160)), exit = fadeOut(tween(120))) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        )
    }
    AnimatedVisibility(
        open,
        enter = if (still) fadeIn(tween(100)) else fadeIn(tween(160, easing = Motion.Emphasized)) +
            scaleIn(tween(160, easing = Motion.Emphasized), initialScale = 0.94f, transformOrigin = TransformOrigin(1f, 0f)),
        exit = if (still) fadeOut(tween(90)) else fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.97f, transformOrigin = TransformOrigin(1f, 0f)),
    ) {
        CompositionLocalProvider(LocalHazeState provides haze) {
            ChromeAccountPopover(onDismiss, onSettings, onAccounts)
        }
    }
}

@Composable
private fun ChromeAccountPopover(onDismiss: () -> Unit, onSettings: () -> Unit, onAccounts: () -> Unit) {
    val anchor = ChromeAnchor.avatar
    var origin by remember { mutableStateOf(Offset.Zero) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val shape = RoundedCornerShape(16.dp)
    val dark = darkTheme()
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        val margin = 12.dp
        Column(
            Modifier
                .layout { measurable, constraints ->
                    val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    val gap = 8.dp.roundToPx()
                    val edge = margin.roundToPx()
                    // Its right edge under the picture's, 8dp below it; kept inside the window.
                    val right = anchor?.let { (it.right - origin.x).roundToInt() } ?: (constraints.maxWidth - edge)
                    val top = anchor?.let { (it.bottom - origin.y).roundToInt() + gap } ?: (TOP_BAR_HEIGHT + DesktopWindow.topInset).roundToPx()
                    val x = (right - p.width).coerceIn(edge, (constraints.maxWidth - p.width - edge).coerceAtLeast(edge))
                    layout(constraints.maxWidth, constraints.maxHeight) { p.place(x, top) }
                }
                .width(470.dp).widthIn(max = maxWidth - margin * 2)
                .shadow(28.dp, shape, ambientColor = Color.Black.copy(alpha = 0.45f), spotColor = Color.Black.copy(alpha = 0.45f))
                .chromeFrost(shape, dark)
                // A click on the menu itself isn't a click outside it.
                .pointerInput(Unit) { detectTapGestures { } }
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { onDismiss(); true } else false }
                .semantics { contentDescription = "Account menu" }
                .padding(vertical = 10.dp),
        ) { ChromeAccountMenuRows(onDismiss, onSettings, onAccounts) }
    }
}

/**
 * The menu's frosted glass: the page and the window's backdrop behind it (the areas the window
 * records for its glass) blurred 40dp under a dark film, so the rows read over anything, with
 * the lit rim the Liquid Glass has. Solid, a touch see-through, with blur turned off.
 */
@Composable
private fun Modifier.chromeFrost(shape: androidx.compose.ui.graphics.Shape, dark: Boolean): Modifier {
    val haze = LocalHazeState.current
    val reduce = AppSettings.ui.collectAsState().value.reduceBlur
    val film = if (dark) Color(0xFF121214) else Color(0xFFF6F6F8)
    val rim = androidx.compose.ui.graphics.Brush.linearGradient(
        0f to Color.White.copy(alpha = if (dark) 0.30f else 0.9f),
        0.5f to Color.White.copy(alpha = if (dark) 0.06f else 0.2f),
        1f to Color.White.copy(alpha = if (dark) 0.16f else 0.5f),
    )
    val filled = if (haze == null || reduce) this.clip(shape).background(film.copy(alpha = 0.97f))
    else this.clip(shape).hazeEffect(
        haze,
        dev.chrisbanes.haze.HazeStyle(
            backgroundColor = MaterialTheme.colorScheme.background,
            tint = dev.chrisbanes.haze.HazeTint(film.copy(alpha = if (dark) 0.72f else 0.7f)),
            blurRadius = 40.dp,
            noiseFactor = 0f,
        ),
    )
    return filled.border(1.dp, rim, shape)
}

@Composable
private fun ChromeAccountMenuRows(onDismiss: () -> Unit, onSettings: () -> Unit, onAccounts: () -> Unit) {
    val signedIn by AccountStore.signedIn.collectAsState()
    val account by AccountStore.account.collectAsState()
    val saved by AccountStore.accounts.collectAsState()
    val preview = ChromeAccountsPreview.accounts
    val accounts = preview ?: saved.ifEmpty {
        // Signed in before the list was kept: the one account there is.
        account?.takeIf { signedIn }?.let { listOf(SavedAccount("", it.name, it.email, it.thumbnailUrl, active = true)) }.orEmpty()
    }
    val active = accounts.firstOrNull { it.active }
    val ink = MaterialTheme.colorScheme.onSurface
    if (active != null) {
        Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 8.dp, bottom = 6.dp)) {
            Text("Switch account", color = ink, style = chromeMenuType(18, 24, FontWeight.Bold), modifier = Modifier.semantics { heading() })
            active.email.takeIf { it.isNotBlank() }?.let {
                Text(it, color = ChromeInk.secondary(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = chromeMenuType(13, 18), modifier = Modifier.padding(top = 2.dp))
            }
        }
        accounts.forEach { a ->
            val tick: (@Composable () -> Unit)? = if (a.active) { { Icon(Icons.Rounded.Check, "Active", tint = ink, modifier = Modifier.size(20.dp)) } } else null
            ChromeMenuRow(
                leading = { ChromeAccountPicture(a.thumbnailUrl, a.name, 40.dp) },
                title = a.name.ifBlank { "YouTube Music account" },
                subtitle = a.email.takeIf { it.isNotBlank() },
                trailing = tick,
                label = if (a.active) "${a.name}, active" else "Switch to ${a.name}",
            ) {
                if (!a.active && a.id.isNotBlank()) AccountStore.switchTo(a.id)
                onDismiss()
            }
        }
        ChromeMenuDivider()
        ChromeMenuRow(Icons.Rounded.Add, "Add account") { onDismiss(); onAccounts() }
        ChromeMenuRow(Icons.Outlined.ManageAccounts, "Manage accounts") { onDismiss(); onAccounts() }
        ChromeMenuRow(Icons.Outlined.Settings, "Settings") { onDismiss(); onSettings() }
    } else {
        Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 8.dp, bottom = 6.dp)) {
            Text("Account", color = ink, style = chromeMenuType(18, 24, FontWeight.Bold), modifier = Modifier.semantics { heading() })
            Text("Not signed in", color = ChromeInk.secondary(), style = chromeMenuType(13, 18), modifier = Modifier.padding(top = 2.dp))
        }
        ChromeMenuRow(
            leading = { ChromeMenuGlyph(Icons.AutoMirrored.Outlined.Login) },
            title = "Sign in",
            subtitle = "With YouTube Music on your phone",
            label = "Sign in",
        ) { onDismiss(); onAccounts() }
        ChromeMenuDivider()
        ChromeMenuRow(Icons.Outlined.Settings, "Settings") { onDismiss(); onSettings() }
    }
}

@Composable
private fun chromeMenuType(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
    MaterialTheme.typography.bodyMedium.copy(fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = 0.sp)

@Composable
private fun ChromeMenuDivider() {
    Box(Modifier.padding(horizontal = 22.dp, vertical = 6.dp).fillMaxWidth().height(1.dp).background(ChromeInk.hairline()))
}

/** A 20dp glyph in the 40dp slot an account's picture takes, so the rows' names line up. */
@Composable
private fun ChromeMenuGlyph(icon: ImageVector) {
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ChromeMenuRow(icon: ImageVector, title: String, onClick: () -> Unit) =
    ChromeMenuRow(leading = { ChromeMenuGlyph(icon) }, title = title, label = title, onClick = onClick)

/** One of the menu's rows: 64dp, its picture or glyph, the name over a grey line; a faint fill under the pointer or the keyboard. */
@Composable
private fun ChromeMenuRow(
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    label: String,
    onClick: () -> Unit,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val focused by hover.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    val wash by animateColorAsState(MaterialTheme.colorScheme.onSurface.copy(alpha = if (hovered || focused) 0.08f else 0f), tween(if (hovered) 0 else 150), label = "menu row")
    Row(
        Modifier.padding(horizontal = 8.dp).fillMaxWidth().height(64.dp)
            .background(wash, shape).hoverable(hover)
            .clickable(interactionSource = hover, indication = FocusRing(shape), role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface, style = chromeMenuType(15, 20, FontWeight.Medium))
            subtitle?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, color = ChromeInk.secondary(), style = chromeMenuType(13, 17)) }
        }
        trailing?.let { Spacer(Modifier.width(12.dp)); it() }
    }
}

/**
 * Where an asked-for section of Settings is waiting: the account menu sets it, and Settings
 * glides to that section as it opens and clears it.
 */
internal object ChromeSettingsJump {
    val accounts = kotlinx.coroutines.flow.MutableStateFlow(false)
}
