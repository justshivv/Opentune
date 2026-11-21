package com.opentune.ui.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import com.opentune.data.DebugLog as Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Writes a [LyricVideo] to an MP4: H.264 at 30 frames a second, and, when it can be read, the
 * song's own audio for the same seconds as AAC. Each frame is drawn on a Canvas, then handed to
 * the encoder through OpenGL, which is what lets every frame carry its exact time.
 */
internal object LyricVideoEncoder {
    private const val TAG = "LyricVideoEncoder"

    /** The song's audio for the video: AAC samples ready to mux, and their format. */
    class Audio(val format: MediaFormat, val samples: List<Pair<ByteArray, MediaCodec.BufferInfo>>)

    /**
     * Encodes [video] to [out], calling [progress] with 0 to 1 as it goes. [audio] is muxed in
     * alongside when there is one. Throws when the phone can't encode it.
     */
    suspend fun encode(video: LyricVideo, audio: Audio?, out: File, progress: (Float) -> Unit) {
        val frames = (video.lengthMs * LyricVideo.FPS / 1000).toInt()
        val (codec, width, height) = videoEncoder(video.width, video.height)
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val surface = codec.createInputSurface()
        codec.start()
        val gl = GlTarget(surface, width, height)
        val frame = Bitmap.createBitmap(video.width, video.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        val info = MediaCodec.BufferInfo()
        var videoTrack = -1
        var audioTrack = -1
        var started = false
        var audioAt = 0
        fun writeAudioUpTo(us: Long) {
            val a = audio ?: return
            while (started && audioAt < a.samples.size && a.samples[audioAt].second.presentationTimeUs <= us) {
                val (bytes, i) = a.samples[audioAt++]
                muxer.writeSampleData(audioTrack, ByteBuffer.wrap(bytes), i)
            }
        }
        fun drain(end: Boolean) {
            if (end) codec.signalEndOfInputStream()
            while (true) {
                val o = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                when {
                    o == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoTrack = muxer.addTrack(codec.outputFormat)
                        if (audio != null) audioTrack = muxer.addTrack(audio.format)
                        muxer.start()
                        started = true
                    }
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && started) {
                            buf.position(info.offset).limit(info.offset + info.size)
                            writeAudioUpTo(info.presentationTimeUs)
                            muxer.writeSampleData(videoTrack, buf, info)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }
        try {
            for (n in 0 until frames) {
                coroutineContext.ensureActive()
                val t = n * 1000L / LyricVideo.FPS
                video.draw(canvas, t)
                gl.draw(frame, n * 1_000_000_000L / LyricVideo.FPS)
                drain(end = false)
                progress((n + 1f) / frames)
            }
            drain(end = true)
            writeAudioUpTo(Long.MAX_VALUE)
        } finally {
            runCatching { codec.stop() }
            codec.release()
            gl.release()
            surface.release()
            frame.recycle()
            runCatching { if (started) muxer.stop() }
            muxer.release()
        }
    }

    /** An H.264 encoder at [width] by [height], or at 720 by 1280 where the phone won't take that. */
    private fun videoEncoder(width: Int, height: Int): Triple<MediaCodec, Int, Int> {
        var last: Exception? = null
        for ((w, h) in listOf(width to height, 720 to 1280)) {
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            try {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, if (w >= 1080) 9_000_000 else 5_000_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, LyricVideo.FPS)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return Triple(codec, w, h)
            } catch (e: Exception) {
                last = e
                codec.release()
            }
        }
        throw last ?: IllegalStateException("No video encoder")
    }

    // ---- The song's audio --------------------------------------------------------------

    /**
     * The song from [fromMs] to [toMs] (its own playback time), read from [source] (a file
     * path or an address), decoded and encoded again as AAC with a short fade at each end;
     * null when it can't be read.
     */
    suspend fun audio(source: String, fromMs: Long, toMs: Long): Audio? = runCatching {
        val pcm = decode(source, fromMs * 1000, toMs * 1000) ?: return null
        encodeAac(pcm)
    }.onFailure { Log.w(TAG, "audio not added", it) }.getOrNull()

    private class Pcm(val samples: ShortArray, val rate: Int)

    /** Stereo 16-bit samples of [source] from [fromUs] to [toUs]. */
    private suspend fun decode(source: String, fromUs: Long, toUs: Long): Pcm? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(source)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val c = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(format, null, null, 0)
            c.start()
            extractor.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val out = java.io.ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (true) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = extractor.readSampleData(c.getInputBuffer(i)!!, 0)
                        if (n < 0 || extractor.sampleTime > toUs + 200_000) {
                            c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rate = c.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = c.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (o >= 0) {
                    if (info.size > 0) {
                        val buf = c.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buf.position(info.offset).limit(info.offset + info.size)
                        val shorts = buf.asShortBuffer()
                        val frames = shorts.remaining() / channels
                        // Only the frames inside the part asked for, as stereo.
                        val firstUs = info.presentationTimeUs
                        for (f in 0 until frames) {
                            val us = firstUs + f * 1_000_000L / rate
                            if (us < fromUs) continue
                            if (us >= toUs) break
                            val l = shorts.get(f * channels)
                            val r = if (channels > 1) shorts.get(f * channels + 1) else l
                            out.write(l.toInt() and 0xFF); out.write(l.toInt() shr 8 and 0xFF)
                            out.write(r.toInt() and 0xFF); out.write(r.toInt() shr 8 and 0xFF)
                        }
                    }
                    c.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || info.presentationTimeUs > toUs) break
                }
            }
            val bytes = out.toByteArray()
            if (bytes.size < 4 * rate / 2) return null
            val samples = ShortArray(bytes.size / 2)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
            fade(samples, rate)
            return Pcm(samples, rate)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    /** In over 0.15 s and out over 0.6 s, so the part doesn't start or end on a click. */
    private fun fade(s: ShortArray, rate: Int) {
        val frames = s.size / 2
        val fadeIn = rate * 15 / 100
        val fadeOut = rate * 6 / 10
        for (f in 0 until frames) {
            val g = minOf(1f, f / fadeIn.toFloat(), (frames - f) / fadeOut.toFloat()).coerceIn(0f, 1f)
            if (g < 1f) {
                s[f * 2] = (s[f * 2] * g).toInt().toShort()
                s[f * 2 + 1] = (s[f * 2 + 1] * g).toInt().toShort()
            }
        }
    }

