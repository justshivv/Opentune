package com.opentune.data.account

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.innertube.Innertube
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Signing a TV in with the phone, both ends in this process over real sockets: the TV's
 * [PairingReceiver] shows a code, the phone reads its link as a scan would and [PairingSender]
 * hands the session over; then the TV's [AccountStore] is signed in, and still is after a restart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SessionPairingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val cookie = "SAPISID=abc/def; __Secure-3PAPISID=abc/def; HSID=h1; SSID=s1; SID=sid1; LOGIN_INFO=li"

    @Before fun setUp() {
        Lan.allowLoopback = true
        AccountStore.init(context)
    }

    @After fun tearDown() {
        Lan.allowLoopback = false
        while (AccountStore.signedIn.value) AccountStore.signOut()
    }

    /** The phone's reading of the link in the TV's QR code, as Android's Uri would split it. */
    private fun scan(link: String, steady: Long = Lan.steadyMs()): PairingOffer {
        val uri = URI(link)
        val query = uri.rawQuery.orEmpty().split('&').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        return PairingOffer.parse(uri.rawFragment, take = query["take"], also = query["also"], steady = steady)
    }

    private fun <T> background(block: () -> T): CompletableFuture<T> = CompletableFuture.supplyAsync(block)

    private fun <T> CompletableFuture<T>.failure(): Throwable =
        try { get(15, TimeUnit.SECONDS); fail("expected it to fail"); throw AssertionError() } catch (e: ExecutionException) { e.cause!! }

    @Test fun signsTheTvInAndItStaysSignedInAfterARestart() {
        val tv = PairingReceiver(addresses = { listOf("127.0.0.1") })
        val served = background { tv.receive { c -> AccountStore.signIn(c); true } }
        PairingSender.send(scan(tv.offer.link), cookie)
        served.get(10, TimeUnit.SECONDS)
        assertTrue(AccountStore.signedIn.value)
        assertEquals(cookie, Innertube.cookie)
        // Every cookie arrives as it was sent, on the account that's now active.
        AccountStore.init(context)
        assertTrue(AccountStore.signedIn.value)
        assertEquals(cookie, Innertube.cookie)
        assertEquals(1, AccountStore.accounts.value.count { it.active })
    }

    @Test fun thePhoneTriesEveryAddressTheTvGave() {
        // The TV's main address answers nobody (another interface, a Wi-Fi Direct one, say); its other one works.
        val tv = PairingReceiver(addresses = { listOf("10.255.255.1", "127.0.0.1") })
        assertEquals("10.255.255.1", tv.offer.host)
        val read = scan(tv.offer.link)
        assertEquals(listOf("10.255.255.1", "127.0.0.1"), read.hosts)
        var got: String? = null
        val served = background { tv.receive { got = it; true } }
        PairingSender.send(read, cookie, connectMs = 1_500)
        served.get(10, TimeUnit.SECONDS)
        assertEquals(cookie, got)
    }

    @Test fun anAddressTheTvAnnouncedIsTriedToo() {
        // A code from an older TV names one address only, and it doesn't answer; the TV announced itself here.
        val tv = PairingReceiver(addresses = { listOf("127.0.0.1") })
        val read = scan(tv.offer.link)
        var got: String? = null
        val served = background { tv.receive { got = it; true } }
        val unreachable = PairingOffer.parse(
            PairingCrypto.encode("1|10.255.255.1|${read.port}|${read.expires}|${PairingCrypto.encode(read.key)}".toByteArray()),
        )
        PairingSender.send(unreachable, cookie, also = listOf("127.0.0.1"), connectMs = 1_500)
        served.get(10, TimeUnit.SECONDS)
        assertEquals(cookie, got)
    }

    @Test fun skewedClocksDontMatter() {
        val wall = System.currentTimeMillis()
        // The TV's clock a minute ahead of the phone's, then five minutes behind.
        for (tvClock in listOf(wall + 60_000, wall - 300_000)) {
            val code = PairingOffer.create("192.168.1.20", 40000, now = tvClock)
            val read = PairingOffer.parse(code.payload, now = wall)
            assertTrue(read.valid())
            assertEquals("192.168.1.20", read.host)
        }
        // And end to end, whatever the TV's wall clock says.
        val tv = PairingReceiver(addresses = { listOf("127.0.0.1") })
        val served = background { tv.receive { true } }
        PairingSender.send(scan(tv.offer.link), cookie)
        served.get(10, TimeUnit.SECONDS)
    }

    @Test fun theTvStopsTakingTheCodeAfterTwoMinutesByItsOwnClock() {
        var now = 0L
        val tv = PairingReceiver(addresses = { listOf("127.0.0.1") }, steady = { now })
        val read = scan(tv.offer.link)
        val served = background { tv.receive { fail("signed in with a code that ran out"); true } }
        now = PairingOffer.LIFETIME_MS
        assertEquals(PairingFailed.Reason.EXPIRED, (served.failure() as PairingFailed).reason)
        // The phone still thinks it has time (it read the code just now), but the TV has gone.
        val e = runCatching { PairingSender.send(read, cookie) }.exceptionOrNull() as PairingSender.Failed
        assertEquals(PairingSender.Failed.Reason.NOT_LISTENING, e.reason)
    }

    @Test fun thePhoneSaysWhenItHasHeldTheCodeTooLong() {
        val tv = PairingReceiver(addresses = { listOf("127.0.0.1") })
        val read = scan(tv.offer.link, steady = 0)
        val e = runCatching { PairingSender.send(read, cookie, steady = PairingOffer.LIFETIME_MS) }.exceptionOrNull() as PairingSender.Failed
        assertEquals(PairingSender.Failed.Reason.EXPIRED, e.reason)
        tv.close()
    }

    @Test fun aTvThatReadTheCodeTooLateSaysSo() {
        var valid = true
        val tv = PairingReceiver(addresses = { listOf("127.0.0.1") })
        val read = scan(tv.offer.link)
        ServerSocket(0).use { server ->
            val served = background { server.accept().use { PairingWire.serve(it, read.key, emptySet(), { true }, null) { valid } } }
            valid = false
            val moved = PairingOffer.parse(
                PairingCrypto.encode("1|127.0.0.1|${server.localPort}|${read.expires}|${PairingCrypto.encode(read.key)}".toByteArray()),
            )
            val e = runCatching { PairingSender.send(moved, cookie) }.exceptionOrNull() as PairingSender.Failed
            assertEquals(PairingSender.Failed.Reason.EXPIRED_THERE, e.reason)
            assertTrue(served.failure() is PairingWire.Refused)
        }
        tv.close()
    }

    @Test fun anOlderCodeIsTurnedDownAndTheTvSaysWhy() {
        var now = 0L
        val tv = PairingReceiver(addresses = { listOf("127.0.0.1") }, steady = { now })
        val served = background { tv.receive { fail("signed in with another code's key"); true } }
        // Another code's key, at this TV's address: the code the phone kept from last time.
        val stale = PairingOffer.create("127.0.0.1", tv.offer.port)
        val e = runCatching { PairingSender.send(stale, cookie) }.exceptionOrNull() as PairingSender.Failed
        assertEquals(PairingSender.Failed.Reason.REJECTED, e.reason)
        now = PairingOffer.LIFETIME_MS
        assertEquals(PairingFailed.Reason.WRONG_CODE, (served.failure() as PairingFailed).reason)
    }

    @Test fun aSessionTheTvWontTakeIsReportedOnBothSides() {
        var now = 0L
        val tv = PairingReceiver(addresses = { listOf("127.0.0.1") }, steady = { now })
        val served = background { tv.receive { false } }
        val e = runCatching { PairingSender.send(scan(tv.offer.link), cookie) }.exceptionOrNull() as PairingSender.Failed
        assertEquals(PairingSender.Failed.Reason.BAD_SESSION, e.reason)
        // Never in what's thrown, where a log could print it.
        assertFalse(generateSequence<Throwable>(e) { it.cause }.any { it.message.orEmpty().contains("SAPISID") })
        now = PairingOffer.LIFETIME_MS
        assertEquals(PairingFailed.Reason.BAD_SESSION, (served.failure() as PairingFailed).reason)
    }

    @Test fun nothingListeningIsSaidPlainlyAndQuickly() {
        val port = ServerSocket(0).use { it.localPort }
        val offer = PairingOffer.create("127.0.0.1", port)
        val started = System.currentTimeMillis()
        val e = runCatching { PairingSender.send(offer, cookie) }.exceptionOrNull() as PairingSender.Failed
        assertEquals(PairingSender.Failed.Reason.NOT_LISTENING, e.reason)
        assertTrue(System.currentTimeMillis() - started < 5_000)
    }

    @Test fun anAddressThatNeverAnswersEndsInTimeInsteadOfHanging() {
        val offer = PairingOffer.create("10.255.255.1", 40000)
        val started = System.currentTimeMillis()
        val e = runCatching { PairingSender.send(offer, cookie, connectMs = 1_000) }.exceptionOrNull() as PairingSender.Failed
        assertTrue(e.reason.name, e.reason in setOf(PairingSender.Failed.Reason.NO_ANSWER, PairingSender.Failed.Reason.NO_ROUTE, PairingSender.Failed.Reason.NOT_LISTENING))
        assertTrue(System.currentTimeMillis() - started < 6_000)
    }

    @Test fun lastFmComesAlongWhenTheComputerAsksForIt() {
        val tv = PairingReceiver(take = setOf(PairingExtras.LASTFM), addresses = { listOf("127.0.0.1") })
        var user: String? = null
        val served = background { tv.receive(extras = { e -> user = e.lastFm?.username; setOf(PairingExtras.LASTFM) }) { true } }
        val took = PairingSender.send(
            scan(tv.offer.link), cookie,
            PairingExtras(lastFm = PairingExtras.LastFm("0123456789abcdef0123456789abcdef", "shiv")),
        )
        served.get(10, TimeUnit.SECONDS)
        assertEquals(setOf(PairingExtras.LASTFM), took)
        assertEquals("shiv", user)
    }

    @Test fun aCodeOffTheHomeNetworkIsNamedAsSuch() {
        val payload = PairingCrypto.encode("1|8.8.8.8|40000|${System.currentTimeMillis()}|${PairingCrypto.encode(ByteArray(32))}".toByteArray())
        val e = runCatching { PairingOffer.parse(payload) }.exceptionOrNull()
        assertTrue(e is Lan.OffHomeNetwork)
        assertEquals("8.8.8.8", (e as Lan.OffHomeNetwork).host)
        // Damaged codes are not that.
        assertFalse(runCatching { PairingOffer.parse(payload.dropLast(5)) }.exceptionOrNull() is Lan.OffHomeNetwork)
    }

    @Test fun cancellingTheCodeIsNotMistakenForASignIn() {
        val tv = PairingReceiver(addresses = { listOf("127.0.0.1") })
        val served = background { tv.receive { true } }
        Thread.sleep(200)
        tv.close()
        assertEquals(PairingFailed.Reason.STOPPED, (served.failure() as PairingFailed).reason)
    }
}
