package com.opentune.ui.spotify

import android.annotation.SuppressLint
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.opentune.data.DebugLog as Log
import com.opentune.data.spotify.ExportedPlaylists
import com.opentune.ui.components.PageHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val EXPORTIFY = "https://exportify.app"
private const val TAG = "Exportify"

/**
 * Exportify (exportify.app) inside the app. You sign in with Spotify there,
 * on Spotify's own login page, and tap Export on a playlist or Export All.
 * Exportify builds the file in the page; instead of saving it to Downloads
 * the app takes it, reads the songs and goes back to the import page with
 * them ready to import.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun ExportifyScreen(contentPadding: PaddingValues, onBack: () -> Unit, onCaught: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var reading by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    BackHandler { if (web?.canGoBack() == true) web?.goBack() else onBack() }

    fun caught(fileName: String, dataUrl: String) {
        reading = true
        scope.launch {
            val playlists = withContext(Dispatchers.Default) {
                runCatching {
                    val bytes = Base64.decode(dataUrl.substringAfter(','), Base64.DEFAULT)
                    ExportedPlaylists.read(fileName, bytes)
                }.onFailure { Log.w(TAG, "couldn't read $fileName", it) }.getOrDefault(emptyList())
            }
            reading = false
            if (playlists.isEmpty()) {
                Toast.makeText(context, "That file had no songs in it", Toast.LENGTH_SHORT).show()
            } else {
                ExportedPlaylists.hand(playlists)
                onCaught()
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        PageHeader("Export from Spotify", onBack = { if (web?.canGoBack() == true) web?.goBack() else onBack() })
        Text(
            "Sign in with Spotify, then tap Export on a playlist, or Export All. OpenTune picks up the file by itself. " +
                "Sign in with your Spotify email or username: Google sign-in doesn't work inside apps.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (loading || reading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (reading) Text("Reading the playlist…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(bottom = contentPadding.calculateBottomPadding()),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun receive(fileName: String, dataUrl: String) {
                                post { caught(fileName, dataUrl) }
                            }
                        },
                        "OpenTuneExport",
                    )
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            // Exportify and Spotify's login stay in here; app links (intent://, spotify:) go nowhere.
                            val scheme = request.url.scheme
                            return scheme != "https" && scheme != "http"
                        }

                        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                            loading = true
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            loading = false
                            if (url?.startsWith(EXPORTIFY) == true) view.evaluateJavascript(CATCH_DOWNLOADS, null)
                        }
                    }
                    // Anything that still turns into a download (a browser saving a blob) is read the same way.
                    setDownloadListener { url, _, disposition, mime, _ ->
                        val name = URLUtil.guessFileName(url, disposition, mime)
                        if (url.startsWith("blob:") || url.startsWith("data:")) {
                            evaluateJavascript("window.__openTuneRead && window.__openTuneRead(${quote(url)}, ${quote(name)})", null)
                        }
                    }
                    loadUrl(EXPORTIFY)
                    web = this
                }
            },
            onRelease = { it.destroy() },
        )
    }
}

private fun quote(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

/**
 * Exportify saves files the FileSaver.js way: a link with a download name
 * and a blob address, clicked by script. This catches that click on any
 * such link, reads the blob as a data URL and passes it to the app.
 */
private val CATCH_DOWNLOADS = """
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
