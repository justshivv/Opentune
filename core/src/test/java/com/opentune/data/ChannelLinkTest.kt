package com.opentune.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelLinkTest {
    @Test fun channelLinksAndHandlesComeBackAsTheChannel() {
        assertEquals("https://www.youtube.com/@lexfridman", ChannelLink.channelUrl("https://www.youtube.com/@lexfridman"))
        // A tab after the name, tracking after the "?", another YouTube host: still the channel.
        assertEquals("https://www.youtube.com/@huberman", ChannelLink.channelUrl("https://youtube.com/@huberman/podcasts?si=abc"))
        assertEquals("https://www.youtube.com/@small.show", ChannelLink.channelUrl("https://m.youtube.com/@small.show/videos"))
        assertEquals("https://www.youtube.com/c/veritasium", ChannelLink.channelUrl("https://www.youtube.com/c/veritasium/featured"))
        assertEquals("https://www.youtube.com/user/someone", ChannelLink.channelUrl("http://www.youtube.com/user/someone"))
        // Typed or pasted on its own.
        assertEquals("https://www.youtube.com/@tinypod", ChannelLink.channelUrl("  @tinypod "))
    }

    @Test fun anythingElseIsNotAChannel() {
        assertNull(ChannelLink.channelUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(ChannelLink.channelUrl("https://evil.example/@lexfridman"))
        assertNull(ChannelLink.channelUrl("@a"))
        assertNull(ChannelLink.channelUrl("lex fridman podcast"))
        assertEquals("https://music.youtube.com/playlist?list=PLx", ChannelLink.base(" https://music.youtube.com/playlist?list=PLx "))
    }
}
