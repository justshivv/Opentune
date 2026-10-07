package com.opentune.data.account

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.opentune.data.DebugLog as Log
import com.opentune.data.innertube.Innertube
import com.opentune.data.innertube.InnertubeParser
import com.opentune.data.innertube.StreamResolver
import com.opentune.data.model.Account
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The signed-in YouTube Music session: the cookie captured by the login
 * WebView, and the profile it belongs to.
 *
 * The cookie is kept in app-private storage and excluded from Android
 * backups (see res/xml/backup_rules.xml), since it is a live credential.
 * [Innertube] signs every request with it; no other token is minted.
 */
object AccountStore {
    private const val TAG = "AccountStore"
    private const val K_COOKIE = "cookie"
    private const val K_NAME = "profile_name"
    private const val K_EMAIL = "profile_email"
    private const val K_PHOTO = "profile_photo"
    private var prefs: SharedPreferences? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()

    fun init(context: Context) {
        val p = context.getSharedPreferences("account", Context.MODE_PRIVATE)
        prefs = p
        p.getString(K_COOKIE, null)?.takeIf { hasSession(it) }?.let { cookie ->
            Innertube.cookie = cookie
            _signedIn.value = true
            // The profile from last time shows at once; the fresh one replaces it.
            p.getString(K_NAME, null)?.let { name ->
                _account.value = Account(name, p.getString(K_EMAIL, null).orEmpty(), p.getString(K_PHOTO, null))
            }
            refreshProfile()
        }
    }

    /** A Google cookie only signs requests if it carries one of the SAPISID cookies. */
    fun hasSession(cookie: String): Boolean =
        cookie.split(';').any { it.trim().substringBefore('=') in SESSION_COOKIES }

    fun signIn(cookie: String) {
        // A new sign-in may be a different account: last profile goes until the new one loads.
        fresh = false
        _account.value = null
        prefs?.edit { putString(K_COOKIE, cookie); remove(K_NAME); remove(K_EMAIL); remove(K_PHOTO) }
        Innertube.cookie = cookie
        // Verdicts and client stand-downs made while signed out don't hold now.
        StreamResolver.onSessionChanged()
        _signedIn.value = true
        refreshProfile()
    }

    fun signOut() {
        loading?.cancel()
        fresh = false
        prefs?.edit { remove(K_COOKIE); remove(K_NAME); remove(K_EMAIL); remove(K_PHOTO) }
        Innertube.cookie = null
        StreamResolver.onSessionChanged()
        _signedIn.value = false
        _account.value = null
    }

    private var loading: Job? = null
    /** Whether this run of the app has read the profile from YouTube Music, not only from last time. */
    @Volatile private var fresh = false

    /**
     * Reads the profile, trying again a few times if the network isn't
     * there yet. The process can start in the background (the widget
     * refreshes after an update, say) before the phone lets it go online,
     * and that process then lives on into the next time the app is opened.
     */
    fun refreshProfile() {
        loading?.cancel()
        loading = scope.launch {
            for ((attempt, wait) in RETRY_MS.withIndex()) {
                delay(wait)
                val result = runCatching {
                    Innertube.ensureSessionScope()
                    InnertubeParser.parseAccount(Innertube.accountMenu())
                }
                if (!_signedIn.value) return@launch
                // No profile in the answer is treated like no answer: the one from last time stays.
                val a = result.getOrNull()
                if (a != null) {
                    _account.value = a
                    fresh = true
                    prefs?.edit { putString(K_NAME, a.name); putString(K_EMAIL, a.email); putString(K_PHOTO, a.thumbnailUrl) }
                    return@launch
                }
                val why = "Couldn't read the account profile (try ${attempt + 1})"
                result.exceptionOrNull()?.let { Log.w(TAG, why, it) } ?: Log.w(TAG, "$why: no profile in the answer")
            }
        }
    }

    /** Called when the app comes to the front: a signed-in session whose profile didn't load this run tries again. */
    fun ensureProfile() {
        if (_signedIn.value && !fresh && loading?.isActive != true) refreshProfile()
    }

    private val SESSION_COOKIES = setOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID")

    /** Waits before each try at the profile: now, then after 3 s, 10 s and 30 s. */
    private val RETRY_MS = listOf(0L, 3_000L, 10_000L, 30_000L)
}
