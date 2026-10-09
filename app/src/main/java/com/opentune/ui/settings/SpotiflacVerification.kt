package com.opentune.ui.settings

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.opentune.data.lossless.LosslessRegistry
import com.opentune.data.lossless.SpotiflacSession
import kotlinx.coroutines.delay
import org.json.JSONTokener

/** The user completes verification. There is no native JavaScript bridge in remote frames. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun SpotiflacVerification(onDismiss: () -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var active by remember { mutableStateOf(true) }
    DisposableEffect(Unit) {
        onDispose { active = false; webView?.stopLoading(); webView?.destroy() }
    }
    LaunchedEffect(webView) {
        val view = webView ?: return@LaunchedEffect
        while (true) {
            view.evaluateJavascript("JSON.stringify(window.opentuneSession || null)") { result ->
                if (active) {
                    val json = runCatching { JSONTokener(result).nextValue() as? String }.getOrNull()
                    if (json != null && json != "null") {
                        if (SpotiflacSession.save(json)) {
                            active = false
                            onDismiss()
                        } else {
                            view.evaluateJavascript("window.opentuneSession=null;statusText('The session is missing a valid expiry. Please try again later.');", null)
                        }
                    }
                }
            }
            delay(500)
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(16.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp)) {
                Text("Verify SpotiFLAC", style = MaterialTheme.typography.titleLarge)
                Text("Optional community provider. Complete the check below yourself. Public providers also work without verification.", Modifier.padding(vertical = 8.dp))
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.isForMainFrame
                            }
                            loadDataWithBaseURL(LosslessRegistry.SESSION_BASE, context.assets.open("spotiflac_verify.html").bufferedReader().use { it.readText() }, "text/html", "utf-8", null)
                            webView = this
                        }
                    },
                )
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}
