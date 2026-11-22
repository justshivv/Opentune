package com.opentune.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.opentune.data.settings.AppSettings

internal fun darkSystemIcons(background: Color): Boolean = background.luminance() > 0.5f

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** Transparent system bars expose the same edge-to-edge backdrop as the content. */
@Suppress("DEPRECATION")
@Composable
fun SystemBars(background: Color) {
    val view = LocalView.current
    val window = view.context.activity()?.window ?: return
    val dark = darkSystemIcons(background)
    DisposableEffect(window, dark) {
        val controller = WindowCompat.getInsetsController(window, view)
        val oldStatus = controller.isAppearanceLightStatusBars
        val oldNavigation = controller.isAppearanceLightNavigationBars
        val statusColor = window.statusBarColor
        val navigationColor = window.navigationBarColor
        val oldContrast = if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced else false
        window.statusBarColor = Color.Transparent.toArgb()
        window.navigationBarColor = Color.Transparent.toArgb()
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        controller.isAppearanceLightStatusBars = dark
        controller.isAppearanceLightNavigationBars = dark
        onDispose {
            controller.isAppearanceLightStatusBars = oldStatus
            controller.isAppearanceLightNavigationBars = oldNavigation
            window.statusBarColor = statusColor
            window.navigationBarColor = navigationColor
            if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = oldContrast
        }
    }
}

/**
 * The system bars on a background of their own (Settings › Solid system bars):
 * a strip the height of each bar, in the colour of the page behind it — on a
 * washed page the wash's own colour, as the frosted top edge takes it — so
 * the bars read as part of the page instead of the page showing through them.
 * The bars stay edge-to-edge and nothing moves; only what fills them changes.
 * Draws nothing while the setting is off.
 */
@Composable
fun SystemBarBackdrops(top: Boolean = true, bottom: Boolean = true) {
    val ui by AppSettings.ui.collectAsState()
    if (!ui.solidSystemBars) return
    val colour = com.opentune.ui.components.LocalPageWash.current?.tint ?: MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize()) {
        if (top) Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
                .background(colour),
        )
        if (bottom) Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .height(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
                .background(colour),
        )
    }
}
