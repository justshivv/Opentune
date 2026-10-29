package com.opentune.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.opentune.R
import com.opentune.ui.components.FloatingDialog
import com.opentune.ui.components.SheetButton

internal enum class AppIcon(val title: String, val resource: Int) {
    // The names are the launcher aliases in the manifest: kept as they are so a choice made
    // before carries over, whatever each is called on screen now.
    Classic("Classic", R.mipmap.ic_launcher),
    Midnight("Midnight", R.mipmap.ic_launcher_midnight),
    Pearl("Pearl", R.mipmap.ic_launcher_pearl),
    Rose("Sunset", R.mipmap.ic_launcher_rose),
    Ocean("Ocean", R.mipmap.ic_launcher_ocean),
    Aurora("Aurora", R.mipmap.ic_launcher_aurora),
    Gold("Gold", R.mipmap.ic_launcher_gold),
    Neon("Neon", R.mipmap.ic_launcher_neon);

    fun component(context: Context) = ComponentName(context, "${context.packageName}.icon.$name")
    companion object {
        fun selected(context: Context): AppIcon = entries.firstOrNull {
            context.packageManager.getComponentEnabledSetting(it.component(context)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } ?: Classic
    }
}

/** Enable the new alias first. MainActivity itself is never disabled or restarted. */
internal fun selectAppIcon(context: Context, choice: AppIcon) {
    val pm = context.packageManager
    pm.setComponentEnabledSetting(choice.component(context), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
    AppIcon.entries.filter { it != choice }.forEach {
        pm.setComponentEnabledSetting(it.component(context), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
    }
}

@Composable
internal fun AppIconDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    var selected by remember { mutableStateOf(AppIcon.selected(context)) }
    var error by remember { mutableStateOf<String?>(null) }
    FloatingDialog(onDismissRequest = onDismiss, title = { Text("App icon") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Your launcher controls the icon shape. Changes may take a moment to appear.", style = MaterialTheme.typography.bodyMedium)
            // Three to a row, each as the launcher would show it, the chosen one ringed and ticked.
            AppIcon.entries.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { icon ->
                        val chosen = selected == icon
                        val bitmap = remember(icon, resources) { resources.getDrawable(icon.resource, context.theme).toBitmap(216, 216).asImageBitmap() }
                        val ring by animateColorAsState(if (chosen) MaterialTheme.colorScheme.primary else Color.Transparent, label = "iconRing")
                        val scale by animateFloatAsState(if (chosen) 1f else 0.92f, spring(dampingRatio = 0.7f, stiffness = 500f), label = "iconScale")
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).semantics { this.selected = chosen }
                                .clickable(role = Role.RadioButton) {
                                    runCatching { selectAppIcon(context, icon) }.onSuccess { selected = icon; error = null }
                                        .onFailure { error = "Couldn't change the launcher icon. Try again." }
                                }
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(Modifier.size(84.dp).border(2.5.dp, ring, RoundedCornerShape(26.dp)).padding(6.dp), contentAlignment = Alignment.Center) {
                                Image(bitmap, icon.title, Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale })
                                if (chosen) Box(
                                    Modifier.align(Alignment.BottomEnd).offset(x = 6.dp, y = 6.dp).size(24.dp).clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary),
                                    contentAlignment = Alignment.Center,
                                ) { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary) }
                            }
                            Text(
                                icon.title, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    // A short last row keeps its tiles the same width as the others.
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { SheetButton("Done", onClick = onDismiss, closes = true) })
}
