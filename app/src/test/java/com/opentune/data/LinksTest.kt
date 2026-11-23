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
        // A playlist may be a podcast show: YouTube says which, and the playlist page is the fallback.
        assertEquals(
            LinkTarget.Resolve("https://music.youtube.com/playlist?list=PL123", LinkTarget.Browse("VLPL123")),
            Links.parse("https://music.youtube.com/playlist?list=PL123"),
        )
        assertEquals(LinkTarget.Browse("MPREb_abc"), Links.parse("https://music.youtube.com/browse/MPREb_abc"))
        assertEquals(LinkTarget.Browse("UCabc"), Links.parse("https://music.youtube.com/channel/UCabc"))
    }

    @Test fun channelHandlesAreResolvedByYouTube() {
        assertEquals(LinkTarget.Resolve("https://www.youtube.com/@tinypod"), Links.parse("https://youtube.com/@tinypod/podcasts?si=x"))
        assertEquals(LinkTarget.Resolve("https://www.youtube.com/@tinypod"), Links.parse("@tinypod"))
        assertEquals(LinkTarget.Resolve("https://www.youtube.com/c/someshow"), Links.parse("Check this out https://www.youtube.com/c/someshow"))
    }

    @Test fun searchOpensOnlyLinksAndHandles() {
        assertEquals(LinkTarget.Resolve("https://www.youtube.com/@tinypod"), com.opentune.ui.search.pastedLink(" @tinypod "))
        assertEquals(LinkTarget.Song("dQw4w9WgXcQ"), com.opentune.ui.search.pastedLink("https://youtu.be/dQw4w9WgXcQ"))
        // Plain searches stay searches, even ones with an @ in them.
        assertNull(com.opentune.ui.search.pastedLink("lex fridman podcast"))
        assertNull(com.opentune.ui.search.pastedLink("songs @ night"))
        assertNull(com.opentune.ui.search.pastedLink("https://example.com/page"))
    }

    @Test fun otherLinksAreIgnored() {
        assertNull(Links.parse("https://example.com/watch?v=dQw4w9WgXcQ"))
        assertNull(Links.parse("no link here"))
        assertNull(Links.parse("https://music.youtube.com/watch?v=<script>"))
    }
}
