package com.opentune.desktop

import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.io.File
import java.util.Base64

/**
 * Where [SecretVault]'s key is kept: the system's own protected storage. Each
 * holds one 32-byte key and nothing else, so the size limits some of them have
 * never matter; the secrets themselves are sealed with it in files.
 */
internal interface KeyBackend {
    /** Said in Settings: where the key is. */
    val label: String
    /** The key, null when none has been kept yet. Throws when the store can't be reached. */
    fun load(): ByteArray?
    /** Keeps [key], replacing any before it. Throws when the store can't be reached. */
    fun save(key: ByteArray)

    companion object {
        /** The one this system has, or null where there's none OpenTune can use. */
        fun forThisSystem(root: File): KeyBackend? = when {
            // The tests' own, so no run of them touches (or waits on) this computer's keyring.
            System.getProperty("opentune.vault") == "memory" -> InMemoryKeys()
            Platform.isWindows() -> WindowsDataProtection(File(root, "vault.key"))
            Platform.isMac() -> MacKeychain
            Platform.isLinux() -> SecretService
            else -> null
        }
    }
}

/** A key held only while the app runs, for tests (-Dopentune.vault=memory). */
internal class InMemoryKeys : KeyBackend {
    private var kept: ByteArray? = null
    override val label = "memory (tests)"
    override fun load() = kept
    override fun save(key: ByteArray) { kept = key.copyOf() }
}

/**
 * Windows' data protection (DPAPI): the key is encrypted for this Windows user
 * account and kept in a file, which no other user, nor this one on another
 * computer, can decrypt.
 */
internal class WindowsDataProtection(private val file: File) : KeyBackend {
    override val label = "Windows data protection"

    override fun load(): ByteArray? {
        if (!file.exists()) return null
        return Crypt32Util.cryptUnprotectData(file.readBytes())
    }

    override fun save(key: ByteArray) {
        val sealed = Crypt32Util.cryptProtectData(key)
        OwnerOnly.write(file, sealed)
    }
}

/** The login keychain on macOS, as a generic password under OpenTune's own service name. */
internal object MacKeychain : KeyBackend {
    override val label = "the macOS Keychain"
    private const val SERVICE = "OpenTune Desktop"
    private const val ACCOUNT = "vault-key"
    private const val NOT_FOUND = -25300

    private val security by lazy { NativeLibrary.getInstance("Security") }
    private val foundation by lazy { NativeLibrary.getInstance("CoreFoundation") }
    private fun fn(name: String): Function = security.getFunction(name)

    private fun find(item: PointerByReference?): Pair<Int, ByteArray?> {
        val service = SERVICE.toByteArray()
        val account = ACCOUNT.toByteArray()
        val length = IntByReference()
        val data = PointerByReference()
        val status = fn("SecKeychainFindGenericPassword").invokeInt(
            arrayOf(null, service.size, service, account.size, account, length, data, item),
        )
        if (status != 0) return status to null
        val bytes = data.value.getByteArray(0, length.value)
        fn("SecKeychainItemFreeContent").invokeInt(arrayOf(null, data.value))
        return 0 to bytes
    }

    override fun load(): ByteArray? {
        val (status, bytes) = find(null)
        if (status == NOT_FOUND) return null
        check(status == 0) { "Keychain answered $status" }
        return Base64.getDecoder().decode(bytes)
    }

    override fun save(key: ByteArray) {
        val encoded = Base64.getEncoder().encode(key)
        val item = PointerByReference()
        val (status, _) = find(item)
        val result = if (status == 0) {
            fn("SecKeychainItemModifyAttributesAndData").invokeInt(arrayOf(item.value, null, encoded.size, encoded))
                .also { foundation.getFunction("CFRelease").invokeVoid(arrayOf(item.value)) }
        } else {
            val service = SERVICE.toByteArray()
            val account = ACCOUNT.toByteArray()
            fn("SecKeychainAddGenericPassword").invokeInt(arrayOf(null, service.size, service, account.size, account, encoded.size, encoded, null))
        }
        check(result == 0) { "Keychain answered $result" }
    }
}

/**
 * The desktop's keyring through libsecret (the Secret Service: GNOME Keyring,
 * KWallet and others), as a password labelled "OpenTune Desktop" in the
 * default collection.
 */
internal object SecretService : KeyBackend {
    override val label = "your keyring (Secret Service)"

    private val lib by lazy { NativeLibrary.getInstance("secret-1") }
    private val glib by lazy { NativeLibrary.getInstance("glib-2.0") }

    @Structure.FieldOrder("name", "type")
    open class Attribute : Structure() {
        @JvmField var name: String? = null
        @JvmField var type: Int = 0 // SECRET_SCHEMA_ATTRIBUTE_STRING
    }

    @Structure.FieldOrder("name", "flags", "attributes", "reserved", "reserved1", "reserved2", "reserved3", "reserved4", "reserved5", "reserved6", "reserved7")
    class Schema : Structure() {
        @JvmField var name: String? = "com.opentune.desktop.Vault"
        @JvmField var flags: Int = 0
        @JvmField var attributes: Array<Attribute?> = Attribute().toArray(32).map { it as Attribute }.toTypedArray()
        @JvmField var reserved: Int = 0
        @JvmField var reserved1: Pointer? = null
        @JvmField var reserved2: Pointer? = null
        @JvmField var reserved3: Pointer? = null
        @JvmField var reserved4: Pointer? = null
        @JvmField var reserved5: Pointer? = null
        @JvmField var reserved6: Pointer? = null
        @JvmField var reserved7: Pointer? = null

        init {
            attributes[0]!!.name = "kind"
            write()
        }
    }

    private val schema by lazy { Schema() }

    /** The GError's message, freed; null when there was none. */
    private fun failure(error: PointerByReference): String? {
        val e = error.value ?: return null
        val message = e.getPointer(8)?.getString(0)
        runCatching { glib.getFunction("g_error_free").invokeVoid(arrayOf(e)) }
        return message ?: "unknown error"
    }

    override fun load(): ByteArray? {
        val error = PointerByReference()
        val found = lib.getFunction("secret_password_lookup_sync").invokePointer(arrayOf(schema, null, error, "kind", "vault-key", null))
        failure(error)?.let { error(it) }
        found ?: return null
        val text = found.getString(0)
        lib.getFunction("secret_password_free").invokeVoid(arrayOf(found))
        return Base64.getDecoder().decode(text)
    }

    override fun save(key: ByteArray) {
        val error = PointerByReference()
        val ok = lib.getFunction("secret_password_store_sync").invokeInt(
            arrayOf(schema, "default", "OpenTune Desktop", Base64.getEncoder().encodeToString(key), null, error, "kind", "vault-key", null),
        )
        failure(error)?.let { error(it) }
        check(ok != 0) { "The keyring didn't keep the key" }
    }
}

/** Files only this user can read and write, made so before anything goes into them. */
internal object OwnerOnly {
    fun write(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.delete()
        tmp.createNewFile()
        restrict(tmp)
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "Couldn't write ${file.name}" }
        }
    }

    fun restrict(file: File) {
        runCatching { java.nio.file.Files.setPosixFilePermissions(file.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
            .onFailure {
                file.setReadable(false, false); file.setReadable(true, true)
                file.setWritable(false, false); file.setWritable(true, true)
            }
    }
}
