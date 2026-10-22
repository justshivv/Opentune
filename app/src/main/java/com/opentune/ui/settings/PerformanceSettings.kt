package com.opentune.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.InterfaceSettings
import com.opentune.ui.components.PillSegmented
import com.opentune.ui.components.RowDivider
import com.opentune.ui.components.SettingRow
import com.opentune.ui.components.ToggleRow
import kotlin.math.roundToInt

/**
 * High performance mode and the refresh rate it asks for. Turning it on also
 * asks for unrestricted battery use: Android's battery saver throttles apps it
 * restricts, which shows up as dropped frames and a stutter on the first
 * scrolls after the app comes back.
 */
@Composable
internal fun HighPerformanceRows(ui: InterfaceSettings) {
    val context = LocalContext.current
    var unrestricted by remember { mutableStateOf(isUnrestricted(context)) }
    var askBattery by remember { mutableStateOf(false) }
    // Read again on return from the system's battery screen.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) unrestricted = isUnrestricted(context) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val rates = remember { supportedRates(context) }
    val chosen = ui.refreshRateHz.takeIf { it in rates } ?: rates.lastOrNull() ?: 60
    // Already on (from before, or turned on elsewhere) but still restricted: asked once a run,
    // here, rather than only at the moment the switch is flipped.
    LaunchedEffect(Unit) {
        if (ui.highRefreshRate && !unrestricted && !askedThisRun) {
            askedThisRun = true
            askBattery = true
        }
    }

    ToggleRow(
        "High performance mode",
        ui.highRefreshRate,
        { v ->
            AppSettings.updateUi { it.copy(highRefreshRate = v) }
            if (v && !unrestricted) { askedThisRun = true; askBattery = true }
        },
        summary = if (ui.highRefreshRate) "$chosen Hz requested. Android may lower it when needed." else "Runs the screen at a faster rate while OpenTune is open, for smoother scrolling",
        icon = Icons.Rounded.Speed,
    )
    if (ui.highRefreshRate && rates.size > 1) {
        RowDivider()
        SettingRow("Refresh rate", icon = Icons.Rounded.GraphicEq, below = {
            PillSegmented(
                options = rates,
                selected = chosen,
                label = { "$it Hz" },
                onSelect = { hz -> AppSettings.updateUi { it.copy(refreshRateHz = hz) } },
            )
        })
    }
    if (ui.highRefreshRate && !unrestricted) {
        RowDivider()
        SettingRow(
            "Allow unrestricted battery use",
            summary = "Keeps Android from slowing OpenTune down to save power",
            icon = Icons.Rounded.BatteryChargingFull,
            onClick = { requestUnrestricted(context) },
        )
    }

    if (askBattery) {
        AlertDialog(
            onDismissRequest = { askBattery = false },
            icon = { Icon(Icons.Rounded.BatteryChargingFull, null) },
            title = { com.opentune.ui.components.FrostBehind(enabled = !com.opentune.data.settings.AppSettings.ui.collectAsState().value.reduceBlur); Text("Allow unrestricted battery use?") },
            text = {
                Text(
                    "Android slows down apps it saves battery on, so frames drop and the first scrolls stutter. " +
                        "Unrestricted use lets OpenTune keep the faster refresh rate. You can change this later in Android's settings.",
                    modifier = Modifier.padding(top = 4.dp),
                )
            },
            confirmButton = { TextButton(onClick = { askBattery = false; requestUnrestricted(context) }) { Text("Allow") } },
            dismissButton = { TextButton(onClick = { askBattery = false }) { Text("Not now") } },
        )
    }
}

/** Whether the battery prompt has come up this run, so opening the page again doesn't ask again. */
private var askedThisRun = false

private fun isUnrestricted(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

/** Opens Android's own prompt for this app; the general battery list where that isn't there. */
@SuppressLint("BatteryLife")
private fun requestUnrestricted(context: Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
    runCatching { context.startActivity(direct) }
        .recoverCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
}

/** The refresh rates offered: 60, 90 and 120 Hz, those the screen has at its current resolution. */
internal val OFFERED_RATES = listOf(60, 90, 120)

/** The refresh rates the screen offers at its current resolution, of [OFFERED_RATES], slowest first. */
private fun supportedRates(context: Context): List<Int> {
    @Suppress("DEPRECATION")
    val display = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) context.display else
        (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay) ?: return emptyList()
    val now = display.mode
    return display.supportedModes
        .filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight }
        .map { it.refreshRate.roundToInt() }
        .filter { it in OFFERED_RATES }
        .distinct()
        .sorted()
}
