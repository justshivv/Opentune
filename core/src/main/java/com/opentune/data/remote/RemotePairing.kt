package com.opentune.data.remote

import android.content.Context
import com.opentune.data.account.Lan
import com.opentune.data.account.PairingCrypto
import java.nio.charset.StandardCharsets.UTF_8
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Somewhere to keep a small document: the app's private preferences, or memory in a test. */
interface RemoteStorage {
    fun read(): String?
    fun write(text: String)
}

class PrefsStorage(context: Context, name: String) : RemoteStorage {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    override fun read(): String? = prefs.getString("doc", null)
    override fun write(text: String) { prefs.edit().putString("doc", text).apply() }
}

class MemoryStorage(private var text: String? = null) : RemoteStorage {
    override fun read(): String? = text
    override fun write(text: String) { this.text = text }
}

private val store = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** A phone this TV has been paired with: [id] says which, [key] (base64) is what its messages are sealed with. */
@Serializable
data class PairedPhone(val id: String, val name: String, val key: String, val addedAt: Long)

@Serializable
private data class TvDocument(val tvId: String, val phones: List<PairedPhone> = emptyList())

/** On the TV: this TV's own id, and the phones allowed to control it. */
class PairedPhones(private val storage: RemoteStorage) {
    private var doc: TvDocument = storage.read()
        ?.let { runCatching { store.decodeFromString(TvDocument.serializer(), it) }.getOrNull() }
        ?: TvDocument(tvId = SecureChannel.hex(ByteArray(8).also(SecureChannel.random::nextBytes))).also(::save)
    private val _phones = MutableStateFlow(doc.phones)
    val phones: StateFlow<List<PairedPhone>> = _phones.asStateFlow()

    /** Stays the same for this TV for good, so a phone knows it again on any network. */
    val tvId: String get() = doc.tvId

    @Synchronized fun find(id: String): PairedPhone? = doc.phones.firstOrNull { it.id == id }

    /** A new phone with a key of its own. */
    @Synchronized fun add(name: String, now: Long = System.currentTimeMillis()): PairedPhone {
        val phone = PairedPhone(
            id = SecureChannel.hex(ByteArray(RemoteHandshake.ID_BYTES).also(SecureChannel.random::nextBytes)),
            name = name.take(MAX_NAME).ifBlank { "Phone" },
            key = PairingCrypto.encode(PairingCrypto.secret()),
            addedAt = now,
        )
        update(doc.copy(phones = doc.phones + phone))
        return phone
    }

    /** Takes a phone's access away; the phone's key stops opening anything. */
    @Synchronized fun remove(id: String) = update(doc.copy(phones = doc.phones.filterNot { it.id == id }))

    private fun update(next: TvDocument) { doc = next; save(next); _phones.value = next.phones }
    private fun save(d: TvDocument) = storage.write(store.encodeToString(TvDocument.serializer(), d))

    private companion object { const val MAX_NAME = 40 }
}

/** A TV a phone has been paired with. [host] and [port] are where it was last seen, for networks that block discovery. */
@Serializable
data class PairedTv(val tvId: String, val name: String, val id: String, val key: String, val host: String? = null, val port: Int? = null)

/** On the phone: the TVs it can control. */
class PairedTvs(private val storage: RemoteStorage) {
    private var items: List<PairedTv> = storage.read()
        ?.let { runCatching { store.decodeFromString(ListSerializer(PairedTv.serializer()), it) }.getOrNull() }
        ?: emptyList()
    private val _tvs = MutableStateFlow(items)
    val tvs: StateFlow<List<PairedTv>> = _tvs.asStateFlow()

    /** Added, or replaced when this TV was paired before. */
    @Synchronized fun add(tv: PairedTv) = update(items.filterNot { it.tvId == tv.tvId } + tv)
    @Synchronized fun remove(tvId: String) = update(items.filterNot { it.tvId == tvId })
    @Synchronized fun remember(tvId: String, host: String, port: Int) =
        update(items.map { if (it.tvId == tvId) it.copy(host = host, port = port) else it })

    private fun update(next: List<PairedTv>) {
        items = next
        storage.write(store.encodeToString(ListSerializer(PairedTv.serializer()), next))
        _tvs.value = next
    }
}

/**
 * What a TV shows as a QR code to pair a phone: where it is, who it is, and a one-time token
 * that the phone proves it has seen the screen with. The token opens one pairing and lasts
 * two minutes; after that the phone has a key of its own. Nothing here is a lasting secret.
 *
 * The two minutes are timed on each side by its own steady clock, from when the TV made the
 * code and from when the phone read it; the TV's clock is never compared with the phone's,
 * since a TV's is often minutes out. The TV, which holds the token, is what stops taking it.
 */
