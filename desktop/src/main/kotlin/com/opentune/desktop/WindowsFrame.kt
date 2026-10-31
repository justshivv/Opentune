package com.opentune.desktop

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * On Windows, OpenTune draws the title bar itself: the close, minimise and zoom buttons at the
 * head of the sidebar, and the empty parts of the sidebar's head and the player bar to drag the
 * window by. Underneath it's still an ordinary resizable Windows window, so Windows keeps its
 * shadow, rounded corners, Snap, the resize edges and the maximise animation; only the caption
 * Windows would draw is taken away.
 *
 * The window is created undecorated, so AWT lays the app out over all of it, and then given back
 * the styles of a normal window. A hook in front of AWT's window procedure answers two questions
 * Windows asks: how much of the window is the app's (all of it; when maximised, the screen's work
 * area, so nothing hides behind the taskbar), and whether the pointer is on a resize edge.
 *
 * On Windows 11 the window can also be backed by Mica or Acrylic, DWM's own materials.
 *
 * Everything here goes through JNA to user32 and dwmapi. Anything that fails leaves the window
 * as it was, and on Linux and macOS none of it is touched.
 */
internal object WindowsFrame {
    val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    /** Windows 11, where DWM has materials to put behind a window. */
    val isWindows11 = isWindows && System.getProperty("os.name").orEmpty().contains("11")

    /** The material behind the window: DWM's own value for each, or 1 for none. */
    enum class Material(val label: String, val dwm: Int) { OFF("Off", 1), MICA("Mica", 2), ACRYLIC("Acrylic", 3) }

    // ---- What Settings keeps -------------------------------------------------------

