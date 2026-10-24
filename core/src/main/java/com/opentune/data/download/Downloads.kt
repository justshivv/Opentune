package com.opentune.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.opentune.data.DebugLog as Log
import com.opentune.data.Http
import com.opentune.data.innertube.StreamResolver
import com.opentune.data.lyrics.LyricsRepository
import com.opentune.data.library.SongRef
import com.opentune.data.model.PLAYER_ART_PX
import com.opentune.data.model.Song
import com.opentune.data.model.artworkAt
import com.opentune.data.settings.AppSettings
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
enum class DownloadState { QUEUED, DOWNLOADING, DONE, FAILED }

@Serializable
data class DownloadEntry(
    val song: SongRef,
    val state: DownloadState = DownloadState.QUEUED,
    val progress: Float = 0f,
    val path: String? = null,
    val artPath: String? = null,
    val bytes: Long = 0,
    val error: String? = null,
    /** YouTube's loudness figure, kept so normalization works offline. */
    val loudnessDb: Double? = null,
    val addedAt: Long = System.currentTimeMillis(),
    /** Identifies the worker allowed to publish updates, including after cancellation/retry. */
    val workId: String? = null,
    val source: String? = null,
    val bits: Int? = null,
    val sampleRate: Int? = null,
    /** The copy in the Music folder, when Export compatible downloads made one. */
    val exportUri: String? = null,
)

/**
 * Songs saved for offline listening. Files live in app storage
 * (files/downloads), so they're private to the app and go away with it.
 * Each download is a WorkManager job, so it continues after the app is
 * closed and waits for Wi-Fi when that setting is on. Playback picks the
 * file up by video id; see [com.opentune.playback.trackUri].
 */
