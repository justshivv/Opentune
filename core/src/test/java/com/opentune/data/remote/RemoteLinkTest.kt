package com.opentune.data.remote

import com.opentune.data.account.PairingCrypto
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** A phone and a TV talking over real sockets on this machine: pairing, control, and what must be refused. */
class RemoteLinkTest {
    private val phones = PairedPhones(MemoryStorage())
    private val heard = CopyOnWriteArrayList<RemoteMessage>()
    private val controllers = CopyOnWriteArrayList<List<String>>()
    private lateinit var host: RemoteHost
    private val clients = ArrayList<RemoteClient>()

    @Before fun start() {
        HomeNetwork.allowLoopback = true
        host = RemoteHost(phones, { "Living room" }, { heard += it }, { controllers += it }, address = { "127.0.0.1" }, port = 0)
    }

    @After fun stop() {
        clients.forEach { it.close() }
        host.close()
        HomeNetwork.allowLoopback = false
    }

    private fun client(name: String = "Test phone") = RemoteClient { name }.also { clients += it }

    private fun pairedTv(): PairedTv = client().pair(host.createInvite())

    private fun connected(c: RemoteClient, tv: PairedTv) {
        c.connect(tv, listOf("127.0.0.1" to host.port))
        await("connected") { c.state.value is RemoteClient.State.Connected }
    }

    private fun await(what: String, within: Long = 5_000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + within
        while (System.currentTimeMillis() < end) { if (check()) return; Thread.sleep(10) }
        fail("timed out waiting for $what")
    }

    @Test fun pairsOnceThenControlsTheTv() {
        val c = client("Shivam's phone")
        val tv = c.pair(host.createInvite())
        assertEquals("Living room", tv.name)
        assertEquals(phones.tvId, tv.tvId)
        assertEquals(1, phones.phones.value.size)
        assertEquals("Shivam's phone", phones.phones.value.single().name)

        connected(c, tv)
        assertEquals(RemoteClient.State.Connected("Living room"), c.state.value)
        await("controller listed") { controllers.lastOrNull() == listOf("Shivam's phone") }

        c.send(RemoteMessage.Move(12f, -3.5f))
        c.send(RemoteMessage.Click())
        c.send(RemoteMessage.Key(RemoteKey.PLAY_PAUSE))
        c.send(RemoteMessage.Text("pink floyd"))
        await("four messages") { heard.size == 4 }
        assertEquals(
            listOf(RemoteMessage.Move(12f, -3.5f), RemoteMessage.Click(), RemoteMessage.Key(RemoteKey.PLAY_PAUSE), RemoteMessage.Text("pink floyd")),
            heard.toList(),
        )
    }

    @Test fun theTvTellsThePhoneWhatIsPlaying() {
        val c = client()
        connected(c, pairedTv())
        val now = RemoteMessage.Now("Echoes", "Pink Floyd", "https://x/y.jpg", playing = true, positionMs = 12_000, durationMs = 1_400_000, volume = 40)
        host.push(now)
        await("now playing") { c.now.value == now }
    }

    @Test fun aCodeWorksOnlyOnce() {
        val invite = host.createInvite()
        client().pair(invite)
        try { client().pair(invite); fail("a second pairing with the same code") } catch (e: java.io.IOException) { /* refused */ }
        assertEquals(1, phones.phones.value.size)
    }

    @Test fun aNewCodeReplacesTheOldOne() {
        val old = host.createInvite()
        host.createInvite()
        try { client().pair(old); fail("the replaced code still worked") } catch (e: java.io.IOException) { }
        assertEquals(0, phones.phones.value.size)
    }

    @Test fun aRemovedPhoneIsTurnedAwayAndCutOff() {
        val c = client()
        val tv = pairedTv()
        connected(c, tv)
        val id = phones.phones.value.single().id
        phones.remove(id); host.disconnect(id)
        await("cut off") { c.state.value is RemoteClient.State.Failed }
        val again = client()
        again.connect(tv, listOf("127.0.0.1" to host.port))
        await("refused") { again.state.value is RemoteClient.State.Failed }
        assertTrue((again.state.value as RemoteClient.State.Failed).reason.contains("Pair it again"))
        assertNotEquals(true, heard.any { it is RemoteMessage.Click })
    }

