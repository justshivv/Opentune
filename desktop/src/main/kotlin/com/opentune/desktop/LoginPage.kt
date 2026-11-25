package com.opentune.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.opentune.data.account.AccountStore
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.web.WebView
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI

private const val MUSIC = "https://music.youtube.com"
private const val SIGN_IN_URL =
    "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&passive=true&continue=" +
        "https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F"

/**
 * Google's own sign-in page in a JavaFX WebView. Once it lands back on
 * YouTube Music with a session cookie, the cookie is handed to [AccountStore]
 * and [onDone] is called. The password only ever goes to Google's page.
 */
@Composable
internal fun LoginPage(onDone: () -> Unit) {
    val top = LocalPageTop.current
    val bottom = LocalPageBottom.current
    var loading by remember { mutableStateOf(true) }
    var unavailable by remember { mutableStateOf<String?>(null) }
    val panel = remember { JFXPanel() } // initializes JavaFX toolkit
    val previousCookies = remember { CookieHandler.getDefault() }
    val cookieManager = remember { CookieManager(null, CookiePolicy.ACCEPT_ALL).also { CookieHandler.setDefault(it) } }
    val done = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    fun finish(cookie: String) {
        if (!done.compareAndSet(false, true)) return
        AccountStore.signIn(cookie)
        onDone()
    }

    DisposableEffect(Unit) {
        onDispose {
            done.set(true)
            runCatching {
                Platform.runLater {
                    runCatching { (panel.scene?.root as? javafx.scene.layout.StackPane)?.children?.clear() }
                    runCatching { panel.scene = null }
                }
            }
            runCatching { cookieManager.cookieStore.removeAll() }
            runCatching { CookieHandler.setDefault(previousCookies) }
        }
    }

    LaunchedEffect(Unit) {
        Platform.runLater {
            runCatching {
                val view = WebView()
                view.engine.isJavaScriptEnabled = true
                view.engine.loadWorker.stateProperty().addListener { _, _, state ->
                    loading = state == javafx.concurrent.Worker.State.RUNNING
                    if (state == javafx.concurrent.Worker.State.SUCCEEDED || state == javafx.concurrent.Worker.State.FAILED) {
                        loading = state == javafx.concurrent.Worker.State.RUNNING
                        checkCookies(cookieManager, view.engine.location.orEmpty())?.let(::finish)
                    }
                }
                view.engine.locationProperty().addListener { _, _, url ->
                    checkCookies(cookieManager, url.orEmpty())?.let(::finish)
                }
                // Cookies can arrive just after page load, like the Android flow.
                val poll = javafx.animation.Timeline(javafx.animation.KeyFrame(javafx.util.Duration.seconds(1.0), { 
                    checkCookies(cookieManager, view.engine.location.orEmpty())?.let(::finish)
                }))
                poll.cycleCount = 30
                poll.play()
                cookieManager.cookieStore.removeAll()
                view.engine.load(SIGN_IN_URL)
                val root = javafx.scene.layout.StackPane(view)
                panel.scene = Scene(root)
            }.onFailure {
                unavailable = it.message ?: "Couldn't open sign-in page."
                loading = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(top = top + 16.dp, bottom = bottom, start = 24.dp, end = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.PlayCircle, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f)) {
                Text("Sign in to YouTube Music", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Your playlists, liked songs, history and recommendations", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = onDone) { Text("Cancel") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        val problem = unavailable
        if (problem != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Sign-in isn't available here", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(problem, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onDone) { Text("Back") }
                }
            }
        } else {
            SwingPanel(factory = { panel }, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(4.dp))
    }
}

private fun checkCookies(manager: CookieManager, url: String): String? {
    val host = runCatching { URI(url).host?.lowercase().orEmpty() }.getOrDefault("")
    if (host != "youtube.com" && !host.endsWith(".youtube.com")) return null
    val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
    if (path.startsWith("/signin")) return null
    val cookie = collectCookies(manager)
    if (!AccountStore.hasSession(cookie)) return null
    return if (host == "music.youtube.com") cookie else null
}

private fun collectCookies(manager: CookieManager): String {
    val uris = listOf("https://music.youtube.com", "https://youtube.com", "https://www.youtube.com")
    val pairs = linkedMapOf<String, String>()
    for (u in uris) {
        val list = runCatching { manager.cookieStore.get(URI(u)) }.getOrDefault(emptyList())
        for (c in list) {
            if (c.name.isNotBlank()) pairs[c.name] = c.value.orEmpty()
        }
    }
    return pairs.entries.joinToString("; ") { "${it.key}=${it.value}" }
}
