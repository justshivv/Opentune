package com.opentune.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.opentune.BuildConfig
import com.opentune.data.UpdateCheck
import com.opentune.data.UpdateLock
import com.opentune.ui.components.ReleaseHero
import com.opentune.ui.components.ReleaseNotes

/**
 * In place of the whole app while it is more than [UpdateLock.MAX_BEHIND]
 * releases behind: the newest release and a button that installs it. Once
 * the update is installed the app restarts as the new version, unlocked.
 */
@Composable
fun UpdateRequiredScreen(found: UpdateCheck.Release, behind: Int) {
    val install = rememberUpdateInstall(found)
    val release = install.release
    val progress = install.progress
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Update OpenTune to keep listening", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "You have ${BuildConfig.VERSION_NAME}, which is $behind versions behind. Versions this old stop working as YouTube Music and " +
                        "the add-ons change, so OpenTune needs the update before it opens. Your library, downloads and settings stay as they are.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ReleaseHero(release.version, "Required update · you have ${BuildConfig.VERSION_NAME}")
                Button(
                    enabled = progress == null,
                    onClick = { if (release.apk == null) install.openReleasePage() else install.start() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    Text(if (progress != null) "Downloading…" else if (release.apk == null) "Open release page" else "Update to ${release.version}")
                }
                progress?.let { p ->
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    Text("Downloading · ${(p * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = install::cancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel download") }
                }
                install.error?.let {
                    Text(
                        it, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(16.dp),
                    )
                }
                if (progress == null && release.apk != null) {
                    // For a phone that can't install it here (an app store's build, a blocked installer).
                    TextButton(onClick = install::openReleasePage, modifier = Modifier.fillMaxWidth()) { Text("Download from the release page") }
                }
                if (release.notes.isNotBlank()) ReleaseNotes(release.notes)
            }
        }
    }
}