    @Test fun aPhoneWithTheWrongKeyGetsNothing() {
        val tv = pairedTv().copy(key = PairingCrypto.encode(PairingCrypto.secret()))
        val c = client()
        c.connect(tv, listOf("127.0.0.1" to host.port))
        await("failed") { c.state.value is RemoteClient.State.Failed }
        // The TV hung up without hearing a word from it.
        assertTrue(heard.isEmpty())
    }

    @Test fun aPlayedBackFrameEndsTheConnection() {
        val tv = pairedTv()
        val key = PairingCrypto.decode(tv.key)
        val socket = Socket("127.0.0.1", host.port)
        val recorded = ByteArrayOutputStream()
        val tee = object : OutputStream() {
            override fun write(b: Int) { socket.getOutputStream().write(b); recorded.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) { socket.getOutputStream().write(b, off, len); recorded.write(b, off, len) }
            override fun flush() = socket.getOutputStream().flush()
        }
        socket.getOutputStream().write(RemoteHandshake.header(RemoteHandshake.CONTROL, SecureChannel.unhex(tv.id)))
        val nonce = ByteArray(RemoteHandshake.NONCE_BYTES).also { java.io.DataInputStream(socket.getInputStream()).readFully(it) }
        val channel = SecureChannel(socket.getInputStream(), tee, key, nonce, "c2s", "s2c")
        assertTrue(channel.receive() is RemoteMessage.Welcome)
        channel.send(RemoteMessage.Hello("raw"))
        val before = recorded.size()
        channel.send(RemoteMessage.Click())
        val frame = recorded.toByteArray().copyOfRange(before, recorded.size())
        await("the click") { heard.count { it is RemoteMessage.Click } == 1 }
        // The same bytes again, as someone who recorded them would send.
        socket.getOutputStream().write(frame); socket.getOutputStream().flush()
        Thread.sleep(300)
        assertEquals(1, heard.count { it is RemoteMessage.Click })
        socket.close()
    }

    @Test fun aHugeFrameIsRefusedAndTheTvCarriesOn() {
        val tv = pairedTv()
        val socket = Socket("127.0.0.1", host.port)
        socket.getOutputStream().write(RemoteHandshake.header(RemoteHandshake.CONTROL, SecureChannel.unhex(tv.id)))
        val nonce = ByteArray(RemoteHandshake.NONCE_BYTES).also { java.io.DataInputStream(socket.getInputStream()).readFully(it) }
        SecureChannel(socket.getInputStream(), socket.getOutputStream(), PairingCrypto.decode(tv.key), nonce, "c2s", "s2c").receive()
        DataOutputStream(socket.getOutputStream()).apply { writeInt(50_000_000); flush() }
        socket.close()
        val c = client()
        connected(c, tv)
        c.send(RemoteMessage.Click())
        await("click") { heard.any { it is RemoteMessage.Click } }
    }

