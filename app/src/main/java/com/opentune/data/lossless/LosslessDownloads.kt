package com.opentune.data.lossless

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.AtomicFile
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.UUID

@Serializable
internal data class LosslessTrack(val id: String, val title: String, val artist: String,
    val artwork: String? = null, val durationMs: Long = 0) {
    fun song(duration: Long = durationMs, art: String? = artwork) = Song("lossless:$id", title, artist, art,
        durationText = duration.takeIf { it > 0 }?.let { "${it / 60000}:${(it / 1000 % 60).toString().padStart(2, '0')}" })
}
@Serializable
internal enum class LosslessState(val label: String) {
    QUEUED("Queued"), WAITING("Verification required"), REQUESTING("Requesting FLAC"),
    DOWNLOADING("Downloading"), PREPARING("Preparing audio"), VERIFYING("Checking FLAC"), DONE("Saved lossless"), FAILED("Needs attention");
    val active get() = this !in setOf(WAITING, DONE, FAILED)
}
@Serializable
internal data class LosslessEntry(val track: LosslessTrack, val workId: String,
    val state: LosslessState = LosslessState.QUEUED, val bytes: Long = 0, val total: Long = 0,
    val bits: Int = 0, val sampleRate: Int = 0, val durationMs: Long = 0,
    val error: String? = null, val addedAt: Long = System.currentTimeMillis())