    private suspend fun encodeAac(pcm: Pcm): Audio {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, pcm.rate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        c.start()
        val samples = mutableListOf<Pair<ByteArray, MediaCodec.BufferInfo>>()
        var outFormat: MediaFormat? = null
        val info = MediaCodec.BufferInfo()
        var at = 0
        var inputDone = false
        try {
            while (true) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = c.getInputBuffer(i)!!.order(ByteOrder.LITTLE_ENDIAN)
                        val n = minOf(buf.capacity() / 2, pcm.samples.size - at) / 2 * 2
                        val us = (at / 2).toLong() * 1_000_000 / pcm.rate
                        if (n <= 0) {
                            c.queueInputBuffer(i, 0, 0, us, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.clear()
                            buf.asShortBuffer().put(pcm.samples, at, n)
                            c.queueInputBuffer(i, 0, n * 2, us, 0)
                            at += n
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outFormat = c.outputFormat
                } else if (o >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val buf = c.getOutputBuffer(o)!!
                        buf.position(info.offset).limit(info.offset + info.size)
                        val bytes = ByteArray(info.size).also { buf.get(it) }
                        samples += bytes to MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
                    }
                    c.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            runCatching { c.stop() }
            c.release()
        }
        return Audio(outFormat ?: error("no AAC format"), samples)
    }

    // ---- OpenGL ------------------------------------------------------------------------

    /** The encoder's input surface as an OpenGL target that each frame's bitmap is drawn to. */
    private class GlTarget(surface: Surface, private val width: Int, private val height: Int) {
        private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val context: EGLContext
        private val eglSurface: EGLSurface
        private val program: Int
        private val texture: Int
        private var uploaded = false
        private val quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            // x, y, u, v: the bitmap's top row at the top of the frame.
            put(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f))
            position(0)
        }

        init {
            val version = IntArray(2)
            EGL14.eglInitialize(display, version, 0, version, 1)
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0)
            check(count[0] > 0) { "No EGL config" }
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
            EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)
            program = program(
                "attribute vec4 pos; attribute vec2 uv; varying vec2 v; void main() { gl_Position = pos; v = uv; }",
                "precision mediump float; varying vec2 v; uniform sampler2D tex; void main() { gl_FragColor = texture2D(tex, v); }",
            )
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            texture = t[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        private fun program(vertex: String, fragment: String): Int {
            fun shader(type: Int, src: String) = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, src); GLES20.glCompileShader(it) }
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vertex))
            GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fragment))
            GLES20.glLinkProgram(p)
            return p
        }

        /** Draws [frame] stretched to the whole target, stamped [ptsNs] for the encoder. */
        fun draw(frame: Bitmap, ptsNs: Long) {
            GLES20.glViewport(0, 0, width, height)
            GLES20.glUseProgram(program)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            if (!uploaded) { GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, frame, 0); uploaded = true } else GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, frame)
            val pos = GLES20.glGetAttribLocation(program, "pos")
            val uv = GLES20.glGetAttribLocation(program, "uv")
            quad.position(0)
            GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(pos)
            quad.position(2)
            GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(uv)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            EGLExt.eglPresentationTimeANDROID(display, eglSurface, ptsNs)
            EGL14.eglSwapBuffers(display, eglSurface)
        }

        fun release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, eglSurface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }

        companion object {
            private const val EGL_RECORDABLE_ANDROID = 0x3142
        }
    }
}
