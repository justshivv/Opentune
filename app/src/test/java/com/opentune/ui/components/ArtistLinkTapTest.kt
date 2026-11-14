package com.opentune.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class ArtistLinkTapTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun plainArtistLinksSurviveTheAppTextWrapper() {
        val opened = mutableListOf<String>()
        val names = "Artist One, Artist Two"
        compose.setContent { MaterialTheme { ArtistLinks(names, { opened += it }, explicit = false) } }
        val node = compose.onNodeWithText(names)
        val layout = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
        assertEquals(2, layout.single().layoutInput.text.getLinkAnnotations(0, names.length).size)
        for (name in listOf("Artist One", "Artist Two")) {
            val at = layout.single().getBoundingBox(names.indexOf(name) + 2).center
            node.performTouchInput { click(at) }
        }
        assertEquals(listOf("Artist One", "Artist Two"), opened)
    }
}
