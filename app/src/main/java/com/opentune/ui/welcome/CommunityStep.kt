package com.opentune.ui.welcome

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.opentune.ui.components.Support
import com.opentune.ui.components.glass
import com.opentune.ui.settings.DISCORD_BLURPLE
import com.opentune.ui.settings.discordLogo
import com.opentune.ui.settings.githubLogo
import com.opentune.ui.theme.AppIcons
import com.opentune.ui.theme.Icon
import com.opentune.ui.theme.Text

/** The project's two front doors, as the remote config names them when they're opened. */
internal object CommunityLinks {
    /** OpenTune on GitHub, read when opened so a link changed from the dashboard is followed. */
    val github: String get() = Support.REPO
    val discord: String get() = Support.DISCORD

    fun intent(url: String): Intent = Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)

    /** Opens [url] in the browser, or Discord's app for its invites; false when nothing here can. */
    fun open(context: Context, url: String): Boolean = try {
        context.startActivity(intent(url))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    /** "github.com/justshivv/Opentune" from the link: what to type where nothing opens it. */
    fun shown(url: String): String = url.substringAfter("://").removePrefix("www.").trimEnd('/')
}

/**
 * The last step: an invitation to star OpenTune on GitHub and to join its Discord.
 * Each only opens the link; nothing is done on anyone's account, and both are optional.
 */
@Composable
internal fun CommunityStep(reduceMotion: Boolean) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(topInset()))
        Column(Modifier.widthIn(max = 600.dp).fillMaxWidth()) {
            StepTitle(
                "Made together",
                "OpenTune is free and open source, and it grows with the people who listen on it. Both of these are optional.",
            )
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CommunityCard(
                    logo = githubLogo,
                    accent = MaterialTheme.colorScheme.onSurface,
                    onAccent = MaterialTheme.colorScheme.surface,
                    title = "Star OpenTune on GitHub",
                    line = "A star helps more listeners find the app. It opens GitHub, where you can star it if you like.",
                    url = { CommunityLinks.github },
                    reduceMotion = reduceMotion,
                    context = context,
                )
                CommunityCard(
                    logo = discordLogo,
                    accent = DISCORD_BLURPLE,
                    onAccent = Color.White,
                    title = "Join the Discord",
                    line = "Talk to other listeners, report bugs and hear about new features first.",
                    url = { CommunityLinks.discord },
                    reduceMotion = reduceMotion,
                    context = context,
                )
            }
        }
        Spacer(Modifier.height(bottomInset() + 16.dp))
    }
}

@Composable
private fun CommunityCard(
    logo: ImageVector,
    accent: Color,
    onAccent: Color,
    title: String,
    line: String,
    url: () -> String,
    reduceMotion: Boolean,
    context: Context,
) {
    val shape = RoundedCornerShape(24.dp)
    var opened by rememberSaveable(title) { mutableStateOf(false) }
    var unopenable by rememberSaveable(title) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            .tile(shape, reduceMotion) {
                val ok = CommunityLinks.open(context, url())
                opened = opened || ok
                unopenable = !ok
            }
            .glass(shape, touchResponse = false)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).background(accent, CircleShape), contentAlignment = Alignment.Center) {
            Icon(logo, null, Modifier.size(28.dp), tint = onAccent)
        }
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(line, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            // Where nothing opens links (a TV without a browser), the address to visit from another device.
            if (unopenable) {
                Text(
                    com.opentune.i18n.Tr.f("Nothing here can open it. Visit {0} on another device.", CommunityLinks.shown(url())),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                androidx.compose.material3.Text(
                    CommunityLinks.shown(url()),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        Icon(
            ImageVector.vectorResource(if (opened) AppIcons.CheckCircle else AppIcons.OpenInNew),
            if (opened) "Opened" else null,
            Modifier.size(22.dp),
            tint = if (opened) scheme.primary else scheme.onSurfaceVariant,
        )
    }
}