    private val file = File(System.getProperty("user.home"), ".opentune/window.json")
    private fun saved(): JSONObject = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())

    private val _ownFrame = MutableStateFlow(saved().optBoolean("ownFrame", true))
    /** Whether the next start draws its own title bar. Read once at start; changing it needs a restart. */
    val ownFrame: StateFlow<Boolean> = _ownFrame.asStateFlow()

    private val _material = MutableStateFlow(
        runCatching { Material.valueOf(saved().optString("material", Material.MICA.name)) }.getOrDefault(Material.MICA),
    )
    /** The material asked for in Settings. */
    val material: StateFlow<Material> = _material.asStateFlow()

    private val _activeMaterial = MutableStateFlow(Material.OFF)
    /** The material actually behind the window: off until Windows has taken it. */
    val activeMaterial: StateFlow<Material> = _activeMaterial.asStateFlow()

    /** Draws the app as if [m] were behind the window, for pictures of it taken off Windows. */
    internal fun pretendMaterial(m: Material) { _activeMaterial.value = m }

    fun setOwnFrame(on: Boolean) { _ownFrame.value = on; save() }

    fun setMaterial(m: Material, dark: Boolean) {
        _material.value = m
        save()
        applyMaterial(dark)
    }

    private fun save() = runCatching {
        file.parentFile.mkdirs()
        file.writeText(JSONObject().put("ownFrame", _ownFrame.value).put("material", _material.value.name).toString())
    }

    // ---- At start --------------------------------------------------------------------

    /**
     * Whether this run draws its own title bar: decided before the window is made, since an
     * undecorated window can't become decorated later. Needs JNA to load.
     */
    val drawn: Boolean by lazy { isWindows && _ownFrame.value && Native.POINTER_SIZE == 8 && api != null }

    /** Whether the window is made see-through, for a material to show through it. Also fixed at creation. */
    val transparent: Boolean by lazy { drawn && isWindows11 }

    private var hwnd: Pointer? = null
    private var previous: Pointer? = null
    // Held here for as long as the window lives: JNA frees a callback whose object is collected.
    private var proc: WndProc? = null

    /** Puts the frame on [window]'s handle. False, with the window untouched, when anything refuses. */
    fun install(windowHandle: Long): Boolean {
        if (!drawn || windowHandle == 0L) return false
        val u = api?.user ?: return false
        val d = api?.dwm
        return runCatching {
            val h = Pointer(windowHandle)
            if (hwnd == h && previous != null) return true
            val hook = WndProc { w, msg, wParam, lParam -> handle(w, msg, wParam, lParam) }
            val old = u.SetWindowLongPtrW(h, GWLP_WNDPROC, hook) ?: return false
            proc = hook
            previous = old
            hwnd = h
            val style = u.GetWindowLongPtrW(h, GWL_STYLE)
            u.SetWindowLongPtrW(h, GWL_STYLE, (style and WS_POPUP.inv()) or WS_CAPTION or WS_SYSMENU or WS_THICKFRAME or WS_MINIMIZEBOX or WS_MAXIMIZEBOX)
            // Windows 11's rounded corners, and a frame a pixel deep so DWM keeps the shadow.
            d?.DwmSetWindowAttribute(h, DWMWA_WINDOW_CORNER_PREFERENCE, intArrayOf(DWMWCP_ROUND), 4)
            d?.DwmExtendFrameIntoClientArea(h, intArrayOf(1, 1, 1, 1))
            u.SetWindowPos(h, null, 0, 0, 0, 0, SWP_FRAMECHANGED or SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_NOACTIVATE)
            applyMaterial(lastDark)
            true
        }.onFailure { System.err.println("OpenTune: own window frame not installed: $it") }.getOrDefault(false)
    }

    // The theme's darkness last asked for, for the material put on when the frame goes in.
    @Volatile private var lastDark = true

    /** Asks DWM for the chosen material, or takes it away. [dark] picks the dark or light kind of it. */
    fun applyMaterial(dark: Boolean) {
        lastDark = dark
        val h = hwnd ?: return
        val d = api?.dwm ?: return
        if (!transparent) { _activeMaterial.value = Material.OFF; return }
        val wanted = _material.value
        runCatching {
            d.DwmSetWindowAttribute(h, DWMWA_USE_IMMERSIVE_DARK_MODE, intArrayOf(if (dark) 1 else 0), 4)
            val ok = d.DwmSetWindowAttribute(h, DWMWA_SYSTEMBACKDROP_TYPE, intArrayOf(wanted.dwm), 4) >= 0
            // The material shows wherever the frame reaches and the app leaves clear: all of it, or just the shadow's pixel.
            val on = ok && wanted != Material.OFF
            d.DwmExtendFrameIntoClientArea(h, if (on) intArrayOf(-1, -1, -1, -1) else intArrayOf(1, 1, 1, 1))
            _activeMaterial.value = if (on) wanted else Material.OFF
        }.onFailure { _activeMaterial.value = Material.OFF }
    }

    // ---- What the title bar's buttons and empty parts do ---------------------------------

    fun minimize(): Boolean = command(SC_MINIMIZE)

    fun toggleMaximize(): Boolean {
        val h = hwnd ?: return false
        val maximized = runCatching { api!!.user.IsZoomed(h) }.getOrDefault(false)
        return command(if (maximized) SC_RESTORE else SC_MAXIMIZE)
    }

    /** Hands the press in progress to Windows, which moves the window as it would from its own title bar. */
    fun startDrag(): Boolean {
        val h = hwnd ?: return false
        return runCatching { api!!.user.PostMessageW(h, WM_OPENTUNE_DRAG, 0, 0) }.getOrDefault(false)
    }

    private fun command(sc: Long): Boolean {
        val h = hwnd ?: return false
        return runCatching { api!!.user.PostMessageW(h, WM_SYSCOMMAND, sc, 0) }.getOrDefault(false)
    }

    // ---- The hook ------------------------------------------------------------------------

    /** Runs on AWT's toolkit thread for every message the window gets: Win32 calls only, nothing of AWT's. */
    private fun handle(w: Pointer, msg: Int, wParam: Long, lParam: Long): Long {
        val u = api!!.user
        when (msg) {
            WM_NCCALCSIZE -> if (wParam != 0L) {
                // All of the window is the app's. Maximised, Windows makes the window a frame's
                // width bigger than the screen; the screen's work area keeps the edges on it.
                if (u.IsZoomed(w)) workArea(w)?.let { Pointer(lParam).write(0, it, 0, 4) }
                return 0
            }
            WM_NCHITTEST -> if (!u.IsZoomed(w)) edge(w, lParam)?.let { return it }
            WM_OPENTUNE_DRAG -> {
                // The press may be over by the time this arrives; a move started then would follow
                // the pointer until the next click.
                val button = if (u.GetSystemMetrics(SM_SWAPBUTTON) != 0) VK_RBUTTON else VK_LBUTTON
                if (u.GetAsyncKeyState(button).toInt() and 0x8000 == 0) return 0
                u.ReleaseCapture()
                u.DefWindowProcW(w, WM_SYSCOMMAND, SC_DRAGMOVE, 0)
                // Windows' move loop swallowed the release; give it back, or the next click is
                // taken as the end of this press.
                val at = IntArray(2)
                if (u.GetCursorPos(at) && u.ScreenToClient(w, at)) {
                    u.PostMessageW(w, WM_LBUTTONUP, 0, (at[0].toLong() and 0xFFFF) or ((at[1].toLong() and 0xFFFF) shl 16))
                }
                return 0
            }
            WM_NCDESTROY -> {
                val old = previous
                hwnd = null
                previous = null
                return if (old != null) u.CallWindowProcW(old, w, msg, wParam, lParam) else u.DefWindowProcW(w, msg, wParam, lParam)
            }
        }
        val old = previous ?: return u.DefWindowProcW(w, msg, wParam, lParam)
        return u.CallWindowProcW(old, w, msg, wParam, lParam)
    }

    /** Which resize edge or corner the point in [lParam] is on, or null for none. */
    private fun edge(w: Pointer, lParam: Long): Long? {
        val u = api!!.user
        val x = (lParam and 0xFFFF).toShort().toInt()
        val y = ((lParam shr 16) and 0xFFFF).toShort().toInt()
        val r = IntArray(4)
        if (!u.GetWindowRect(w, r)) return null
        val bx = border(w, horizontal = true)
        val by = border(w, horizontal = false)
        val left = x < r[0] + bx
        val right = x >= r[2] - bx
        val top = y < r[1] + by
        val bottom = y >= r[3] - by
        return when {
            top && left -> HTTOPLEFT
            top && right -> HTTOPRIGHT
            bottom && left -> HTBOTTOMLEFT
            bottom && right -> HTBOTTOMRIGHT
            left -> HTLEFT
            right -> HTRIGHT
            top -> HTTOP
            bottom -> HTBOTTOM
            else -> null
        }
    }

    /** How wide Windows' own resize border is at this window's scale. */
    private fun border(w: Pointer, horizontal: Boolean): Int {
        val u = api!!.user
        return runCatching {
            val dpi = u.GetDpiForWindow(w)
            u.GetSystemMetricsForDpi(if (horizontal) SM_CXFRAME else SM_CYFRAME, dpi) + u.GetSystemMetricsForDpi(SM_CXPADDEDBORDER, dpi)
        }.getOrElse { u.GetSystemMetrics(if (horizontal) SM_CXFRAME else SM_CYFRAME) + u.GetSystemMetrics(SM_CXPADDEDBORDER) }
    }

    /** The work area (the screen less the taskbar) of the monitor the window is on, as left, top, right, bottom. */
    private fun workArea(w: Pointer): IntArray? {
        val u = api!!.user
        val monitor = u.MonitorFromWindow(w, MONITOR_DEFAULTTONEAREST) ?: return null
        // MONITORINFO: its size, the monitor's rectangle, the work area's, flags.
        val info = IntArray(10).also { it[0] = 40 }
        if (!u.GetMonitorInfoW(monitor, info)) return null
        return info.copyOfRange(5, 9)
    }

    // ---- Win32, through JNA -----------------------------------------------------------------

    fun interface WndProc : Callback {
        fun callback(hWnd: Pointer, msg: Int, wParam: Long, lParam: Long): Long
    }

    @Suppress("FunctionName")
    interface User32 : Library {
        fun SetWindowLongPtrW(hWnd: Pointer, index: Int, proc: WndProc): Pointer?
        fun SetWindowLongPtrW(hWnd: Pointer, index: Int, value: Long): Long
        fun GetWindowLongPtrW(hWnd: Pointer, index: Int): Long
        fun CallWindowProcW(proc: Pointer, hWnd: Pointer, msg: Int, wParam: Long, lParam: Long): Long
        fun DefWindowProcW(hWnd: Pointer, msg: Int, wParam: Long, lParam: Long): Long
        fun PostMessageW(hWnd: Pointer, msg: Int, wParam: Long, lParam: Long): Boolean
        fun SendMessageW(hWnd: Pointer, msg: Int, wParam: Long, lParam: Long): Long
        fun SetWindowPos(hWnd: Pointer, after: Pointer?, x: Int, y: Int, cx: Int, cy: Int, flags: Int): Boolean
        fun IsZoomed(hWnd: Pointer): Boolean
        fun GetWindowRect(hWnd: Pointer, rect: IntArray): Boolean
        fun GetClientRect(hWnd: Pointer, rect: IntArray): Boolean
        fun GetDpiForWindow(hWnd: Pointer): Int
        fun GetSystemMetrics(index: Int): Int
        fun GetSystemMetricsForDpi(index: Int, dpi: Int): Int
        fun MonitorFromWindow(hWnd: Pointer, flags: Int): Pointer?
        fun GetMonitorInfoW(monitor: Pointer, info: IntArray): Boolean
        fun GetAsyncKeyState(key: Int): Short
        fun ReleaseCapture(): Boolean
        fun GetCursorPos(point: IntArray): Boolean
        fun ScreenToClient(hWnd: Pointer, point: IntArray): Boolean
    }

    @Suppress("FunctionName")
    interface Dwm : Library {
        fun DwmSetWindowAttribute(hWnd: Pointer, attribute: Int, value: IntArray, size: Int): Int
        fun DwmExtendFrameIntoClientArea(hWnd: Pointer, margins: IntArray): Int
    }

    class Api(val user: User32, val dwm: Dwm?)

    /** user32 and dwmapi, or null off Windows or when JNA won't load. */
    internal val api: Api? by lazy {
        if (!isWindows) null else runCatching {
            Api(Native.load("user32", User32::class.java), runCatching { Native.load("dwmapi", Dwm::class.java) }.getOrNull())
        }.onFailure { System.err.println("OpenTune: JNA unavailable, keeping Windows' own title bar: $it") }.getOrNull()
    }

    internal const val GWLP_WNDPROC = -4
    internal const val GWL_STYLE = -16
    internal const val WS_POPUP = 0x80000000L
    internal const val WS_CAPTION = 0x00C00000L
    internal const val WS_SYSMENU = 0x00080000L
    internal const val WS_THICKFRAME = 0x00040000L
    internal const val WS_MINIMIZEBOX = 0x00020000L
    internal const val WS_MAXIMIZEBOX = 0x00010000L
    private const val SWP_NOSIZE = 0x1
    private const val SWP_NOMOVE = 0x2
    private const val SWP_NOZORDER = 0x4
    private const val SWP_NOACTIVATE = 0x10
    private const val SWP_FRAMECHANGED = 0x20
    internal const val WM_NCHITTEST = 0x84
    private const val WM_NCCALCSIZE = 0x83
    private const val WM_NCDESTROY = 0x82
    private const val WM_SYSCOMMAND = 0x112
    private const val WM_LBUTTONUP = 0x202
    private const val WM_OPENTUNE_DRAG = 0x8000 + 0x42
    private const val SC_MINIMIZE = 0xF020L
    private const val SC_MAXIMIZE = 0xF030L
    private const val SC_RESTORE = 0xF120L
    /** A move started from the title bar: SC_MOVE with HTCAPTION. */
    private const val SC_DRAGMOVE = 0xF012L
    internal const val HTCLIENT = 1L
    internal const val HTLEFT = 10L
    internal const val HTRIGHT = 11L
    internal const val HTTOP = 12L
    internal const val HTTOPLEFT = 13L
    internal const val HTTOPRIGHT = 14L
    internal const val HTBOTTOM = 15L
    internal const val HTBOTTOMLEFT = 16L
    internal const val HTBOTTOMRIGHT = 17L
    private const val SM_CXFRAME = 32
    private const val SM_CYFRAME = 33
    private const val SM_SWAPBUTTON = 23
    private const val SM_CXPADDEDBORDER = 92
    private const val VK_LBUTTON = 1
    private const val VK_RBUTTON = 2
    private const val MONITOR_DEFAULTTONEAREST = 2
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_WINDOW_CORNER_PREFERENCE = 33
    private const val DWMWA_SYSTEMBACKDROP_TYPE = 38
    private const val DWMWCP_ROUND = 2
}
