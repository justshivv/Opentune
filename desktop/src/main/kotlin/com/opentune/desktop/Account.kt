package com.opentune.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opentune.data.account.AccountStore
import com.opentune.data.account.PairingReceiver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Accounts and integrations, as the web player's: YouTube Music first, as an integration row
 * with who's signed in under it (photo, name, email, Sign out), the other saved accounts to
 * switch to, and signing in with the phone; then [integrations] (Discord, Last.fm) in a card
 * of their own. Signed in, Home, search and the mixes are the account's own.
 */
@Composable
internal fun AccountSection(integrations: @Composable () -> Unit) {
    val signedIn by AccountStore.signedIn.collectAsState()
    val account by AccountStore.account.collectAsState()
    val accounts by AccountStore.accounts.collectAsState()
    var signingIn by remember { mutableStateOf(false) }
    if (signingIn) SignInWithPhone { signingIn = false }
    Sections("Accounts & integrations") {
        RowsCard {
            IntegrationRow(
                "YouTube Music", if (signedIn) "Signed in" else "Not signed in",
                tile = { YouTubeMusicTile() },
            ) {
                if (signedIn) LiveDot() else KeyPill("Sign in with your phone", { signingIn = true })
            }
            if (signedIn) {
                // The accounts sit under YouTube Music's row, their hairlines starting where its name does.
                val nested = Modifier.hairline(ICON_INSET).padding(start = ICON_INSET - TEXT_INSET)
                SettingRow(
                    account?.name ?: "Signed in", nested,
                    detail = account?.email?.takeIf { it.isNotBlank() },
                    leading = { Photo(account?.thumbnailUrl) }, leadingSize = PHOTO,
                ) { QuietPill("Sign out", AccountStore::signOut) }
                // Other accounts signed in here, one click to switch.
                accounts.filter { !it.active }.forEach { other ->
                    SettingRow(
                        other.name.ifBlank { "Another account" }, nested,
                        detail = other.email.takeIf { it.isNotBlank() },
                        leading = { Photo(other.thumbnailUrl) }, leadingSize = PHOTO,
                        onClick = { AccountStore.switchTo(other.id) },
                    ) { RowAction("Switch", { AccountStore.switchTo(other.id) }) }
                }
                SettingRow(
                    "Add another account", nested,
                    leading = {
                        Box(Modifier.size(PHOTO).clip(CircleShape).background(fillQuaternary()), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Add, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
                        }
                    },
                    leadingSize = PHOTO,
                    onClick = { signingIn = true },
                )
            }
        }
        CardFooter(
            if (signedIn) "Home, search and the mixes are your account's own."
            else "Sign in to have Home, mixes and suggestions made for you, from your YouTube Music account. Your phone signs this computer in: no password is typed here.",
        )
        CardHeading("Integrations")
        RowsCard(content = integrations)
    }
}

/** How big an account's photo is in its row. */
private val PHOTO = 32.dp

/** An account's photo, round, or the plain account glyph when it has none. */
@Composable
private fun Photo(url: String?) {
    if (url != null) Cover(url, PHOTO, CircleShape)
    else Icon(Icons.Rounded.AccountCircle, null, Modifier.size(PHOTO), tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * A QR code the phone scans, as the TV signs in: the phone hands this computer its YouTube
 * Music session straight over the home Wi-Fi, encrypted. Nothing goes through a server.
 */
@Composable
private fun SignInWithPhone(onClose: () -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    var receiver by remember { mutableStateOf<PairingReceiver?>(null) }
    var status by remember { mutableStateOf("Preparing a private connection…") }
    var done by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    DisposableEffect(attempt) { onDispose { receiver?.close() } }
    LaunchedEffect(attempt) {
        receiver = null; done = false; failed = false
        try {
            val current = withContext(Dispatchers.IO) { PairingReceiver() }
            receiver = current
            status = "On your phone, open OpenTune, go to Settings, then Link a device, then TV, and scan this code. Approve it on the phone. Both must be on the same Wi-Fi; the code lasts two minutes."
            withContext(Dispatchers.IO) { current.receive { cookie -> AccountStore.signIn(cookie); true } }
            done = true
            status = "Signed in. Your YouTube Music account is on this computer."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            status = if (e.message?.contains("same Wi-Fi") == true) "Connect this computer and your phone to the same Wi-Fi network."
            else "That code ran out, or the phone couldn't reach this computer. Make a new one."
        } finally {
            receiver?.close(); receiver = null
        }
    }
    LaunchedEffect(done) { if (done) { kotlinx.coroutines.delay(1_600); onClose() } }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Sign in with your phone") },
        text = {
            Column(Modifier.widthIn(max = 420.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val offer = receiver?.offer
                when {
                    done -> Icon(Icons.Rounded.Check, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
                    offer != null -> Box(Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).padding(16.dp)) { QrCode(offer.link, 220.dp) }
                }
                Text(status, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            if (failed) TextButton(onClick = { attempt++ }) { Text("New code") }
            else TextButton(onClick = onClose) { Text(if (done) "Done" else "Cancel") }
        },
    )
}
