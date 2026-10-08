package com.opentune.ui.share

import android.graphics.Bitmap
import com.opentune.ui.player.coverArt
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The picture sent with a shared song, to build/screenshots/share. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class ShareCardTest {
    @Test fun drawsTheCard() {
        val card = ShareCard.draw(coverArt(), "Blinding Lights", "The Weeknd", 52_000, 133_000)
        assertEquals(ShareCard.SIZE, card.width)
        assertEquals(ShareCard.SIZE, card.height)
        save("share/card", card)
        // A long name is cut short inside its pill, and no cover still makes a card.
        save("share/long-no-cover", ShareCard.draw(null, "A song with a very long name that will not fit in the pill at all", "Somebody, Somebody Else", 0, 0))
    }

    @Test fun readsSongLengths() {
        assertEquals(200_000, ShareCard.durationOf("3:20"))
        assertEquals(3_723_000, ShareCard.durationOf("1:02:03"))
        assertEquals(0, ShareCard.durationOf(null))
        assertEquals(0, ShareCard.durationOf("live"))
    }

    private fun save(name: String, b: Bitmap) {
        val file = File("build/screenshots/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
