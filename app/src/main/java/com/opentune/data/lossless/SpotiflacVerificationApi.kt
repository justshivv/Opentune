package com.opentune.data.lossless

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONTokener

/** The provider owns the challenge widget and its server-issued Turnstile binding. */
internal object SpotiflacVerificationApi {
    suspend fun bootstrap(installId: String): JSONObject {
        val url = "${LosslessRegistry.SESSION_BASE}/bootstrap".toHttpUrl().newBuilder()
            .addQueryParameter("install_id", installId)
            .addQueryParameter("app_version", SpotiflacGated.APP_VERSION).build()
        return JSONObject(LosslessHttp.text(url.toString()))
    }

    fun challengeUrl(data: JSONObject): String? {
        val id = data.optString("challenge_id")
        if (!Regex("[A-Za-z0-9_-]{1,256}").matches(id)) return null
        // No callback scheme: the provider's popup flow exposes zarzGrant in
        // this page. Read only that top-level variable; no native JS bridge.
        return "${LosslessRegistry.SESSION_BASE}/challenge".toHttpUrl().newBuilder()
            .addQueryParameter("id", id).build().toString()
    }

    fun isChallengePage(actual: String?, expected: String): Boolean {
        val url = actual?.toHttpUrlOrNull() ?: return false
        val pinned = expected.toHttpUrlOrNull() ?: return false
        return url.newBuilder().fragment(null).build() == pinned
    }

    fun grantFromJavascript(result: String?): String? = runCatching {
        result ?: return null
        if (result.length > 16_384 || !result.startsWith('"') || !result.endsWith('"')) return null
        (JSONTokener(result).nextValue() as? String)?.takeIf {
            it.isNotBlank() && it.length <= 8_192 && it.none(Char::isISOControl)
        }
    }.getOrNull()

    suspend fun exchange(installId: String, grant: String): String {
        val body = JSONObject().put("grant", grant).put("install_id", installId)
            .put("app_version", SpotiflacGated.APP_VERSION).put("platform", "android")
        val request = Request.Builder().url("${LosslessRegistry.SESSION_BASE}/session/exchange")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json").build()
        return LosslessHttp.bytes(request, 1_048_576).toString(Charsets.UTF_8)
    }
}
