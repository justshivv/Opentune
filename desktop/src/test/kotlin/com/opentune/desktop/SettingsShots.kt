package com.opentune.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.ThemeMode
import java.io.File
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The whole of Settings, every group in one tall picture, for looking at the
 * rows below the first screenful without a display. Off unless asked for:
 * ./gradlew :desktop:test --tests '*SettingsShots*' -Dopentune.shots=/some/folder
 */
class SettingsShots {
    private val folder = System.getProperty("opentune.shots")?.takeIf { it.isNotBlank() }?.let(::File)

    private fun shoot(name: String, width: Int, height: Int, waitMs: Long = 3_000, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width, height, Density(1.5f)) { content() }
        val start = System.nanoTime()
        var at = 0L
        // Let loading finish and animations settle, drawing as time passes.
        while (at < waitMs) {
            scene.render(System.nanoTime() - start)
            Thread.sleep(50)
            at = (System.nanoTime() - start) / 1_000_000
        }
        val image = scene.render(System.nanoTime() - start)
        folder!!.mkdirs()
        File(folder, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }

    /** The page as tall as all its groups, so the lazy list composes every one of them. */
    @Composable
    private fun Page() = DesktopTheme {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalPageTop provides TOP_BAR_HEIGHT) { SettingsPage() }
        }
    }

    @Test fun settings() {
        assumeTrue(folder != null)
        AppSettings.init(appContext)
        AppSettings.updateTheme { it.copy(mode = ThemeMode.DARK) }
        shoot("settings-all-dark", 1600, 9000) { Page() }
        shoot("settings-narrow-dark", 900, 9000) { Page() }
        AppSettings.updateTheme { it.copy(mode = ThemeMode.LIGHT) }
        shoot("settings-all-light", 1600, 9000) { Page() }
        AppSettings.updateTheme { it.copy(mode = ThemeMode.DARK) }
        // The audio sources group alone, as DesktopShots draws it.
        shoot("settings-sources", 900, 1400) {
            DesktopTheme {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.verticalScroll(rememberScrollState()).padding(24.dp).widthIn(max = 760.dp)) { SourcesSection() }
                }
            }
        }
    }
}
