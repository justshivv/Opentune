package com.opentune.data.spotify

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypted credentials; the preference file is also excluded from backup/transfer. */
internal class SpotifyVault(context: Context) {
    private val prefs = context.getSharedPreferences("spotify_oauth", Context.MODE_PRIVATE)
    private val alias = "opentune.spotify.oauth.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun read(): JSONObject = try {
        val encoded = prefs.getString("data", null)
        if (encoded == null) JSONObject() else {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        }
    } catch (_: Exception) { prefs.edit().clear().commit(); JSONObject() }
    @Synchronized fun write(data: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(data.toString().toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("data", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()) { "Couldn't save Spotify connection" }
    }
    @Synchronized fun clear() { prefs.edit().clear().commit() }
}
