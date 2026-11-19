package com.opentune.desktop

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
    CrashLog.install()
    // The shared settings and music server, as the phone starts them.
    AppSettings.init(appContext)
    // A setting changed in the last moment before closing is written on its way out.
    Runtime.getRuntime().addShutdownHook(Thread { AppSettings.flush() })
    Subsonic.init(appContext)
    com.opentune.data.config.RemoteConfig.init(appContext)
    com.opentune.data.config.HomeDismissals.init(appContext)
    // The YouTube Music account, before Home asks for anything: signed in, Home is your own.
    com.opentune.data.account.AccountStore.init(appContext)
    // Its library on YouTube Music, read in the background and again on each sign-in or sign-out.
    AccountLibrary.start()
    // Listen together, run against this computer's player; a guest's controls go to the host.
    com.opentune.data.together.Together.init(appContext)
    com.opentune.data.lyrics.LyricsTranslator.init(appContext)
    com.opentune.data.together.Together.attach(DesktopRoomPlayer)
    Player.intercept = com.opentune.data.together.Together::intercept
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
        // Closing the window keeps the music going in the tray, as BitChord's desktop does,
        // when the tray is there and the setting is on; the tray's Quit ends it.
        var shown by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
        val inTray by TrayPrefs.keepPlaying.collectAsState()
        val trayed = inTray && TrayPrefs.supported
        val close: () -> Unit = { if (trayed) shown = false else exitApplication() }
        if (TrayPrefs.supported) TrayMenu(onShow = { shown = true }, onQuit = ::exitApplication)
        // An error inside the window (Compose's own layout has a bug in this version that throws
        // "LayoutNode … not found in RectList" now and then) is written to crash.log and the window
        // carries on drawing from the next frame, instead of closing the app. Only a flood of them
        // still ends it, with the error shown, so a window that can't draw at all doesn't sit there.
        @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.window.LocalWindowExceptionHandlerFactory provides CrashLog.windowHandler,
        ) {
        Window(
            onCloseRequest = close,
            visible = shown,
            onKeyEvent = Shortcuts::handle,
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
            DesktopWindow.close = close
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

/**
 * What took the app down, kept in ~/.opentune/crash.log: the error, where it was thrown, the
 * version, the system and the memory in use, so a crash on someone's computer can be read
 * afterwards. A crash in native code (Skia, the audio) is written by Java itself, as
 * hs_err_pid….log in the folder the app was started from.
 */
internal object CrashLog {
    private val file = File(File(System.getProperty("user.home"), ".opentune"), "crash.log")

    fun install() {
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            write(thread, error)
            before?.uncaughtException(thread, error)
        }
    }

    /** Times of the window's recent errors, for telling one now and then from a flood. */
    private val recent = java.util.ArrayDeque<Long>()

    /**
     * The window's own errors: written down and let go while they're rare; past [FLOOD] in ten
     * seconds, the window can't be drawing, so Compose's own handling (an error box, then closing) takes over.
     */
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    val windowHandler = androidx.compose.ui.window.WindowExceptionHandlerFactory { window ->
        androidx.compose.ui.window.WindowExceptionHandler { error ->
            write(Thread.currentThread(), error)
            val now = System.currentTimeMillis()
            val flood = synchronized(recent) {
                recent.addLast(now)
                while (recent.isNotEmpty() && now - recent.peekFirst() > 10_000) recent.pollFirst()
                recent.size > FLOOD
            }
            if (flood) androidx.compose.ui.window.DefaultWindowExceptionHandlerFactory.exceptionHandler(window).onException(error)
        }
    }

    private const val FLOOD = 30

    private fun write(thread: Thread, error: Throwable) = runCatching {
        file.parentFile.mkdirs()
        // The newest at the end, the file kept small.
        if (file.length() > 512 * 1024) file.writeText("")
        val rt = Runtime.getRuntime()
        val text = buildString {
            appendLine("---- ${java.time.ZonedDateTime.now()} ----")
            appendLine("OpenTune ${System.getProperty("jpackage.app-version") ?: "dev"} on ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")}), Java ${System.getProperty("java.version")}")
            appendLine("Memory: ${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} MB used of ${rt.maxMemory() / 1_048_576} MB")
            appendLine("Thread: ${thread.name}")
            appendLine(error.stackTraceToString())
        }
        file.appendText(text)
    }
}
