package com.opentune.data.connect

import com.opentune.data.together.toHex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectGroupTest {
    /** Shared with the web player's tests, so both read each other's messages. */
    private val vectors = Json.parseToJsonElement(javaClass.getResource("/connect/vectors.json")!!.readText()).jsonObject
    private fun v(name: String) = vectors[name]!!.jsonPrimitive.content
    private val fixed = ConnectGroup(ByteArray(32) { 1 })

    @Test fun hkdfMatchesTheRfc() {
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            ConnectGroup.hkdf(ByteArray(22) { 0x0b }, "", 42).toHex(),
        )
    }

    @Test fun theVectorsHold() {
        assertEquals(v("secret"), fixed.encoded)
        assertEquals(v("tag"), fixed.tag)
        assertEquals(v("plain"), fixed.open(v("sealed")))
    }

    @Test fun sealedTextOpensOnlyInItsGroup() {
        val sealed = fixed.seal("hello")
        assertEquals("hello", fixed.open(sealed))
        assertNull(ConnectGroup.generate().open(sealed))
        assertNotEquals(sealed, fixed.seal("hello"))
    }

    @Test fun linksAndTheBareSecretParse() {
        val g = ConnectGroup.generate()
        assertEquals(g, ConnectGroup.parse(g.appLink))
        assertEquals(g, ConnectGroup.parse(g.webLink))
        assertEquals(g, ConnectGroup.parse("  ${g.encoded} "))
        assertNull(ConnectGroup.parse("https://example.com/#connect=${g.encoded}"))
        assertNull(ConnectGroup.parse(g.encoded.dropLast(1)))
        assertNull(ConnectGroup.parse("opentune://together/K7QXM2PA9DTE"))
        assertNull(ConnectGroup.parse(null))
    }
}
