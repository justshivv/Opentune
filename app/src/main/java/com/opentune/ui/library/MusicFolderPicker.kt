package com.opentune.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.opentune.data.local.MusicFolder
import com.opentune.data.settings.AppSettings

/**
 * Opens Android's folder picker; the folder chosen there becomes the only place the phone's
 * songs are read from (with its subfolders), so the app needs no access to every audio file.
 */
@Composable
fun rememberMusicFolderPicker(onPicked: () -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            MusicFolder.use(context, tree)
            onPicked()
        }
    }
    return {
        // Opens where the folder picked last time is, if there is one.
        runCatching { launcher.launch(AppSettings.library.value.localTree?.let(android.net.Uri::parse)) }
            .onFailure { com.opentune.data.Pills.info(com.opentune.i18n.Tr.t("This phone has no folder picker")) }
    }
}
