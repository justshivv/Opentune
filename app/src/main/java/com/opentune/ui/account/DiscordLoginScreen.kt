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
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.opentune.data.discord.DiscordLogin
import com.opentune.data.discord.DiscordRpc
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.launch

private const val DISCORD_LOGIN = "https://discord.com/login"

private const val DISCORD_DESTROY_AFTER_MS = 1_500L
/** Chrome on a Samsung phone, for Motorola's WebView, which Discord's page turns away. */
private const val MOTOROLA_AS_SAMSUNG =
    "Mozilla/5.0 (Linux; Android 14; SM-S921U) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
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
 * Discord's own sign-in page in a WebView, the way in people can see is Discord's.
 * Once signed in and on Discord's app, the account's token is read from the page
 * and handed to [DiscordRpc], which checks it with Discord and keeps it encrypted.
 *
 * When Android takes the page's renderer back to free memory, the page goes blank
 * (and an unhandled loss takes the whole app with it); that's handled, with a Retry
 * that builds a fresh page. Motorola's WebView is turned away by Discord with its
 * own identity, so there it goes as Chrome on a Samsung phone, as SimpMusic does.
 */
@Composable
private fun DiscordWebPage(
    modifier: Modifier,
    onLoading: (Boolean) -> Unit,
    onProblem: (String) -> Unit,
    onSignedIn: () -> Unit,
) {
    // A page whose renderer was taken can't be used again: Retry builds a new one.
    var attempt by remember { mutableStateOf(0) }
    var rendererGone by remember { mutableStateOf(false) }
    if (rendererGone) {
        androidx.compose.foundation.layout.Box(modifier.background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow), contentAlignment = androidx.compose.ui.Alignment.Center) {
            androidx.compose.foundation.layout.Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                com.opentune.ui.theme.Text("Discord's page closed to free memory", style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
                androidx.compose.material3.Button(onClick = { rendererGone = false; attempt++ }, modifier = Modifier.padding(top = 12.dp)) {
                    com.opentune.ui.theme.Text("Retry")
                }
            }
        }
        return
    }
    androidx.compose.runtime.key(attempt) {
        DiscordWebPageView(modifier, onLoading, onProblem, onSignedIn, onRendererGone = { rendererGone = true; onLoading(false) })
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun DiscordWebPageView(
    modifier: Modifier,
    onLoading: (Boolean) -> Unit,
    onProblem: (String) -> Unit,
    onSignedIn: () -> Unit,
    onRendererGone: () -> Unit,
) {
    var pageError by remember { mutableStateOf<String?>(null) }
    val gone = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val taken = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    DisposableEffect(Unit) { onDispose { gone.set(true) } }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val scope = rememberCoroutineScope()
    AndroidView(
        modifier = modifier,
        factory = { context ->
            val view = runCatching {
                CookieManager.getInstance().setAcceptCookie(true)
                WebView(context)
            }.onFailure {
                onProblem("Discord's page needs Android System WebView, which isn't working on this device. Sign in above instead.")
                onLoading(false)
            }.getOrNull() ?: return@AndroidView android.view.View(context)
            view.apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                if (android.os.Build.MANUFACTURER.equals("motorola", ignoreCase = true)) settings.userAgentString = MOTOROLA_AS_SAMSUNG
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                        if (message.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR && pageError == null) {
                            pageError = message.message().take(160)
                        }
                        return super.onConsoleMessage(message)
                    }
                }
                fun check(view: WebView, url: String?) {
                    if (gone.get() || taken.get()) return
                    val path = url?.let { runCatching { android.net.Uri.parse(it).path }.getOrNull() }.orEmpty()
                    if (!path.startsWith("/app") && !path.startsWith("/channels")) return
                    view.evaluateJavascript(READ_TOKEN) { raw ->
                        val token = raw?.trim()?.removeSurrounding("\"").orEmpty()
                        if (token.length < 20 || gone.get() || !taken.compareAndSet(false, true)) return@evaluateJavascript
                        scope.launch {
                            DiscordRpc.signIn(token)
                                .onSuccess { main.post { onSignedIn() } }
                                .onFailure {
                                    onProblem(it.message ?: "Discord didn't accept the sign-in. Try again.")
                                    taken.set(false)
                                }
                        }
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                        val scheme = request.url.scheme?.lowercase()
                        return scheme != "https" && scheme != "http"
                    }
                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { if (!gone.get()) onLoading(true) }
                    override fun onPageFinished(view: WebView, url: String?) {
                        if (gone.get()) return
                        onLoading(false)
                        check(view, url)
                        for (wait in listOf(1_000L, 2_500L, 5_000L, 9_000L)) main.postDelayed({ if (!gone.get()) check(view, view.url) }, wait)
                    }
                    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                        if (gone.get()) return
                        check(view, url)
                        for (wait in listOf(1_500L, 4_000L)) main.postDelayed({ if (!gone.get()) check(view, view.url) }, wait)
                    }

                    // The renderer taken to free memory (or crashed): this page is done, the app isn't.
                    override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                        gone.set(true)
                        (view.parent as? android.view.ViewGroup)?.removeView(view)
                        runCatching { view.destroy() }
                        main.post { onRendererGone() }
                        return true
                    }
                }
                loadUrl(DISCORD_LOGIN)
                main.postDelayed({
                    if (gone.get() || taken.get()) return@postDelayed
                    evaluateJavascript("(document.body && document.body.innerText || '').trim().length") { n ->
                        if (gone.get() || taken.get()) return@evaluateJavascript
                        if ((n?.toIntOrNull() ?: 0) == 0) {
                            onProblem("Discord's page didn't appear" + (pageError?.let { ": $it" } ?: ".") + " Use OpenTune's form or a token above instead.")
                        }
                    }
                }, 15_000)
            }
        },
        onRelease = { view ->
            gone.set(true)
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

/**
 * Signing in to Discord for the Discord card, on Discord's own page first, so it's
 * plainly Discord being signed in to. OpenTune's own form (the email and password
 * straight to Discord, with its captcha and two-factor steps, see [DiscordLogin])
 * and a token are other ways in, for a phone whose WebView won't show the page.
 */
@Composable
fun DiscordLoginScreen(onDone: () -> Unit) {
    var loading by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    // Discord's own page first, so it's plainly Discord being signed in to; the form and a token are other ways in.
    var onPage by remember { mutableStateOf(true) }
    var tokenDialog by remember { mutableStateOf(false) }
    val left = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val leave = { if (left.compareAndSet(false, true)) onDone() }
    androidx.activity.compose.BackHandler { if (onPage) { onPage = false; problem = null } else leave() }

    if (tokenDialog) DiscordTokenDialog(onDismiss = { tokenDialog = false }, onSignedIn = { tokenDialog = false; leave() })
    SignInPage(
        service = "Discord",
        brand = DiscordBlurple,
        icon = Icons.Rounded.Headphones,
        logo = com.opentune.R.drawable.ic_discord,
        gives = "The song you play on your Discord profile, as \"Playing on OpenTune\"",
        loading = loading,
        onBack = leave,
        hint = problem,
        privacy = if (onPage) null else "Your email and password go straight to Discord and aren't kept. Only the sign-in is kept on this phone, encrypted.",
        extra = {
            androidx.compose.foundation.layout.Row(Modifier.padding(horizontal = 12.dp)) {
                androidx.compose.material3.TextButton(onClick = { onPage = !onPage; problem = null; loading = false }) {
                    com.opentune.ui.theme.Text(if (onPage) "Use OpenTune's form" else "Use Discord's page")
                }
                androidx.compose.material3.TextButton(onClick = { tokenDialog = true }) {
                    com.opentune.ui.theme.Text("Sign in with a token")
                }
            }
        },
    ) { webModifier ->
        if (onPage) {
            DiscordWebPage(webModifier, onLoading = { loading = it }, onProblem = { problem = it }, onSignedIn = leave)
        } else {
            DiscordForm(webModifier, onBusy = { loading = it }, onSignedIn = leave)
        }
    }
}

/**
 * Signing in with the account's token, for when Discord's page won't show. The token
 * is checked with Discord before it's kept, and kept encrypted, as a page sign-in is.
 */
@Composable
private fun DiscordTokenDialog(onDismiss: () -> Unit, onSignedIn: () -> Unit) {
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { com.opentune.ui.theme.Text("Sign in with a token") },
        text = {
            androidx.compose.foundation.layout.Column {
                com.opentune.ui.theme.Text(
                    "Your Discord token, from Discord in a computer's browser (Developer tools, Application, Local Storage, token). It's checked with Discord and kept encrypted on this phone. Anyone with it can use your account, so never share it.",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = token, onValueChange = { token = it; error = null }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    label = { com.opentune.ui.theme.Text("Token") },
                    isError = error != null,
                    supportingText = error?.let { e -> { com.opentune.ui.theme.Text(e) } },
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = token.isNotBlank() && !busy, onClick = {
                busy = true
                scope.launch {
                    DiscordRpc.signIn(token).onSuccess { onSignedIn() }.onFailure { error = it.message ?: "Discord didn't accept that token" }
                    busy = false
                }
            }) { com.opentune.ui.theme.Text(if (busy) "Checking…" else "Sign in") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { com.opentune.ui.theme.Text("Cancel") } },
    )
}

/** Where OpenTune's own Discord sign-in is. */
private sealed interface FormStage {
    data object Details : FormStage
    /** Solving Discord's captcha; [retry] sends the step again with the answer. */
    data class Captcha(val asked: DiscordLogin.Step.Captcha, val retry: suspend (DiscordLogin.Solved) -> DiscordLogin.Step) : FormStage
    data class Code(val step: DiscordLogin.Step.TwoFactor, val bySms: Boolean = false) : FormStage
}

@Composable
private fun DiscordForm(modifier: Modifier, onBusy: (Boolean) -> Unit, onSignedIn: () -> Unit) {
    val scheme = androidx.compose.material3.MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var stage by remember { mutableStateOf<FormStage>(FormStage.Details) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    /** Runs one call to Discord and moves on by what it answers. */
    fun go(retry: suspend (DiscordLogin.Solved?) -> DiscordLogin.Step) {
        if (busy) return
        busy = true; onBusy(true); error = null
        scope.launch {
            val step = retry(null)
            handle(step, retry = { solved -> retry(solved) }, onStage = { stage = it }, onError = { error = it }, onSignedIn = onSignedIn) { busy = false; onBusy(false) }
        }
    }

    androidx.compose.foundation.layout.Column(
        modifier.background(scheme.surfaceContainerLow).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(20.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
    ) {
        when (val s = stage) {
            FormStage.Details -> {
                com.opentune.ui.theme.Text("Sign in to Discord", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                androidx.compose.material3.OutlinedTextField(
                    value = login, onValueChange = { login = it; error = null }, singleLine = true,
                    label = { com.opentune.ui.theme.Text("Email or phone number") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Email, imeAction = androidx.compose.ui.text.input.ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                var shown by remember { mutableStateOf(false) }
                androidx.compose.material3.OutlinedTextField(
                    value = password, onValueChange = { password = it; error = null }, singleLine = true,
                    label = { com.opentune.ui.theme.Text("Password") },
                    visualTransformation = if (shown) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    trailingIcon = {
                        androidx.compose.material3.TextButton(onClick = { shown = !shown }) { com.opentune.ui.theme.Text(if (shown) "Hide" else "Show") }
                    },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                FormError(error)
                FormButton(if (busy) "Signing in…" else "Sign in", enabled = login.isNotBlank() && password.isNotEmpty() && !busy) {
                    val l = login; val pw = password
                    go { solved -> DiscordLogin.start(l, pw, solved) }
                }
            }
            is FormStage.Captcha -> {
                com.opentune.ui.theme.Text("Discord asks you to show you're not a bot", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                FormError(error)
                CaptchaView(
                    s.asked,
                    Modifier.fillMaxWidth().height(520.dp),
                    onSolved = { key ->
                        if (busy) return@CaptchaView
                        busy = true; onBusy(true); error = null
                        scope.launch {
                            val step = s.retry(DiscordLogin.Solved(key, s.asked))
                            handle(step, retry = s.retry, onStage = { stage = it }, onError = { error = it }, onSignedIn = onSignedIn) { busy = false; onBusy(false) }
                        }
                    },
                    onFailed = { error = com.opentune.i18n.Tr.f("The captcha didn't work ({0}). Try again.", it) },
                )
                androidx.compose.material3.TextButton(onClick = { stage = FormStage.Details; error = null }) { com.opentune.ui.theme.Text("Back") }
            }
            is FormStage.Code -> {
                com.opentune.ui.theme.Text("Two-factor code", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                com.opentune.ui.theme.Text(
                    if (s.bySms) "Enter the code Discord texted you."
                    else "Enter the 6-digit code from your authenticator app" + if (s.step.backup) ", or an 8-digit backup code." else ".",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = code, onValueChange = { code = it.take(12); error = null }, singleLine = true,
                    label = { com.opentune.ui.theme.Text("Code") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                note?.let { com.opentune.ui.theme.Text(it, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
                FormError(error)
                FormButton(if (busy) "Checking…" else "Sign in", enabled = code.count { it.isDigit() || it.isLetter() } >= 6 && !busy) {
                    val c = code; val ticket = s.step.ticket
                    if (s.bySms) go { _ -> DiscordLogin.smsCode(ticket, c) } else go { solved -> DiscordLogin.code(ticket, c, solved) }
                }
                if (s.step.sms && !s.bySms) {
                    androidx.compose.material3.TextButton(onClick = {
                        scope.launch {
                            val why = DiscordLogin.sendSms(s.step.ticket)
                            if (why == null) { stage = s.copy(bySms = true); note = "Discord sent a code to your phone."; code = "" } else error = why
                        }
                    }) { com.opentune.ui.theme.Text("Text me a code instead") }
                }
                androidx.compose.material3.TextButton(onClick = { stage = FormStage.Details; code = ""; error = null; note = null }) { com.opentune.ui.theme.Text("Start again") }
            }
        }
    }
}

/** Moves the form on by Discord's answer [step]; [done] runs once whatever it was. */
private suspend fun handle(
    step: DiscordLogin.Step,
    retry: suspend (DiscordLogin.Solved) -> DiscordLogin.Step,
    onStage: (FormStage) -> Unit,
    onError: (String) -> Unit,
    onSignedIn: () -> Unit,
    done: () -> Unit,
) {
    when (step) {
        is DiscordLogin.Step.Done -> DiscordRpc.signIn(step.token)
            .onSuccess { onSignedIn() }
            .onFailure { onError(it.message ?: "Discord didn't accept the sign-in. Try again.") }
        is DiscordLogin.Step.Captcha -> onStage(FormStage.Captcha(step, retry))
        is DiscordLogin.Step.TwoFactor -> onStage(FormStage.Code(step))
        is DiscordLogin.Step.Failed -> onError(step.message)
    }
    done()
}

@Composable
private fun FormError(error: String?) {
    if (error == null) return
    com.opentune.ui.theme.Text(error, color = androidx.compose.material3.MaterialTheme.colorScheme.error, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
}

@Composable
private fun FormButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.Button(
        onClick = onClick, enabled = enabled,
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = DiscordBlurple, contentColor = Color.White),
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) { com.opentune.ui.theme.Text(text) }
}

/** Discord's captcha (hCaptcha), on a small page with discord.com as its address so hCaptcha accepts it. */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
private fun CaptchaView(asked: DiscordLogin.Step.Captcha, modifier: Modifier, onSolved: (String) -> Unit, onFailed: (String) -> Unit) {
    val dark = androidx.compose.material3.MaterialTheme.colorScheme.surface.let { (it.red + it.green + it.blue) / 3f < 0.5f }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                addJavascriptInterface(object {
                    @android.webkit.JavascriptInterface fun solved(key: String) { main.post { onSolved(key) } }
                    @android.webkit.JavascriptInterface fun failed(why: String) { main.post { onFailed(why) } }
                }, "OpenTune")
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                        // The puzzle's own frames load as they like; a link it opens in the page (its terms) goes outside.
                        if (!request.isForMainFrame) return false
                        runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, request.url)) }
                        return true
                    }
                }
                loadDataWithBaseURL("https://discord.com/login", DiscordLogin.captchaPage(asked, dark), "text/html", "utf-8", null)
            }
        },
        onRelease = { (it as? WebView)?.run { stopLoading(); destroy() } },
    )
}
