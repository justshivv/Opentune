package com.opentune.ui.account

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.opentune.data.account.AccountStore
import com.opentune.ui.components.GlassBackButton

private const val MUSIC = "https://music.youtube.com"
private const val SIGN_IN_URL =
    "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&passive=true&continue=" +
        "https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F"

/**
 * Google's own sign-in page in a WebView. Once it lands back on YouTube
 * Music with a session cookie, the cookie is handed to [AccountStore] and
 * the screen closes. The password only ever goes to Google's page.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(onDone: () -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var finished by remember { mutableStateOf(false) }
    // Set once the screen has gone: a page that finishes loading after that is ignored.
    val gone = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    // Some devices (most TVs, and phones with Android System WebView turned off or mid-update)
    // can't make a WebView: creating one throws. That's said here instead of closing the app.
    var unavailable by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { gone.set(true) } }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(top = 72.dp),
            factory = { context ->
                val view = runCatching {
                    CookieManager.getInstance().setAcceptCookie(true)
                    WebView(context)
                }.onFailure {
                    android.util.Log.w("LoginScreen", "No WebView for sign-in", it)
                    unavailable = true
                    loading = false
                }.getOrNull() ?: return@AndroidView android.view.View(context)
                view.apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                            if (!gone.get()) loading = true
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            if (gone.get()) return
                            loading = false
                            if (finished || url?.startsWith(MUSIC) != true) return
                            val cookie = CookieManager.getInstance().getCookie(MUSIC) ?: return
                            if (!AccountStore.hasSession(cookie)) return
                            finished = true
                            CookieManager.getInstance().flush()
                            AccountStore.signIn(cookie)
                            // Closed once this callback has returned: leaving from inside it destroyed
                            // the WebView while it was still running the callback, and on some WebView
                            // versions that crashed the app outright, signed in or not.
                            main.post { if (!gone.get()) onDone() }
                        }
                    }
                    loadUrl(SIGN_IN_URL)
                }
            },
            // Taken down only once it's off the screen: destroying it while still attached,
            // as leaving the page did, crashed some WebView versions on the way back.
            onRelease = { view ->
                gone.set(true)
                (view as? WebView)?.run {
                    stopLoading()
                    webViewClient = WebViewClient()
                    (parent as? android.view.ViewGroup)?.removeView(this)
                    // On the next turn of the main thread, never inside one of its own callbacks.
                    main.post { runCatching { destroy() } }
                }
            },
        )
        if (unavailable) {
            Text(
                "Sign-in needs Android System WebView, which isn't working on this device. Update or turn it on in the " +
                    "Play Store, or sign in on your phone and use Link a device to sign this one in.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
            )
        }
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(top = 8.dp)) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                GlassBackButton(onDone)
                Text(
                    "Sign in to YouTube Music",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            AnimatedVisibility(loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        }
    }
}
