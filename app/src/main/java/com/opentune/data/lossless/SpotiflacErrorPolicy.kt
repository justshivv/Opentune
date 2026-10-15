package com.opentune.data.lossless

import org.json.JSONObject

/** Error contract used by SpotiFLAC-Mobile's signed-session client. HTTP status alone is insufficient. */
internal object SpotiflacErrorPolicy {
    private fun data(error: LosslessHttp.HttpFailure) = runCatching { JSONObject(error.responseBody) }.getOrNull()
    fun requiresVerification(error: LosslessHttp.HttpFailure): Boolean {
        val json = data(error) ?: return false
        if (!json.optString("origin").trim().equals("gateway", true)) return false
        return (error.code == 401 && json.optString("code").trim().equals("SESSION_INVALID", true) &&
            json.optString("action").trim().equals("bootstrap_session", true)) ||
            (error.code == 428 && json.optString("code").trim().equals("VERIFY_REQUIRED", true) &&
                json.optString("action").trim().equals("verify", true))
    }

    /** At most one background retry, and never ignore a long Retry-After by retrying sooner. */
    fun retryDelayMs(error: LosslessHttp.HttpFailure, attempt: Int): Long? {
        if (attempt != 0 || error.code != 503) return null
        val json = data(error) ?: return null
        if (!json.optString("origin").trim().equals("provider", true) ||
            !json.optString("code").trim().equals("PROVIDER_UNAVAILABLE", true) ||
            json.opt("retryable") != true || !json.optString("retry_mode").trim().equals("same_operation", true)) return null
        val seconds = maxOf(error.retryAfterSeconds ?: 0, json.optLong("retry_after_seconds", 0), 1)
        return seconds.takeIf { it <= 2 }?.times(1000)
    }
}
