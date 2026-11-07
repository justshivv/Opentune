package com.opentune.data.account

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.opentune.data.DebugLog as Log
import com.opentune.data.innertube.Innertube
import com.opentune.data.innertube.InnertubeParser
import com.opentune.data.innertube.StreamResolver
import com.opentune.data.model.Account
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One saved account as the switcher shows it. [active] marks the one playing now. */
data class SavedAccount(val id: String, val name: String, val email: String, val thumbnailUrl: String?, val active: Boolean)

/**
 * The signed-in YouTube Music sessions: the login cookie for each, and the
 * profile it belongs to. Several can be saved at once and switched between;
 * one is active and signs every request.
 *
 * Cookies are kept in app-private storage and excluded from Android backups
 * (see res/xml/backup_rules.xml), since they are live credentials. [Innertube]
 * signs requests with the active one; no other token is minted.
 */
object AccountStore {
    private const val TAG = "AccountStore"
    private const val K_LIST = "accounts_v2"
    private const val K_ACTIVE = "active_id"
    // Legacy single-account keys, migrated into the list on first run.
    private const val K_COOKIE = "cookie"
    private const val K_NAME = "profile_name"
    private const val K_EMAIL = "profile_email"
    private const val K_PHOTO = "profile_photo"
    private var prefs: SharedPreferences? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class Stored(val id: String, val cookie: String, val name: String = "", val email: String = "", val photo: String? = null)

    private val saved = mutableListOf<Stored>()
    private var activeId: String? = null

    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()

    private val _accounts = MutableStateFlow<List<SavedAccount>>(emptyList())
    /** Every saved account, newest sign-in last, with the active one flagged. */
    val accounts: StateFlow<List<SavedAccount>> = _accounts.asStateFlow()

    fun init(context: Context) {
        val p = context.getSharedPreferences("account", Context.MODE_PRIVATE)
        prefs = p
        saved.clear()
        saved.addAll(load(p))
        if (saved.isEmpty()) migrateLegacy(p)
        activeId = p.getString(K_ACTIVE, null)?.takeIf { id -> saved.any { it.id == id } } ?: saved.firstOrNull()?.id
        applyActive(announce = false)
        publish()
        if (_signedIn.value) refreshProfile()
    }

    private fun load(p: SharedPreferences): List<Stored> =
        p.getString(K_LIST, null)?.let { runCatching { json.decodeFromString<List<Stored>>(it) }.getOrNull() }
            ?.filter { hasSession(it.cookie) }.orEmpty()

    private fun migrateLegacy(p: SharedPreferences) {
        val cookie = p.getString(K_COOKIE, null)?.takeIf { hasSession(it) } ?: return
        saved.add(Stored(UUID.randomUUID().toString(), cookie, p.getString(K_NAME, null).orEmpty(), p.getString(K_EMAIL, null).orEmpty(), p.getString(K_PHOTO, null)))
        p.edit { remove(K_COOKIE); remove(K_NAME); remove(K_EMAIL); remove(K_PHOTO) }
        persist()
    }

    /** A Google cookie only signs requests if it carries one of the SAPISID cookies. */
    fun hasSession(cookie: String): Boolean =
        cookie.split(';').any { it.trim().substringBefore('=') in SESSION_COOKIES }

    /** Signs in a freshly captured cookie as a new active account. */
    fun signIn(cookie: String) {
        val id = UUID.randomUUID().toString()
        saved.add(Stored(id, cookie))
        activeId = id
        fresh = false
        Innertube.cookie = cookie
        // Verdicts and client stand-downs made under another session don't hold now.
        StreamResolver.onSessionChanged()
        _signedIn.value = true
        _account.value = null
        persist()
        publish()
        refreshProfile()
    }

    /** Makes a saved account the active one. */
    fun switchTo(id: String) {
        if (id == activeId || saved.none { it.id == id }) return
        loading?.cancel()
        activeId = id
        fresh = false
        applyActive(announce = true)
        persist()
        publish()
        if (_signedIn.value) refreshProfile()
    }

    /** Signs out the active account; another saved account takes over if there is one. */
    fun signOut() {
        loading?.cancel()
        fresh = false
        saved.removeAll { it.id == activeId }
        activeId = saved.firstOrNull()?.id
        applyActive(announce = true)
        persist()
        publish()
        if (_signedIn.value) refreshProfile()
    }

    /** Points [Innertube] at the active account's cookie and shows its last-known profile. */
    private fun applyActive(announce: Boolean) {
        val a = saved.firstOrNull { it.id == activeId && hasSession(it.cookie) }
        if (a == null) {
            activeId = null
            Innertube.cookie = null
            if (announce) StreamResolver.onSessionChanged()
            _signedIn.value = false
            _account.value = null
            return
        }
        Innertube.cookie = a.cookie
        if (announce) StreamResolver.onSessionChanged()
        _signedIn.value = true
        _account.value = if (a.name.isNotBlank()) Account(a.name, a.email, a.photo) else null
    }

    private fun publish() {
        _accounts.value = saved.map { SavedAccount(it.id, it.name, it.email, it.photo, it.id == activeId) }
    }

    private fun persist() {
        prefs?.edit {
            putString(K_LIST, json.encodeToString(saved.toList()))
            if (activeId != null) putString(K_ACTIVE, activeId) else remove(K_ACTIVE)
        }
    }

    private var loading: Job? = null
    /** Whether this run of the app has read the profile from YouTube Music, not only from last time. */
    @Volatile private var fresh = false

    /**
     * Reads the active account's profile, trying again a few times if the
     * network isn't there yet. The process can start in the background (the
     * widget refreshes after an update, say) before the phone lets it go
     * online, and that process then lives on into the next time the app opens.
     */
    fun refreshProfile() {
        loading?.cancel()
        val target = activeId
        loading = scope.launch {
            for ((attempt, wait) in RETRY_MS.withIndex()) {
                delay(wait)
                val result = runCatching {
                    Innertube.ensureSessionScope()
                    InnertubeParser.parseAccount(Innertube.accountMenu())
                }
                if (!_signedIn.value || activeId != target) return@launch
                // No profile in the answer is treated like no answer: the one from last time stays.
                val a = result.getOrNull()
                if (a != null) {
                    _account.value = a
                    fresh = true
                    record(target, a)
                    return@launch
                }
                val why = com.opentune.i18n.Tr.f("Couldn't read the account profile (try {0})", attempt + 1)
                result.exceptionOrNull()?.let { Log.w(TAG, why, it) } ?: Log.w(TAG, "$why: no profile in the answer")
            }
        }
    }

    /** Stores the loaded profile on its account and drops any duplicate of the same email. */
    private fun record(id: String?, a: Account) {
        var changed = false
        for (i in saved.indices) if (saved[i].id == id) {
            saved[i] = saved[i].copy(name = a.name, email = a.email, photo = a.thumbnailUrl)
            changed = true
        }
        if (a.email.isNotBlank() && saved.removeAll { it.id != id && it.email == a.email }) changed = true
        if (changed) { persist(); publish() }
    }

    /** Called when the app comes to the front: a signed-in session whose profile didn't load this run tries again. */
    fun ensureProfile() {
        if (_signedIn.value && !fresh && loading?.isActive != true) refreshProfile()
    }

    private val SESSION_COOKIES = setOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID")

    /** Waits before each try at the profile: now, then after 3 s, 10 s and 30 s. */
    private val RETRY_MS = listOf(0L, 3_000L, 10_000L, 30_000L)
}
