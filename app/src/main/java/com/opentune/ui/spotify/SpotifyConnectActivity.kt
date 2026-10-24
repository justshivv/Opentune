package com.opentune.ui.spotify

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.os.Message
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Connect Spotify for Canvas: Spotify's own sign-in page in a screen of its
 * own. Once Spotify has set its sign-in cookie the screen closes; the cookie
 * stays in the app's WebView cookies, where [SpotifyConnection] reads it.
 * Nothing is typed into the app itself.
 *
 * A plain Android screen rather than a Compose page, as with
 * [ExportifyActivity]: the app's glass effects can leave a WebView showing a
 * stale frame after it navigates.
 */
class SpotifyConnectActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private var popup: WebView? = null
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val dark = Color.rgb(0x12, 0x12, 0x12)
        val close = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "Close"
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = "Connect Spotify"
            setTextColor(Color.WHITE)
            textSize = 18f
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(dark)
            addView(close, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val hint = TextView(this).apply {
            text = "Sign in on Spotify's page. OpenTune uses it to bring your playlists over and show each song's Canvas, and closes this when you're in. " +
                "If Google sign-in won't work here, use your Spotify email or username."
            setTextColor(Color.rgb(0xB3, 0xB3, 0xB3))
            textSize = 12f
            setBackgroundColor(dark)
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        web = runCatching { newWebView() }.getOrElse {
            com.opentune.data.Pills.error("This phone's web view isn't available", this)
            finish()
            return
        }
        val frame = FrameLayout(this).apply { addView(web) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(dark)
            addView(bar)
            addView(hint)
            addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)))
            addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    popup != null -> closePopup()
                    web.canGoBack() -> web.goBack()
                    else -> finish()
                }
            }
        })
        if (savedInstanceState == null) web.loadUrl(LOGIN) else web.restoreState(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::web.isInitialized) web.saveState(outState)
    }

    override fun onDestroy() {
        popup?.destroy()
        if (::web.isInitialized) web.destroy()
        SpotifyConnection.refresh()
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(): WebView = WebView(this).apply {
        setBackgroundColor(Color.rgb(0x12, 0x12, 0x12))
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
            progress.visibility = View.GONE
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
            progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
        }

        /** Facebook and Apple sign-in open a window; it gets its own WebView over the page until it closes. */
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val child = newWebView()
            popup = child
            (web.parent as FrameLayout).addView(child)
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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** Spotify's sign-in, coming back to the web player, which is where the cookie is set. */
        const val LOGIN = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
    }
}
