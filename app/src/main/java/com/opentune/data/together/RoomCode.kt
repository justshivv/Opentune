package com.opentune.data.together

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * A room is named by a 12-character code, like K7QX-M2PA-9DTE. Everything
 * else comes from it: the tag relays file the room's messages under, and
 * the key they're encrypted with. Relays only ever see the tag and
 * ciphertext; whoever has the code can read and write.
 */
class RoomCode private constructor(val raw: String) {
    /** "K7QX-M2PA-9DTE" */
    val pretty: String get() = raw.chunked(4).joinToString("-")

    /** What relays index the room's events by; reveals nothing about the code. */
    val tag: String by lazy { sha256("opentune/together/tag/$raw").toHex().take(32) }

    /** AES-256 key for the room's messages, stretched so the code can't be guessed quickly from the tag. */
    private val key: SecretKeySpec by lazy {
        val spec = PBEKeySpec(raw.toCharArray(), "opentune/together/key".toByteArray(), ITERATIONS, 256)
        SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
    }

    /** Encrypts [plain] as base64(iv || ciphertext), bound to this room's tag. */
    fun seal(plain: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        c.updateAAD(tag.toByteArray())
        return java.util.Base64.getEncoder().encodeToString(iv + c.doFinal(plain.toByteArray()))
    }

    /** The plain text of a [seal]ed message, or null if it isn't one of this room's. */
    fun open(sealed: String): String? = runCatching {
        val all = java.util.Base64.getDecoder().decode(sealed)
        if (all.size < 12 + 16) return null
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, all, 0, 12))
        c.updateAAD(tag.toByteArray())
        String(c.doFinal(all, 12, all.size - 12))
    }.getOrNull()

    /** A link that opens the room in OpenTune. */
    val link: String get() = "opentune://together/$raw"

    override fun equals(other: Any?) = other is RoomCode && other.raw == raw
    override fun hashCode() = raw.hashCode()
    override fun toString() = pretty

    companion object {
        /** No 0/O or 1/I, so a code read aloud or off a screen can't be misread. */
        const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        const val LENGTH = 12
        private const val ITERATIONS = 40_000
        private val random = SecureRandom()

        fun generate() = RoomCode(String(CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }))

        /** A code typed or pasted in any form ("k7qx m2pa-9dte", or inside a link); null if it isn't one. */
        fun parse(text: String?): RoomCode? {
            text ?: return null
            val fromLink = Regex("""together/([A-Za-z0-9-]{12,16})""").find(text)?.groupValues?.get(1)
            val cleaned = (fromLink ?: text).uppercase().filter { it.isLetterOrDigit() }
            return cleaned.takeIf { it.length == LENGTH && it.all { c -> c in ALPHABET } }?.let(::RoomCode)
        }

        /** The first room code in a longer message, like a shared invite. */
        fun find(text: String?): RoomCode? {
            text ?: return null
            parse(Regex("""opentune://together/\S+""").find(text)?.value)?.let { return it }
            val group = "[$ALPHABET]{4}"
            return Regex("""\b$group-$group-$group\b""", RegexOption.IGNORE_CASE).find(text)?.value?.let(::parse)
        }

        internal fun sha256(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
internal fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
