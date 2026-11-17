package com.opentune.data.connect

import com.opentune.data.together.gcmOpen
import com.opentune.data.together.gcmSeal
import com.opentune.data.together.toHex
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A person's own devices. One secret, held by each of them, names the group
 * on the relays and encrypts what it says there. Relays see only [tag] and
 * ciphertext; whoever has the secret is one of the devices.
 */
class ConnectGroup(val secret: ByteArray) {
    init { require(secret.size == SIZE) }

    /** What relays file the group's messages under; reveals nothing about the secret. */
    val tag: String by lazy { hkdf(secret, "opentune/connect/tag", 16).toHex() }
    private val key by lazy { SecretKeySpec(hkdf(secret, "opentune/connect/key", 32), "AES") }

    /** Encrypts [plain] as base64(iv || ciphertext), bound to this group's tag. */
    fun seal(plain: String): String = gcmSeal(key, tag, plain)

    /** The plain text of a [seal]ed message, or null if it isn't this group's. */
    fun open(sealed: String): String? = gcmOpen(key, tag, sealed)

    val encoded: String get() = Base64.getUrlEncoder().withoutPadding().encodeToString(secret)

    /** Links that add a device to the group. The secret is in the fragment, which never reaches a server. */
    val appLink: String get() = "opentune://connect/#$encoded"
    val webLink: String get() = "$WEB_PLAYER#/settings/devices?join=$encoded"

    override fun equals(other: Any?) = other is ConnectGroup && other.secret.contentEquals(secret)
    override fun hashCode() = secret.contentHashCode()

    companion object {
        const val SIZE = 32
        const val WEB_PLAYER = "https://justshivv.github.io/Opentune/player/"
        private val random = SecureRandom()
        private val ENCODED = Regex("[A-Za-z0-9_-]{43}")
        private val WEB_SECRET = Regex("[?&#](?:join|connect)=([A-Za-z0-9_-]{43})")

        fun generate() = ConnectGroup(ByteArray(SIZE).also(random::nextBytes))

        /** A group from a link or the bare secret; null for anything else. */
        fun parse(text: String?): ConnectGroup? {
            val value = text?.trim() ?: return null
            val raw = when {
                value.startsWith("opentune://connect/#") -> value.removePrefix("opentune://connect/#")
                value.startsWith(WEB_PLAYER) -> WEB_SECRET.find(value)?.groupValues?.get(1).orEmpty()
                "://" in value -> return null
                else -> value
            }
            if (!raw.matches(ENCODED)) return null
            return runCatching { ConnectGroup(Base64.getUrlDecoder().decode(raw)) }.getOrNull()
        }

        /** HKDF-SHA256 (RFC 5869) with an empty salt. */
        fun hkdf(ikm: ByteArray, info: String, length: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
            val prk = mac.doFinal(ikm)
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            val out = ByteArrayOutputStream()
            var block = ByteArray(0)
            var i = 1
            while (out.size() < length) {
                mac.update(block)
                mac.update(info.toByteArray())
                mac.update(i.toByte())
                block = mac.doFinal()
                out.write(block)
                i++
            }
            return out.toByteArray().copyOf(length)
        }
    }
}