object Downloads {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), DownloadEntry.serializer())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var dir: File? = null

    private val _entries = MutableStateFlow<Map<String, DownloadEntry>>(emptyMap())
    val entries: StateFlow<Map<String, DownloadEntry>> = _entries.asStateFlow()

    /**
     * One song's entry, changing only when that song's does. A song row
     * watches this rather than [entries], which changes every 256 KB of any
     * download in progress.
     */
    fun entry(videoId: String): Flow<DownloadEntry?> = _entries.map { it[videoId] }.distinctUntilChanged()

    /** What [entry] holds right now, to start from. */
    fun entryNow(videoId: String): DownloadEntry? = _entries.value[videoId]

    /** Finished downloads; progress on others leaves it alone. */
    val done: Flow<List<DownloadEntry>> = _entries.map(::finished).distinctUntilChanged()

    /** What [done] holds right now, to start from without a blank first frame. */
    fun doneNow(): List<DownloadEntry> = finished(_entries.value)

    private fun finished(m: Map<String, DownloadEntry>) = m.values.filter { it.state == DownloadState.DONE }

    @OptIn(FlowPreview::class)
    fun init(context: Context) {
        val d = File(context.filesDir, "downloads").apply { mkdirs() }
        dir = d
        val f = com.opentune.data.SavedFile(File(d, "index.json"), "Downloads").also { saved = it }
        // An index that won't read is kept aside: saved over empty, every download would be forgotten.
        _entries.value = f.read(emptyMap()) { json.decodeFromString(serializer, it) }
            // A download interrupted by the process dying is picked up again by
            // WorkManager; until then it is simply queued.
            .mapValues { (_, e) -> if (e.state == DownloadState.DOWNLOADING) e.copy(state = DownloadState.QUEUED) else e }
        scope.launch {
            _entries.drop(1).onEach { f.dirty = true }.debounce(500).collect { map ->
                runCatching { f.write(json.encodeToString(serializer, map)) }
            }
        }
    }

    private var saved: com.opentune.data.SavedFile? = null

    /** Saves at once a change still waiting for its save. */
    fun flush() { saved?.flushIfDirty { json.encodeToString(serializer, _entries.value) } }

    /** The finished file for [videoId], if it's been downloaded and is still there. */
    fun fileFor(videoId: String): File? {
        val e = _entries.value[videoId] ?: return null
        if (e.state != DownloadState.DONE) return null
        return e.path?.let(::File)?.takeIf { it.isFile && it.length() > 0 }
    }

    fun artFor(videoId: String): File? =
        _entries.value[videoId]?.takeIf { it.state == DownloadState.DONE }?.artPath?.let(::File)?.takeIf { it.exists() }

    fun loudnessFor(videoId: String): Double? = _entries.value[videoId]?.loudnessDb

    /** Where a downloaded song's lyrics are kept, if it's downloaded. */
    fun lyricsFileFor(videoId: String): File? {
        val entry = _entries.value[videoId]?.takeIf { it.state == DownloadState.DONE } ?: return null
        val root = dir ?: return null
        val folder = entry.workId?.let { File(root, "$videoId.$it") } ?: root
        return File(folder, "$videoId.lyrics.json")
    }

    fun enqueue(context: Context, song: Song) {
        if (!com.opentune.data.isYouTubeId(song.videoId)) return
        val existing = _entries.value[song.videoId]
        if (existing?.state == DownloadState.DONE || existing?.state == DownloadState.DOWNLOADING || existing?.state == DownloadState.QUEUED) return
        schedule(context, SongRef.of(song))
    }

    private fun schedule(context: Context, song: SongRef) {
        val wifiOnly = AppSettings.library.value.downloadWifiOnly
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_ID to song.videoId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            // Started straight away rather than whenever Android next runs background work,
            // which on a phone saving battery could be a long while; past the quota it's ordinary work.
            .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 15, java.util.concurrent.TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        put(song.videoId) { DownloadEntry(song, workId = request.id.toString()) }
        WorkManager.getInstance(context).enqueueUniqueWork(workName(song.videoId), ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * Schedules every waiting download again under the current settings: a
     * job keeps the network it was queued for, so turning "Wi-Fi only" off
     * left the songs already waiting still waiting for Wi-Fi.
     */
    fun requeueWaiting(context: Context) {
        _entries.value.values.filter { it.state == DownloadState.QUEUED }.forEach { schedule(context, it.song) }
    }

    /** Downloads wait because only Wi-Fi is allowed and the phone is on mobile data (or another metered network). */
    fun waitingForWifi(context: Context): Boolean {
        if (!AppSettings.library.value.downloadWifiOnly) return false
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        return cm.activeNetwork == null || cm.isActiveNetworkMetered
    }

    fun remove(context: Context, videoId: String) = remove(context, videoId, onlyActive = false)

    private fun remove(context: Context, videoId: String, onlyActive: Boolean) {
        // Withdraw ownership first: progress, failure and completion callbacks can
        // never resurrect the row while WorkManager stops its coroutine.
        fun eligible(entry: DownloadEntry?) = entry != null && (!onlyActive || entry.state == DownloadState.QUEUED || entry.state == DownloadState.DOWNLOADING)
        val entry = _entries.getAndUpdate { if (eligible(it[videoId])) it - videoId else it }[videoId]
            ?.takeIf { eligible(it) } ?: return
        val manager = WorkManager.getInstance(context)
        val workId = entry.workId?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
        if (workId != null) manager.cancelWorkById(workId) else manager.cancelUniqueWork(workName(videoId))
        val directory = dir
        scope.launch {
            entry.exportUri?.let { DownloadExport.unpublish(context, it) }
            entry.path?.let { File(it).delete() }
            entry.artPath?.let { File(it).delete() }
            directory?.let { d ->
                entry.workId?.let { File(d, "$videoId.$it").deleteRecursively() }
                File(d, "$videoId.part").delete()
                if (entry.workId == null) File(d, "$videoId.lyrics.json").delete()
            }
        }
    }

    /**
     * Copies every finished download not yet in the Music folder there, for
     * Export compatible downloads being turned on. The number copied, and the
     * number left out because their format can't go there (older Opus downloads).
     */
    suspend fun exportExisting(context: Context): Pair<Int, Int> {
        var copied = 0
        var skipped = 0
        for (entry in doneNow()) {
            if (entry.exportUri != null) continue
            val file = entry.path?.let(::File)?.takeIf { it.exists() } ?: continue
            if (!DownloadExport.compatible(file)) { skipped++; continue }
            val uri = DownloadExport.publish(context, entry.song, file) ?: continue
            put(entry.song.videoId) { (it ?: entry).copy(exportUri = uri.toString()) }
            copied++
        }
        return copied to skipped
    }

    /** Cancel only queued/in-flight songs, preserving completed offline copies. */
    fun cancelAll(context: Context, songs: List<Song>) {
        selection(songs).active.forEach { remove(context, it, onlyActive = true) }
    }

    /** Remove these offline copies, leaving playlist membership and likes intact. */
    fun removeAll(context: Context, songs: List<Song>) {
        selection(songs).stored.forEach { remove(context, it) }
    }

    fun selection(songs: List<Song>): DownloadSelection = downloadSelection(songs, _entries.value)

    fun retry(context: Context, videoId: String) {
        val e = _entries.value[videoId]?.takeIf { it.state == DownloadState.FAILED } ?: return
        remove(context, videoId)
        enqueue(context, e.song.toSong())
    }

    /** Reject stale callbacks from removed/replaced workers atomically. */
    internal fun updateWorker(videoId: String, workId: String?, transform: (DownloadEntry) -> DownloadEntry): Boolean {
        while (true) {
            val before = _entries.value
            val entry = before[videoId]?.takeIf { it.workId == workId } ?: return false
            if (_entries.compareAndSet(before, before + (videoId to transform(entry)))) return true
        }
    }

    /** A legacy download failed the player's extractor; keep the row so it can be repaired. */
    fun markBroken(videoId: String) {
        val entry = _entries.value[videoId] ?: return
        entry.path?.let { File(it).delete() }
        updateWorker(videoId, entry.workId) { it.copy(state = DownloadState.FAILED, path = null, progress = 0f,
            error = "Saved audio is incomplete. Tap to download again.") }
    }

    fun put(videoId: String, t: (DownloadEntry?) -> DownloadEntry) {
        _entries.update { entries -> entries + (videoId to t(entries[videoId])) }
    }

    internal fun directory(): File = dir ?: error("Downloads not initialised")

    private fun workName(videoId: String) = "download:$videoId"
    private const val TAG = "downloads"
}

/** Fetches one track's audio (and cover) into app storage, with progress. */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    /** Asked for by Android before an expedited job starts on versions before 12. */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(Downloads.entries.value[inputData.getString(KEY_ID)]?.song?.title ?: "Song", 0f)

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val entry = Downloads.entries.value[id] ?: return Result.failure()
        if (entry.workId != null && entry.workId != this.id.toString()) return Result.success()
        runCatching { setForeground(foregroundInfo(entry.song.title, 0f)) }
        fun publish(transform: (DownloadEntry) -> DownloadEntry) {
            if (!Downloads.updateWorker(id, entry.workId, transform)) throw CancellationException("Download removed or replaced")
        }
        val jobDir = File(Downloads.directory(), "$id.${this.id}")
        publish { it.copy(state = DownloadState.DOWNLOADING, error = null) }
        var addon: com.opentune.data.addons.AddonStream? = null
        var fromSaavn = false
        return try {
            val maxKbps = AppSettings.library.value.downloadQuality.maxKbps
            val exporting = AppSettings.library.value.exportDownloads && DownloadExport.supported
            val dir = jobDir.apply { mkdirs() }
            // A playlist queues every song at once; a few at a time each get the
            // bandwidth, and YouTube isn't asked for dozens of streams together.
            val target = songsAtOnce.withPermit {
                // Lossless: every add-on that allows downloads and has this recording verified,
                // best file first; one that won't hand its file over is passed over for the
                // next, and with none that works the song comes from the normal stream.
                if (maxKbps == Int.MAX_VALUE) {
                    val candidates = com.opentune.data.addons.AddonResolver
                        .resolveAll(entry.song.toSong(), parseDuration(entry.song.durationText), download = true)
                        .filter { it.extension != null }
                    for (candidate in candidates) {
                        val file = File(dir, "$id.${candidate.extension}")
                        try {
                            fetch(candidate.url, File(dir, "$id.part"), file, entry.song.title, candidate) { p -> publish { it.copy(progress = p) } }
                            addon = candidate
                            return@withPermit file
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            com.opentune.data.addons.AddonResolver.failed(candidate.addonId, id)
                            Log.w(TAG, "${candidate.provider} wouldn't hand over $id (${e.javaClass.simpleName}: ${e.message}); trying the next source")
                            publish { it.copy(progress = 0f) }
                        }
                    }
                }
                // No lossless file: JioSaavn's 320 kbps next, before YouTube's own stream,
                // when JioSaavn is on and the download quality reaches that far.
                if (maxKbps >= SAAVN_KBPS && AppSettings.playback.value.jioSaavnQuality) {
                    val song = entry.song.toSong()
                    val duration = com.opentune.data.lossless.CatalogMetadata.duration(song, parseDuration(entry.song.durationText))
                    val url = try {
                        if (duration > 0) com.opentune.data.lossless.JioSaavnSource.resolve(song, duration) else null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "JioSaavn lookup for $id failed (${e.javaClass.simpleName}: ${e.message}); using YouTube")
                        null
                    }
                    if (url != null) {
                        val file = File(dir, "$id.m4a")
                        try {
                            fetch(url, File(dir, "$id.part"), file, entry.song.title) { p -> publish { it.copy(progress = p) } }
                            fromSaavn = true
                            return@withPermit file
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "JioSaavn wouldn't hand over $id (${e.javaClass.simpleName}: ${e.message}); using YouTube")
                            publish { it.copy(progress = 0f) }
                        }
                    }
                }
                // Last, YouTube's stream. Exported copies have to be AAC in MP4: the Music folder refuses WebM.
                val stream = StreamResolver.resolveForDownload(id, maxKbps, requireM4a = exporting)
                File(dir, "$id.${stream.downloadExtension}").also { target ->
                    fetch(stream.url, File(dir, "$id.part"), target, entry.song.title) { p ->
                        publish { it.copy(progress = p) }
                    }
                }
            }
            val art = runCatching { fetchArt(entry.song.thumbnailUrl.artworkAt(PLAYER_ART_PX), File(dir, "$id.jpg")) }.getOrNull()
            publish {
                it.copy(
                    state = DownloadState.DONE, progress = 1f, path = target.path, artPath = art?.path,
                    bytes = target.length(), loudnessDb = if (addon == null && !fromSaavn) StreamResolver.loudnessDbFor(id) else null,
                    source = addon?.provider ?: if (fromSaavn) "JioSaavn · 320 kbps" else null, bits = addon?.bits, sampleRate = addon?.sampleRate,
                )
            }
            // A copy in the Music folder for other apps, when asked for and the format allows.
            if (exporting && DownloadExport.compatible(target)) {
                DownloadExport.publish(applicationContext, entry.song, target)?.let { uri ->
                    runCatching { publish { it.copy(exportUri = uri.toString()) } }
                }
            }
            // Lyrics too, so they show offline; a miss doesn't fail the download.
            runCatching {
                LyricsRepository.saveOffline(id, entry.song.title, entry.song.artist, parseDuration(entry.song.durationText))
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Download of $id failed", e)
            // A stream YouTube wouldn't hand over just then fails the same way as a
            // dropped connection: both are worth another go a little later.
            val retrying = runAttemptCount < MAX_ATTEMPTS
            publish { it.copy(state = if (retrying) DownloadState.QUEUED else DownloadState.FAILED,
                progress = 0f, error = e.message) }
            if (retrying) Result.retry() else Result.failure()
        } finally {
            val current = Downloads.entryNow(id)
            if (current == null || current.workId != entry.workId || current.state != DownloadState.DONE) {
                jobDir.deleteRecursively()
            }
        }
    }

    /** The audio, in ranges (see [RangedFetch]), then moved into place. */
    private suspend fun fetch(url: String, part: File, target: File, title: String, addon: com.opentune.data.addons.AddonStream? = null, onProgress: (Float) -> Unit) {
        fun permitted() {
            if (addon != null) {
                val current = com.opentune.data.addons.AddonStore.addons.value.firstOrNull { it.key == addon.addonId }
                check(current != null && current.manifest.downloads && com.opentune.data.addons.AddonStore.usable(current)) { "Add-on disabled or download permission changed." }
            }
        }
        permitted()
        RangedFetch.fetch(if (addon == null) Http.client else com.opentune.data.addons.AddonHttp.mediaClient, url,
            if (addon == null) StreamResolver.mediaHeadersFor(url) else emptyMap(), part,
            maxBytes = if (addon == null) Long.MAX_VALUE else 512L * 1024 * 1024) { p ->
            permitted()
            onProgress(p)
            runCatching { setForegroundAsync(foregroundInfo(title, p)) }
        }
        DownloadAudio.validate(part)
        if (!part.renameTo(target)) throw IOException("Couldn't save the file")
    }

    private fun fetchArt(url: String?, file: File): File? {
        url ?: return null
        Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            file.outputStream().use { out -> r.body?.byteStream()?.copyTo(out) }
        }
        return file
    }

    private fun foregroundInfo(title: String, progress: Float): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading")
            .setContentText(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, (progress * 100).toInt(), progress <= 0f)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    /** "3:45" or "1:02:03" in milliseconds, or 0. */
    private fun parseDuration(text: String?): Long =
        text?.split(':')?.mapNotNull { it.trim().toLongOrNull() }?.takeIf { it.isNotEmpty() }
            ?.fold(0L) { acc, part -> acc * 60 + part }?.times(1000) ?: 0L

    companion object {
        const val KEY_ID = "videoId"
        private const val TAG = "DownloadWorker"
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_ID = 4120
        private const val MAX_ATTEMPTS = 3
        /** JioSaavn's quality: a download quality below it goes straight to YouTube. */
        private const val SAAVN_KBPS = 320
        /** Songs downloading at once, across the whole queue. */
        private val songsAtOnce = Semaphore(3)
    }
}

