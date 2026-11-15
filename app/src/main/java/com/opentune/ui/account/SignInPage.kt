package com.opentune.ui.account

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.opentune.ui.components.FloatingCard
import com.opentune.ui.theme.Icon
import com.opentune.ui.theme.Text

/** A full-page browser. Help never takes space away from the service's sign-in form. */
@Composable
internal fun SignInPage(
    service: String,
    brand: Color,
    icon: ImageVector,
    gives: String,
    loading: Boolean,
    onBack: () -> Unit,
    unavailable: String? = null,
    hint: String? = null,
    logo: Int? = null,
    url: String? = null,
    pageColor: Color? = null,
    extra: (@Composable (closeHelp: () -> Unit) -> Unit)? = null,
    privacy: String? = null,
    web: @Composable (Modifier) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // The strips above and below the page wear the page's own background colour, read from
    // the page itself, so the screen reads as one full-bleed page while the page's own
    // words stay in their box under the header.
    val pageBackground = pageColor ?: scheme.background
    val view = LocalView.current
    DisposableEffect(pageBackground) {
        val controller = (view.context as? android.app.Activity)?.window?.let { WindowCompat.getInsetsController(it, view) }
        val oldStatus = controller?.isAppearanceLightStatusBars
        val oldNavigation = controller?.isAppearanceLightNavigationBars
        if (controller != null) {
            val dark = com.opentune.ui.theme.darkSystemIcons(pageBackground)
            controller.isAppearanceLightStatusBars = dark
            controller.isAppearanceLightNavigationBars = dark
        }
        onDispose {
            if (controller != null) {
                if (oldStatus != null) controller.isAppearanceLightStatusBars = oldStatus
                if (oldNavigation != null) controller.isAppearanceLightNavigationBars = oldNavigation
            }
        }
    }
    var showHelp by remember { mutableStateOf(false) }
    val initialHint = remember { hint }
    // Static tips live behind Help; a new browser error is brought to the user's attention.
    LaunchedEffect(hint) { if (hint != null && hint != initialHint) showHelp = true }
    Surface(Modifier.fillMaxSize(), color = pageBackground, contentColor = scheme.onBackground) {
        Column(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.exclude(WindowInsets.navigationBars).union(WindowInsets.ime))
                .testTag("sign-in-page"),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    onClick = onBack,
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = scheme.surfaceContainerHigh,
                    contentColor = scheme.onSurface,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close sign-in", Modifier.size(22.dp))
                    }
                }
                Row(
                    Modifier.weight(1f).height(48.dp)
                        .background(scheme.surfaceContainerHigh, androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (logo != null) {
                        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(logo), null, Modifier.size(24.dp))
                    } else Icon(icon, null, Modifier.size(24.dp), tint = brand)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(service,
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (url != null) Text(url,
                            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Surface(
                    onClick = { showHelp = true },
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = scheme.surfaceContainerHigh,
                    contentColor = scheme.onSurface,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Info, "Sign-in help", Modifier.size(22.dp))
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().testTag("sign-in-browser")) {
                if (unavailable == null) web(Modifier.fillMaxSize())
                else Box(Modifier.fillMaxSize().background(scheme.surfaceContainer).padding(24.dp), Alignment.Center) {
                    Text(unavailable, Modifier.verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                }
                if (loading && unavailable == null) LinearProgressIndicator(
                    Modifier.fillMaxWidth().height(3.dp), color = brand, trackColor = Color.Transparent,
                )
            }
        }
    }
    if (showHelp) FloatingCard(onDismiss = { showHelp = false }, title = service, icon = icon) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(gives, style = MaterialTheme.typography.bodyLarge)
            Text(privacy ?: com.opentune.i18n.Tr.f(
                "You sign in on {0}'s own page. Only the sign-in is kept on this phone; your password never reaches OpenTune.", service),
                style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodyMedium)
            extra?.invoke { showHelp = false }
        }
    }
}

/** Asks the page for its own background colour; calls back only with a colour worth using. */
internal fun WebView.probePageColor(onColor: (Color) -> Unit) {
    evaluateJavascript(
        "(function(){function bg(e){if(!e)return '';var c=getComputedStyle(e).backgroundColor;" +
                "return (c&&c!=='rgba(0, 0, 0, 0)'&&c!=='transparent')?c:'';}" +
                "return bg(document.body)||bg(document.documentElement);})()",
    ) { raw -> parseCssColor(raw)?.let(onColor) }
}

private fun parseCssColor(raw: String?): Color? {
    val text = raw?.trim()?.removeSurrounding("\"") ?: return null
    val parts = text.removePrefix("rgba(").removePrefix("rgb(").removeSuffix(")")
        .split(',').map { it.trim().toFloatOrNull() ?: return null }
    if (parts.size < 3) return null
    if (parts.size > 3 && parts[3] <= 0f) return null
    return Color(parts[0].toInt().coerceIn(0, 255), parts[1].toInt().coerceIn(0, 255), parts[2].toInt().coerceIn(0, 255))
}

internal val YouTubeMusicRed = Color(0xFFFF0033)
internal val SpotifyGreen = Color(0xFF1DB954)
