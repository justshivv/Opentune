package com.opentune.desktop

/**
 * The pictures' sign-in store: a key held in memory in a folder of its own, as on a computer with a
 * keyring, so the no-keyring question (KeyringChoiceDialog) doesn't stand over the window in them.
 * The machine drawing them has no keyring; the app's own choice of store is left as it is.
 */
internal fun shotVault() {
    SecretVault.start(java.nio.file.Files.createTempDirectory("shots-vault").toFile(), InMemoryKeys())
}