/** Atomic durable transitions; only the current worker may publish progress/completion. */
internal class LosslessStore(val root: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(LosslessEntry.serializer())
    private val index = AtomicFile(File(root.apply { mkdirs() }, "index.json"))
    private val data = MutableStateFlow<Map<String, LosslessEntry>>(emptyMap())
    val entries = data.asStateFlow()
    init {
        data.value = runCatching { index.openRead().bufferedReader().use { json.decodeFromString(serializer, it.readText()) } }
            .getOrDefault(emptyList()).filter(::valid).associateBy { it.track.id }.mapValues { (_, e) ->
                if (e.state == LosslessState.DONE && (!file(e).isFile || file(e).length() <= 42))
                    e.copy(state = LosslessState.FAILED, error = "Saved file is missing. Download it again.") else e
            }
    }
    private fun valid(e: LosslessEntry) = Regex("[A-Z0-9]{10}").matches(e.track.id) &&
        runCatching { UUID.fromString(e.workId).toString() == e.workId }.getOrDefault(false)
    fun folder(e: LosslessEntry) = File(root, e.workId)
    fun file(e: LosslessEntry) = File(folder(e), "audio.flac")
    @Synchronized private fun publish(next: Map<String, LosslessEntry>, durable: Boolean = true) {
        if (durable) {
            val out = index.startWrite()
            try { out.write(json.encodeToString(serializer, next.values.toList()).toByteArray()); index.finishWrite(out) }
            catch (e: Exception) { index.failWrite(out); throw e }
        }
        data.value = next
    }
    @Synchronized fun put(e: LosslessEntry) { require(valid(e)); publish(data.value + (e.track.id to e)) }
    @Synchronized fun update(id: String, owner: String, durable: Boolean = true, change: (LosslessEntry) -> LosslessEntry): Boolean {
        val old = data.value[id]?.takeIf { it.workId == owner } ?: return false
        val next = change(old); require(next.workId == owner && next.track.id == id)
        publish(data.value + (id to next), durable); return true
    }
    @Synchronized fun remove(id: String): LosslessEntry? {
        val old = data.value[id] ?: return null; publish(data.value - id); return old
    }
}
internal object LosslessDownloads {
    internal lateinit var store: LosslessStore
    private lateinit var context: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scheduling = Mutex()
    val entries get() = store.entries
    fun isLossless(id: String) = id.startsWith("lossless:")
    fun entry(id: String) = if (::store.isInitialized) store.entries.value[id.removePrefix("lossless:")] else null
    fun fileFor(id: String) = entry(id)?.takeIf { it.state == LosslessState.DONE }?.let { store.file(it) }?.takeIf { it.isFile && it.length() > 42 }
    fun artFor(id: String) = entry(id)?.takeIf { it.state == LosslessState.DONE }?.let { File(store.folder(it), "cover.jpg") }?.takeIf { it.isFile }
    fun song(e: LosslessEntry) = e.track.song(e.durationMs, artFor(e.track.id)?.toURI()?.toString() ?: e.track.artwork)
    fun init(context: Context) {
        this.context = context.applicationContext
        store = LosslessStore(File(context.filesDir, "lossless-downloads"))
        scope.launch {
            // WorkManager restarts active jobs. Only repair work that was never scheduled or has finished.
            entries.value.values.filter { it.state.active }.forEach { e ->
                runCatching {
                    val info = runInterruptible { WorkManager.getInstance(context).getWorkInfoById(UUID.fromString(e.workId)).get() }
                    if (info == null || info.state.isFinished) store.update(e.track.id, e.workId) {
                        it.copy(state = LosslessState.FAILED, error = "Download was interrupted. Tap Retry to start again.")
                    }
                }
            }
            ExtensionSessions.status.collect { status ->
                if (status == "Connected") entries.value.values.filter { it.state == LosslessState.WAITING }.forEach {
                    runCatching { enqueue(it.track) }
                }
            }
        }
    }
    suspend fun enqueue(track: LosslessTrack) = withContext(Dispatchers.IO) { scheduling.withLock {
        val old = entries.value[track.id]
        if (old?.state == LosslessState.DONE || old?.state?.active == true) return@withLock
        val request = OneTimeWorkRequestBuilder<LosslessWorker>().setInputData(workDataOf("trackId" to track.id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (AppSettings.library.value.downloadWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .addTag("lossless-downloads").build()
        val next = LosslessEntry(track, request.id.toString(), addedAt = old?.addedAt ?: System.currentTimeMillis())
        store.put(next)
        try {
            runInterruptible { WorkManager.getInstance(context).enqueueUniqueWork("lossless:${track.id}", ExistingWorkPolicy.REPLACE, request).result.get() }
        } catch (e: Exception) {
            store.update(track.id, next.workId) { it.copy(state = LosslessState.FAILED, error = "Could not queue download. Try again.") }; throw e
        }
        old?.let { store.folder(it).deleteRecursively() }
    } }
    suspend fun remove(id: String) = withContext(Dispatchers.IO) { scheduling.withLock {
        val old = store.remove(id) ?: return@withLock
        WorkManager.getInstance(context).cancelWorkById(UUID.fromString(old.workId)); store.folder(old).deleteRecursively()
    } }
    suspend fun export(id: String, target: Uri) = withContext(Dispatchers.IO) {
        val file = fileFor(id) ?: throw IOException("Saved file is missing")
        val out = context.contentResolver.openOutputStream(target, "wt") ?: throw IOException("Destination unavailable")
        out.use { dest -> file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break; dest.write(buffer, 0, n) }
        } }
    }
}
class LosslessWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val key = inputData.getString("trackId") ?: return Result.failure()
        val store = LosslessDownloads.store
        val e = store.entries.value[key]?.takeIf { it.workId == id.toString() } ?: return Result.success()
        if (e.state == LosslessState.DONE) return Result.success()
        fun update(durable: Boolean = true, change: (LosslessEntry) -> LosslessEntry) {
            if (!store.update(key, e.workId, durable, change)) throw CancellationException("Removed or replaced")
        }
        var stage = "Preparing download"
        var lastProgress = 0L
        try {
            slots.withPermit {
                setForeground(notification(e.track.title))
                if (ExtensionSessions.current() == null) {
                    update { it.copy(state = LosslessState.WAITING, error = "Connect Amazon Music to continue.") }; return@withPermit
                }
                val folder = store.folder(e).apply { mkdirs() }
                val info = AmazonAudio.downloadTrack(e.track, folder) { state, bytes, total ->
                    val oldState = store.entries.value[key]?.state
                    stage = state.label
                    val now = System.currentTimeMillis()
                    if (oldState != state || now - lastProgress > 500 || bytes == total) {
                        lastProgress = now
                        update(oldState != state) { it.copy(state = state, bytes = bytes, total = total, error = null) }
                        setForegroundAsync(notification(e.track.title, bytes, total))
                    }
                }
                currentCoroutineContext().ensureActive()
                update { it.copy(state = LosslessState.DONE, bytes = store.file(e).length(), total = store.file(e).length(),
                    bits = info.bits, sampleRate = info.sampleRate, durationMs = info.durationMs, error = null) }
            }
            return Result.success()
        } catch (cancelled: CancellationException) {
            store.update(key, e.workId) { it.copy(state = LosslessState.FAILED, error = "Download interrupted. Tap Retry to start again.") }
            throw cancelled
        } catch (error: Exception) {
            update { it.copy(state = if (ExtensionSessions.current() == null) LosslessState.WAITING else LosslessState.FAILED, error = losslessError(stage, error)) }
            return Result.failure()
        } finally {
            val current = store.entries.value[key]
            if (current?.workId != e.workId || current.state != LosslessState.DONE) store.folder(e).deleteRecursively()
        }
    }
    private fun notification(title: String, bytes: Long = 0, total: Long = 0): ForegroundInfo {
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("lossless", "Lossless downloads", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, "lossless").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Saving lossless audio").setContentText(title).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(100, if (total > 0) (100 * bytes / total).toInt().coerceIn(0, 100) else 0, total <= 0)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(id.hashCode(), n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(id.hashCode(), n)
    }
    companion object { private val slots = Semaphore(1) }
}
internal class LosslessFailure(message: String) : IOException(message)
internal fun losslessError(stage: String, error: Exception): String = when (error) {
    is LosslessHttp.HttpFailure -> "$stage: provider returned HTTP ${error.code}. Check the provider connection or try again later."
    is LosslessFailure -> error.message ?: "Could not verify FLAC."
    else -> "$stage could not finish. Check your connection and free space, then retry."
}
