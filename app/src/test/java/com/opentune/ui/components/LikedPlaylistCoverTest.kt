package com.opentune.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LikedPlaylistCoverTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun likedMusicHasArtworkWithoutAnyRemoteImageOrSongs() {
        compose.setContent { MaterialTheme { LikedPlaylistCover(Modifier.size(240.dp)) } }
        compose.onNodeWithContentDescription("Liked Music artwork").assertIsDisplayed()
    }
}
