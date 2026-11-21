package com.opentune.ui.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import com.opentune.data.DebugLog as Log
import com.opentune.data.Http
import com.opentune.data.covers.AppleMotionArt
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * An album's animated cover as a lyric video's background: a few seconds of Apple's looping
 * clip, decoded to small, blurred frames. Blurred to a wash, a frame needs only a few dozen
 * pixels across, so the whole loop fits in a few megabytes and draws at any size.
 */
internal class MotionFrames(private val frames: List<Bitmap>, private val fps: Float) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** Draws the frame at [ms], blended into the next so the slow loop moves smoothly, over [dst]. */
    fun draw(c: Canvas, ms: Long, dst: RectF) {
        if (frames.isEmpty()) return
        val at = ms / 1000f * fps
        val i = at.toInt() % frames.size
        val next = (i + 1) % frames.size
        val blend = at - at.toInt()
        paint.alpha = 255
        c.drawBitmap(frames[i], null, dst, paint)
        paint.alpha = (blend * 255).toInt()
        c.drawBitmap(frames[next], null, dst, paint)
        paint.alpha = 255
    }

    companion object {
        private const val TAG = "MotionFrames"
        /** Pixels across a kept frame. */
        private const val SIDE = 64
        /** Frames kept per second of the clip; the drawing blends between them. */
        private const val KEEP_FPS = 12f
        private const val MAX_BYTES = 24L * 1024 * 1024

        /** The first [seconds] of [motion], or null when it can't be read or decoded here. */
        suspend fun load(motion: AppleMotionArt.Motion, cacheDir: File, seconds: Int = 10): MotionFrames? = withContext(Dispatchers.IO) {
            for (url in listOfNotNull(motion.url, motion.fallbackUrl)) {
                val file = File(cacheDir, "motion-${url.hashCode()}.mp4")
                try {
                    if (!download(url, file, seconds)) continue
                    val frames = decode(file, seconds)
                    if (frames.size >= 2) return@withContext MotionFrames(frames, KEEP_FPS)
                } catch (e: Exception) {
                    ensureActive()
                    Log.w(TAG, "animated cover didn't load", e)
                } finally {
                    file.delete()
                }
            }
            null
        }

        /** A Spotify Canvas (a short MP4 loop) as frames; null when it can't be read. */
        suspend fun loadVideo(url: String, cacheDir: File, seconds: Int = 10): MotionFrames? = withContext(Dispatchers.IO) {
            val address = url.toHttpUrlOrNull()?.takeIf { it.isHttps } ?: return@withContext null
            val file = File(cacheDir, "canvas-${url.hashCode()}.mp4")
            try {
                Http.client.newCall(Request.Builder().url(address).build()).execute().use { r ->
                    check(r.isSuccessful) { "HTTP ${r.code}" }
                    val body = r.body!!
                    check(body.contentLength() <= MAX_BYTES) { "too large" }
                    file.outputStream().use { o -> body.byteStream().use { it.copyTo(o) } }
                }
                decode(file, seconds).takeIf { it.size >= 2 }?.let { MotionFrames(it, KEEP_FPS) }
            } catch (e: Exception) {
                ensureActive()
                Log.w(TAG, "Canvas didn't load", e)
                null
            } finally {
                file.delete()
            }
        }

        /** Only Apple's own https hosts, as [AppleMotionArt] takes them. */
        private fun allowed(url: HttpUrl?) = url != null && url.isHttps && (url.host == "apple.com" || url.host.endsWith(".apple.com"))

        private fun text(url: HttpUrl): String =
            Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                check(r.isSuccessful) { "HTTP ${r.code}" }
                r.body!!.string().also { check(it.length < 1_000_000) }
            }

        /**
         * The HLS stream's video, in one playable file: the H.264 rendition with the fewest bits
         * (HEVC where nothing else is offered and this phone decodes it), its init segment and
         * enough segments for [seconds].
         */
        private fun download(master: String, out: File, seconds: Int): Boolean {
            val masterUrl = master.toHttpUrlOrNull()?.takeIf(::allowed) ?: return false
            var playlistUrl = masterUrl
            var playlist = text(masterUrl)
            if ("#EXT-X-STREAM-INF" in playlist) {
                data class Variant(val url: HttpUrl, val bandwidth: Long, val codecs: String)
                val lines = playlist.lines()
                val variants = lines.mapIndexedNotNull { i, line ->
                    if (!line.startsWith("#EXT-X-STREAM-INF")) return@mapIndexedNotNull null
                    val uri = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return@mapIndexedNotNull null
                    val url = masterUrl.resolve(uri.trim())?.takeIf(::allowed) ?: return@mapIndexedNotNull null
                    val bandwidth = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: Long.MAX_VALUE
                    val codecs = Regex("CODECS=\"([^\"]*)\"").find(line)?.groupValues?.get(1).orEmpty()
                    Variant(url, bandwidth, codecs)
                }
                val avc = variants.filter { it.codecs.startsWith("avc1") || it.codecs.isEmpty() }
                val hevc = variants.filter { (it.codecs.startsWith("hvc1") || it.codecs.startsWith("hev1")) && decodes(MediaFormat.MIMETYPE_VIDEO_HEVC) }
                val pick = (avc.ifEmpty { hevc }).minByOrNull { it.bandwidth } ?: return false
                playlistUrl = pick.url
                playlist = text(pick.url)
            }
            // Encrypted streams aren't for this.
            if (Regex("#EXT-X-KEY:.*METHOD=(?!NONE)").containsMatchIn(playlist)) return false
            val parts = mutableListOf<Pair<HttpUrl, String?>>()
            Regex("#EXT-X-MAP:URI=\"([^\"]+)\"(?:,BYTERANGE=\"(\\d+)@(\\d+)\")?").find(playlist)?.let { m ->
                val url = playlistUrl.resolve(m.groupValues[1])?.takeIf(::allowed) ?: return false
                parts += url to m.groupValues[2].takeIf { it.isNotEmpty() }?.let { "bytes=${m.groupValues[3]}-${m.groupValues[3].toLong() + it.toLong() - 1}" }
            }
            var covered = 0.0
            var range: String? = null
            for (line in playlist.lines()) {
                when {
                    line.startsWith("#EXTINF:") -> covered += line.removePrefix("#EXTINF:").substringBefore(',').toDoubleOrNull() ?: 0.0
                    line.startsWith("#EXT-X-BYTERANGE:") -> {
                        val (len, at) = line.removePrefix("#EXT-X-BYTERANGE:").split('@').let { it[0].toLong() to it.getOrNull(1)?.toLongOrNull() }
                        range = at?.let { "bytes=$it-${it + len - 1}" }
                    }
                    line.isNotBlank() && !line.startsWith("#") -> {
                        val url = playlistUrl.resolve(line.trim())?.takeIf(::allowed) ?: return false
                        parts += url to range
                        range = null
                        if (covered >= seconds) break
                    }
                }
            }
            if (parts.size < 2) return false
            var written = 0L
            out.outputStream().use { o ->
                for ((url, r) in parts) {
                    val request = Request.Builder().url(url).apply { r?.let { header("Range", it) } }.build()
                    Http.client.newCall(request).execute().use { resp ->
                        check(resp.isSuccessful) { "HTTP ${resp.code}" }
                        resp.body!!.byteStream().use { input ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                written += n
                                check(written <= MAX_BYTES) { "too large" }
                                o.write(buf, 0, n)
                            }
                        }
                    }
                }
            }
            return true
        }

        private fun decodes(mime: String): Boolean =
            runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(MediaFormat.createVideoFormat(mime, 640, 640)) != null }.getOrDefault(false)

        /** Decodes [file] into small blurred frames, [KEEP_FPS] a second for [seconds]. */
        private fun decode(file: File, seconds: Int): List<Bitmap> {
            val extractor = MediaExtractor()
            val out = mutableListOf<Bitmap>()
            var codec: MediaCodec? = null
            try {
                extractor.setDataSource(file.absolutePath)
                val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true } ?: return out
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                val c = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                codec = c
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                c.configure(format, null, null, 0)
                c.start()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var nextUs = 0L
                val stepUs = (1_000_000 / KEEP_FPS).toLong()
                val endUs = seconds * 1_000_000L
                while (true) {
                    if (!inputDone) {
                        val i = c.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val n = extractor.readSampleData(c.getInputBuffer(i)!!, 0)
                            if (n < 0 || extractor.sampleTime > endUs) {
                                c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                c.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val o = c.dequeueOutputBuffer(info, 10_000)
                    if (o >= 0) {
                        if (info.size > 0 && info.presentationTimeUs >= nextUs) {
                            c.getOutputImage(o)?.use { image -> tiny(image)?.let { out += ShareCard.blurred(it, SIDE, 3) } }
                            nextUs = info.presentationTimeUs + stepUs
                        }
                        c.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                extractor.release()
            }
            return out
        }

        /** The middle square of a decoded frame, read point by point into [SIDE] by [SIDE] pixels. */
        private fun tiny(image: android.media.Image): Bitmap? {
            if (image.format != ImageFormat.YUV_420_888) return null
            val crop = image.cropRect
            val side = minOf(crop.width(), crop.height())
            val x0 = crop.left + (crop.width() - side) / 2
            val y0 = crop.top + (crop.height() - side) / 2
            val (yp, up, vp) = image.planes
            val px = IntArray(SIDE * SIDE)
            for (j in 0 until SIDE) {
                val y = y0 + j * side / SIDE
                for (i in 0 until SIDE) {
                    val x = x0 + i * side / SIDE
                    val yy = (yp.buffer.get(y * yp.rowStride + x * yp.pixelStride).toInt() and 0xFF) - 16
                    val ci = (y / 2) * up.rowStride + (x / 2) * up.pixelStride
                    val u = (up.buffer.get(ci).toInt() and 0xFF) - 128
                    val v = (vp.buffer.get((y / 2) * vp.rowStride + (x / 2) * vp.pixelStride).toInt() and 0xFF) - 128
                    val r = (1.164f * yy + 1.596f * v).toInt().coerceIn(0, 255)
                    val g = (1.164f * yy - 0.392f * u - 0.813f * v).toInt().coerceIn(0, 255)
                    val b = (1.164f * yy + 2.017f * u).toInt().coerceIn(0, 255)
                    px[j * SIDE + i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            return Bitmap.createBitmap(px, SIDE, SIDE, Bitmap.Config.ARGB_8888)
        }
    }
}
