package com.opentune.data.connect

import com.opentune.data.model.ShelfItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectAccountSyncTest {
    @Test fun theSecretComesBackOutOfThePlaylistPage() {
        val g = ConnectGroup.generate()
        // As a browse response carries a description: JSON-escaped, the line break as \n.
        val page = """{"header":{"description":{"runs":[{"text":${kotlinx.serialization.json.JsonPrimitive(ConnectAccountSync.description(g))}}]}}}"""
        assertEquals(g, ConnectAccountSync.secretIn(page))
        assertNull(ConnectAccountSync.secretIn("""{"header":{"description":"just a playlist"}}"""))
    }

    @Test fun onlyThePlaylistWithItsNameIsTaken() {
        val mine = ShelfItem("NepTune Connect", "Playlist", null, null, "VLPL123")
        val others = listOf(ShelfItem("OpenTune Connect fans", "", null, null, "VLPL9"), ShelfItem("Liked Music", "", null, null, "VLLM"))
        assertEquals(mine, ConnectAccountSync.pick(others + mine))
        assertNull(ConnectAccountSync.pick(others))
    }

    @Test fun aPlaylistMadeBeforeTheRenameIsStillTaken() {
        val legacy = ShelfItem("OpenTune Connect", "Playlist", null, null, "VLPL456")
        assertEquals(legacy, ConnectAccountSync.pick(listOf(legacy)))
    }
}
