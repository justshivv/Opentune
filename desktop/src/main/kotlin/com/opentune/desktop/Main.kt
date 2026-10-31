package com.opentune.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.opentune.data.Http
import com.opentune.data.settings.AppSettings
import com.opentune.data.subsonic.Subsonic
import java.io.File

/** Where the desktop keeps what it saves: ~/.opentune, read through the shared code's Context. */
internal val appContext by lazy { android.content.Context(File(System.getProperty("user.home"), ".opentune")) }

fun main(args: Array<String>) {
    if ("--self-check" in args) return selfCheck()
    // The shared settings and music server, as the phone starts them.
    AppSettings.init(appContext)
    Subsonic.init(appContext)
    com.opentune.data.config.RemoteConfig.init(appContext)
    // What's playing, to the listener's Discord profile and Last.fm scrobbles, when they've turned them on.
    DiscordPresence.start()
    LastFmDesktop.start()
    // FFmpeg's natives unpack once on first use: done now, off the window's thread, so the first song doesn't wait.
    Thread({ Ffmpeg.available }, "ffmpeg-load").apply { isDaemon = true; start() }
    // Covers on the same HTTP client as everything else.
    SingletonImageLoader.setSafe { context ->
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { Http.client })) }
            .build()
    }
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "OpenTune",
            icon = rememberLogo(Logo.APP),
            state = rememberWindowState(size = DpSize(1180.dp, 760.dp)),
            // On Windows the app draws its own title bar (see WindowsFrame), and on Windows 11 the
            // window is see-through for Mica or Acrylic to show. Both are fixed when it's made.
            undecorated = WindowsFrame.drawn,
            transparent = WindowsFrame.transparent,
        ) {
            window.minimumSize = java.awt.Dimension(760, 520)
            DesktopWindow.style(window)
            DesktopWindow.window = window
            DesktopWindow.close = ::exitApplication
            if (WindowsFrame.drawn) androidx.compose.runtime.LaunchedEffect(Unit) {
                // The handle exists once the window is on screen.
                repeat(20) {
                    if (window.isDisplayable && window.windowHandle != 0L && WindowsFrame.install(window.windowHandle)) return@LaunchedEffect
                    kotlinx.coroutines.delay(100)
                }
            }
            DesktopApp()
        }
    }
}

/**
 * `OpenTune --self-check`: loads what the installer must carry (FFmpeg,
 * GraalJS, SQLite), prints what it found and exits, failing if any is
 * missing. For checking a packaged build, where the trimmed Java runtime
 * could be short of a module the tests' full one has.
 */
private fun selfCheck() {
    val checks = listOf(
        "FFmpeg" to { check(Ffmpeg.available) { "didn't load" }; "loaded" },
        "GraalJS" to {
            val got = org.schabi.newpipe.extractor.utils.JavaScript.run("var f = (a) => a?.at(-1) ?? 'x';", "f", "abc")
            check(got == "c") { "got $got" }
            "ran"
        },
        "SQLite" to { "${Library.playlists.value.size} playlists, ${Library.liked.value.size} liked" },
    )
    var failed = false
    for ((name, run) in checks) {
        val result = runCatching(run)
        println("$name: " + result.fold({ "ok, $it" }, { failed = true; "FAILED, $it" }))
    }
    kotlin.system.exitProcess(if (failed) 1 else 0)
}
