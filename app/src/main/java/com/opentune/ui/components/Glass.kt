package com.opentune.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opentune.data.settings.AppSettings
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/** The content layer that floating glass blurs, provided by the app shell. */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

/**
 * Frosted glass: the content behind is blurred and tinted (Android 12+,
 * via Haze), with a hairline edge. With "Reduce dynamic blur" on, or without
 * a content layer to blur, it's a solid fill in the same color.
 */
@Composable
fun Modifier.glass(shape: Shape, tint: Color = MaterialTheme.colorScheme.surfaceContainerHigh): Modifier {
    val ui by AppSettings.ui.collectAsState()
    val haze = LocalHazeState.current
    val edge = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val base = this.clip(shape)
    val filled = if (haze == null || ui.reduceBlur) {
        base.background(tint.copy(alpha = 0.96f))
    } else {
        base.hazeEffect(
            state = haze,
            style = HazeStyle(
                backgroundColor = MaterialTheme.colorScheme.background,
                tint = HazeTint(tint.copy(alpha = 0.62f)),
                blurRadius = 28.dp,
                noiseFactor = 0.06f,
            ),
        )
    }
    return filled.border(0.75.dp, edge, shape)
}

/** A round glass button holding one icon, as used for back, menu and search. */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    content: (@Composable () -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.size(size).glass(CircleShape),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (content != null) content() else Icon(icon, contentDescription, Modifier.size(size * 0.46f))
        }
    }
}

@Composable
fun GlassBackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick, modifier)
}

/** A page's top: optional glass back button and a large title. Draws under the status bar inset. */
@Composable
fun PageHeader(title: String, onBack: (() -> Unit)? = null, actions: (@Composable () -> Unit)? = null) {
    Column(
        Modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = 8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) GlassBackButton(onBack) else Box(Modifier.size(56.dp))
            Box(Modifier.weight(1f))
            actions?.invoke()
        }
        Text(
            title,
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
        )
    }
}
