package com.opentune.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.opentune.data.settings.AppSettings
import java.io.File
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Pictures of the window, drawn offscreen, for looking at a change without a
 * display. Off unless asked for: ./gradlew :desktop:test -Dopentune.shots=/some/folder
 */
class DesktopShots {
    private val folder = System.getProperty("opentune.shots")?.takeIf { it.isNotBlank() }?.let(::File)

    private fun shoot(name: String, width: Int, height: Int, waitMs: Long = 2_500, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width, height, Density(1.5f)) { content() }
        val start = System.nanoTime()
        var at = 0L
        // Let loading finish and animations settle, drawing as time passes.
        while (at < waitMs) {
            scene.render((System.nanoTime() - start))
            Thread.sleep(50)
            at = (System.nanoTime() - start) / 1_000_000
        }
        val image = scene.render(System.nanoTime() - start)
        folder!!.mkdirs()
        File(folder, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }

    @Test fun windows() {
        assumeTrue(folder != null)
        AppSettings.init(appContext)
        shoot("desktop-wide", 1280, 800, 9_000) { DesktopApp() }
        shoot("desktop-narrow", 640, 900, 9_000) { DesktopApp() }
        shoot("desktop-sources", 900, 1100) {
            DesktopTheme {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) { SourcesSection() }
                }
            }
        }
        shoot("desktop-qr", 300, 300, 300) { DesktopTheme { Box(Modifier.padding(20.dp)) { QrCode("opentune://addon-approve?id=abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ", 180.dp) } } }
    }
}
