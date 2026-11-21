package com.opentune.data.innertube

import android.content.Context
import com.opentune.data.DebugLog as Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Which songs have a music video, remembered on disk. The answer is a fact
 * about the song, like its title: learned once, by whichever probe settles
 * it, and kept — so the player's Song | Video switch knows at once, even
 * for a song restored paused after a relaunch, without asking the network
 * again. Only settled answers are kept; a song whose probe never answered
 * is simply absent, and may be asked again.
 */
object VideoAvailability {
    private const val TAG = "VideoAvailability"
    /** The most answers kept, newest first; a music library's probed songs fit easily. */
    private const val MAX = 2000

    @Serializable
    private data class Saved(val has: List<String> = emptyList(), val none: List<String> = emptyList())

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var file: File? = null
    private val lock = kotlinx.coroutines.sync.Mutex()
    private val gate = Any()
    @Volatile private var saved = Saved()

    fun init(context: Context) {
        val f = File(context.applicationContext.filesDir, "video-availability.json")
        file = f
        runCatching { json.decodeFromString<Saved>(f.readText()) }.getOrNull()?.let { saved = it }
    }

    /** The settled answer for [videoId]: true or false, or null when it was never settled. */
    fun known(videoId: String): Boolean? = when {
        videoId in saved.has -> true
        videoId in saved.none -> false
        else -> null
    }

    /** Keeps the settled answer for [videoId], newest first, up to [MAX] answers each way. */
    fun remember(videoId: String, hasVideo: Boolean) {
        synchronized(gate) {
            val before = saved
            val next = if (hasVideo) {
                if (videoId in before.has) return
                before.copy(has = (listOf(videoId) + before.has).take(MAX), none = before.none - videoId)
            } else {
                if (videoId in before.none) return
                before.copy(has = before.has - videoId, none = (listOf(videoId) + before.none).take(MAX))
            }
            saved = next
        }
        write()
    }

    /** Writes whatever is newest when its turn comes, so an older copy never lands last. */
    private fun write() {
        val f = file ?: return
        scope.launch {
            lock.withLock {
                runCatching { f.writeText(json.encodeToString(saved)) }.onFailure { Log.w(TAG, "couldn't save", it) }
            }
        }
    }
}
