// The phone's icons as the shared dock and side rail call them. On the phone they are
// Material Symbols vector drawables in res/drawable (see app's ui/theme/AppIcons.kt),
// which the desktop has no Android resources to load, so the same Material icons stand in.
package com.opentune.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.ui.graphics.vector.ImageVector

internal object AppIcons {
    val KeyboardArrowLeft: ImageVector get() = Icons.AutoMirrored.Rounded.KeyboardArrowLeft
    val Pause: ImageVector get() = Icons.Rounded.Pause
    val PlayArrow: ImageVector get() = Icons.Rounded.PlayArrow
    val Search: ImageVector get() = Icons.Rounded.Search
    val SkipNext: ImageVector get() = Icons.Rounded.SkipNext
}
