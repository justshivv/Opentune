package com.opentune.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.DisplayCutout
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.opentune.R
import com.opentune.data.DebugLog
import com.opentune.ui.theme.AppLanguages
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Measured front-camera / notch geometry used to position the floating pill cleanly around the lens. */
data class CutoutGeometry(
    val hasCutout: Boolean,
    val cutoutWidthPx: Int,
    val cutoutHeightPx: Int,
    val cutoutTopPx: Int,
    val cutoutCenterXPx: Int,
    val screenWidthPx: Int,
    val density: Float,
) {
    val cutoutWidthDp: Float get() = cutoutWidthPx / density
    val cutoutHeightDp: Float get() = cutoutHeightPx / density
    val cutoutTopDp: Float get() = cutoutTopPx / density
    val screenWidthDp: Float get() = screenWidthPx / density
}

/** Snapshot of active playback rendered inside the floating notch mini-player. */
data class NotchOverlayState(
    val mediaId: String? = null,
    val title: String = "",
    val artist: String = "",
    val artworkUrl: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val hasPrevious: Boolean = true,
    val hasNext: Boolean = true,
    val expanded: Boolean = false,
) {
    val hasActiveMedia: Boolean
        get() = !mediaId.isNullOrBlank() && (title.isNotBlank() || artist.isNotBlank())
}

/** User interactions dispatched from [NotchPlayerOverlay] to the Media3 controller in [NotchOverlayService]. */
data class NotchOverlayActions(
    val onToggleExpanded: () -> Unit,
    val onCollapse: () -> Unit,
    val onDismiss: () -> Unit,
    val onOpenPlayer: () -> Unit,
    val onTogglePlayPause: () -> Unit,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onSeekTo: (Long) -> Unit,
)

/**
 * Custom lifecycle/state owner attached to a [ComposeView] hosted directly inside [WindowManager]
 * so Jetpack Compose animations, `LaunchedEffect`, and `StateFlow` collection run outside an Activity.
 */
private class OverlayLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val viewModelStore: ViewModelStore = ViewModelStore()
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private var attached = false

    fun attach() {
        if (!attached) {
            savedStateController.performAttach()
            savedStateController.performRestore(null)
            attached = true
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun detach() {
        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
            if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            }
            if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            }
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
        viewModelStore.clear()
    }
}

/**
 * Manages the [WindowManager] overlay window, front-camera cutout measurements, outside-touch
 * collapse handling, and Jetpack Compose view lifecycle for the Dynamic Notch Mini-Player.
 */
