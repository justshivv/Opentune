package com.opentune.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

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
