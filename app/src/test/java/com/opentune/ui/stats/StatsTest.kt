package com.opentune.ui.stats

import android.graphics.Bitmap
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.opentune.data.ProjectStats
import com.opentune.data.history.History
import com.opentune.data.history.PlayRecord
import java.io.File
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class StatsTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun readsGitHubsNumbers() {
        val releases = JSONArray(
            """[
              {"tag_name":"v0.3.4","published_at":"2026-10-09T03:00:00Z","draft":false,"assets":[
                {"name":"OpenTune-v0.3.4-arm64-v8a.apk","download_count":5},{"name":"OpenTune-v0.3.4-universal.apk","download_count":2},{"name":"SHA256SUMS.txt","download_count":40}]},
              {"tag_name":"v0.3.5","draft":true,"assets":[{"name":"x.apk","download_count":9}]},
              {"tag_name":"v0.3.3","published_at":"2026-10-08T18:27:15Z","assets":[{"name":"OpenTune-v0.3.3-arm64-v8a.apk","download_count":3}]}
            ]""",
        )
        val repo = JSONObject("""{"stargazers_count":12,"forks_count":3,"subscribers_count":2,"created_at":"2026-09-17T14:59:29Z"}""")
        val n = ProjectStats.parse(releases, repo, 1000L)
        assertEquals(listOf("v0.3.4", "v0.3.3"), n.releases.map { it.tag })
        assertEquals(10, n.downloads)
        assertEquals(12, n.stars)
        assertEquals(3, n.forks)
        assertEquals(n, ProjectStats.fromJson(ProjectStats.toJson(n)))
    }

    @Test fun page() {
        val day = 24 * 60 * 60 * 1000L
        val now = System.currentTimeMillis()
        val records = (0 until 240).map { i ->
            val pick = listOf(0, 0, 1, 2, 0, 3, 1, 4)[i % 8]
            PlayRecord("v$pick", listOf("Husn", "Kesariya", "Blinding Lights", "Levitating", "Excuses")[pick], listOf("Anuv Jain", "Arijit Singh", "The Weeknd", "Dua Lipa", "AP Dhillon")[pick], null, "3:30", null, now - i * day / 3, 200_000)
        }
        History.importJson(Json.encodeToJsonElement(ListSerializer(PlayRecord.serializer()), records))
        ProjectStats.show(
            ProjectStats.Numbers(
                listOf("v0.3.5" to 4, "v0.3.4" to 1, "v0.3.3" to 3, "v0.3.2" to 5, "v0.3.1" to 6, "v0.3.0" to 2, "v0.2.9" to 2, "v0.2.8" to 9, "v0.2.4" to 11, "v0.2.3" to 10)
                    .map { (t, d) -> ProjectStats.Release(t, "", d) },
                stars = 2, forks = 0, watchers = 0, createdAt = null, fetchedAt = now,
            ),
        )
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(darkColorScheme(primary = Color(0xFFFF8A65), tertiary = Color(0xFFB388FF))) {
                Surface(color = MaterialTheme.colorScheme.background) { StatsScreen(PaddingValues(), onBack = {}) }
            }
        }
        compose.mainClock.advanceTimeBy(2_500)
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val file = File("build/screenshots/stats/page.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
