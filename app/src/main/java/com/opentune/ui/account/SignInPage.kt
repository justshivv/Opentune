package com.opentune.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.opentune.ui.components.GlassBackButton

/**
 * A service's own sign-in page inside the app, with OpenTune around it: who
 * it's for and what it's used for, a line on what's kept, and the page in a
 * rounded card below with a thin bar while it loads. [web] draws the page in
 * the modifier it's handed. Shared by YouTube Music's and Spotify's sign-in.
 */
@Composable
internal fun SignInPage(
    service: String,
    brand: Color,
    icon: ImageVector,
    /** What signing in brings, in a sentence. */
    gives: String,
    loading: Boolean,
    onBack: () -> Unit,
    /** Set when the phone can't show the page at all, to say why. */
    unavailable: String? = null,
    /** A short tip under the header, e.g. which sign-in methods work here. */
    hint: String? = null,
    web: @Composable (Modifier) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // A Surface, so text takes the theme's colour on the page's background.
    androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = scheme.background, contentColor = scheme.onBackground) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.navigationBars).union(WindowInsets.ime)),
    ) {
        Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
            GlassBackButton(onBack, Modifier.size(48.dp))
            Text(
                "Sign in",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(brand), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(service, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(gives, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
        }
        Row(
            Modifier
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(scheme.surfaceContainerHigh)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Rounded.Lock, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Text(
                "You sign in on $service's own page. Only the sign-in is kept on this phone; your password never reaches OpenTune.",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
        }
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
        val card = RoundedCornerShape(24.dp)
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = if (hint == null) 12.dp else 4.dp, bottom = 12.dp)
                .clip(card)
                .border(1.dp, scheme.outlineVariant, card)
                .background(Color.White),
        ) {
            if (unavailable == null) {
                web(Modifier.fillMaxSize())
            } else {
                Text(
                    unavailable,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = scheme.onSurface,
                    modifier = Modifier.fillMaxSize().background(scheme.surfaceContainer).padding(32.dp).align(Alignment.Center),
                )
            }
            val shown by androidx.compose.animation.core.animateFloatAsState(if (loading && unavailable == null) 1f else 0f, label = "signInLoading")
            if (shown > 0f) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().height(3.dp).graphicsLayer { alpha = shown },
                    color = brand,
                    trackColor = Color.Transparent,
                )
            }
        }
    }
    }
}

/** YouTube Music's red and Spotify's green, for the sign-in pages' marks. */
internal val YouTubeMusicRed = Color(0xFFFF0033)
internal val SpotifyGreen = Color(0xFF1DB954)