    @Test fun garbageOnThePortIsIgnored() {
        Socket("127.0.0.1", host.port).use { it.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".toByteArray()) }
        val c = client()
        connected(c, pairedTv())
    }

    @Test fun theInviteLinkRoundTripsAndRefusesTampering() {
        val invite = host.createInvite()
        val parsed = RemoteInvite.parse(invite.link)
        assertNotNull(parsed)
        assertEquals(invite.host, parsed!!.host); assertEquals(invite.port, parsed.port); assertEquals(invite.tvId, parsed.tvId)
        assertEquals("Living room", parsed.tvName)
        assertNull(RemoteInvite.parse(invite.link.dropLast(40)))
        assertNull(RemoteInvite.parse("opentune://remote/#"))
        assertNull(RemoteInvite.parse("https://example.com"))
        // Run out, by the phone's own clock from when it read the code.
        val read = RemoteInvite.parse(invite.link, steady = 0)!!
        assertTrue(read.valid(steady = RemoteInvite.LIFETIME_MS - 1))
        assertFalse(read.valid(steady = RemoteInvite.LIFETIME_MS))
    }

    private fun inviteLink(host: String, expires: Long) =
        "opentune://remote/#" + PairingCrypto.encode(
            "1|$host|47731|abcd|${PairingCrypto.encode("TV".toByteArray())}|0011223344556677|${PairingCrypto.encode(ByteArray(32))}|$expires".toByteArray(),
        )

    @Test fun aTvClockThatIsOffDoesntMakeACodeUnreadable() {
        val phone = System.currentTimeMillis()
        // The TV a minute ahead of the phone, then five minutes behind: both fresh codes all the same.
        for (tv in listOf(phone + 60_000, phone - 300_000)) {
            val read = RemoteInvite.read(inviteLink("192.168.1.20", tv + RemoteInvite.LIFETIME_MS))
            assertTrue("$tv", read is RemoteInvite.Read.Invite)
            assertTrue((read as RemoteInvite.Read.Invite).invite.valid())
        }
    }

    @Test fun eachKindOfBadCodeIsToldApart() {
        val later = System.currentTimeMillis() + 60_000
        assertEquals(RemoteInvite.Read.OffNetwork("8.8.8.8"), RemoteInvite.read(inviteLink("8.8.8.8", later)))
        assertEquals(RemoteInvite.Read.Damaged, RemoteInvite.read(inviteLink("192.168.1.20", later).let { it.substring(0, it.length - 40) }))
        assertEquals(RemoteInvite.Read.NotAnInvite, RemoteInvite.read("opentune://pair/#abc"))
        assertEquals(RemoteInvite.Read.NotAnInvite, RemoteInvite.read("hello"))
        // Addresses a home network really hands out.
        for (host in listOf("10.1.2.3", "172.20.0.4", "100.70.1.2", "169.254.7.8", "fd00::20")) {
            assertTrue(host, RemoteInvite.read(inviteLink(host, later)) is RemoteInvite.Read.Invite)
        }
    }

    @Test fun eitherScannerKnowsTheOtherCode() {
        val signIn = com.opentune.data.account.PairingOffer.create("192.168.1.20", 40000)
        assertEquals(ScannedCode.SIGN_IN, ScannedCode.of(signIn.link))
        assertEquals(ScannedCode.SIGN_IN, ScannedCode.of("https://justshivv.github.io/Opentune/pair/#abc"))
        assertEquals(ScannedCode.WEB_SIGN_IN, ScannedCode.of("opentune://pair/web#abc"))
        assertEquals(ScannedCode.REMOTE, ScannedCode.of(host.createInvite().link))
        assertEquals(ScannedCode.OTHER_NEPTUNE, ScannedCode.of("opentune://together/ABCD"))
        assertEquals(ScannedCode.NOT_NEPTUNE, ScannedCode.of("https://example.com"))
        // The sign-in code isn't a pairing code for the remote, and the other way round.
        assertEquals(RemoteInvite.Read.NotAnInvite, RemoteInvite.read(signIn.link))
        assertTrue(runCatching { com.opentune.data.account.PairingOffer.parse(host.createInvite().link.substringAfter('#')) }.isFailure)
    }

    @Test fun theTvStopsTakingItsCodeAfterTwoMinutesByItsOwnClock() {
        var now = 0L
        val tv = RemoteHost(PairedPhones(MemoryStorage()), { "Den" }, { }, address = { "127.0.0.1" }, port = 0, steady = { now })
        try {
            val invite = RemoteInvite.parse(tv.createInvite().link)!!
            now = RemoteInvite.LIFETIME_MS
            val e = runCatching { client().pair(invite) }.exceptionOrNull() as RemoteClient.PairFailed
            assertEquals(RemoteClient.PairFailed.Reason.USED_OR_EXPIRED, e.reason)
        } finally { tv.close() }
    }

    @Test fun pairingFallsBackToWhereTheTvAnnouncedItself() {
        // The code names an address that doesn't answer; the TV announced itself at another.
        val good = host.createInvite()
        val elsewhere = RemoteInvite.parse(
            "opentune://remote/#" + PairingCrypto.encode(
                "1|10.255.255.1|${host.port}|${good.tvId}|${PairingCrypto.encode("Living room".toByteArray())}|${SecureChannel.hex(good.id)}|${PairingCrypto.encode(good.token)}|${good.expires}".toByteArray(),
            ),
        )!!
        val tv = client().pair(elsewhere, also = listOf("127.0.0.1" to host.port))
        assertEquals(phones.tvId, tv.tvId)
        assertEquals("127.0.0.1", tv.host)
    }

    @Test fun nothingListeningIsToldFromNoAnswer() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val invite = RemoteInvite.parse(
            "opentune://remote/#" + PairingCrypto.encode(
                "1|127.0.0.1|$port|t|${PairingCrypto.encode("TV".toByteArray())}|0011223344556677|${PairingCrypto.encode(ByteArray(32))}|1".toByteArray(),
            ),
        )!!
        val e = runCatching { client().pair(invite) }.exceptionOrNull() as RemoteClient.PairFailed
        assertEquals(RemoteClient.PairFailed.Reason.NOT_LISTENING, e.reason)
    }

