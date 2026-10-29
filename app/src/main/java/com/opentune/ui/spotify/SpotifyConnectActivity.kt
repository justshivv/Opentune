package com.opentune.ui.spotify

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.viewinterop.AndroidView
import com.opentune.data.settings.AppSettings
import com.opentune.ui.account.SignInPage
import com.opentune.ui.account.SpotifyGreen
import com.opentune.ui.theme.OpenTuneTheme

/**
 * Connect Spotify: Spotify's own sign-in page in a screen of its own, laid
 * out like YouTube Music's (see [SignInPage]). Once Spotify has set its
 * sign-in cookie the screen closes; the cookie stays in the app's WebView
 * cookies, where [SpotifyConnection] reads it. Nothing is typed into the app
 * itself.
 *
 * Its own screen rather than a page of the app, as with [ExportifyActivity]:
 * the app's glass effects redraw what's behind them through graphics
 * layers, which can leave a WebView showing a stale frame after it navigates.
 */
class SpotifyConnectActivity : ComponentActivity() {
    private var frame: FrameLayout? = null
    private var web: WebView? = null
    private var popup: WebView? = null
    private var done = false
    private var loading by mutableStateOf(true)
    private var unavailable by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val page = runCatching { newWebView() }.getOrNull()
        if (page == null) {
            unavailable = true
        } else {
            web = page
            frame = FrameLayout(this).apply { addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
            if (savedInstanceState == null) page.loadUrl(LOGIN) else page.restoreState(savedInstanceState)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val w = web
                when {
                    popup != null -> closePopup()
                    w != null && w.canGoBack() -> w.goBack()
                    else -> finish()
                }
            }
        })
        setContent {
            val theme by AppSettings.theme.collectAsState()
            OpenTuneTheme(theme) {
                SignInPage(
                    service = "Spotify",
                    brand = SpotifyGreen,
                    icon = Icons.Rounded.GraphicEq,
                    logo = com.opentune.R.drawable.ic_spotify,
                    gives = "Your playlists in Library, each song's Canvas, and suggestions made for you",
                    loading = loading,
                    onBack = { finish() },
                    unavailable = if (unavailable) "This phone's web view isn't working, so Spotify's sign-in page can't open. Update Android System WebView in the Play Store and try again." else null,
                    hint = "If Google sign-in won't open here, use your Spotify email or username, or a one-time code by email.",
                ) { modifier ->
                    frame?.let { f -> AndroidView(factory = { f }, modifier = modifier) }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web?.saveState(outState)
    }

    override fun onDestroy() {
        popup?.destroy()
        web?.destroy()
        SpotifyConnection.refresh()
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(): WebView = WebView(this).apply {
        setBackgroundColor(Color.WHITE)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        // Some sign-in pages hide or refuse themselves to an app's embedded browser.
        settings.userAgentString = settings.userAgentString.replace("; wv", "").replace(Regex("""Version/\d+\.\d+ """), "")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webViewClient = Client()
        webChromeClient = Chrome()
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            // Sign-in stays in here; app links (intent://, spotify:) go nowhere.
            val scheme = request.url.scheme
            return scheme != "https" && scheme != "http"
        }

        override fun onPageFinished(view: WebView, url: String?) {
            loading = false
            signedIn()
        }
    }

    /** Spotify has set its sign-in cookie: keep it and close. */
    private fun signedIn() {
        if (done || SpotifyConnection.cookie() == null) return
        done = true
        CookieManager.getInstance().flush()
        SpotifyConnection.refresh()
        SpotifyConnection.syncPlaylists(force = true)
        com.opentune.data.Pills.info("Spotify connected. Your playlists are coming over to Library", this)
        finish()
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            loading = newProgress < 100
        }

        /** Facebook and Apple sign-in open a window; it gets its own WebView over the page until it closes. */
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val container = frame ?: return false
            val child = newWebView()
            popup = child
            container.addView(child, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            (resultMsg.obj as WebView.WebViewTransport).webView = child
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) {
            if (window == popup) closePopup()
        }
    }

    private fun closePopup() {
        val child = popup ?: return
        popup = null
        (child.parent as? ViewGroup)?.removeView(child)
        child.destroy()
        signedIn()
    }

    companion object {
        /** Spotify's sign-in, coming back to the web player, which is where the cookie is set. */
        const val LOGIN = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
    }
}