/**
 * A file fetched in [CHUNK]-sized ranges, a few at a time. One open-ended
 * request is slowed by googlevideo to about the speed the song plays at;
 * ranges the size the player asks for come at the connection's full speed.
 * Stream URLs are probed with a range this size (see [StreamResolver]), so
 * they serve them.
 */
internal object RangedFetch {
    /** The range the player fetches with. */
    const val CHUNK = 2L * 1024 * 1024
    private const val PARALLEL = 3
    private const val ATTEMPTS = 3

    suspend fun fetch(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>,
        part: File,
        parallel: Int = PARALLEL,
        maxBytes: Long = Long.MAX_VALUE,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        // Ranges land out of order, so a partial file isn't a prefix to resume from.
        part.delete()
        val total = url.toHttpUrlOrNull()?.queryParameter("clen")?.toLongOrNull()?.takeIf { it > 0 }
            ?: sizeFrom(client, url, headers)
            // A server that doesn't serve ranges (many add-ons' file hosts) sends the file whole.
            ?: return@withContext fetchWhole(client, url, headers, part, maxBytes, onProgress)
        require(total in 1..maxBytes) { "Audio file exceeds the download size limit." }
        val done = AtomicLong(0)
        val reported = AtomicLong(0)
        RandomAccessFile(part, "rw").use { file ->
            file.setLength(total)
            val starts = (0 until total step CHUNK).toList()
            val next = AtomicInteger(0)
            coroutineScope {
                repeat(minOf(parallel, starts.size)) {
                    launch {
                        while (true) {
                            val start = starts.getOrNull(next.getAndIncrement()) ?: break
                            val end = minOf(start + CHUNK, total) - 1
                            fetchRange(client, url, headers, start, end, total, file) { n ->
                                val now = done.addAndGet(n)
                                val last = reported.get()
                                if (now - last > 256 * 1024 && reported.compareAndSet(last, now)) onProgress(now.toFloat() / total)
                            }
                        }
                    }
                }
            }
        }
        if (part.length() != total) throw IOException("Download came out the wrong size")
    }