    @Test fun anInviteNeverPointsOffTheHomeNetwork() {
        HomeNetwork.allowLoopback = false
        val body = "1|8.8.8.8|47731|abcd|${PairingCrypto.encode("TV".toByteArray())}|0011223344556677|${PairingCrypto.encode(ByteArray(32))}|${System.currentTimeMillis() + 60_000}"
        assertNull(RemoteInvite.parse("opentune://remote/#" + PairingCrypto.encode(body.toByteArray())))
    }

    @Test fun messagesRoundTripAndANewerOnesAreIgnored() {
        listOf(
            RemoteMessage.Hello("a"), RemoteMessage.Ping(5), RemoteMessage.Move(1.5f, -2f), RemoteMessage.Click(true), RemoteMessage.Press(true),
            RemoteMessage.Scroll(0f, 9f), RemoteMessage.Key(RemoteKey.VOLUME_UP, repeat = true), RemoteMessage.Text("é 😀"), RemoteMessage.Cursor(false),
            RemoteMessage.Welcome("tv"), RemoteMessage.Now("t", null, null, true, 1, 2, 3), RemoteMessage.PairRequest("p"), RemoteMessage.PairGranted("i", "k", "t"),
            RemoteMessage.Field(true, "pink", "Search"), RemoteMessage.Field(false), RemoteMessage.Edit("é 😀"), RemoteMessage.Edit("abba", submit = true),
        ).forEach { assertEquals(it, RemoteCodec.decode(RemoteCodec.encode(it))) }
        // As they go on the wire, which an older app reads as a type it doesn't know.
        assertEquals("""{"t":"field","focused":true,"text":"ab"}""", RemoteCodec.encode(RemoteMessage.Field(true, "ab")))
        assertEquals("""{"t":"edit","text":"ab","submit":true}""", RemoteCodec.encode(RemoteMessage.Edit("ab", true)))
        assertEquals(RemoteMessage.Field(focused = false), RemoteCodec.decode("""{"t":"field","focused":false}"""))
        assertNull(RemoteCodec.decode("""{"t":"teleport","x":1}"""))
        assertNull(RemoteCodec.decode("not json"))
        // A newer app's extra fields don't break an older one.
        assertEquals(RemoteMessage.Click(), RemoteCodec.decode("""{"t":"click","hands":2}"""))
    }

    @Test fun theTvTellsThePhoneWhenAFieldHasTheFocusAndThePhoneTypesIntoIt() {
        val c = client()
        val tv = pairedTv()
        connected(c, tv)
        assertNull(c.field.value)
        host.field(RemoteMessage.Field(focused = true, text = "pink", hint = "Search songs, albums, artists"))
        await("focused") { c.field.value == RemoteMessage.Field(true, "pink", "Search songs, albums, artists") }
        c.send(RemoteMessage.Edit("pink floyd"))
        c.send(RemoteMessage.Edit("pink floyd", submit = true))
        await("typed") { heard.size == 2 }
        assertEquals(listOf(RemoteMessage.Edit("pink floyd"), RemoteMessage.Edit("pink floyd", submit = true)), heard.toList())
        // A phone that connects while the field has the focus hears so at once.
        val late = client("Second phone")
        connected(late, late.pair(host.createInvite()))
        await("told on connecting") { late.field.value?.focused == true }
        host.field(RemoteMessage.Field(focused = false))
        await("left") { c.field.value?.focused == false && late.field.value?.focused == false }
        // Gone when the phone lets go of the TV.
        c.disconnect()
        assertNull(c.field.value)
    }

