package com.opentune.ui.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import com.opentune.data.lyrics.LyricLine
import com.opentune.data.lyrics.LyricWord
import com.opentune.data.settings.LyricsAnimation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LyricLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun becomingActiveKeepsTheSameTextNodeAndBounds() {
        val active = mutableStateOf(false)
        val line = line(listOf("A steady ", "line of lyrics"))
        compose.setContent {
            MaterialTheme {
                LyricLineView(line, 0, active.value, LyricsAnimation.MINIMAL, false, { 400L }, {})
            }
        }
        val before = compose.onNodeWithText(line.text, useUnmergedTree = true).fetchSemanticsNode()
        compose.runOnIdle { active.value = true }
        compose.waitForIdle()
        val after = compose.onNodeWithText(line.text, useUnmergedTree = true).fetchSemanticsNode()
        assertEquals("Activation must not replace the text layout", before.id, after.id)
        assertEquals(before.boundsInRoot, after.boundsInRoot)
    }

    @Test fun rtlMasksDoNotReachBackOverEarlierWords() {
        val line = line(listOf("مرحبا ", "بكم ", "هنا"), rtl = true)
        compose.setContent {
            val layout = rememberTextMeasurer().measure(AnnotatedString(line.text), TextStyle(fontSize = 28.sp, textDirection = TextDirection.Rtl), constraints = Constraints(maxWidth = 1000))
            val boxes = wordBoxesOf(layout, line, line.text).map { it.single() }
            assertTrue("Each later word must stay to the left of the previous mask", boxes.zipWithNext().all { (a, b) -> b.right <= a.left + 0.5f })
        }
        compose.waitForIdle()
    }

    @Test fun wrappedWordsHaveSeparateMasksForEachRow() {
        val line = line(listOf("A very long lyric with ", "several wrapped words"))
        compose.setContent {
            val layout = rememberTextMeasurer().measure(AnnotatedString(line.text), TextStyle(fontSize = 28.sp), constraints = Constraints(maxWidth = 180))
            val boxes = wordBoxesOf(layout, line, line.text)
            assertTrue(boxes.flatten().all { it.width > 0 && it.height > 0 })
            assertTrue(boxes.first().size > 1)
        }
        compose.waitForIdle()
    }

    private fun line(words: List<String>, rtl: Boolean = false) = LyricLine(0, 4000, words.joinToString(""),
        words.mapIndexed { i, text -> LyricWord(text, i * 1000L, (i + 1) * 1000L) }, true, rtl = rtl)
}
