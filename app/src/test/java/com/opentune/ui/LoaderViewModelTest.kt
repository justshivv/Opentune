package com.opentune.ui

import com.opentune.data.model.UiState
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LoaderViewModelTest {
    /** An older reload that answers after a newer one (an account switch mid-load) doesn't put its answer back. */
    @Test fun aNewerReloadWinsOverAnOlderOneThatAnswersLater() {
        ScreenCache.clear()
        val answers = ArrayDeque<CompletableDeferred<String>>()
        val loader = LoaderViewModel("test:overlap", load = { CompletableDeferred<String>().also(answers::addLast).await() })
        ShadowLooper.idleMainLooper()
        val first = answers.removeFirst()
        first.complete("first")
        ShadowLooper.idleMainLooper()
        assertEquals("first", (loader.state.value as UiState.Success).data)

        loader.reload(keepContent = true)
        ShadowLooper.idleMainLooper()
        val older = answers.removeFirst()
        loader.reload(keepContent = true)
        ShadowLooper.idleMainLooper()
        val newer = answers.removeFirst()
        assertTrue(loader.refreshing.value)

        newer.complete("newer")
        ShadowLooper.idleMainLooper()
        older.complete("older")
        ShadowLooper.idleMainLooper()
        assertEquals("newer", (loader.state.value as UiState.Success).data)
        assertFalse(loader.refreshing.value)
    }

    /** The older reload finishing first doesn't end the newer one's refreshing early. */
    @Test fun refreshingLastsUntilTheNewestReloadIsDone() {
        ScreenCache.clear()
        val answers = ArrayDeque<CompletableDeferred<String>>()
        val loader = LoaderViewModel("test:refreshing", load = { CompletableDeferred<String>().also(answers::addLast).await() })
        ShadowLooper.idleMainLooper()
        answers.removeFirst().complete("first")
        ShadowLooper.idleMainLooper()

        loader.reload(keepContent = true)
        ShadowLooper.idleMainLooper()
        val older = answers.removeFirst()
        loader.reload(keepContent = true)
        ShadowLooper.idleMainLooper()
        val newer = answers.removeFirst()
        older.complete("older")
        ShadowLooper.idleMainLooper()
        assertTrue(loader.refreshing.value)
        newer.complete("newer")
        ShadowLooper.idleMainLooper()
        assertFalse(loader.refreshing.value)
        assertEquals("newer", (loader.state.value as UiState.Success).data)
    }
}
