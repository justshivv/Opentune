package com.opentune.data.history

import com.opentune.data.account.AccountStore
import com.opentune.data.innertube.Innertube
import com.opentune.data.settings.AppSettings
import com.opentune.data.innertube.StreamResolver
import java.security.SecureRandom

/** Account writes are opt-in; public/add-on audio uses the song's original YouTube identity. */
internal object YouTubeHistory {
    fun session(videoId: String, active: () -> Boolean): YouTubeHistorySession? {
        if (!videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) return null
        val cookie = Innertube.cookie ?: return null
        val account = AccountStore.accounts.value.firstOrNull { it.active }?.id
        val allowed = {
            val settings = AppSettings.playback.value
            active() && !settings.pauseListeningHistory &&
                settings.listeningHistoryTarget == ListeningHistoryTarget.YOUTUBE_MUSIC &&
                Innertube.cookie == cookie &&
                AccountStore.accounts.value.firstOrNull { it.active }?.id == account
        }
        if (!allowed()) return null
        val random = SecureRandom()
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val nonce = CharArray(16) { alphabet[random.nextInt(alphabet.length)] }.concatToString()
        return YouTubeHistorySession(object : YouTubeHistorySession.Transport {
            private var tracking: Innertube.PlaybackTracking? = null
            override suspend fun prepare(): Boolean {
                val timestamp = StreamResolver.signatureTimestamp(videoId)
                if (!allowed()) return false
                tracking = Innertube.playbackTracking(videoId, timestamp)
                return tracking != null && allowed()
            }
            override suspend fun playback(): Boolean {
                val t = tracking ?: return false
                val ok = Innertube.pingPlayback(t.playbackUrl, nonce, allowed) in 200..299
                if (ok) com.opentune.data.MusicRepository.refreshHomeRecommendations()
                return ok
            }
            override suspend fun atr(): Boolean {
                val url = tracking?.atrUrl ?: return true
                return Innertube.pingAtr(url, nonce, allowed) in 200..299
            }
            override suspend fun watchtime(heardSeconds: Long, final: Boolean): Boolean {
                val url = tracking?.watchtimeUrl ?: return true
                return Innertube.pingWatchtime(url, nonce, heardSeconds, final, allowed) in 200..299
            }
        }, allowed)
    }
}
