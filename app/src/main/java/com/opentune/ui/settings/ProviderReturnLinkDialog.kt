package com.opentune.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import com.opentune.ui.components.FloatingDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** A visible, editable recovery path; callback text is never saved in instance state. */
@Composable
internal fun ProviderReturnLinkDialog(
    status: String,
    pending: Boolean,
    canRetry: Boolean,
    readClipboard: () -> String?,
    onVerify: (String) -> Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    var link by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    val connected = status == "Connected"
    val saving = status == "Saving verified session…"
    val editable = pending && !connected && !saving
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(connected) { if (connected) { link = ""; feedback = null } }
    FloatingDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (connected) "Amazon Music connected" else "Finish verification") },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(status, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (connected) {
                    Text("Your current track will be checked again for an Amazon Music stream.")
                } else {
                    if (!pending && !saving) Text("This verification has expired or was cancelled. Close this dialog and connect again.")
                    else Text("Paste the return link or grant= code from the verification page you just opened for this connection. A code alone cannot be matched locally; the provider must accept it before you are connected.")
                    OutlinedTextField(
                        value = link,
                        onValueChange = { value ->
                            if (value.length <= 32768) { link = value; feedback = null; isError = false }
                            else { feedback = "This link is too long. Copy the completed return link only."; isError = true }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("provider-return-link"),
                        label = { Text("Return link or code") },
                        minLines = 2,
                        maxLines = 3,
                        enabled = editable,
                        isError = isError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    )
                    feedback?.let {
                        Text(it, color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    OutlinedButton(enabled = editable, onClick = {
                        try {
                            val copied = readClipboard()?.trim().orEmpty()
                            when {
                                copied.isEmpty() -> { feedback = "Your clipboard has no text. Copy the return link in your browser, or paste it into the field."; isError = true }
                                copied.length > 32768 -> { feedback = "This link is too long. Copy the completed return link only."; isError = true }
                                else -> { link = copied; feedback = "Link pasted. Tap Verify link to continue."; isError = false }
                            }
                        } catch (_: Exception) {
                            feedback = "Clipboard unavailable. Press and hold inside Return link to paste instead."; isError = true
                        }
                    }) { Text("Paste") }
                    if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (canRetry) TextButton(onClick = onRetry) { Text("Retry saving session") }
                }
            }
        },
        confirmButton = {
            if (connected) TextButton(onClick = onDismiss) { Text("Done") }
            else TextButton(enabled = editable, onClick = {
                keyboard?.hide()
                if (link.isBlank()) { feedback = "Paste the completed return link first."; isError = true }
                else try {
                    if (onVerify(link.trim())) {
                        link = ""; feedback = "Result received. Saving your session…"; isError = false
                    } else {
                        feedback = "This link is incomplete or does not match this verification. Copy the full return link or the exact grant= code from the current browser page."; isError = true
                    }
                } catch (_: Exception) {
                    feedback = "Could not save the result. Try again."; isError = true
                }
            }) { Text("Verify link") }
        },
        dismissButton = { if (!connected) TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
