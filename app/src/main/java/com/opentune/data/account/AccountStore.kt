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
import kotlinx.coroutines.SupervisorJob
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
            refreshProfile()
        }
    }

    /** A Google cookie only signs requests if it carries one of the SAPISID cookies. */
    fun hasSession(cookie: String): Boolean =
        cookie.split(';').any { it.trim().substringBefore('=') in SESSION_COOKIES }

    fun signIn(cookie: String) {
        prefs?.edit { putString(K_COOKIE, cookie) }
        Innertube.cookie = cookie
        // Verdicts and client stand-downs made while signed out don't hold now.
        StreamResolver.onSessionChanged()
        _signedIn.value = true
        refreshProfile()
    }

    fun signOut() {
        prefs?.edit { remove(K_COOKIE) }
        Innertube.cookie = null
        StreamResolver.onSessionChanged()
        _signedIn.value = false
        _account.value = null
    }

    fun refreshProfile() {
        scope.launch {
            runCatching {
                Innertube.ensureSessionScope()
                InnertubeParser.parseAccount(Innertube.accountMenu())
            }.onSuccess { _account.value = it }
                .onFailure { Log.w(TAG, "Couldn't read the account profile", it) }
        }
    }

    private val SESSION_COOKIES = setOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID")
}
