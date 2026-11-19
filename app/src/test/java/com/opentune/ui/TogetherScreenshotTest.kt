package com.opentune.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.foundation.layout.PaddingValues
import org.junit.Assert.assertNull
import com.opentune.ui.together.TogetherScreen
import com.opentune.data.together.Member
import com.opentune.data.together.RoomCode
import com.opentune.data.together.Together
import com.opentune.data.together.Track
import com.opentune.ui.together.RoomView
import com.opentune.ui.together.StartRoom
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.GlassStyle
import java.io.File
import androidx.compose.ui.test.assertIsNotEnabled
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Draws the Listen together screens to build/screenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class TogetherScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    /** The room's island is glass; Robolectric can't draw the Liquid Glass shaders, so it's drawn frosted. */
    @Before fun plainGlass() {
        AppSettings.init(ApplicationProvider.getApplicationContext())
        AppSettings.updateUi { it.copy(reduceBlur = true, glassStyle = GlassStyle.LIQUID) }
    }

    private fun shoot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            MaterialTheme(darkColorScheme(primary = Color(0xFFFF8A65), primaryContainer = Color(0xFF5A2E22), onPrimaryContainer = Color(0xFFFFDBCF))) {
                androidx.compose.material3.Surface(color = Color(0xFF0B0B0E), contentColor = Color(0xFFECE6F0)) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0E))) { content() }
                }
            }
        }
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File(File("build/screenshots").apply { mkdirs() }, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun start() = shoot("together-start") { StartRoom(code = null, onBack = {}) }

    /** Join a room: the code, Paste, and Join as the one main button. */
    @Test fun startOnJoin() {
        shoot("together-start-join") { StartRoom(code = null, onBack = {}) }
        compose.onNodeWithText("Join").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.onNodeWithText("Room code").assertExists()
        compose.onNodeWithText("Paste").assertExists()
        compose.onNodeWithText("Join a room").assertIsNotEnabled()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File(File("build/screenshots").apply { mkdirs() }, "together-start-join.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun inviteWaitsForJoinAndDoesNotAskForTheCodeAgain() {
        shoot("together-invite") {
            TogetherScreen(PaddingValues(), "K7QXM2PA9DTE", onBack = {})
        }
        compose.onNodeWithText("You’re invited").assertExists()
        compose.onNodeWithText("K7QX-M2PA-9DTE").assertExists()
        compose.onNodeWithText("Join").assertIsEnabled()
        compose.onNodeWithText("Start a room").assertDoesNotExist()
        compose.onNodeWithText("Room code").assertDoesNotExist()
        assertNull("Opening an invite must not join before confirmation", Together.room.value)
    }

    /** Start a room: the button turns into a spinner until the room opens, then the invite rises over it. */
    @Test fun startingARoomOpensTheInvite() {
        try {
            shoot("together-starting") { TogetherScreen(PaddingValues(), null, onBack = {}) }
            compose.onNodeWithContentDescription("Your name").performTextInput("Asha")
            compose.onNodeWithText("Start a room").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Opening the room…").assertExists()
            // No relays here, so the room opens when the wait runs out.
            compose.mainClock.advanceTimeBy(6_000)
            compose.waitForIdle()
            compose.onNodeWithText("Your room").assertExists()
            compose.onNodeWithText("Invite friends to your room").assertExists()
            compose.onNodeWithText("Share link").assertExists()
        } finally {
            Together.leave()
        }
    }

    @Test fun room() {
        val code = RoomCode.parse("K7QX-M2PA-9DTE")!!
        val room = Together.Room(
            code = code, hosting = true, myName = "Asha", phase = Together.Phase.Live, relays = 6,
            members = listOf(Member("a", "Asha", host = true), Member("b", "Ravi"), Member("c", "Meera")),
            hostName = "Asha", open = false,
            track = Track("4NRXx6U8ABQ", "Blinding Lights", "The Weeknd"),
            chat = listOf(
                Together.ChatLine("", "Room open. Share the code so friends can join.", system = true),
                Together.ChatLine("", "Ravi joined", system = true),
                Together.ChatLine("Ravi", "this one's a classic"),
                Together.ChatLine("Asha", "turn it up", mine = true),
                Together.ChatLine("", "Meera added Levitating", system = true),
            ),
        )
        shoot("together-room") { RoomView(room, onBack = {}) }
    }

    /** A new room's first messages arrive once it's open; it stays at its top, on the code and the invite. */
    @Test fun roomOpensAtItsTop() {
        val line = Together.ChatLine("", "Ravi joined", system = true)
        val room = androidx.compose.runtime.mutableStateOf(
            Together.Room(
                code = RoomCode.parse("K7QX-M2PA-9DTE")!!, hosting = true, myName = "Asha", phase = Together.Phase.Live,
                members = listOf(Member("a", "Asha", host = true)), hostName = "Asha",
            ),
        )
        shoot("together-room-start") { RoomView(room.value, onBack = {}) }
        room.value = room.value.copy(chat = List(30) { line })
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        compose.onNodeWithText("ROOM CODE").assertIsDisplayed()
        compose.onNodeWithText("End room").assertIsDisplayed()
    }

    /** The whole room on one tall screen: someone knocking, talk, and voice notes in the chat. */
    @Test @Config(sdk = [35], qualifiers = "w411dp-h2000dp-xxhdpi", application = android.app.Application::class)
    fun roomWithRequestsAndVoice() {
        val code = RoomCode.parse("K7QX-M2PA-9DTE")!!
        val note = Together.VoiceClip("b/n1", byteArrayOf(0x14, 0x08), listOf(ByteArray(10)), 12_000, complete = true, live = false)
        val talk = Together.VoiceClip("c/t1", byteArrayOf(0x14, 0x08), listOf(ByteArray(10)), 4_000, complete = true, live = true)
        val room = Together.Room(
            code = code, hosting = true, myName = "Asha", phase = Together.Phase.Live, relays = 6, myId = "a",
            members = listOf(Member("a", "Asha", host = true), Member("b", "Ravi"), Member("c", "Meera")),
            requests = listOf(Member("d", "Kabir")),
            hostName = "Asha", open = true,
            talking = listOf("c"),
            track = Track("4NRXx6U8ABQ", "Blinding Lights", "The Weeknd"),
            chat = listOf(
                Together.ChatLine("", "Ravi joined", system = true, at = 1_760_000_000_000),
                Together.ChatLine("Ravi", "this one's a classic", at = 1_760_000_060_000),
                Together.ChatLine("Ravi", "", voice = note, at = 1_760_000_090_000),
                Together.ChatLine("Asha", "turn it up", mine = true, at = 1_760_000_120_000),
                Together.ChatLine("Meera", "", voice = talk, at = 1_760_000_150_000),
            ),
        )
        shoot("together-room-full") { RoomView(room, onBack = {}) }
    }
}
