package com.opentune.ui.account

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.opentune.data.account.PairingCrypto
import com.opentune.data.account.PairingExtras
import com.opentune.data.account.WebShare
import com.opentune.data.account.WebShareOffer
import com.opentune.data.discord.DiscordRpc
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The "Sign in the web player" sheet: done once the web player confirms, and a swipe down is Cancel for good. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class WebSignInTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    // Invented, and not a real token.
    private val account = DiscordRpc.Account("not.a.real.token-0123456789abcdef", "100000000000000001", "Wren Calloway", "wrencalloway", null)

    /** A fresh code from the web player, as its page shows it: a new one-use key each time. */
    private fun freshCode(): Intent {
        val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public as ECPublicKey
        fun fixed(v: BigInteger) = v.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else ByteArray(32 - it.size) + it }
        val raw = byteArrayOf(4) + fixed(key.w.affineX) + fixed(key.w.affineY)
        val payload = PairingCrypto.encode("w1|${System.currentTimeMillis() + 100_000}|${PairingCrypto.encode(raw)}|discord".toByteArray())
        return Intent(Intent.ACTION_VIEW, Uri.parse("opentune://pair/web#$payload"))
    }

    @Before @After fun clear() {
        PairingLinks.pendingWeb.value?.let(PairingLinks::dismissWeb)
        PairingLinks.problem.value = null
    }

    private fun show(send: WebSender) = compose.setContent {
        MaterialTheme(darkColorScheme()) { ConfirmWebShare(send = send, discord = account) }
    }

    @Test fun theWebPlayerConfirmingShowsSignedInThenTheSheetCloses() {
        val code = freshCode()
        PairingLinks.receive(code)
        var sent: PairingExtras? = null
        show { _, extras -> sent = extras; setOf(PairingExtras.DISCORD) }
        compose.waitForIdle()
        compose.onNodeWithText("Also sign in to Discord (Wren Calloway · @wrencalloway)").assertExists()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Sign it in").performClick()
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("Signed in").assertExists()
        compose.onNodeWithText("The web player is signed in to Discord.").assertExists()
        compose.onNodeWithText("Couldn't reach the web player. Keep its page open, make a fresh code there and scan it again.").assertDoesNotExist()
        assertEquals(account.token, sent?.discord?.token)
        // It goes by itself, and the same code coming in again doesn't bring it back.
        compose.mainClock.advanceTimeBy(WebSignIn.DONE_SHOWN_MS + 1_000)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertNull(PairingLinks.pendingWeb.value)
        compose.onNodeWithText("Signed in").assertDoesNotExist()
        PairingLinks.receive(code)
        assertNull(PairingLinks.pendingWeb.value)
    }

    @Test fun swipingTheSheetDownWhileItSendsIsCancelAndItStaysAway() {
        val code = freshCode()
        PairingLinks.receive(code)
        val cancelled = CompletableDeferred<Unit>()
        show { _, _ -> try { awaitCancellation() } finally { cancelled.complete(Unit) } }
        compose.waitForIdle()
        compose.onNodeWithText("Sign it in").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Connecting securely…").assertExists()
        compose.onNodeWithContentDescription("Close").performTouchInput { swipeDown(centerY, centerY + 1_400f, 300) }
        compose.waitForIdle()
        // Well past the moment a card that was kept open comes back.
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        assertNull(PairingLinks.pendingWeb.value)
        compose.onNodeWithText("Sign in the web player").assertDoesNotExist()
        assertTrue(cancelled.isCompleted)
        // The same link handed over again (the camera app, Recents) doesn't reopen it...
        PairingLinks.receive(code)
        PairingLinks.receive(Intent(code).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
        compose.waitForIdle()
        assertNull(PairingLinks.pendingWeb.value)
        compose.onNodeWithText("Sign in the web player").assertDoesNotExist()
        // ...a fresh code does.
        PairingLinks.receive(freshCode())
        compose.waitForIdle()
        compose.onNodeWithText("Sign in the web player").assertExists()
    }

    @Test fun swipingTheSheetDownBeforeApprovingIsCancel() {
        val code = freshCode()
        PairingLinks.receive(code)
        var asked = false
        show { _, _ -> asked = true; emptySet() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Close").performTouchInput { swipeDown(centerY, centerY + 1_400f, 300) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        assertNull(PairingLinks.pendingWeb.value)
        compose.onNodeWithText("Sign in the web player").assertDoesNotExist()
        assertTrue(!asked)
    }

    // ---- The sheet's steps, without drawing it ----------------------------------------

    private fun offer(): WebShareOffer = freshCode().data!!.fragment!!.let { WebShareOffer.parse(it) }

    @Test fun anAnswerIsDoneAndNoAnswerAfterSendingIsNotCalledAFailureToReach() = runBlocking {
        val done = WebSignIn(offer(), this) { _, _ -> setOf(PairingExtras.DISCORD) }
        done.approve(PairingExtras())
        assertEquals(WebSignIn.Step.Sending, done.step.value)
        kotlinx.coroutines.yield()
        assertEquals(WebSignIn.Step.Done(setOf(PairingExtras.DISCORD)), done.step.value)

        val unconfirmed = WebSignIn(offer(), this) { _, _ -> throw WebShare.Unconfirmed() }
        unconfirmed.approve(PairingExtras())
        kotlinx.coroutines.yield()
        assertEquals(WebSignIn.Step.Failed(WebSignIn.UNCONFIRMED, retry = false), unconfirmed.step.value)

        val unsent = WebSignIn(offer(), this) { _, _ -> throw WebShare.Unsent() }
        unsent.approve(PairingExtras())
        kotlinx.coroutines.yield()
        assertEquals(WebSignIn.Step.Failed(WebSignIn.UNREACHABLE, retry = true), unsent.step.value)
    }

    @Test fun theDiscordTickNamesTheAccountOrNothing() {
        fun label(name: String, username: String) = discordLabel(account.copy(name = name, username = username))
        assertEquals("Wren Calloway · @wrencalloway", label("Wren Calloway", "wrencalloway"))
        assertEquals("@wrencalloway", label("wrencalloway", "wrencalloway"))
        assertEquals("@wrencalloway", label(" ", "wrencalloway"))
        assertEquals("Wren Calloway", label("Wren Calloway", ""))
        assertNull(label("", ""))
    }
}
