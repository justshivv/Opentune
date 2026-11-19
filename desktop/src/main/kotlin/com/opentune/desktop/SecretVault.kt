package com.opentune.desktop

import java.io.File
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The desktop's sign-ins at rest: the YouTube Music cookies, the Subsonic
 * token, the Last.fm session and the add-on device key.
 *
 * Each file holding one is sealed with AES-256-GCM under a key that the
 * system's protected storage keeps ([KeyBackend]: Windows data protection, the
 * macOS Keychain, or the Secret Service on Linux). A file still in plain text
 * from an older version is read once and sealed in its place.
 *
 * Where no keyring answers, nothing is written in plain text until the listener
 * says so ([choose]): until then, and for good if they pick [Mode.SessionOnly],
 * sign-ins are held in memory and gone when the app quits.
 */
internal object SecretVault {
    enum class Mode {
        /** Sealed with a key the system keeps. */
        Keystore,
        /** No keyring; the listener chose plain files only their user can read. */
        Unencrypted,
        /** No keyring; the listener chose to sign in again after each start. */
        SessionOnly,
        /** No keyring, and the listener hasn't chosen yet: held in memory meanwhile. */
        Undecided,
    }

    /** What the files the vault looks after begin with once sealed. */
    private val MAGIC = "OTV1".toByteArray()
    private const val NONCE = 12

    /** The files under the app's folder that hold sign-ins, removed when the listener picks [Mode.SessionOnly]. */
    val KNOWN = listOf("prefs/account.properties", "prefs/subsonic.properties", "lastfm.json", "addon-device.json")

    private var root = File(System.getProperty("user.home"), ".opentune")
    private var backend: KeyBackend? = null
    private var key: SecretKeySpec? = null
    private var started = false
    private val memory = ConcurrentHashMap<String, String>()
    private val random = SecureRandom()

    private val _mode = MutableStateFlow(Mode.Undecided)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    /** Where the key is, for Settings; null without a keyring. */
    var keptIn: String? = null
        private set

    private val consentFile get() = File(root, "vault-choice")

    /**
     * Finds or makes the key. Called once at start, before anything reads a
     * sign-in; a keyring that doesn't answer within [waitSeconds] (a locked one
     * waiting on its password, say) counts as none for this run.
     */
    @Synchronized
    fun start(at: File = root, use: KeyBackend? = KeyBackend.forThisSystem(at), waitSeconds: Long = 60) {
        if (started && at == root) return
        root = at
        started = true
        memory.clear()
        key = null
        keptIn = null
        backend = use
        val found = use?.let { b -> runCatching { withTimeout(waitSeconds) { b.load() ?: newKey().also { b.save(it); check(b.load()?.contentEquals(it) == true) { "The key didn't read back" } } } } }
        found?.onFailure { System.err.println("[vault] ${use.label}: ${it.message}") }
        val bytes = found?.getOrNull()
        if (bytes != null && bytes.size == 32) {
            key = SecretKeySpec(bytes, "AES")
            keptIn = use.label
            _mode.value = Mode.Keystore
        } else {
            _mode.value = when (runCatching { consentFile.readText().trim() }.getOrNull()) {
                "unencrypted" -> Mode.Unencrypted
                "session" -> Mode.SessionOnly
                else -> Mode.Undecided
            }
        }
        System.err.println("[vault] ${_mode.value}${keptIn?.let { " in $it" } ?: ""}")
    }

    private fun ensure() { if (!started) start() }

    private fun newKey() = ByteArray(32).also(random::nextBytes)

    private fun <T> withTimeout(seconds: Long, work: () -> T): T {
        val pool = Executors.newSingleThreadExecutor { Thread(it, "vault").apply { isDaemon = true } }
        try {
            return pool.submit(work).get(seconds, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * The listener's answer where there's no keyring. [Mode.Unencrypted] writes
     * what's held in memory to its files; [Mode.SessionOnly] removes the files.
     */
    @Synchronized
    fun choose(choice: Mode) {
        ensure()
        require(choice == Mode.Unencrypted || choice == Mode.SessionOnly)
        if (_mode.value == Mode.Keystore) return
        OwnerOnly.write(consentFile, (if (choice == Mode.Unencrypted) "unencrypted" else "session").toByteArray())
        _mode.value = choice
        if (choice == Mode.Unencrypted) {
            memory.forEach { (path, text) -> OwnerOnly.write(File(path), text.toByteArray()) }
        } else {
            KNOWN.forEach { File(root, it).delete() }
        }
    }

    /** The text kept in [file], or null when there's none (or it can't be opened). */
    @Synchronized
    fun read(file: File): String? {
        ensure()
        memory[file.path]?.let { return it }
        if (!file.exists()) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (sealed(bytes)) {
            val k = key ?: return null.also { System.err.println("[vault] ${file.name} is sealed and the key isn't here") }
            return runCatching { open(k, file.name, bytes) }
                .onFailure { System.err.println("[vault] ${file.name} didn't open: ${it.message}") }
                .getOrNull()
        }
        val text = String(bytes)
        when (_mode.value) {
            // From before the vault: sealed now, so the plain copy is gone.
            Mode.Keystore -> runCatching { write(file, text) }
            Mode.SessionOnly -> { memory[file.path] = text; file.delete() }
            else -> {}
        }
        return text
    }

    /** Keeps [text] in [file], sealed where there's a key; null removes it. */
    @Synchronized
    fun write(file: File, text: String?) {
        ensure()
        if (text == null) {
            memory.remove(file.path)
            file.delete()
            return
        }
        when (_mode.value) {
            Mode.Keystore -> OwnerOnly.write(file, seal(key!!, file.name, text))
            Mode.Unencrypted -> OwnerOnly.write(file, text.toByteArray())
            Mode.SessionOnly, Mode.Undecided -> memory[file.path] = text
        }
    }

    private fun sealed(bytes: ByteArray) = bytes.size > MAGIC.size + NONCE && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    /** The file's name is bound in, so one sealed file can't be passed off as another. */
    private fun seal(k: SecretKeySpec, name: String, text: String): ByteArray {
        val nonce = ByteArray(NONCE).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, nonce))
        cipher.updateAAD(name.toByteArray())
        return MAGIC + nonce + cipher.doFinal(text.toByteArray())
    }

    private fun open(k: SecretKeySpec, name: String, bytes: ByteArray): String {
        val nonce = bytes.copyOfRange(MAGIC.size, MAGIC.size + NONCE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, nonce))
        cipher.updateAAD(name.toByteArray())
        return String(cipher.doFinal(bytes, MAGIC.size + NONCE, bytes.size - MAGIC.size - NONCE))
    }

    /** For tests: whether [file] on disk is sealed. */
    internal fun isSealed(file: File) = file.exists() && sealed(file.readBytes())
}