    /**
     * The file's size, from the total in a one-byte range's Content-Range;
     * null when the server doesn't serve ranges (it answers with the whole
     * file, or without a total).
     */
    private fun sizeFrom(client: OkHttpClient, url: String, headers: Map<String, String>): Long? {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
            header("Accept-Encoding", "identity")
            header("Range", "bytes=0-0")
        }.build()
        client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Server answered ${r.code}")
            if (r.code != 206) return null
            return r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()?.takeIf { it > 0 }
        }
    }

    /** The whole file in one request, from the start, tried a few times. */
    private suspend fun fetchWhole(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>,
        part: File,
        maxBytes: Long,
        onProgress: (Float) -> Unit,
    ) {
        var attempt = 0
        while (true) {
            try {
                runInterruptible {
                    val request = Request.Builder().url(url).apply {
                        headers.forEach { (k, v) -> header(k, v) }
                        header("Accept-Encoding", "identity")
                    }.build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("Server answered ${response.code}")
                        val type = response.header("Content-Type").orEmpty().lowercase()
                        if (type.contains("text/") || type.contains("json") || type.contains("html")) {
                            throw IOException("Server returned a page instead of audio")
                        }
                        val body = response.body ?: throw IOException("Empty response")
                        val length = body.contentLength().takeIf { it > 0 }
                        if (length != null && length > maxBytes) throw IOException("Audio file exceeds the download size limit.")
                        var got = 0L
                        var reported = 0L
                        part.outputStream().buffered().use { out ->
                            val input = body.byteStream()
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                got += n
                                if (got > maxBytes) throw IOException("Audio file exceeds the download size limit.")
                                out.write(buf, 0, n)
                                if (length != null && got - reported > 256 * 1024) { reported = got; onProgress(got.toFloat() / length) }
                            }
                        }
                        if (got == 0L || (length != null && got != length)) throw IOException("Download came out the wrong size")
                    }
                }
                return
            } catch (e: IOException) {
                part.delete()
                if (++attempt >= ATTEMPTS) throw e
                delay(500L * attempt)
            }
        }
    }

    /** Bytes [start]..[end] into [file] at their place, tried a few times. */
    private suspend fun fetchRange(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>,
        start: Long,
        end: Long,
        total: Long,
        file: RandomAccessFile,
        onBytes: (Long) -> Unit,
    ) {
        var attempt = 0
        while (true) {
            var got = 0L
            try {
                runInterruptible {
                    val request = Request.Builder().url(url).apply {
                        headers.forEach { (k, v) -> header(k, v) }
                        header("Accept-Encoding", "identity")
            header("Range", "bytes=$start-$end")
                    }.build()
                    client.newCall(request).execute().use { response ->
                        if (response.code != 206) throw IOException("Server answered ${response.code}")
                        if (response.header("Content-Range") != "bytes $start-$end/$total") {
                            throw IOException("Server returned a different audio range")
                        }
                        val type = response.header("Content-Type").orEmpty().lowercase()
                        if (type.contains("text/") || type.contains("json") || type.contains("html")) {
                            throw IOException("Server returned a page instead of audio")
                        }
                        val input = response.body?.byteStream() ?: throw IOException("Empty response")
                        val buf = ByteArray(64 * 1024)
                        var at = start
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            if (at + n > end + 1) throw IOException("Audio range is longer than requested")
                            synchronized(file) { file.seek(at); file.write(buf, 0, n) }
                            at += n
                            got += n
                            onBytes(n.toLong())
                        }
                        if (at != end + 1) throw IOException("Range ended early")
                    }
                }
                return
            } catch (e: IOException) {
                // Take back what this try counted, so progress doesn't run past the end.
                if (got > 0) onBytes(-got)
                if (++attempt >= ATTEMPTS) throw e
                delay(500L * attempt)
            }
        }
    }
}
