package com.opentune.data.connect

import com.opentune.data.together.NostrEvent
import com.opentune.data.together.Schnorr
import com.opentune.data.together.gcmOpen
import com.opentune.data.together.gcmSeal
import com.opentune.data.together.toHex
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import javax.crypto.spec.SecretKeySpec

/** Something a device asked a watch to download: a song, or a playlist or album ([COLLECTION]) by its browse id. */
@Serializable
data class WatchRequest(
    val id: String,
    val kind: String,
    val ytId: String,
    val title: String,
    val subtitle: String? = null,
    val thumbnailUrl: String? = null,
    val at: Long,
    /** A song's length ("3:45"): lyrics are matched by it, on the watch and offline. */
    val durationText: String? = null,
) {
    companion object {
        const val SONG = "song"
        const val COLLECTION = "collection"
    }
}

/** One device's requests for one watch. Only [from] writes it. */
@Serializable
data class WantedBox(val to: String, val from: String, val requests: List<WatchRequest> = emptyList())

/** The requests a watch has queued, by id. Only the watch ([by]) writes it. */
@Serializable
data class TakenBox(val by: String, val taken: List<String> = emptyList())

@Serializable
internal data class MailEnvelope(val wanted: WantedBox? = null, val taken: TakenBox? = null)

/**
 * The watch's mailbox on the relays: what the other devices want it to download, kept there
 * until it comes online. Connect's own messages are ephemeral, so a watch that was asleep never
 * heard them; these are stored events (kind [KIND], NIP-78 application data), which a relay keeps
 * in their newest version per author and `d` tag.
 *
 * Everything comes from the group's secret: one signing key every device shares (so each can
 * replace its own box and nobody outside can tell whose boxes they are), the boxes' `d` tags,
 * and the key that seals what's in them.
 *
 * Each box has a single writer. A device writes its Wanted box for each watch; the watch writes
 * one Taken box listing the requests it has queued; the device then drops those from Wanted.
 */
class WatchMail(secret: ByteArray) {
    private val signing = ConnectGroup.hkdf(secret, "opentune/watch-mail/sign", 32)
    private val key = SecretKeySpec(ConnectGroup.hkdf(secret, "opentune/watch-mail/key", 32), "AES")
    private val salt = ConnectGroup.hkdf(secret, "opentune/watch-mail/d", 32)

    /** Who signs every box: the relays are asked for this author's events. */
    val author: String = Schnorr.publicKey(signing).toHex()

    fun wantedTag(watch: String, from: String) = dTag("wanted/$watch/$from")
    fun takenTag(watch: String) = dTag("taken/$watch")

    private fun dTag(name: String): String = ConnectGroup.hkdf(salt + name.toByteArray(), "opentune/watch-mail/tag", 16).toHex()

    /** The relay filter for every box of this group. */
    fun filter(): JsonObject = buildJsonObject {
        put("kinds", JsonArray(listOf(JsonPrimitive(KIND))))
        put("authors", JsonArray(listOf(JsonPrimitive(author))))
    }

    fun wantedEvent(box: WantedBox, createdAt: Long): NostrEvent = event(wantedTag(box.to, box.from), MailEnvelope(wanted = box), createdAt)
    fun takenEvent(box: TakenBox, createdAt: Long): NostrEvent = event(takenTag(box.by), MailEnvelope(taken = box), createdAt)

    private fun event(d: String, envelope: MailEnvelope, createdAt: Long): NostrEvent =
        NostrEvent.signed(signing, KIND, listOf(listOf("d", d)), gcmSeal(key, d, json.encodeToString(envelope)), createdAt)

    /** What a box event says, or null if it isn't one of this group's, or its tag doesn't match what's in it. */
    internal fun open(event: NostrEvent): MailEnvelope? {
        if (event.kind != KIND || event.pubkey != author) return null
        val d = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: return null
        val plain = gcmOpen(key, d, event.content) ?: return null
        val envelope = runCatching { json.decodeFromString<MailEnvelope>(plain) }.getOrNull() ?: return null
        // A box is read only where it says it belongs: no copying one device's box over another's.
        val belongs = envelope.wanted?.let { wantedTag(it.to, it.from) == d } ?: envelope.taken?.let { takenTag(it.by) == d } ?: false
        return envelope.takeIf { belongs }
    }

    companion object {
        const val KIND = 30078
        /** A request nobody picked up in this long is dropped. */
        const val KEEP_MS = 30L * 24 * 60 * 60 * 1000
        internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

/** The decisions about a mailbox, apart from the relays. */
object WatchMailRules {
    /** The requests to [watch] it hasn't queued yet, oldest first. */
    fun toTake(watch: String, wanted: Collection<WantedBox>, taken: Set<String>, now: Long): List<WatchRequest> =
        wanted.filter { it.to == watch }.flatMap { it.requests }
            .filter { it.id !in taken && fresh(it, now) }
            .distinctBy { it.id }.sortedBy { it.at }

    /**
     * What the watch keeps in its Taken box: the ids it has queued that some Wanted box still
     * lists. Once the asking device drops one, the watch can forget it too.
     */
    fun keepTaken(watch: String, wanted: Collection<WantedBox>, taken: Set<String>): Set<String> {
        val listed = wanted.filter { it.to == watch }.flatMap { it.requests }.map { it.id }.toSet()
        return taken intersect listed
    }

    /** A device's Wanted box once the watch has said what it took, and old requests are gone. */
    fun prune(box: WantedBox, taken: TakenBox?, now: Long): WantedBox {
        val done = taken?.taken?.toSet().orEmpty()
        return box.copy(requests = box.requests.filter { it.id !in done && fresh(it, now) })
    }

    private fun fresh(r: WatchRequest, now: Long) = now - r.at < WatchMail.KEEP_MS
}