class RemoteInvite internal constructor(
    val host: String,
    val port: Int,
    val tvId: String,
    val tvName: String,
    internal val id: ByteArray,
    internal val token: ByteArray,
    /** The TV's wall clock when it runs out: written for older phones, not read for timing. */
    val expires: Long,
    /** [Lan.steadyMs] on this side when it stops being good. */
    private val deadline: Long,
) {
    /** Whether the code is still good, by this side's own clock. */
    fun valid(steady: Long = Lan.steadyMs()): Boolean = steady < deadline

    /** The text of the QR code. */
    val link: String
        get() {
            val name = PairingCrypto.encode(tvName.toByteArray(UTF_8))
            val body = "1|$host|$port|$tvId|$name|${SecureChannel.hex(id)}|${PairingCrypto.encode(token)}|$expires"
            return "opentune://remote/#" + PairingCrypto.encode(body.toByteArray(UTF_8))
        }

    /** What a scanned code turned out to be, for the phone to say. */
    sealed interface Read {
        data class Invite(val invite: RemoteInvite) : Read
        /** Not a pairing code for the remote at all (it may be another NepTune code: see [ScannedCode]). */
        data object NotAnInvite : Read
        /** It starts like one, but the rest is cut off or garbled. */
        data object Damaged : Read
        /** A real code, but its address is not on the home network. */
        data class OffNetwork(val host: String) : Read
    }

    companion object {
        const val LIFETIME_MS = 120_000L
        private const val PREFIX = "opentune://remote/#"

        fun create(
            host: String,
            port: Int,
            tvId: String,
            tvName: String,
            now: Long = System.currentTimeMillis(),
            steady: Long = Lan.steadyMs(),
        ): RemoteInvite {
            require(HomeNetwork.accepts(host) && port in 1024..65535)
            return RemoteInvite(
                host, port, tvId, tvName,
                ByteArray(RemoteHandshake.ID_BYTES).also(SecureChannel.random::nextBytes),
                PairingCrypto.secret(), now + LIFETIME_MS, steady + LIFETIME_MS,
            )
        }

        /** What [text] is; an invite gets two minutes from [steady], when it was read. */
        fun read(text: String, steady: Long = Lan.steadyMs()): Read {
            val payload = text.trim().substringAfter(PREFIX, "").ifEmpty { return Read.NotAnInvite }
            return runCatching {
                require(payload.length in 40..1024)
                val f = String(PairingCrypto.decode(payload), UTF_8).split('|')
                require(f.size == 8 && f[0] == "1")
                val port = f[2].toInt()
                require(port in 1024..65535)
                val id = SecureChannel.unhex(f[5]); require(id.size == RemoteHandshake.ID_BYTES)
                val token = PairingCrypto.decode(f[6]); require(token.size == 32)
                val expires = f[7].toLong(); require(expires > 0)
                if (!HomeNetwork.accepts(f[1])) return Read.OffNetwork(f[1])
                Read.Invite(RemoteInvite(f[1], port, f[3], String(PairingCrypto.decode(f[4]), UTF_8).take(60), id, token, expires, steady + LIFETIME_MS))
            }.getOrDefault(Read.Damaged)
        }

        /** The invite in a scanned [text], or null when it isn't one or points anywhere but the home network. */
        fun parse(text: String, steady: Long = Lan.steadyMs()): RemoteInvite? = (read(text, steady) as? Read.Invite)?.invite
    }
}

/**
 * Which kind of NepTune code a scan read, so either of the phone's scanners can hand it to the
 * right place: a TV shows one code to sign it in and another to pair the phone as a remote.
 */
enum class ScannedCode {
    /** A device's sign-in code: opentune://pair/… (or the website's pair page). */
    SIGN_IN,
    /** The web player's code for this phone's other sign-ins: opentune://pair/web… */
    WEB_SIGN_IN,
    /** A TV's code for pairing this phone as its remote: opentune://remote/… */
    REMOTE,
    /** Some other NepTune link. */
    OTHER_NEPTUNE,
    /** Not a NepTune code. */
    NOT_NEPTUNE;

    companion object {
        fun of(text: String): ScannedCode {
            val t = text.trim()
            return when {
                t.startsWith("opentune://pair/web", ignoreCase = true) -> WEB_SIGN_IN
                t.startsWith("opentune://pair", ignoreCase = true) -> SIGN_IN
                t.startsWith("https://justshivv.github.io/Opentune/pair/", ignoreCase = true) -> SIGN_IN
                t.startsWith("opentune://remote", ignoreCase = true) -> REMOTE
                t.startsWith("opentune://", ignoreCase = true) -> OTHER_NEPTUNE
                else -> NOT_NEPTUNE
            }
        }
    }
}