class NotchOverlayManager(
    private val serviceContext: Context,
    private val actions: NotchOverlayActions,
) {
    private val windowManager = serviceContext.getSystemService(WindowManager::class.java)

    private val _state = MutableStateFlow(NotchOverlayState())
    val state: StateFlow<NotchOverlayState> = _state.asStateFlow()

    private val _cutout = MutableStateFlow(measureCutout(insetsCutout = null))
    val cutout: StateFlow<CutoutGeometry> = _cutout.asStateFlow()

    private var rootContainer: FrameLayout? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var landscape: Boolean = isLandscape(serviceContext.resources.configuration)

    val isShowing: Boolean
        get() = rootContainer != null

    fun updateState(newState: NotchOverlayState) {
        val expandedChanged = _state.value.expanded != newState.expanded
        _state.value = newState
        if (expandedChanged && rootContainer != null) {
            applyLayoutParams(expanded = newState.expanded)
        }
    }

    fun setExpanded(expanded: Boolean) {
        if (_state.value.expanded == expanded) return
        _state.value = _state.value.copy(expanded = expanded)
        if (rootContainer != null) {
            applyLayoutParams(expanded = expanded)
        }
    }

    fun onConfigurationChanged(newConfig: Configuration) {
        val nowLandscape = isLandscape(newConfig)
        landscape = nowLandscape
        if (nowLandscape) {
            if (_state.value.expanded) {
                _state.value = _state.value.copy(expanded = false)
            }
            detachWindow()
        } else {
            refreshCutout(insetsCutout = null)
            if (_state.value.hasActiveMedia && rootContainer == null) {
                attachWindow()
            } else if (rootContainer != null) {
                applyLayoutParams(expanded = _state.value.expanded)
            }
        }
    }

    fun show() {
        landscape = isLandscape(serviceContext.resources.configuration)
        if (landscape || !_state.value.hasActiveMedia) return
        if (!Settings.canDrawOverlays(serviceContext)) return
        if (rootContainer != null) {
            applyLayoutParams(expanded = _state.value.expanded)
            return
        }
        attachWindow()
    }

    fun hide() {
        if (_state.value.expanded) {
            _state.value = _state.value.copy(expanded = false)
        }
        detachWindow()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachWindow() {
        val wm = windowManager ?: return
        if (!Settings.canDrawOverlays(serviceContext)) return
        refreshCutout(insetsCutout = null)

        val themedContext = ContextThemeWrapper(
            AppLanguages.wrap(serviceContext),
            R.style.Theme_OpenTune,
        )
        val owner = OverlayLifecycleOwner()

        val container = FrameLayout(themedContext).apply {
            clipChildren = false
            clipToPadding = false
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE && _state.value.expanded) {
                    actions.onCollapse()
                    true
                } else {
                    false
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                setOnApplyWindowInsetsListener { view, insets ->
                    updateCutoutFromInsets(insets)
                    view.onApplyWindowInsets(insets)
                }
            }
        }

        val compose = ComposeView(themedContext).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val overlayState by state.collectAsState()
                val geometry by cutout.collectAsState()
                NotchPlayerOverlay(
                    state = overlayState,
                    cutout = geometry,
                    actions = actions,
                )
            }
        }
        container.addView(
            compose,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val params = buildLayoutParams(expanded = _state.value.expanded)
        owner.attach()
        val added = runCatching {
            wm.addView(container, params)
            true
        }.onFailure { error ->
            DebugLog.w(TAG, "Failed to add notch overlay window", error)
            owner.detach()
        }.getOrDefault(false)

        if (!added) return

        lifecycleOwner = owner
        rootContainer = container
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            container.requestApplyInsets()
        }
    }

    private fun detachWindow() {
        val container = rootContainer
        rootContainer = null
        if (container != null) {
            runCatching {
                if (container.isAttachedToWindow) {
                    windowManager?.removeViewImmediate(container)
                }
            }.onFailure { error ->
                DebugLog.w(TAG, "Failed to remove notch overlay window", error)
            }
        }
        lifecycleOwner?.detach()
        lifecycleOwner = null
    }

    private fun buildLayoutParams(expanded: Boolean): WindowManager.LayoutParams {
        val geometry = _cutout.value
        val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        val flags = if (expanded) {
            baseFlags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        } else {
            baseFlags
        }

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = computeHorizontalOffsetPx(geometry, expanded)
            y = computeVerticalOffsetPx(geometry)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            title = WINDOW_TITLE
        }
    }

    private fun applyLayoutParams(expanded: Boolean) {
        val container = rootContainer ?: return
        val wm = windowManager ?: return
        val updated = buildLayoutParams(expanded)
        if (container.isAttachedToWindow) {
            runCatching { wm.updateViewLayout(container, updated) }
                .onFailure { error -> DebugLog.w(TAG, "Failed to update overlay layout", error) }
        }
    }

    private fun computeHorizontalOffsetPx(geometry: CutoutGeometry, expanded: Boolean): Int {
        if (expanded || !geometry.hasCutout) return 0
        val screenCenterX = geometry.screenWidthPx / 2
        val rawOffset = geometry.cutoutCenterXPx - screenCenterX
        val estimatedHalfPillPx = ((geometry.cutoutWidthDp + 96f) * 0.5f * geometry.density).roundToInt()
        val edgeMarginPx = (8f * geometry.density).roundToInt()
        val maxOffset = (screenCenterX - estimatedHalfPillPx - edgeMarginPx).coerceAtLeast(0)
        return rawOffset.coerceIn(-maxOffset, maxOffset)
    }

    private fun computeVerticalOffsetPx(geometry: CutoutGeometry): Int {
        val minTopPx = (4f * geometry.density).roundToInt()
        if (!geometry.hasCutout) {
            return (8f * geometry.density).roundToInt()
        }
        val pillHeightPx = ((geometry.cutoutHeightDp + 6f).coerceIn(36f, 48f) * geometry.density).roundToInt()
        val centeredOnCutout = geometry.cutoutTopPx + (geometry.cutoutHeightPx - pillHeightPx) / 2
        return centeredOnCutout.coerceAtLeast(minTopPx)
    }

    private fun updateCutoutFromInsets(insets: WindowInsets) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        refreshCutout(insets.displayCutout)
        if (rootContainer != null) {
            applyLayoutParams(expanded = _state.value.expanded)
        }
    }

    private fun refreshCutout(insetsCutout: DisplayCutout?) {
        val measured = measureCutout(insetsCutout)
        if (measured != _cutout.value) {
            _cutout.value = measured
        }
    }

    private fun measureCutout(insetsCutout: DisplayCutout?): CutoutGeometry {
        val density = serviceContext.resources.displayMetrics.density.coerceAtLeast(1f)
        val screenWidthPx = resolveScreenWidthPx()
        val displayCutout = insetsCutout ?: resolveWindowMetricsCutout()
        val topRect = displayCutout?.let { extractTopCutoutRect(it, density) }

        if (topRect != null && topRect.width() > 0 && topRect.height() > 0) {
            return CutoutGeometry(
                hasCutout = true,
                cutoutWidthPx = topRect.width(),
                cutoutHeightPx = topRect.height(),
                cutoutTopPx = topRect.top.coerceAtLeast(0),
                cutoutCenterXPx = topRect.centerX().coerceIn(0, screenWidthPx),
                screenWidthPx = screenWidthPx,
                density = density,
            )
        }

        // Safe fallback for notchless screens or devices that do not report a top DisplayCutout.
        val fallbackHeightPx = (32f * density).roundToInt()
        val fallbackTopPx = (8f * density).roundToInt()
        return CutoutGeometry(
            hasCutout = false,
            cutoutWidthPx = 0,
            cutoutHeightPx = fallbackHeightPx,
            cutoutTopPx = fallbackTopPx,
            cutoutCenterXPx = screenWidthPx / 2,
            screenWidthPx = screenWidthPx,
            density = density,
        )
    }

    private fun resolveScreenWidthPx(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager?.currentWindowMetrics?.bounds?.width()?.takeIf { it > 0 }?.let { return it }
        }
        return serviceContext.resources.displayMetrics.widthPixels.coerceAtLeast(1)
    }

    private fun resolveWindowMetricsCutout(): DisplayCutout? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return windowManager?.currentWindowMetrics?.windowInsets?.displayCutout
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return rootContainer?.rootWindowInsets?.displayCutout
        }
        return null
    }

    private fun extractTopCutoutRect(cutout: DisplayCutout, density: Float): Rect? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val top = cutout.boundingRectTop
            if (top.width() > 0 && top.height() > 0) return top
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val maxTopThresholdPx = (24f * density).roundToInt()
            return cutout.boundingRects.firstOrNull { rect ->
                rect.top <= maxTopThresholdPx && rect.width() > 0 && rect.height() > 0
            }
        }
        return null
    }

    private fun isLandscape(config: Configuration): Boolean =
        config.orientation == Configuration.ORIENTATION_LANDSCAPE

    private companion object {
        const val TAG = "NotchOverlayManager"
        const val WINDOW_TITLE = "OpenTuneNotchOverlay"
    }
}
