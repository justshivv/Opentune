package com.opentune.ui.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeLikesTest {
    @Test fun youTubeMusicsLikedPlaylistIsKnownByEitherId() {
        assertTrue(isYouTubeLikes("VLLM"))
        assertTrue(isYouTubeLikes("LM"))
        assertFalse(isYouTubeLikes("VLPL123"))
        assertFalse(isYouTubeLikes(null))
    }
}
