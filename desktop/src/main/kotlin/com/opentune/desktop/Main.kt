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
    // Library logs to desktop.log, set before any library asks for its logger.
    Logging.install()
    CrashLog.install()
    CrashLog.startDiagnostics()
    // Before anything is drawn: a renderer that failed to start last time is passed over.
    WindowsFrame.chooseRenderer()
    // The system's fonts read in the background while the rest starts: the first text the window
    // laid out waited two seconds on that on Windows.
    Thread({ runCatching { org.jetbrains.skia.FontMgr.default.let { it.familiesCount; it.matchFamilyStyle(null, org.jetbrains.skia.FontStyle.NORMAL) } } }, "font-warm")
        .apply { isDaemon = true; start() }
    // The key the sign-ins are sealed with, from the system's keyring, before anything reads one.
    SecretVault.start()
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
    // Whether a newer release is out, and the install count if the listener turned it on.
    Updates.start()
    DesktopUsage.start()
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
            // Which of Skia's renderers this window drew with (DirectX, OpenGL, Metal or software), for the log.
            androidx.compose.runtime.LaunchedEffect(Unit) { CrashLog.note("Renderer: ${runCatching { window.renderApi }.getOrNull()}") }
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
        // AES-GCM and the JNA platform classes on the trimmed runtime, with a key in memory only:
        // this computer's keyring is left alone.
        "Vault" to {
            val dir = java.nio.file.Files.createTempDirectory("opentune-check").toFile()
            try {
                SecretVault.start(dir, InMemoryKeys())
                val file = File(dir, "lastfm.json")
                SecretVault.write(file, "check")
                check(SecretVault.isSealed(file) && SecretVault.read(file) == "check") { "didn't seal and open" }
                check(KeyBackend.forThisSystem(dir) != null || !(com.sun.jna.Platform.isWindows() || com.sun.jna.Platform.isMac() || com.sun.jna.Platform.isLinux())) { "no keyring backend" }
                "sealed and opened"
            } finally {
                dir.deleteRecursively()
            }
        },
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

    private val logFile = File(File(System.getProperty("user.home"), ".opentune"), "desktop.log")
    @Volatile private var log: java.io.PrintStream? = null

    /** A line in desktop.log, with the time. */
    fun note(line: String) {
        val out = log ?: return
        synchronized(out) { out.println("${java.time.LocalTime.now().withNano(0)} $line"); out.flush() }
    }

    /**
     * What the app does while it runs, in ~/.opentune/desktop.log (the run before kept as
     * desktop.log.1): everything it prints, the system and the renderer at the start, the memory
     * every ten seconds, and, whenever the window's thread stops answering for two seconds, what
     * it's stuck doing. A freeze that ends with Windows closing the app leaves no crash report of
     * its own; this says where it was.
     */
    fun startDiagnostics() = runCatching {
        logFile.parentFile.mkdirs()
        if (logFile.exists()) logFile.copyTo(File(logFile.parentFile, "desktop.log.1"), overwrite = true)
        val file = java.io.PrintStream(java.io.FileOutputStream(logFile, false), true, "UTF-8")
        log = file
        // What the app prints goes to the console as before and to the log.
        System.setOut(Tee(System.out, file))
        System.setErr(Tee(System.err, file))
        val rt = Runtime.getRuntime()
        note("OpenTune ${System.getProperty("jpackage.app-version") ?: "dev"} on ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")}), Java ${System.getProperty("java.version")}, ${rt.availableProcessors()} cores, up to ${rt.maxMemory() / 1_048_576} MB")
        Thread({
            var tick = 0
            var stuckSince = 0L
            var reportedAt = 0L
            val answered = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
            while (true) {
                Thread.sleep(500)
                val now = System.currentTimeMillis()
                java.awt.EventQueue.invokeLater { answered.set(System.currentTimeMillis()) }
                val waited = now - answered.get()
                if (waited > 2_000) {
                    if (stuckSince == 0L) stuckSince = now
                    // Once at two seconds, then every five while it lasts: where it is may move.
                    if (now - reportedAt > 5_000) {
                        reportedAt = now
                        val edt = Thread.getAllStackTraces().entries.firstOrNull { it.key.name.startsWith("AWT-EventQueue") }
                        note("The window has not answered for ${waited / 1000.0} s. Memory ${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} of ${rt.maxMemory() / 1_048_576} MB. It is in:")
                        edt?.value?.take(40)?.forEach { note("    at $it") }
                    }
                } else if (stuckSince != 0L) {
                    note("The window answered again after ${(now - stuckSince) / 1000.0} s.")
                    stuckSince = 0L; reportedAt = 0L
                }
                if (++tick % 20 == 0) note("Memory ${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} of ${rt.maxMemory() / 1_048_576} MB")
            }
        }, "opentune-watchdog").apply { isDaemon = true; start() }
    }

    /** Writes to both streams. */
    private class Tee(private val a: java.io.PrintStream, private val b: java.io.PrintStream) : java.io.PrintStream(a, true) {
        override fun write(buf: ByteArray, off: Int, len: Int) { a.write(buf, off, len); runCatching { b.write(buf, off, len); b.flush() } }
        override fun write(c: Int) { a.write(c); runCatching { b.write(c) } }
        override fun flush() { a.flush(); runCatching { b.flush() } }
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
        note("Error on ${thread.name}, written to crash.log: $error")
    }
}
