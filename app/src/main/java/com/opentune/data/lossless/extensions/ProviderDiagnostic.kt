package com.opentune.data.lossless

import org.json.JSONObject

/** Only structured, allowlisted fields may leave the provider boundary. Never response text/URLs. */
internal data class ProviderDiagnostic(val status: Int, val code: String = "", val origin: String = "", val action: String = "") {
    fun summary() = buildString {
        append("HTTP $status")
        if (code.isNotEmpty()) append(" · $code")
        if (origin.isNotEmpty()) append(" · $origin")
        if (action.isNotEmpty()) append(" · $action")
    }
    fun guidance() = when {
        code == "REQUEST_AUTH_INVALID" -> "The gateway rejected the signed request. Check automatic date/time and reconnect this provider."
        code == "PROVIDER_AUTH_FAILED" -> "The provider's upstream account was rejected. Your browser session may still be connected; try another provider or retry later."
        action == "verify" || action == "bootstrap_session" -> "Verify this provider again in Manage connection."
        status == 403 -> "The audio request was denied. Connected confirms the browser session, not permission to download this recording."
        status == 429 -> "The provider is rate limiting requests. Wait before retrying."
        status >= 500 -> "The provider is unavailable. Retry later or choose another provider."
        else -> "Check the provider connection and try again."
    }
    companion object {
        fun from(error: LosslessHttp.HttpFailure): ProviderDiagnostic {
            val root = runCatching { JSONObject(error.responseBody) }.getOrNull()
            val code = root?.optString("code").orEmpty().uppercase().takeIf { Regex("[A-Z][A-Z0-9_]{1,63}").matches(it) }.orEmpty()
            val origin = root?.optString("origin").orEmpty().lowercase().takeIf { it in setOf("gateway", "provider") }.orEmpty()
            val action = root?.optString("action").orEmpty().lowercase().takeIf { it in setOf("verify", "bootstrap_session", "retry") }.orEmpty()
            return ProviderDiagnostic(error.code, code, origin, action)
        }
    }
}
