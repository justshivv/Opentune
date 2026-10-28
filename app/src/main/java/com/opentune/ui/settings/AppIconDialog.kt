package com.opentune.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
    Classic("Classic", R.mipmap.ic_launcher),
    Midnight("Midnight glass", R.mipmap.ic_launcher_midnight),
    Pearl("Pearl glass", R.mipmap.ic_launcher_pearl),
    Rose("Rose glass", R.mipmap.ic_launcher_rose),
    Ocean("Ocean glass", R.mipmap.ic_launcher_ocean);

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
    var selected by remember { mutableStateOf(AppIcon.selected(context)) }
    var error by remember { mutableStateOf<String?>(null) }
    FloatingDialog(onDismissRequest = onDismiss, title = { Text("App icon") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Your launcher controls the icon shape. Changes may take a moment to appear.", style = MaterialTheme.typography.bodyMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(AppIcon.entries) { icon ->
                    val bitmap = remember(icon) { context.getDrawable(icon.resource)!!.toBitmap(192, 192).asImageBitmap() }
                    Column(Modifier.width(104.dp).semantics { this.selected = selected == icon }.clickable(role = Role.RadioButton) {
                        runCatching { selectAppIcon(context, icon) }.onSuccess { selected = icon; error = null }
                            .onFailure { error = "Couldn't change the launcher icon. Try again." }
                    }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(bitmap, icon.title, Modifier.size(80.dp))
                        Text(icon.title, style = MaterialTheme.typography.labelMedium, color = if (selected == icon) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { SheetButton("Done", onClick = onDismiss, closes = true) })
}
