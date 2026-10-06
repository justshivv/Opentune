package com.opentune.data.together

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportTest {
    // Official BIP-340 test vectors 0 and 1.
    @Test fun signsLikeBip340() {
        val sk0 = "0000000000000000000000000000000000000000000000000000000000000003".hexToBytes()
        assertEquals("f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9", Schnorr.publicKey(sk0).toHex())
        assertEquals(
            "e907831f80848d1069a5371b402410364bdf1c5f8307b0084c55f1ce2dca821525f66a4a85ea8b71e482a74f382d2ce5ebeee8fdb2172f477df4900d310536c0",
            Schnorr.sign(ByteArray(32), sk0, ByteArray(32)).toHex(),
        )
        val sk1 = "b7e151628aed2a6abf7158809cf4f3c762e7160f38b4da56a784d9045190cfef".hexToBytes()
        val msg = "243f6a8885a308d313198a2e03707344a4093822299f31d0082efa98ec4e6c89".hexToBytes()
        val aux = ByteArray(32).also { it[31] = 1 }
        assertEquals("dff1d77f2a671c5f36183726db2341be58feae1da2deced843240f7b502ba659", Schnorr.publicKey(sk1).toHex())
        val sig = Schnorr.sign(msg, sk1, aux)
        assertEquals(
            "6896bd60eeae296db48a229ff71dfe071bde413e6d43f917dc8dcf8c78de33418906d11ac976abccb20b091292bff4ea897efcb639ea871cfa95f6de339e4b0a",
            sig.toHex(),
        )
        assertTrue(Schnorr.verify(msg, Schnorr.publicKey(sk1), sig))
        assertFalse(Schnorr.verify(msg.copyOf().also { it[0] = 0 }, Schnorr.publicKey(sk1), sig))
    }

    @Test fun freshKeysSignAndVerify() {
        repeat(5) {
            val sk = Schnorr.newPrivateKey()
            val m = MessageDigest.getInstance("SHA-256").digest("hello $it".toByteArray())
            assertTrue(Schnorr.verify(m, Schnorr.publicKey(sk), Schnorr.sign(m, sk)))
        }
    }

    @Test fun eventIdsHashTheNip01Serialization() {
        val text = NostrEvent.serialize("ab", 1700000000, 21420, listOf(listOf("t", "x")), "a\"b\\c\nd")
        assertEquals("""[0,"ab",1700000000,21420,[["t","x"]],"a\"b\\c\nd"]""", text)
        val sk = Schnorr.newPrivateKey()
        val e = NostrEvent.signed(sk, 21420, listOf(listOf("t", "room")), "body", 1700000000)
        val id = MessageDigest.getInstance("SHA-256").digest(NostrEvent.serialize(e.pubkey, e.createdAt, e.kind, e.tags, e.content).toByteArray())
        assertEquals(id.toHex(), e.id)
        assertTrue(Schnorr.verify(id, e.pubkey.hexToBytes(), e.sig.hexToBytes()))
        assertEquals(e, NostrEvent.parse(e.toJson()))
        // Saved so a live relay can be asked whether it accepts what this code signs.
        File("build/nostr-event.json").writeText(NostrEvent.signed(sk, 21420, listOf(listOf("t", "opentune-test")), "aGk=").toJson().toString())
    }

    @Test fun roomCodesReadBackHoweverTheyAreTyped() {
        val code = RoomCode.generate()
        assertEquals(12, code.raw.length)
        assertTrue(code.raw.all { it in RoomCode.ALPHABET })
        assertEquals(code, RoomCode.parse(code.pretty))
        assertEquals(code, RoomCode.parse(code.pretty.lowercase().replace("-", " ")))
        assertEquals(code, RoomCode.parse(code.link))
        assertEquals(code, RoomCode.find("Listen with me on OpenTune! Code ${code.pretty}"))
        assertEquals(code, RoomCode.find("join: ${code.link} now"))
        assertNull(RoomCode.parse("K7QX-M2PA-9DT"))
        assertNull(RoomCode.parse("K7QX-M2PA-9DT0")) // 0 isn't in the alphabet
        assertNull(RoomCode.find("nothing here"))
    }

    @Test fun onlyTheRoomCanReadItsMessages() {
        val a = RoomCode.parse("K7QX-M2PA-9DTE")!!
        val b = RoomCode.parse("K7QX-M2PA-9DTF")!!
        assertEquals(32, a.tag.length)
        assertNotEquals(a.tag, b.tag)
        assertFalse(a.tag.contains("K7QX", ignoreCase = true))
        val sealed = a.seal("""{"t":"chat","text":"hi"}""")
        assertEquals("""{"t":"chat","text":"hi"}""", a.open(sealed))
        assertNull(b.open(sealed))
        assertNull(a.open(sealed.dropLast(4) + "AAAA"))
        assertNotEquals(sealed, a.seal("""{"t":"chat","text":"hi"}""")) // fresh IV every time
    }
}
