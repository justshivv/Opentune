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

    @Test fun songLinksFromAMoment() {
        assertEquals(LinkTarget.Song("dQw4w9WgXcQ", 135_000), Links.parse("https://music.youtube.com/watch?v=dQw4w9WgXcQ&t=135"))
        assertEquals(LinkTarget.Song("dQw4w9WgXcQ", 135_000), Links.parse("https://youtu.be/dQw4w9WgXcQ?t=2m15s"))
        assertEquals(LinkTarget.Song("dQw4w9WgXcQ", 3_723_000), Links.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=1h2m3s"))
        assertEquals(LinkTarget.Song("dQw4w9WgXcQ", 42_000), Links.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s"))
        assertEquals(LinkTarget.Song("dQw4w9WgXcQ"), Links.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=soon"))
    }

    @Test fun shareLinks() {
        assertEquals("https://music.youtube.com/watch?v=abc", Links.songLink("abc"))
        assertEquals("https://music.youtube.com/watch?v=abc", Links.songLink("abc", 400))
        assertEquals("https://music.youtube.com/watch?v=abc&t=135", Links.songLink("abc", 135_900))
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
