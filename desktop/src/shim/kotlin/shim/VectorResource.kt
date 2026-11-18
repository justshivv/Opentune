// ImageVector.vectorResource as the shared files call it with an AppIcons entry, which on
// the desktop is already the icon (see AppIcons.kt in this folder).
package androidx.compose.ui.res

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

@Composable
fun ImageVector.Companion.vectorResource(icon: ImageVector): ImageVector = icon
