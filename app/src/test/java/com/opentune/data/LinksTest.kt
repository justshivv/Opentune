package com.opentune.data

import com.opentune.ui.LinkTarget
import com.opentune.ui.Links
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinksTest {
    @Test fun songLinks() {
        assertEquals(LinkTarget.Song("dQw4w9WgXcQ"), Links.parse("https://music.youtube.com/watch?v=dQw4w9WgXcQ&si=abc"))
        assertEquals(LinkTarget.Song("dQw4w9WgXcQ"), Links.parse("https://youtu.be/dQw4w9WgXcQ?si=abc"))
        assertEquals(LinkTarget.Song("dQw4w9WgXcQ"), Links.parse("Listen: https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test fun pages() {
        assertEquals(LinkTarget.Browse("VLPL123"), Links.parse("https://music.youtube.com/playlist?list=PL123"))
        assertEquals(LinkTarget.Browse("MPREb_abc"), Links.parse("https://music.youtube.com/browse/MPREb_abc"))
        assertEquals(LinkTarget.Browse("UCabc"), Links.parse("https://music.youtube.com/channel/UCabc"))
    }

    @Test fun otherLinksAreIgnored() {
        assertNull(Links.parse("https://example.com/watch?v=dQw4w9WgXcQ"))
        assertNull(Links.parse("no link here"))
        assertNull(Links.parse("https://music.youtube.com/watch?v=<script>"))
    }
}
