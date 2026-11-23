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

    /**
     * The rules [StreamResolver] picks an art track's music video by. An art track found
     * to have none under older rules is asked again: they may have turned one down.
     */
    private const val LINK_RULES = 1

    @Serializable
    private data class Saved(
        val has: List<String> = emptyList(),
        val none: List<String> = emptyList(),
        /** Art tracks no music video was found for, under [linkRules]. */
        val noLink: List<String> = emptyList(),
        val linkRules: Int = 0,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var file: File? = null
    private val lock = kotlinx.coroutines.sync.Mutex()
    private val gate = Any()
    @Volatile private var saved = Saved()

    fun init(context: Context) {
        val dir = context.applicationContext.filesDir
        val f = File(dir, "video-availability-2.json")
        file = f
        runCatching { json.decodeFromString<Saved>(f.readText()) }.getOrNull()?.let { saved = it }
        // The first file counted art tracks (a still of the cover) as having a video:
        // its "no" answers still hold, its "yes" ones are asked again.
        val old = File(dir, "video-availability.json")
        if (old.exists()) {
            if (!f.exists()) runCatching { json.decodeFromString<Saved>(old.readText()) }.getOrNull()?.let { saved = Saved(none = it.none) }
            old.delete()
        }
        if (saved.linkRules != LINK_RULES) saved = saved.copy(noLink = emptyList(), linkRules = LINK_RULES)
    }

    /** Whether [videoId], an art track, was found to have no music video under the current rules. */
    fun knownNoLink(videoId: String): Boolean = videoId in saved.noLink

    /** Keeps that no music video was found for [videoId], an art track, under the current rules. */
    fun rememberNoLink(videoId: String) {
        synchronized(gate) {
            val before = saved
            if (videoId in before.noLink) return
            saved = before.copy(noLink = (listOf(videoId) + before.noLink).take(MAX))
        }
        write()
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
