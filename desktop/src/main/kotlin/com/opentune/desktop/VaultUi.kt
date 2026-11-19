package com.opentune.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Whether the no-keyring question was put aside for this run (Escape, or a click outside). */
private val askedThisRun = mutableStateOf(false)

/** Asked once where no keyring answered: keep sign-ins in a plain file, or only until quitting. */
@Composable
internal fun KeyringChoiceDialog() {
    val mode by SecretVault.mode.collectAsState()
    if (mode != SecretVault.Mode.Undecided || askedThisRun.value) return
    KeyringChoice(onDismiss = { askedThisRun.value = true })
}

@Composable
private fun KeyringChoice(onDismiss: () -> Unit) {
    PageDialog(
        "No keyring found",
        onDismiss = onDismiss,
        buttons = {
            PageButton("Keep until I quit") { SecretVault.choose(SecretVault.Mode.SessionOnly); onDismiss() }
            PageButton("Store unencrypted", primary = true) { SecretVault.choose(SecretVault.Mode.Unencrypted); onDismiss() }
        },
    ) {
        Column {
            Text(
                "OpenTune keeps your sign-ins (YouTube Music, Last.fm, Subsonic) encrypted with a key from your " +
                    "system's keyring, and none answered on this computer.",
                style = PageType.cardLine, color = PageInk.grey,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "You can keep them in files in your home folder that only your user can read, but they won't be " +
                    "encrypted. Or keep them only until you quit OpenTune and sign in again next time.",
                style = PageType.cardLine, color = PageInk.grey,
            )
            if (com.sun.jna.Platform.isLinux()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Installing GNOME Keyring or KWallet and starting OpenTune again keeps them encrypted.",
                    style = PageType.cardLine, color = PageInk.grey,
                )
            }
        }
    }
}

/** Settings → About: where the sign-ins are kept, and a way to change it when there's no keyring. */
@Composable
internal fun SavedSignInsRow() {
    val mode by SecretVault.mode.collectAsState()
    var asking by remember { mutableStateOf(false) }
    val detail = when (mode) {
        SecretVault.Mode.Keystore -> "Encrypted, with the key kept in ${SecretVault.keptIn}."
        SecretVault.Mode.Unencrypted -> "In files only your user can read, not encrypted: no keyring answered."
        SecretVault.Mode.SessionOnly -> "Kept only until you quit: no keyring answered."
        SecretVault.Mode.Undecided -> "Kept until you quit, until you choose: no keyring answered."
    }
    SettingRow(
        "Saved sign-ins",
        detail = detail,
        leading = { IconTile(Icons.Rounded.Key, Color(0xFF8E8E93)) },
        onClick = if (mode != SecretVault.Mode.Keystore) ({ asking = true }) else null,
        role = Role.Button,
    )
    if (asking) KeyringChoice(onDismiss = { asking = false })
}

/** A newer release, over the top of the window until it's closed for that version. */
@Composable
internal fun UpdateBanner(modifier: Modifier = Modifier) {
    val found by Updates.found.collectAsState()
    val dismissed by Updates.dismissed.collectAsState()
    val enabled by Updates.enabled.collectAsState()
    val links = androidx.compose.ui.platform.LocalUriHandler.current
    val f = found?.takeIf { enabled && it.version != dismissed } ?: return
    androidx.compose.foundation.layout.Row(
        modifier.shadow(12.dp, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
    ) {
        Text("OpenTune ${f.version} is out", style = PageType.cardLine, color = PageInk.text)
        PageButton("Get it", primary = true) { runCatching { links.openUri(f.page) } }
        androidx.compose.material3.IconButton(onClick = { Updates.dismiss(f.version) }) {
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Close, "Not now", tint = inkSecondary())
        }
    }
}

/** Settings → About: the update check and the opt-in install count. */
@Composable
internal fun UpdateAndUsageRows() {
    val enabled by Updates.enabled.collectAsState()
    val found by Updates.found.collectAsState()
    val checking by Updates.checking.collectAsState()
    val checked by Updates.checked.collectAsState()
    val counting by DesktopUsage.enabled.collectAsState()
    val links = androidx.compose.ui.platform.LocalUriHandler.current
    val status = when {
        !enabled -> "Off. Releases are on GitHub."
        checking -> "Checking…"
        found != null -> "OpenTune ${found!!.version} is out."
        checked > 0 -> "You have the latest version."
        else -> "Looks at GitHub's latest release twice a day. Nothing is downloaded on its own."
    }
    ToggleRow(
        "Check for updates", status, enabled, Updates::setEnabled,
        leading = { IconTile(androidx.compose.material.icons.Icons.Rounded.SystemUpdate, Color(0xFF34C759)) },
        extra = {
            val f = found
            if (enabled && f != null) PageButton("Get it", primary = true) { runCatching { links.openUri(f.page) } }
            else if (enabled && !checking) PageButton("Check now") { Updates.checkNow() }
        },
    )
    ToggleRow(
        "Count this install",
        "Off unless you turn it on. Once a day it sends a random ID made on this computer and the app's version, so the " +
            "project knows how many people use it. No songs, no account, nothing else.",
        counting, DesktopUsage::setEnabled,
        leading = { IconTile(androidx.compose.material.icons.Icons.Rounded.BarChart, Color(0xFF5E5CE6)) },
    )
}
