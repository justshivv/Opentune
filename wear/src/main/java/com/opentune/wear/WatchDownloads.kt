package com.opentune.wear

import android.content.Context
import com.opentune.data.MusicRepository
import com.opentune.data.connect.Connect
import com.opentune.data.connect.WatchRequest
import com.opentune.data.download.DeviceDownloads
import com.opentune.data.download.DownloadEntry
import com.opentune.data.download.DownloadRules
import com.opentune.data.download.DownloadState
import com.opentune.data.download.Downloads
import com.opentune.data.model.Song
import com.opentune.data.settings.AudioQuality
import kotlinx.coroutines.CancellationException

/**
 * Songs kept on the watch, for listening with the phone left behind. The download itself is
 * core's ([Downloads]); the watch adds its own rules (regular quality, Wi-Fi it asks for, room
 * to spare) and takes what the other devices send it (see [com.opentune.data.connect.WatchMail]).
 */
object WatchDownloads {
    /** Below this much free space the watch stops downloading. */
    const val KEEP_FREE_BYTES = 500L * 1024 * 1024

    private lateinit var app: Context

    /** Before [com.opentune.data.Core.init], so downloads started then already go by these rules. */
    fun rules() {
        DownloadRules.device = DeviceDownloads(
            maxKbps = AudioQuality.HIGH.maxKbps,
            networkReady = WatchWifi::ready,
            keepFreeBytes = KEEP_FREE_BYTES,
            noSpaceMessage = "Not enough space on the watch",
        )
    }

    /** After core is up: Wi-Fi for downloads, and the requests from the other devices. */
    fun init(context: Context) {
        app = context.applicationContext
        WatchWifi.init(app)
        Connect.takeRequests = ::take
    }

    fun download(song: Song) = Downloads.enqueue(app, song)

    fun downloadAll(songs: List<Song>) = songs.forEach { Downloads.enqueue(app, it) }

    fun remove(videoId: String) = Downloads.remove(app, videoId)

    fun removeAll() = Downloads.removeAll(app, Downloads.doneNow().map { it.song.toSong() })

    /** Queues what another device asked for; a playlist that won't load now is tried again next time. */
    private suspend fun take(requests: List<WatchRequest>): Set<String> {
        val took = mutableSetOf<String>()
        for (r in requests) {
            when (r.kind) {
                WatchRequest.SONG -> {
                    download(Song(r.ytId, r.title, r.subtitle.orEmpty(), r.thumbnailUrl, durationText = r.durationText))
                    took += r.id
                }
                WatchRequest.COLLECTION -> try {
                    downloadAll(MusicRepository.collection(r.ytId).songs)
                    took += r.id
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
                // A kind from a newer app: nothing to do with it here, so it doesn't wait for ever.
                else -> took += r.id
            }
        }
        return took
    }

    /** What the Downloaded screen shows. */
    data class Summary(val done: List<DownloadEntry>, val going: List<DownloadEntry>, val failed: List<DownloadEntry>, val bytes: Long)

    fun summary(entries: Map<String, DownloadEntry>): Summary {
        val all = entries.values.sortedByDescending { it.addedAt }
        val done = all.filter { it.state == DownloadState.DONE }
        return Summary(
            done = done,
            going = all.filter { it.state == DownloadState.QUEUED || it.state == DownloadState.DOWNLOADING },
            failed = all.filter { it.state == DownloadState.FAILED },
            bytes = done.sumOf { it.bytes },
        )
    }

    /** "312 MB", "1.2 GB". */
    fun size(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
        else -> "${(bytes + 512 * 1024) / (1024 * 1024)} MB"
    }
}
