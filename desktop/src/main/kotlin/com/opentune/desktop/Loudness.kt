package com.opentune.desktop

import com.opentune.data.innertube.Innertube
import com.opentune.data.innertube.PlayerClient
import com.opentune.data.settings.VolumeLevel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * YouTube's own loudness measure of a song, as the phone uses it for loudness
 * normalisation (Settings → Playback): read from the player response, which
 * carries it for every song.
 */
internal object Loudness {
    /**
     * The gain, in dB, for a song YouTube measured at [loudnessDb] against its reference:
     * brought to the reference, moved by [level]'s offset, and lifted no more than its cap.
     * The same as the phone's (core/…/playback/PlaybackService.kt, loudnessGainDb).
     */
    fun gainDb(loudnessDb: Double, level: VolumeLevel): Double =
        (-loudnessDb + level.offsetDb).coerceAtMost(level.maxGainDb.toDouble())

    /** The figure for [videoId], or null when YouTube didn't give one. */
    suspend fun of(videoId: String): Double? {
        for (client in listOf(PlayerClient.ANDROID_VR, PlayerClient.WEB_REMIX)) {
            val response = runCatching { Innertube.player(videoId, client) }.getOrNull() ?: continue
            read(response)?.let { return it }
        }
        return null
    }

    /** From playerConfig, or from an audio format when a client leaves that out. */
    internal fun read(response: JsonObject): Double? =
        response["playerConfig"]?.jsonObject?.get("audioConfig")?.jsonObject?.get("loudnessDb")?.jsonPrimitive?.doubleOrNull
            ?: response["streamingData"]?.jsonObject?.get("adaptiveFormats")?.jsonArray
                ?.map { it.jsonObject }
                ?.firstOrNull { it["mimeType"]?.jsonPrimitive?.content?.startsWith("audio/") == true && it["loudnessDb"] != null }
                ?.get("loudnessDb")?.jsonPrimitive?.doubleOrNull
}