    @Test fun aFieldIsOnlyToldWhenItChangesAndIsCappedInLength() {
        val c = client()
        connected(c, pairedTv())
        val long = "x".repeat(500)
        host.field(RemoteMessage.Field(true, long))
        await("focused") { c.field.value?.focused == true }
        assertEquals(200, c.field.value!!.text.length)
    }

    @Test fun anOlderPhoneSkipsMessagesItDoesntKnowAndStaysConnected() {
        // A TV as a newer version would be, saying something this phone has never heard of.
        java.net.ServerSocket(0).use { server ->
            val tvSide = Thread {
                server.accept().use { s ->
                    val input = java.io.DataInputStream(s.getInputStream())
                    val header = ByteArray(RemoteHandshake.HEADER_SIZE).also(input::readFully)
                    val (_, id) = RemoteHandshake.parse(header)!!
                    val key = PairingCrypto.decode(phones.find(SecureChannel.hex(id))!!.key)
                    val nonce = ByteArray(RemoteHandshake.NONCE_BYTES).also(SecureChannel.random::nextBytes)
                    s.getOutputStream().apply { write(nonce); flush() }
                    val channel = SecureChannel(s.getInputStream(), s.getOutputStream(), key, nonce, "s2c", "c2s")
                    channel.send(RemoteMessage.Welcome("Future TV"))
                    channel.receive()
                    channel.sendText("""{"t":"hologram","depth":3}""")
                    channel.send(RemoteMessage.Now("Echoes"))
                    Thread.sleep(1_000)
                }
            }.apply { isDaemon = true; start() }
            val c = client()
            val tv = pairedTv()
            c.connect(tv, listOf("127.0.0.1" to server.localPort))
            await("still connected, and heard what came after") { c.now.value?.title == "Echoes" }
            assertTrue(c.state.value is RemoteClient.State.Connected)
            tvSide.join(3_000)
        }
    }

    @Test fun anOlderTvSkipsMessagesItDoesntKnowAndStaysConnected() {
        val tv = pairedTv()
        val socket = Socket("127.0.0.1", host.port)
        socket.getOutputStream().write(RemoteHandshake.header(RemoteHandshake.CONTROL, SecureChannel.unhex(tv.id)))
        val nonce = ByteArray(RemoteHandshake.NONCE_BYTES).also { java.io.DataInputStream(socket.getInputStream()).readFully(it) }
        val channel = SecureChannel(socket.getInputStream(), socket.getOutputStream(), PairingCrypto.decode(tv.key), nonce, "c2s", "s2c")
        assertTrue(channel.receive() is RemoteMessage.Welcome)
        channel.send(RemoteMessage.Hello("raw"))
        // What a newer phone might say, then a click: the click still lands.
        channel.sendText("""{"t":"hologram","depth":3}""")
        channel.send(RemoteMessage.Click())
        await("the click") { heard.any { it is RemoteMessage.Click } }
        socket.close()
    }

    @Test fun pairingsSurviveARestart() {
        val storage = MemoryStorage()
        val first = PairedPhones(storage)
        val a = first.add("A"); first.add("B")
        val second = PairedPhones(storage)
        assertEquals(first.tvId, second.tvId)
        assertEquals(listOf("A", "B"), second.phones.value.map { it.name })
        second.remove(a.id)
        assertEquals(listOf("B"), PairedPhones(storage).phones.value.map { it.name })

        val tvs = PairedTvs(MemoryStorage())
        val tv = PairedTv("t1", "Den", "00", "k")
        tvs.add(tv); tvs.add(tv.copy(name = "Den 2")); tvs.remember("t1", "192.168.1.9", 47731)
        assertEquals(listOf(tv.copy(name = "Den 2", host = "192.168.1.9", port = 47731)), tvs.tvs.value)
        tvs.remove("t1")
        assertTrue(tvs.tvs.value.isEmpty())
    }
}
