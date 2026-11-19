package com.opentune.desktop

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A keyring that keeps its key in memory, as the system's would. */
internal class MemoryKeys : KeyBackend {
    var kept: ByteArray? = null
    override val label = "a test keyring"
    override fun load() = kept
    override fun save(key: ByteArray) { kept = key.copyOf() }
}

/** A system with a keyring service that doesn't answer. */
internal object NoKeyring : KeyBackend {
    override val label = "nothing"
    override fun load(): ByteArray? = error("No Secret Service on this bus")
    override fun save(key: ByteArray) = error("No Secret Service on this bus")
}

class SecretVaultTest {
    private val dir: File = Files.createTempDirectory("vault").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
        // Back to a keyring of its own for the tests after, never the real one.
        SecretVault.start(Files.createTempDirectory("vault-rest").toFile(), MemoryKeys())
    }

    private fun posix() = java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

    @Test
    fun withAKeyringASignInIsSealedAndReadsBack() {
        val keys = MemoryKeys()
        SecretVault.start(dir, keys)
        assertEquals(SecretVault.Mode.Keystore, SecretVault.mode.value)
        assertEquals(32, keys.kept?.size)
        val file = File(dir, "lastfm.json")
        SecretVault.write(file, """{"session":"s3cret-session"}""")
        assertTrue(SecretVault.isSealed(file))
        assertFalse(String(file.readBytes()).contains("s3cret"))
        if (posix()) assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())))
        // Read again after a restart, with the same key from the keyring.
        SecretVault.start(File(dir, "elsewhere"), keys)
        SecretVault.start(dir, keys)
        assertEquals("""{"session":"s3cret-session"}""", SecretVault.read(file))
    }

    @Test
    fun aPlainFileFromBeforeIsSealedWhenFirstRead() {
        val file = File(dir, "addon-device.json").apply { writeText("""{"key":"private"}""") }
        SecretVault.start(dir, MemoryKeys())
        assertEquals("""{"key":"private"}""", SecretVault.read(file))
        assertTrue(SecretVault.isSealed(file))
        assertEquals("""{"key":"private"}""", SecretVault.read(file))
    }

    @Test
    fun aSealedFileOpensOnlyUnderItsOwnNameAndKey() {
        val keys = MemoryKeys()
        SecretVault.start(dir, keys)
        val file = File(dir, "lastfm.json")
        SecretVault.write(file, "secret")
        val moved = File(dir, "addon-device.json")
        file.copyTo(moved)
        assertNull(SecretVault.read(moved))
        // A changed byte doesn't open either.
        val bytes = file.readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        file.writeBytes(bytes)
        assertNull(SecretVault.read(file))
        // Nor under another key.
        SecretVault.write(file, "secret")
        SecretVault.start(File(dir, "other"), MemoryKeys())
        SecretVault.start(dir, MemoryKeys())
        assertNull(SecretVault.read(file))
    }

    @Test
    fun withoutAKeyringNothingIsWrittenUntilTheListenerChooses() {
        SecretVault.start(dir, NoKeyring)
        assertEquals(SecretVault.Mode.Undecided, SecretVault.mode.value)
        val file = File(dir, "lastfm.json")
        SecretVault.write(file, "session")
        assertFalse(file.exists())
        assertEquals("session", SecretVault.read(file))
        // Kept in a plain file only once they say so, readable by them alone.
        SecretVault.choose(SecretVault.Mode.Unencrypted)
        assertEquals("session", file.readText())
        if (posix()) assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())))
        // The choice stays for the next start.
        SecretVault.start(File(dir, "x"), NoKeyring)
        SecretVault.start(dir, NoKeyring)
        assertEquals(SecretVault.Mode.Unencrypted, SecretVault.mode.value)
        assertEquals("session", SecretVault.read(file))
    }

    @Test
    fun keptUntilQuittingLeavesNoFileBehind() {
        val old = File(dir, "prefs/account.properties").apply { parentFile.mkdirs(); writeText("accounts_v2=cookie") }
        SecretVault.start(dir, NoKeyring)
        SecretVault.choose(SecretVault.Mode.SessionOnly)
        assertFalse(old.exists())
        val file = File(dir, "lastfm.json")
        SecretVault.write(file, "session")
        assertFalse(file.exists())
        assertEquals("session", SecretVault.read(file))
        // Gone after a restart.
        SecretVault.start(File(dir, "x"), NoKeyring)
        SecretVault.start(dir, NoKeyring)
        assertEquals(SecretVault.Mode.SessionOnly, SecretVault.mode.value)
        assertNull(SecretVault.read(file))
    }

    @Test
    fun theAccountsPreferencesAreSealedAndOthersAreNot() {
        SecretVault.start(dir, MemoryKeys())
        val context = android.content.Context(dir)
        context.getSharedPreferences("account", 0).edit().putString("accounts_v2", "[{\"cookie\":\"SAPISID=abc\"}]").commit()
        context.getSharedPreferences("tray", 0).edit().putBoolean("minimise", true).commit()
        assertTrue(SecretVault.isSealed(File(dir, "prefs/account.properties")))
        assertFalse(SecretVault.isSealed(File(dir, "prefs/tray.properties")))
        // A fresh read, as after a restart.
        val again = android.content.Context(dir)
        assertEquals("[{\"cookie\":\"SAPISID=abc\"}]", again.getSharedPreferences("account", 0).getString("accounts_v2", null))
    }
}

/**
 * The real keyring on this machine, run only when asked (OPENTUNE_LIVE_KEYRING=1)
 * from a session with a Secret Service on its bus.
 */
class LiveKeyringTest {
    @Test
    fun theSystemKeyringKeepsAndGivesBackTheKey() {
        org.junit.Assume.assumeTrue(System.getenv("OPENTUNE_LIVE_KEYRING") == "1")
        val dir = Files.createTempDirectory("live").toFile()
        val backend = when {
            com.sun.jna.Platform.isWindows() -> WindowsDataProtection(java.io.File(dir, "vault.key"))
            com.sun.jna.Platform.isMac() -> MacKeychain
            else -> SecretService
        }
        val key = ByteArray(32) { it.toByte() }
        backend.save(key)
        assertTrue(backend.load()!!.contentEquals(key))
        val again = ByteArray(32) { (31 - it).toByte() }
        backend.save(again)
        assertTrue(backend.load()!!.contentEquals(again))
    }
}
