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
import com.opentune.data.library.SongRef
import com.opentune.data.model.PLAYER_ART_PX
import com.opentune.data.model.Song
import com.opentune.data.model.artworkAt
import com.opentune.data.settings.AppSettings
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
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

    @OptIn(FlowPreview::class)
    fun init(context: Context) {
        val d = File(context.filesDir, "downloads").apply { mkdirs() }
        dir = d
        val f = File(d, "index.json")
        _entries.value = runCatching { json.decodeFromString(serializer, f.readText()) }.getOrDefault(emptyMap())
            // A download interrupted by the process dying is picked up again by
            // WorkManager; until then it is simply queued.
            .mapValues { (_, e) -> if (e.state == DownloadState.DOWNLOADING) e.copy(state = DownloadState.QUEUED) else e }
        scope.launch {
            _entries.drop(1).debounce(500).collect { map ->
                runCatching {
                    val tmp = File(d, "index.json.tmp")
                    tmp.writeText(json.encodeToString(serializer, map))
                    tmp.renameTo(f)
                }
            }
        }
    }

    /** The finished file for [videoId], if it's been downloaded and is still there. */
    fun fileFor(videoId: String): File? {
        val e = _entries.value[videoId] ?: return null
        if (e.state != DownloadState.DONE) return null
        return e.path?.let(::File)?.takeIf { it.exists() }
    }

    fun artFor(videoId: String): File? =
        _entries.value[videoId]?.takeIf { it.state == DownloadState.DONE }?.artPath?.let(::File)?.takeIf { it.exists() }

    fun loudnessFor(videoId: String): Double? = _entries.value[videoId]?.loudnessDb

    fun enqueue(context: Context, song: Song) {
        if (song.videoId.startsWith("local:")) return
        val existing = _entries.value[song.videoId]
        if (existing?.state == DownloadState.DONE || existing?.state == DownloadState.DOWNLOADING) return
        put(song.videoId) { DownloadEntry(SongRef.of(song)) }
        val wifiOnly = AppSettings.library.value.downloadWifiOnly
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_ID to song.videoId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(song.videoId), ExistingWorkPolicy.KEEP, request)
    }

    fun remove(context: Context, videoId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(videoId))
        _entries.value[videoId]?.let { e ->
            e.path?.let { File(it).delete() }
            e.artPath?.let { File(it).delete() }
            File(dir, "$videoId.part").delete()
        }
        _entries.value = _entries.value - videoId
    }

    fun retry(context: Context, videoId: String) {
        val e = _entries.value[videoId] ?: return
        _entries.value = _entries.value - videoId
        enqueue(context, e.song.toSong())
    }

    internal fun put(videoId: String, t: (DownloadEntry?) -> DownloadEntry) {
        _entries.value = _entries.value + (videoId to t(_entries.value[videoId]))
    }

    internal fun directory(): File = dir ?: error("Downloads not initialised")

    private fun workName(videoId: String) = "download:$videoId"
    private const val TAG = "downloads"
}

/** Fetches one track's audio (and cover) into app storage, with progress. */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val entry = Downloads.entries.value[id] ?: return Result.failure()
        runCatching { setForeground(foregroundInfo(entry.song.title, 0f)) }
        Downloads.put(id) { (it ?: entry).copy(state = DownloadState.DOWNLOADING, error = null) }
        return try {
            val maxKbps = AppSettings.library.value.downloadQuality.maxKbps
            val stream = StreamResolver.resolveForDownload(id, maxKbps)
            val dir = Downloads.directory()
            val target = File(dir, "$id.${stream.downloadExtension}")
            fetch(stream.url, File(dir, "$id.part"), target, entry.song.title) { p ->
                Downloads.put(id) { (it ?: entry).copy(progress = p) }
            }
            val art = runCatching { fetchArt(entry.song.thumbnailUrl.artworkAt(PLAYER_ART_PX), File(dir, "$id.jpg")) }.getOrNull()
            Downloads.put(id) {
                (it ?: entry).copy(
                    state = DownloadState.DONE, progress = 1f, path = target.path, artPath = art?.path,
                    bytes = target.length(), loudnessDb = StreamResolver.loudnessDbFor(id),
                )
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Download of $id failed", e)
            Downloads.put(id) { (it ?: entry).copy(state = DownloadState.FAILED, error = e.message) }
            if (runAttemptCount < MAX_ATTEMPTS && e is IOException) Result.retry() else Result.failure()
        }
    }

    /** Resumes a partial file with a Range request, then moves it into place. */
    private suspend fun fetch(url: String, part: File, target: File, title: String, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val have = if (part.exists()) part.length() else 0L
        val request = Request.Builder().url(url).apply {
            StreamResolver.mediaHeadersFor(url).forEach { (k, v) -> header(k, v) }
            if (have > 0) header("Range", "bytes=$have-")
        }.build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Server answered ${response.code}")
            val body = response.body ?: throw IOException("Empty response")
            val resumed = response.code == 206
            val total = (if (resumed) have else 0L) + body.contentLength()
            var done = if (resumed) have else 0L
            var lastReport = 0L
            part.outputStream().let { if (resumed) java.io.FileOutputStream(part, true) else it }.use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0 && done - lastReport > 256 * 1024) {
                            lastReport = done
                            val p = done.toFloat() / total
                            onProgress(p)
                            runCatching { setForeground(foregroundInfo(title, p)) }
                        }
                    }
                }
            }
        }
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

    companion object {
        const val KEY_ID = "videoId"
        private const val TAG = "DownloadWorker"
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_ID = 4120
        private const val MAX_ATTEMPTS = 3
    }
}
