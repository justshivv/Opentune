package com.opentune.data.discord

import android.util.Base64
import com.opentune.data.DebugLog as Log
import com.opentune.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Signing in to Discord without its web page: the email (or phone) and password go
 * straight to Discord's own sign-in service, the way its site sends them, and
 * nothing of them is kept. Discord may answer with a captcha to solve first, or ask
 * for a two-factor code; each comes back as a [Step] for the screen to show, and
 * the next call carries the answer. The token at the end goes to [DiscordRpc.signIn].
 */
object DiscordLogin {
    /** What Discord wants next. */
    sealed interface Step {
        /** Signed in. */
        data class Done(val token: String) : Step

        /** A captcha first: solve it with hCaptcha's [siteKey] (and [rqdata]), then call again with the answer. */
        data class Captcha(val siteKey: String, val rqdata: String?, val rqtoken: String?, val sessionId: String?) : Step

        /** A two-factor code for [ticket]: from an authenticator app (or a backup code), or by text when [sms]. */
        data class TwoFactor(val ticket: String, val totp: Boolean, val sms: Boolean, val backup: Boolean) : Step

        /** Not signed in, and why, in Discord's words where it gave them. */
        data class Failed(val message: String) : Step
    }

    /** A captcha's answer, with what Discord sent alongside the question. */
    data class Solved(val key: String, val asked: Step.Captcha)

    private const val API = "https://discord.com/api/v9"
    private val json = "application/json".toMediaType()

