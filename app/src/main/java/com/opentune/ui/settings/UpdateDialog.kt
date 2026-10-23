package com.opentune.ui.settings

import android.content.Intent
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import com.opentune.ui.components.FloatingCard
import com.opentune.ui.components.ReleaseHero
import com.opentune.ui.components.ReleaseNotes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.opentune.BuildConfig
import com.opentune.data.UpdateCheck
import com.opentune.data.Updater
import com.opentune.ui.components.FloatingDialog
import com.opentune.ui.components.SheetButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A newer release: what changed, and a button that downloads the APK for
 * this phone and opens Android's installer. Falls back to the release page
 * when the release has no APK to offer.
 */
@Composable
fun UpdateDialog(found: UpdateCheck.Release, onDismiss: () -> Unit, onNotNow: (UpdateCheck.Release) -> Unit = { onDismiss() }) {
    val context = LocalContext.current
    // Swapped for a newer release if one came out since [found] was.
    var release by remember(found) { mutableStateOf(found) }
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val apk = release.apk
    var kept by remember { mutableStateOf(Updater.cachedBytes(context)) }

    fun start() {
        if (!Updater.canInstall(context)) {
            runCatching { context.startActivity(Updater.allowInstallsIntent(context)) }
            error = "Allow OpenTune to install apps, then tap Update again."
            return
        }
        error = null
        progress = 0f
        job = scope.launch {
            try {
                release = UpdateCheck.newest(context, release)
                val file = Updater.download(context, release) { p -> progress = p }
                // Off the main thread: it copies the whole APK into the installer, which froze the screen.
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Updater.install(context, file) }
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Download failed"
            } finally {
                progress = null
                kept = Updater.cachedBytes(context)
            }
        }
    }

    FloatingCard(
        onDismiss = onDismiss,
        title = "A new OpenTune",
        dismissible = progress == null,
        actions = {
            Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
                Button(enabled = progress == null, onClick = {
                    if (apk == null) {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, release.page.toUri())) }
                        onDismiss()
                    } else start()
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(if (progress != null) "Downloading…" else if (apk == null) "Open release page" else "Update OpenTune")
                }
                TextButton(onClick = {
                    if (progress != null) job?.cancel() else onNotNow(release)
                }, modifier = Modifier.fillMaxWidth()) { Text(if (progress != null) "Cancel download" else "Not now") }
            }
        },
    ) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
            .padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ReleaseHero(release.version, "Update available · you have ${BuildConfig.VERSION_NAME}")
            ReleaseNotes(release.notes)
            if (apk != null) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Ready for your phone", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Icon(Icons.Rounded.Android, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(apk.name, style = MaterialTheme.typography.bodyMedium)
                            Text(if (apk.bytes > 0) "Android app · ${Formatter.formatShortFileSize(context, apk.bytes)}" else "Android app",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    progress?.let { p ->
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                        Text("Downloading · ${(p * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                    }
                    if (progress == null && kept > 0) TextButton(onClick = { Updater.deleteCached(context); kept = 0 }) {
                        Text("Remove saved update (${Formatter.formatShortFileSize(context, kept)})")
                    }
                }
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.errorContainer).padding(16.dp))
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
