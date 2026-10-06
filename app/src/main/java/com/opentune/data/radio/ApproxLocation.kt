package com.opentune.data.radio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The phone's rough position, for finding radio stations nearby. Only
 * approximate location is asked for; the position is used for one search
 * and isn't kept.
 */
object ApproxLocation {
    private const val FRESH_MS = 30 * 60 * 1000L
    private const val WAIT_MS = 10_000L

    fun granted(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Whether location is switched on in the phone's settings. */
    fun enabled(context: Context): Boolean {
        val lm = context.getSystemService(LocationManager::class.java) ?: return false
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    /** A recent known position, or a fresh one if none is recent; null if none can be had. */
    @SuppressLint("MissingPermission")
    suspend fun get(context: Context): Location? {
        if (!granted(context)) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = runCatching { lm.getProviders(true) }.getOrDefault(emptyList())
        val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < FRESH_MS) return last
        val provider = listOf(LocationManager.NETWORK_PROVIDER, FUSED, LocationManager.GPS_PROVIDER).firstOrNull { it in providers }
            ?: return last
        return withTimeoutOrNull(WAIT_MS) { current(context, lm, provider) } ?: last
    }

    @SuppressLint("MissingPermission")
    private suspend fun current(context: Context, lm: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            runCatching {
                LocationManagerCompat.getCurrentLocation(lm, provider, signal, ContextCompat.getMainExecutor(context)) { cont.resume(it) }
            }.onFailure { if (cont.isActive) cont.resume(null) }
        }

    private const val FUSED = "fused"
}
