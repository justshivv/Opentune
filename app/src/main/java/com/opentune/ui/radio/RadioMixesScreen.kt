package com.opentune.ui.radio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opentune.data.MusicRepository
import com.opentune.data.RadioMixes
import com.opentune.data.account.AccountStore
import com.opentune.data.model.CARD_ART_PX
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.artworkAt
import com.opentune.ui.cardMenu
import com.opentune.ui.components.AccountButton
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.ErrorState
import com.opentune.ui.components.ItemCard
import com.opentune.ui.components.MessageState
import com.opentune.ui.components.PageHeader
import com.opentune.ui.theme.AppIcons
import com.opentune.ui.theme.Icon
import com.opentune.ui.theme.Text
import com.opentune.ui.theme.rememberArtworkPalette
import com.opentune.ui.type
import kotlinx.coroutines.CancellationException

/**
 * The Radio tab: YouTube Music's mixes made for the account. The big
 * ones (Supermix, Discover, New Release, Replay, Archive, and Liked Music) are tiles washed
 * in their covers' colours; the numbered "My Mix" cards run large in a row of their own;
 * each mood follows with its Supermix and numbered mixes. Live stations are at the foot.
 */
@Composable
fun RadioMixesScreen(
    contentPadding: PaddingValues,
    onItemClick: (ShelfItem) -> Unit,
    onOpenLiked: () -> Unit,
    onOpenStations: () -> Unit,
    onSignIn: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val signedIn by AccountStore.signedIn.collectAsState()
    var page by remember { mutableStateOf(MusicRepository.cachedMixes()?.let(RadioMixes::arrange)) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(signedIn, attempt) {
        if (!signedIn) return@LaunchedEffect
        failed = false
        try {
            page = RadioMixes.arrange(MusicRepository.mixedForYou(fresh = attempt > 0))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            com.opentune.data.DebugLog.w("Radio", "mixed for you failed", e)
            if (page == null) failed = true
        }
    }
    RadioMixesPage(contentPadding, page, signedIn, failed, { attempt++ }, onItemClick, onOpenLiked, onOpenStations, onSignIn, onOpenSettings)
}

/** The page itself, from what [RadioMixesScreen] has read: null while it's still coming. */
@Composable
internal fun RadioMixesPage(
    contentPadding: PaddingValues,
    page: RadioMixes.Page?,
    signedIn: Boolean,
    failed: Boolean,
    onRetry: () -> Unit,
    onItemClick: (ShelfItem) -> Unit,
    onOpenLiked: () -> Unit,
    onOpenStations: () -> Unit,
    onSignIn: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val list = rememberLazyListState()
    LaunchedEffect(list) {
        com.opentune.ui.TabEvents.reselected.collect { if (it == RADIO_ROUTE) list.animateScrollToItem(0) }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val side = 16.dp
        val gap = 12.dp
        val tileWidth = (maxWidth - side * 2 - gap * 2) / 3
        val bigWidth = (maxWidth - side * 2) * 0.62f
        val cardWidth = (maxWidth - side * 2 - gap * 2) / 2.4f
        LazyColumn(state = list, contentPadding = contentPadding, modifier = Modifier.fillMaxSize().testTag("radio-mixes")) {
            item(key = "header") {
                PageHeader(com.opentune.ui.theme.tr("Radio"), actions = { AccountButton(onOpenSettings) })
            }
            val shown = page
            item(key = "tiles") {
                val tiles: List<@Composable (Modifier) -> Unit> = buildList {
                    when {
                        shown != null -> shown.tiles.forEach { mix -> add { m -> MixTile(mix.title, mix.thumbnailUrl, null, { onItemClick(mix) }, m) } }
                        // Still on its way: the tiles' places, so the page doesn't jump when they land.
                        signedIn && !failed -> repeat(5) { add { m -> TilePlaceholder(m) } }
                    }
                    add { m -> MixTile(com.opentune.ui.theme.tr("Liked Music"), null, LIKED_COLORS, onOpenLiked, m) }
                }
                Column(Modifier.padding(horizontal = side).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(gap)) {
                    tiles.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            row.forEach { it(Modifier.width(tileWidth)) }
                        }
                    }
                }
            }
            when {
                !signedIn -> item(key = "signin") {
                    MessageState(
                        icon = ImageVector.vectorResource(AppIcons.Radio),
                        title = "Your mixes come from YouTube Music",
                        message = "Sign in to see your Supermix, Discover Mix, Replay Mix and a mix for each mood.",
                        action = { Button(onClick = onSignIn) { Text("Sign in") } },
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
                failed -> item(key = "error") {
                    ErrorState("YouTube Music didn't send your mixes just now.", onRetry = onRetry, modifier = Modifier.padding(vertical = 24.dp))
                }
                shown != null -> {
                    if (shown.yours.isNotEmpty()) {
                        item(key = "yours-title") { RowTitle(com.opentune.ui.theme.tr("Your Mixes")) }
                        item(key = "yours") {
                            LazyRow(contentPadding = PaddingValues(horizontal = side), horizontalArrangement = Arrangement.spacedBy(gap)) {
                                items(shown.yours, key = { it.browseId ?: it.title }) { mix -> BigMixCard(mix, bigWidth) { onItemClick(mix) } }
                            }
                        }
                    }
                    (shown.moods + shown.others).forEachIndexed { i, row ->
                        item(key = "row-$i-title") { RowTitle(row.title) }
                        item(key = "row-$i") {
                            LazyRow(contentPadding = PaddingValues(horizontal = side), horizontalArrangement = Arrangement.spacedBy(gap)) {
                                items(row.items, key = { it.browseId ?: it.title }) { mix ->
                                    ItemCard(mix.title, mix.subtitle, mix.thumbnailUrl, mix.type(), onClick = { onItemClick(mix) }, width = cardWidth, explicit = mix.explicit, menu = mix.cardMenu())
                                }
                            }
                        }
                    }
                }
            }
            item(key = "stations") { StationsRow(onOpenStations, Modifier.padding(horizontal = side).padding(top = 28.dp, bottom = 16.dp)) }
        }
    }
}

/** The route of the Radio tab. */
const val RADIO_ROUTE = "mixes"

/** Liked Music's tile: no cover of its own, so a violet that warms to pink. */
private val LIKED_COLORS = listOf(Color(0xFF8A5CD6), Color(0xFFD45AA8))

@Composable
private fun RowTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 12.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * A square of tinted glass: the cover's two main colours from corner to corner under a soft
 * light, a hairline rim, the mix's wave in the middle and its name along the foot.
 */
@Composable
private fun MixTile(label: String, artUrl: String?, colors: List<Color>?, onClick: () -> Unit, modifier: Modifier) {
    val palette = colors ?: rememberArtworkPalette(artUrl?.artworkAt(160))
    val scheme = MaterialTheme.colorScheme
    val a = palette.getOrNull(0) ?: scheme.surfaceContainerHighest
    val b = palette.getOrNull(1) ?: palette.getOrNull(0) ?: scheme.surfaceContainerHigh
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(Brush.linearGradient(listOf(lerp(a, Color.White, 0.08f), lerp(a, b, 0.5f), lerp(b, Color.Black, 0.35f))))
            .background(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent), radius = 420f))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)), shape)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        // The OpenTune mark (the launcher icon's, whose shape fills the middle half of its square).
        Icon(
            androidx.compose.ui.res.painterResource(com.opentune.R.drawable.ic_launcher_monochrome), null,
            Modifier.align(Alignment.Center).padding(bottom = 18.dp).size(92.dp),
            tint = Color.White,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 6.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun TilePlaceholder(modifier: Modifier) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier.aspectRatio(1f).clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), shape),
    )
}

/** A "My Mix" card: the cover large, with its name and artists on a band of the cover's own colour. */
@Composable
private fun BigMixCard(mix: ShelfItem, width: Dp, onClick: () -> Unit) {
    val palette = rememberArtworkPalette(mix.thumbnailUrl?.artworkAt(160))
    val scheme = MaterialTheme.colorScheme
    val tone = palette.getOrNull(0) ?: scheme.surfaceContainerHighest
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier.width(width).clip(shape)
            .background(lerp(tone, Color.Black, 0.62f))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)), shape)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Artwork(mix.thumbnailUrl.artworkAt(CARD_ART_PX), Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(0.dp))
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(mix.title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                mix.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = lerp(tone, Color.White, 0.55f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.height(44.dp),
            )
        }
    }
}

/** Live stations, the old Radio page, from the foot of this one. */
@Composable
private fun StationsRow(onClick: () -> Unit, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(ImageVector.vectorResource(AppIcons.Radio), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Live radio stations", style = MaterialTheme.typography.titleMedium)
            Text("Stations near you, by country and by genre", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(ImageVector.vectorResource(AppIcons.KeyboardArrowRight), null, Modifier.size(22.dp))
    }
}
