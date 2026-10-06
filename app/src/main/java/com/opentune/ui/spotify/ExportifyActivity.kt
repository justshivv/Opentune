package com.opentune.ui.spotify

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Message
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.opentune.data.DebugLog as Log
import com.opentune.data.spotify.ExportedPlaylists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Exportify (exportify.app) in a screen of its own: you sign in with
 * Spotify there, on Spotify's own login page, and tap Export on a playlist
 * or Export All. Exportify builds the file in the page; instead of saving
 * it to Downloads the app takes it, reads the songs and finishes, and the
 * import page shows them ready to import.
 *
 * A plain Android screen rather than a Compose page: the app's glass
 * effects redraw what's behind them through graphics layers, which can
 * leave a WebView showing a stale or empty frame after it navigates.
 */
class ExportifyActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private var popup: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val dark = Color.rgb(0x12, 0x12, 0x12)

        val back = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "Close"
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = "Export from Spotify"
            setTextColor(Color.WHITE)
            textSize = 18f
        }
        val browser = TextView(this).apply {
            text = "Open in browser"
            setTextColor(Color.rgb(0x1E, 0xD7, 0x60))
            textSize = 14f
            setPadding(24, 16, 24, 16)
            setOnClickListener {
                // The way out if the in-app page won't sign in: export in a real browser, then pick the CSV.
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, EXPORTIFY.toUri())) }
                Toast.makeText(this@ExportifyActivity, "Export there, then pick the file with \"CSV file\"", Toast.LENGTH_LONG).show()
                finish()
            }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(dark)
            addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(browser)
        }
        val hint = TextView(this).apply {
            text = "Sign in with Spotify, then tap Export on a playlist, or Export All. OpenTune picks the file up by itself. " +
                "Use your Spotify email or username: Google sign-in doesn't work inside apps."
            setTextColor(Color.rgb(0xB3, 0xB3, 0xB3))
            textSize = 12f
            setBackgroundColor(dark)
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        web = newWebView()
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
        if (savedInstanceState == null) web.loadUrl(EXPORTIFY) else web.restoreState(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        popup?.destroy()
        web.destroy()
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun newWebView(): WebView = WebView(this).apply {
        setBackgroundColor(Color.rgb(0x12, 0x12, 0x12))
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        // Some sign-in pages hide or refuse themselves to an app's embedded browser.
        settings.userAgentString = settings.userAgentString.replace("; wv", "").replace(Regex("""Version/\d+\.\d+ """), "")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        addJavascriptInterface(Bridge(), "OpenTuneExport")
        webViewClient = Client()
        webChromeClient = Chrome()
        setDownloadListener { url, _, disposition, mime, _ ->
            val name = URLUtil.guessFileName(url, disposition, mime)
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                evaluateJavascript("window.__openTuneRead && window.__openTuneRead(${quote(url)}, ${quote(name)})", null)
            }
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            // Exportify and Spotify's login stay in here; app links (intent://, spotify:) go nowhere.
            val scheme = request.url.scheme
            return scheme != "https" && scheme != "http"
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            progress.visibility = View.VISIBLE
            Log.i(TAG, "loading ${url?.substringBefore('?')}")
        }

        override fun onPageFinished(view: WebView, url: String?) {
            progress.visibility = View.GONE
            if (url?.startsWith(EXPORTIFY) == true) view.evaluateJavascript(CATCH_DOWNLOADS, null)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) Log.w(TAG, "page error ${error.errorCode} ${error.description} at ${request.url.host}")
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (request.isForMainFrame) Log.w(TAG, "page answered ${response.statusCode} at ${request.url.host}")
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                Log.w(TAG, "page: ${message.message().take(300)} (${message.sourceId().substringBefore('?').takeLast(80)}:${message.lineNumber()})")
            }
            return true
        }

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
    }

    private inner class Bridge {
        @JavascriptInterface
        fun receive(fileName: String, dataUrl: String) {
            runOnUiThread { caught(fileName, dataUrl) }
        }
    }

    private fun caught(fileName: String, dataUrl: String) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val playlists = withContext(Dispatchers.Default) {
                runCatching {
                    ExportedPlaylists.read(fileName, Base64.decode(dataUrl.substringAfter(','), Base64.DEFAULT))
                }.onFailure { Log.w(TAG, "couldn't read $fileName", it) }.getOrDefault(emptyList())
            }
            progress.visibility = View.GONE
            if (playlists.isEmpty()) {
                Toast.makeText(this@ExportifyActivity, "That file had no songs in it", Toast.LENGTH_SHORT).show()
            } else {
                ExportedPlaylists.hand(playlists)
                finish()
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "Exportify"
        const val EXPORTIFY = "https://exportify.app"

        private fun quote(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

        /**
         * Exportify saves files the FileSaver.js way: a link with a download
         * name and a blob address, clicked by script. This catches that click
         * on any such link, reads the blob as a data URL and passes it to the app.
         */
        internal val CATCH_DOWNLOADS = """
(function () {
  if (window.__openTuneHooked) return;
  window.__openTuneHooked = true;
  window.__openTuneRead = function (href, name) {
    fetch(href).then(function (r) { return r.blob(); }).then(function (blob) {
      var reader = new FileReader();
      reader.onload = function () { OpenTuneExport.receive(name || 'playlist.csv', reader.result); };
      reader.readAsDataURL(blob);
    });
  };
  function grab(a) {
    if (a && a.download && /^(blob|data):/.test(a.href)) { window.__openTuneRead(a.href, a.download); return true; }
    return false;
  }
  var dispatch = HTMLAnchorElement.prototype.dispatchEvent;
  HTMLAnchorElement.prototype.dispatchEvent = function (e) {
    if (e && e.type === 'click' && grab(this)) return true;
    return dispatch.call(this, e);
  };
  var click = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    if (grab(this)) return;
    return click.call(this);
  };
  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a[download]') : null;
    if (a && grab(a)) { e.preventDefault(); e.stopPropagation(); }
  }, true);
})();
""".trimIndent()
    }
}
