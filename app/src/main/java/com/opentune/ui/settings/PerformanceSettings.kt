package com.opentune.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import com.opentune.ui.theme.Icon
import com.opentune.ui.theme.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.opentune.ui.theme.AppIcons

/**
 * High performance mode and the refresh rate it asks for, 60 Hz unless a faster one is picked.
 * Picking a faster one also asks for unrestricted battery use: Android's battery saver
 * throttles apps it restricts, which shows up as dropped frames and a stutter on the first
 * scrolls after the app comes back. At 60 Hz there's nothing to keep up, so it isn't asked.
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
    // 60 Hz unless a faster rate was picked (0, from older versions, is 60 too).
    val chosen = ui.refreshRateHz.takeIf { it in rates } ?: rates.firstOrNull { it >= 60 } ?: rates.firstOrNull() ?: 60
    val fast = ui.highRefreshRate && chosen > 60
    // A faster rate already chosen (from before, or elsewhere) but still restricted: asked once
    // a run, here, rather than only at the moment it's picked.
    LaunchedEffect(Unit) {
        if (fast && !unrestricted && !askedThisRun) {
            askedThisRun = true
            askBattery = true
        }
    }

    ToggleRow(
        "High performance mode",
        ui.highRefreshRate,
        { v -> AppSettings.updateUi { it.copy(highRefreshRate = v) } },
        summary = if (ui.highRefreshRate) com.opentune.i18n.Tr.f("{0} Hz requested. Android may lower it when needed.", chosen) else "Runs the screen at a faster rate while OpenTune is open, for smoother scrolling",
        icon = ImageVector.vectorResource(AppIcons.Speed),
    )
    if (ui.highRefreshRate && rates.size > 1) {
        RowDivider()
        SettingRow("Refresh rate", icon = ImageVector.vectorResource(AppIcons.GraphicEq), below = {
            PillSegmented(
                options = rates,
                selected = chosen,
                label = { com.opentune.i18n.Tr.f("{0} Hz", it) },
                onSelect = { hz ->
                    AppSettings.updateUi { it.copy(refreshRateHz = hz) }
                    if (hz > 60 && !unrestricted) { askedThisRun = true; askBattery = true }
                },
            )
        })
    }
    if (fast && !unrestricted) {
        RowDivider()
        SettingRow(
            "Allow unrestricted battery use",
            summary = "Keeps Android from slowing OpenTune down to save power",
            icon = ImageVector.vectorResource(AppIcons.BatteryChargingFull),
            onClick = { requestUnrestricted(context) },
        )
    }

    if (askBattery) {
        AlertDialog(
            onDismissRequest = { askBattery = false },
            icon = { Icon(ImageVector.vectorResource(AppIcons.BatteryChargingFull), null) },
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