    /** The first step: email or phone and password, and a captcha's answer if one was asked for. */
    suspend fun start(login: String, password: String, captcha: Solved? = null): Step = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("login", login.trim())
            .put("password", password)
            .put("undelete", false)
            .put("login_source", JSONObject.NULL)
            .put("gift_code_sku_id", JSONObject.NULL)
        call("/auth/login", body, captcha)
    }

    /** The second step: the code from an authenticator app, or a backup code. */
    suspend fun code(ticket: String, code: String, captcha: Solved? = null): Step = withContext(Dispatchers.IO) {
        val digits = code.filterNot { it.isWhitespace() || it == '-' }
        val body = JSONObject().put("code", digits).put("ticket", ticket)
            .put("login_source", JSONObject.NULL).put("gift_code_sku_id", JSONObject.NULL)
        call("/auth/mfa/totp", body, captcha)
    }

    /** Asks Discord to text a code to the account's phone; null when sent, else why not. */
    suspend fun sendSms(ticket: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            post("/auth/mfa/sms/send", JSONObject().put("ticket", ticket), null).use { r ->
                if (r.isSuccessful) null else errorMessage(r.body?.string().orEmpty()) ?: "Discord couldn't send a text (${r.code})"
            }
        }.getOrElse { it.message ?: "Couldn't reach Discord" }
    }

    /** The second step by text: the code Discord sent. */
    suspend fun smsCode(ticket: String, code: String): Step = withContext(Dispatchers.IO) {
        val body = JSONObject().put("code", code.filter { it.isDigit() }).put("ticket", ticket)
            .put("login_source", JSONObject.NULL).put("gift_code_sku_id", JSONObject.NULL)
        call("/auth/mfa/sms", body, null)
    }

    private fun call(path: String, body: JSONObject, captcha: Solved?): Step = runCatching {
        post(path, body, captcha).use { r -> parse(r.code, r.body?.string().orEmpty()) }
    }.getOrElse {
        Log.w(TAG, "Sign-in call failed", it)
        Step.Failed("Couldn't reach Discord. Check the connection and try again.")
    }

    private fun post(path: String, body: JSONObject, captcha: Solved?): okhttp3.Response {
        val request = Request.Builder().url(API + path)
            .post(body.toString().toRequestBody(json))
            .header("User-Agent", USER_AGENT)
            .header("X-Super-Properties", superProperties)
            .header("X-Discord-Locale", "en-US")
            .header("Origin", "https://discord.com")
            .header("Referer", "https://discord.com/login")
            .apply {
                fingerprint()?.let { header("X-Fingerprint", it) }
                if (captcha != null) {
                    header("X-Captcha-Key", captcha.key)
                    captcha.asked.rqtoken?.let { header("X-Captcha-Rqtoken", it) }
                    captcha.asked.sessionId?.let { header("X-Captcha-Session-Id", it) }
                }
            }
            .build()
        return Http.client.newCall(request).execute()
    }

    /** Discord's answer to a sign-in call as the next [Step]. */
    internal fun parse(status: Int, text: String): Step {
        val o = runCatching { JSONObject(text) }.getOrNull()
            ?: return Step.Failed(if (status == 429) "Discord asks to wait a moment before trying again." else "Discord said $status.")
        // A two-factor answer carries "token": null, which isn't a token.
        if (!o.isNull("token")) o.optString("token").takeIf { it.isNotBlank() }?.let { return Step.Done(it) }
        if (o.optBoolean("mfa")) {
            val ticket = if (o.isNull("ticket")) "" else o.optString("ticket")
            if (ticket.isNotBlank()) {
                return Step.TwoFactor(ticket, totp = o.optBoolean("totp", true), sms = o.optBoolean("sms"), backup = o.optBoolean("backup", true))
            }
        }
        val site = o.optString("captcha_sitekey")
        if (site.isNotBlank()) {
            return Step.Captcha(
                site,
                o.optString("captcha_rqdata").takeUnless { o.isNull("captcha_rqdata") || it.isBlank() },
                o.optString("captcha_rqtoken").takeUnless { o.isNull("captcha_rqtoken") || it.isBlank() },
                o.optString("captcha_session_id").takeUnless { o.isNull("captcha_session_id") || it.isBlank() },
            )
        }
        if (status == 429) return Step.Failed("Discord asks to wait ${o.optDouble("retry_after", 30.0).toInt()} s before trying again.")
        return Step.Failed(errorMessage(text) ?: "Discord said $status.")
    }

    /** The first message in a Discord error, e.g. "Login or password is invalid." */
    internal fun errorMessage(text: String): String? {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
        fun first(e: Any?): String? = when (e) {
            is JSONObject -> {
                e.optJSONArray("_errors")?.optJSONObject(0)?.let { err ->
                    return when (err.optString("code")) {
                        "ACCOUNT_LOGIN_VERIFICATION_EMAIL" -> "Discord emailed you to confirm this new sign-in. Confirm it, then sign in again."
                        "INVALID_LOGIN" -> "That email or password isn't right."
                        else -> err.optString("message").ifBlank { null }
                    }
                }
                e.keys().asSequence().firstNotNullOfOrNull { first(e.opt(it)) }
            }
            else -> null
        }
        return first(o.optJSONObject("errors")) ?: o.optString("message").ifBlank { null }
    }

    @Volatile private var fingerprintValue: String? = null

    /** The fingerprint Discord's site asks for before signing in, fetched once. */
    private fun fingerprint(): String? = fingerprintValue ?: runCatching {
        val request = Request.Builder().url("$API/experiments")
            .header("User-Agent", USER_AGENT)
            .header("X-Super-Properties", superProperties)
            .build()
        Http.client.newCall(request).execute().use { r ->
            JSONObject(r.body?.string().orEmpty()).optString("fingerprint").ifBlank { null }
        }
    }.getOrNull()?.also { fingerprintValue = it }

    /** Discord's site on Chrome for Android. */
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 15; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

    private val superProperties: String by lazy {
        val p = JSONObject()
            .put("os", "Android")
            .put("browser", "Android Chrome")
            .put("device", "")
            .put("system_locale", "en-US")
            .put("browser_user_agent", USER_AGENT)
            .put("browser_version", "130.0.0.0")
            .put("os_version", "15")
            .put("referrer", "")
            .put("referring_domain", "")
            .put("referrer_current", "")
            .put("referring_domain_current", "")
            .put("release_channel", "stable")
            .put("client_event_source", JSONObject.NULL)
        Base64.encodeToString(p.toString().toByteArray(), Base64.NO_WRAP)
    }

    /**
     * A page that shows hCaptcha's puzzle for [captcha], to be loaded with discord.com as
     * its address (hCaptcha checks the site), calling `OpenTune.solved(answer)` once
     * it's solved and `OpenTune.failed(reason)` if it can't be.
     */
    fun captchaPage(captcha: Step.Captcha, dark: Boolean): String {
        val site = JSONObject.quote(captcha.siteKey)
        val rq = captcha.rqdata?.let { JSONObject.quote(it) } ?: "null"
        val bg = if (dark) "#1c1b1f" else "#ffffff"
        return """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;height:100%;background:$bg;display:flex;align-items:center;justify-content:center}</style>
<script>
function ready() {
  try {
    var id = hcaptcha.render('box', {
      sitekey: $site, theme: '${if (dark) "dark" else "light"}',
      callback: function (t) { OpenTune.solved(t); },
      'error-callback': function (e) { OpenTune.failed(String(e)); },
      'expired-callback': function () { OpenTune.failed('expired'); }
    });
    var rq = $rq;
    if (rq) hcaptcha.setData(id, { rqdata: rq });
  } catch (e) { OpenTune.failed(String(e)); }
}
</script>
<script src="https://js.hcaptcha.com/1/api.js?render=explicit&onload=ready&recaptchacompat=off" async defer></script>
</head><body><div id="box"></div></body></html>
""".trimIndent()
    }

    private const val TAG = "DiscordLogin"
}
