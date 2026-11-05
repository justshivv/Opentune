package com.opentune.ui.account

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import com.opentune.data.discord.DiscordRpc
import kotlinx.coroutines.launch

private const val DISCORD_LOGIN = "https://discord.com/login"
private const val DISCORD_DESTROY_AFTER_MS = 1_500L
private val DiscordBlurple = Color(0xFF5865F2)

/**
 * Reads the signed-in account's token from Discord's page. Discord hides it from
 * the page's own storage once its app has loaded, so a fresh frame's storage is
 * read, and failing that the module Discord's own requests take it from.
 */
private const val READ_TOKEN = """
(function () {
  var unquote = function (raw) { return raw ? String(raw).replace(/^"|"$/g, '') : ''; };
  try { var direct = unquote(window.localStorage && window.localStorage.getItem('token')); if (direct) return direct; } catch (e) {}
  try {
    var frame = document.createElement('iframe');
    frame.style.display = 'none';
    document.body.appendChild(frame);
    var raw = frame.contentWindow.localStorage.getItem('token');
    frame.remove();
    if (raw) return unquote(raw);
  } catch (e) {}
  try {
    var chunks = window.webpackChunkdiscord_app;
    if (!chunks) return '';
    var found = '';
    chunks.push([[Symbol('opentune')], {}, function (req) {
      for (var id in req.c) {
        try {
          var ex = req.c[id] && req.c[id].exports;
          var list = [ex && ex.default, ex];
          for (var i = 0; i < list.length; i++) {
            var c = list[i];
            if (c && typeof c.getToken === 'function') {
              var t = c.getToken.call(c);
              if (typeof t === 'string' && t) { found = t; return; }
            }
          }
        } catch (e) {}
      }
    }]);
    chunks.pop();
    return found;
  } catch (e) { return ''; }
})();
"""

/**
 * Discord's own sign-in page in a WebView, for the Discord card. Once signed in
 * and on Discord's app, the account's token is read from the page and handed to
 * [DiscordRpc], which checks it with Discord and keeps it encrypted. The password
 * only ever goes to Discord's page.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DiscordLoginScreen(onDone: () -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var unavailable by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val gone = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val taken = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val left = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    DisposableEffect(Unit) { onDispose { gone.set(true) } }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val scope = rememberCoroutineScope()
    val leave = { if (!gone.get() && left.compareAndSet(false, true)) onDone() }
    val web = remember { java.util.concurrent.atomic.AtomicReference<WebView?>(null) }
    androidx.activity.compose.BackHandler {
        val page = web.get()
        if (page != null && !left.get() && page.canGoBack()) page.goBack() else leave()
    }

    SignInPage(
        service = "Discord",
        brand = DiscordBlurple,
        icon = Icons.Rounded.Headphones,
        gives = "The song you play on your Discord profile, as \"Listening to OpenTune\"",
        loading = loading,
        onBack = leave,
        hint = problem,
        unavailable = if (unavailable) "Signing in needs Android System WebView, which isn't working on this device." else null,
    ) { webModifier ->
        AndroidView(
            modifier = webModifier,
            factory = { context ->
                val view = runCatching {
                    CookieManager.getInstance().setAcceptCookie(true)
                    WebView(context)
                }.onFailure {
                    unavailable = true
                    loading = false
                }.getOrNull() ?: return@AndroidView android.view.View(context)
                view.apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    fun check(view: WebView, url: String?) {
                        if (gone.get() || taken.get()) return
                        val path = url?.let { runCatching { android.net.Uri.parse(it).path }.getOrNull() }.orEmpty()
                        // Signed in: Discord moves on to its app.
                        if (!path.startsWith("/app") && !path.startsWith("/channels")) return
                        view.evaluateJavascript(READ_TOKEN) { raw ->
                            val token = raw?.trim()?.removeSurrounding("\"").orEmpty()
                            if (token.length < 20 || gone.get() || !taken.compareAndSet(false, true)) return@evaluateJavascript
                            scope.launch {
                                DiscordRpc.signIn(token)
                                    .onSuccess { main.post { leave() } }
                                    .onFailure {
                                        problem = it.message ?: "Discord didn't accept the sign-in. Try again."
                                        taken.set(false)
                                    }
                            }
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                            if (!gone.get()) loading = true
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            if (gone.get()) return
                            loading = false
                            check(view, url)
                            for (wait in listOf(1_000L, 2_500L, 5_000L, 9_000L)) main.postDelayed({ if (!gone.get()) check(view, view.url) }, wait)
                        }

                        // Discord's pages change address without loading a new page.
                        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                            if (gone.get()) return
                            check(view, url)
                            for (wait in listOf(1_500L, 4_000L)) main.postDelayed({ if (!gone.get()) check(view, view.url) }, wait)
                        }
                    }
                    loadUrl(DISCORD_LOGIN)
                    web.set(this)
                }
            },
            onRelease = { view ->
                gone.set(true)
                web.set(null)
                (view as? WebView)?.run {
                    stopLoading()
                    onPause()
                    webViewClient = WebViewClient()
                    (parent as? android.view.ViewGroup)?.removeView(this)
                    main.postDelayed({ runCatching { destroy() } }, DISCORD_DESTROY_AFTER_MS)
                }
            },
        )
    }
}
