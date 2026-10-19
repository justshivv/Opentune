package com.opentune.ui.lossless

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.opentune.data.lossless.ExtensionSessions
import com.opentune.ui.components.FloatingDialog
import com.opentune.ui.settings.ProviderReturnLinkDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun LosslessConnectionDialog(provider: String = "amazon", onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember(provider) { ExtensionSessions.forProvider(provider) }
    val status by manager.status.collectAsState()
    val pending by manager.pending.collectAsState()
    val retry by manager.canRetry.collectAsState()
    var paste by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pending) { while (pending) { delay(1000); manager.refreshStatus() } }
    FloatingDialog(onDismissRequest = onDismiss, title = { Text("Lossless provider") }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(com.opentune.data.lossless.ProviderPackages.displayName(provider), style = MaterialTheme.typography.titleLarge)
            Text(status, color = MaterialTheme.colorScheme.primary)
            Text("Connect through the SpotiFLAC extension service. Verification opens in your browser. Downloads still depend on provider availability.")
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(enabled = !busy && status != "Connected" && status != "Saving verified session…", onClick = {
                scope.launch {
                    busy = true; message = null
                    try {
                        manager.connect()?.let {
                            context.startActivity(Intent(context, ProviderVerificationActivity::class.java)
                                .putExtra(ProviderVerificationActivity.PROVIDER, provider))
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { message = "Verification could not open. Check your connection and try again." }
                    finally { busy = false }
                }
            }) { Text(if (pending) "Continue verification" else if (status == "Connected") "Connected" else "Connect in browser") }
            if (pending) {
                Text("Finish verification in your browser. Supported browsers close automatically and save the connection here. If an older browser stays open, use its Copy link button and paste the result below.")
                OutlinedButton(onClick = { paste = true }) { Text("Paste browser result") }
            }
            if (retry) TextButton(onClick = manager::retryCompletion) { Text("Retry saving session") }
            if (manager.enabled()) TextButton(onClick = manager::disconnect) { Text("Disconnect / cancel verification") }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
    if (paste) ProviderReturnLinkDialog(status, pending, retry, readClipboard = {
        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.let { it.text?.toString() ?: it.uri?.toString() }
    }, onVerify = { manager.receiveCallback(it, pasted = true) }, onRetry = manager::retryCompletion, onDismiss = { paste = false })
}
