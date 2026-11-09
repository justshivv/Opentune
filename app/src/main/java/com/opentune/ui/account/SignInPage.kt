package com.opentune.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    extra: (@Composable (closeHelp: () -> Unit) -> Unit)? = null,
    privacy: String? = null,
    web: @Composable (Modifier) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var showHelp by remember { mutableStateOf(false) }
    val initialHint = remember { hint }
    // Static tips live behind Help; a new browser error is brought to the user's attention.
    LaunchedEffect(hint) { if (hint != null && hint != initialHint) showHelp = true }
    Surface(Modifier.fillMaxSize(), color = scheme.background, contentColor = scheme.onBackground) {
        Column(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime))
                .testTag("sign-in-page"),
        ) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close sign-in") }
                if (logo != null) {
                    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(logo), null, Modifier.size(24.dp))
                } else Icon(icon, null, Modifier.size(24.dp), tint = brand)
                Text(service, Modifier.weight(1f).padding(horizontal = 12.dp),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { showHelp = true }) { Icon(Icons.Rounded.Info, "Sign-in help") }
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

internal val YouTubeMusicRed = Color(0xFFFF0033)
internal val SpotifyGreen = Color(0xFF1DB954)
