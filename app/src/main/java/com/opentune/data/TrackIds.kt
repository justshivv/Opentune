package com.opentune.data

import com.opentune.data.local.LocalMusic
import com.opentune.data.subsonic.Subsonic

/**
 * Whether a song id is a YouTube video id, as opposed to a file on this
 * phone or a song on your own server. Only YouTube songs can be resolved,
 * downloaded, upgraded, shared, followed with radio or measured for
 * loudness by YouTube.
 */
fun isYouTubeId(id: String): Boolean = !LocalMusic.isLocal(id) && !Subsonic.isSubsonic(id)
