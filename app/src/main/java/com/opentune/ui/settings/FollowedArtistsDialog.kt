package com.opentune.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.releases.NewReleases
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.FIELD_TINT
import com.opentune.ui.components.FloatingCard
import com.opentune.ui.theme.Icon
import com.opentune.ui.theme.Text
import kotlinx.coroutines.launch

/**
 * The artists followed for new-release alerts: each with their photo, since when, and a
 * Following button that unfollows (and turns into Follow, to take it back while the page is
 * open). At the top, a check for new releases right now and how it went; with many artists,
 * a field to find one.
 */
@Composable
fun FollowedArtistsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val followed by NewReleases.followed.collectAsState()
    // Unfollowed while the page is open: kept in the list, to follow again with one tap.
    var dropped by remember { mutableStateOf(emptyList<NewReleases.Followed>()) }
    val shown = remember(followed, dropped) { (followed + dropped.filter { d -> followed.none { it.browseId == d.browseId } }).sortedByDescending { it.followedAt } }
    var status by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val c = MaterialTheme.colorScheme
    FloatingCard(
        onDismiss = onDismiss,
        title = "Followed artists",
        subtitle = when (followed.size) {
            0 -> com.opentune.i18n.Tr.t("No one yet")
            1 -> com.opentune.i18n.Tr.t("1 artist · alerts for new releases")
            else -> com.opentune.i18n.Tr.f("{0} artists · alerts for new releases", followed.size)
        },
        // Opens most of the way up; dragged or scrolled up, it takes over the page.
        expandable = true,
        fitContent = false,
        fullFraction = 1f,
        openFraction = com.opentune.ui.components.OPEN_FRACTION,
        header = {
            if (followed.isNotEmpty()) Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 8.dp).clip(RoundedCornerShape(18.dp))
                    .background(c.onSurface.copy(alpha = FIELD_TINT)).clickable(enabled = !checking, role = Role.Button) {
                        checking = true
                        status = null
                        scope.launch {
                            status = runCatching { NewReleases.check() }.fold(
                                { found ->
                                    NewReleases.notify(context, found)
                                    if (found.isEmpty()) com.opentune.i18n.Tr.t("Nothing new since the last check")
                                    else com.opentune.i18n.Tr.f("{0} new, in your notifications", found.size)
                                },
                                { com.opentune.i18n.Tr.t("Couldn't check. Try again in a moment") },
                            )
                            checking = false
                        }
                    }.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(c.primary), contentAlignment = Alignment.Center) {
                    if (checking) CircularProgressIndicator(Modifier.size(18.dp), color = c.onPrimary, strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.Refresh, null, tint = c.onPrimary, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("Check for new releases", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(status ?: if (checking) "Checking…" else "Also checked by itself every 12 hours",
                        style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (shown.size > SEARCH_FROM) Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 8.dp).height(48.dp).clip(RoundedCornerShape(16.dp))
                    .background(c.onSurface.copy(alpha = FIELD_TINT)).padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = c.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Box(Modifier.weight(1f).padding(start = 10.dp)) {
                    if (query.isEmpty()) Text("Find an artist", style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
                    BasicTextField(query, { query = it }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onSurface),
                        cursorBrush = SolidColor(c.primary), modifier = Modifier.fillMaxWidth())
                }
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear", Modifier.size(20.dp)) }
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
            if (shown.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(64.dp).clip(CircleShape).background(c.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.NotificationsActive, null, tint = c.primary, modifier = Modifier.size(30.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("Follow artists to hear about new music", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Text("Open an artist and tap Follow. New albums and singles from them show up as a notification.",
                        style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                }
            }
            val q = query.trim()
            shown.filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }.forEach { a ->
                val on = followed.any { it.browseId == a.browseId }
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(a.thumbnailUrl, Modifier.size(52.dp), CircleShape, placeholder = Icons.Rounded.Person)
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(a.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (on) com.opentune.i18n.Tr.f("Following since {0}", since(a.followedAt)) else com.opentune.i18n.Tr.t("Not following any more"),
                            style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1,
                        )
                    }
                    // Following: outlined; tap to stop. Follow: filled, to take it back.
                    Box(
                        Modifier.clip(RoundedCornerShape(50))
                            .then(if (on) Modifier.border(1.dp, c.outline, RoundedCornerShape(50)) else Modifier.background(c.onSurface))
                            .clickable(role = Role.Button) {
                                if (on) {
                                    dropped = dropped.filterNot { it.browseId == a.browseId } + a
                                    NewReleases.unfollow(context, a.browseId)
                                } else {
                                    NewReleases.follow(context, a.browseId, a.name, a.thumbnailUrl)
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(if (on) "Following" else "Follow", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                            color = if (on) c.onSurface else c.surface)
                    }
                }
            }
        }
    }
}

/** "March 2026", or "today" for one followed today. */
private fun since(at: Long): String {
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = at }
    if (now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) && now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)) {
        return com.opentune.i18n.Tr.t("today")
    }
    return java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(at))
}

/** From this many artists, a field to find one. */
private const val SEARCH_FROM = 8
