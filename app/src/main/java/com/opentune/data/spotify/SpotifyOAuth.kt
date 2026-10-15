package com.opentune.data.spotify

import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Official authorization-code PKCE protocol; no client secret belongs in an APK. */
internal object SpotifyOAuth {
    const val REDIRECT = "https://justshivv.github.io/Opentune/spotify/callback/"
    private val random = SecureRandom()
    fun randomValue(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    fun validClientId(value: String) = Regex("[a-fA-F0-9]{32}").matches(value)
    fun authorize(clientId: String, state: String, verifier: String): String {
        require(validClientId(clientId))
        return "https://accounts.spotify.com/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientId).addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", REDIRECT)
            .addQueryParameter("scope", "playlist-read-private playlist-read-collaborative user-library-read")
            .addQueryParameter("state", state).addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("code_challenge", challenge(verifier)).build().toString()
    }
}
