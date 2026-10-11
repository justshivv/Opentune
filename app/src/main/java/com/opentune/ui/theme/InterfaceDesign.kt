package com.opentune.ui.theme

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentune.data.settings.*
import com.opentune.ui.components.glass

data class InterfacePresentation(val options: DesignOptions = DesignOptions(), val reduceMotion: Boolean = false) {
    val active get() = options.style != InterfaceDesign.CURRENT
    val editorial get() = options.style == InterfaceDesign.EDITORIAL
    val glass get() = options.style == InterfaceDesign.GLASS
    val compact get() = options.density == DesignDensity.COMPACT
    val gutter get() = if (!active) 16.dp else if (compact) 18.dp else 22.dp
    val radius get() = if (editorial) 14.dp else 28.dp
    // Rows in four-row horizontal grids must keep their original height.
    val rowArt get() = if (!active || compact) 52.dp else 56.dp
    val rowPadding get() = if (!active) 6.dp else if (compact) 4.dp else 8.dp
    val rowHeight get() = rowArt + rowPadding * 2
    val motion get() = if (active) options.motion else DesignMotion.INHERIT
    val damping get() = when (motion) { DesignMotion.EXPRESSIVE -> .72f; DesignMotion.CALM -> 1f; else -> .9f }
    val duration get() = if (reduceMotion) 0 else when (motion) { DesignMotion.CALM -> 160; DesignMotion.EXPRESSIVE -> 360; else -> 280 }
    fun <T> spec(): FiniteAnimationSpec<T> = if (reduceMotion || motion == DesignMotion.CALM) tween(duration) else spring(dampingRatio = damping, stiffness = 480f)
}

val LocalInterface = compositionLocalOf { InterfacePresentation() }

@Composable
fun rememberInterface(ui: InterfaceSettings): InterfacePresentation {
    val context = LocalContext.current
    // Compose also honours the system animator scale for all animation specs.
    fun disabled() = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    var systemStill by remember(context) { mutableStateOf(disabled()) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { systemStill = disabled() }
        }
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return remember(ui.design, ui.reduceAnimation, systemStill) {
        InterfacePresentation(ui.design, ui.reduceAnimation || systemStill)
    }
}

fun designTypography(design: InterfaceDesign): Typography {
    if (design == InterfaceDesign.CURRENT) return AppTypography
    val family = if (design == InterfaceDesign.EDITORIAL) FontFamily.Serif else Outfit
    val weight = if (design == InterfaceDesign.EDITORIAL) FontWeight.Normal else FontWeight.SemiBold
    return AppTypography.copy(
        displaySmall = AppTypography.displaySmall.copy(fontFamily = family, fontWeight = weight, fontSize = 36.sp, lineHeight = 42.sp),
        headlineLarge = AppTypography.headlineLarge.copy(fontFamily = family, fontWeight = weight),
        headlineMedium = AppTypography.headlineMedium.copy(fontFamily = family, fontWeight = weight),
        headlineSmall = AppTypography.headlineSmall.copy(fontFamily = family, fontWeight = weight),
        titleLarge = AppTypography.titleLarge.copy(fontFamily = family, fontWeight = weight, fontSize = 24.sp, lineHeight = 30.sp),
    )
}

fun designShapes(design: InterfaceDesign): Shapes = when (design) {
    InterfaceDesign.CURRENT -> AppShapes
    InterfaceDesign.EDITORIAL -> Shapes(RoundedCornerShape(4.dp), RoundedCornerShape(8.dp), RoundedCornerShape(12.dp), RoundedCornerShape(18.dp), RoundedCornerShape(24.dp))
    else -> Shapes(RoundedCornerShape(8.dp), RoundedCornerShape(14.dp), RoundedCornerShape(22.dp), RoundedCornerShape(28.dp), RoundedCornerShape(36.dp))
}

/** Glass uses the existing blur, glass-style and reduced-blur settings. */
@Composable
fun Modifier.designSurface(accent: Boolean = false): Modifier {
    val design = LocalInterface.current
    if (!design.active) return this
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(design.radius)
    val fill = if (accent) colors.primaryContainer else colors.surfaceContainerLow
    return if (design.glass) {
        this.glass(shape, fill.copy(alpha = .7f)).border(1.dp, colors.outlineVariant.copy(alpha = .45f), shape)
    } else {
        this.clip(shape).background(fill).then(
            if (design.editorial) Modifier.border(1.dp, colors.outlineVariant.copy(alpha = .5f), shape) else Modifier,
        )
    }
}
