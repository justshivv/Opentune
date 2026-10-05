package com.opentune.data

import android.util.Log
import kotlinx.coroutines.CoroutineName
import kotlin.coroutines.CoroutineContext

/**
 * Logging for the stream-resolution path, tagged by the track it is about.
 *
 * [StreamResolver] resolves several tracks concurrently (the one playing, the
 * one queued next), so a plain `Log.d` interleaves their lines. [about] gives
 * each walk a [CoroutineName] carrying the video id, and every call here
 * reads it back off the current coroutine so a line always says which track
 * it belongs to, without every call site having to pass one explicitly.
 */
object TrackLog {
    fun about(videoId: String): CoroutineContext = CoroutineName(videoId)

    fun d(tag: String, message: String) {
        Log.d(tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
    }

    fun w(tag: String, message: String, error: Throwable) {
        Log.w(tag, message, error)
    }

    fun e(tag: String, message: String) {
        Log.e(tag, message)
    }
}
