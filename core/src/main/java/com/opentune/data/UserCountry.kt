package com.opentune.data

import android.content.Context
import android.telephony.TelephonyManager
import com.opentune.data.radio.ApproxLocation
import java.util.Locale
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Which country the listener is in, for suggestions that start from its
 * charts: where the phone is, when approximate location is allowed; else its
 * mobile network or SIM; else the region of its language. The language comes
 * last because many phones run in English (US) far from the US.
 */
object UserCountry {
    /** How long the position may take before the network's country is used instead. */
    private const val LOCATION_WAIT_MS = 6_000L

    /** Two-letter country code, upper case, or null when nothing names one. */
    suspend fun detect(context: Context): String? {
        val located = if (ApproxLocation.granted(context) && ApproxLocation.enabled(context)) {
            withTimeoutOrNull(LOCATION_WAIT_MS) {
                runCatching {
                    ApproxLocation.get(context)?.let { ApproxLocation.place(context, it.latitude, it.longitude)?.countryCode }
                }.getOrNull()
            }
        } else null
        return pick(located, network(context), Locale.getDefault().country)
    }

    /** The first of these that is a country code. */
    internal fun pick(vararg candidates: String?): String? =
        candidates.firstNotNullOfOrNull { it?.trim()?.uppercase(Locale.ROOT)?.takeIf { c -> c.length == 2 && c.all(Char::isLetter) } }

    private fun network(context: Context): String? = runCatching {
        val phone = context.getSystemService(TelephonyManager::class.java) ?: return null
        phone.networkCountryIso?.takeIf { it.length == 2 } ?: phone.simCountryIso?.takeIf { it.length == 2 }
    }.getOrNull()
}
