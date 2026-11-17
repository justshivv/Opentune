package com.opentune.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opentune.data.lyrics.LyricsTranslator
import com.opentune.data.lyrics.Romanizer
import com.opentune.data.settings.AppSettings
import com.opentune.ui.components.FloatingCard
import com.opentune.ui.components.SheetButton
import com.opentune.ui.components.SheetTone
import com.opentune.ui.theme.Icon
import com.opentune.ui.theme.Text
import java.util.Locale
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.opentune.ui.theme.AppIcons

/** The language offered first: the phone's own, when Google Translate has it. */
private fun defaultTarget(): String {
    val code = when (val lang = Locale.getDefault().language) {
        "zh" -> "zh-CN"
        "in" -> "id"
        "iw" -> "he"
        else -> lang
    }
    return LyricsTranslator.LANGUAGES.firstOrNull { it.code == code }?.code ?: "en"
}

/**
 * One sheet for the words on the lyrics page: romanized lines under another
 * script, and a translation under every line. Picking a language and tapping
 * Translate fetches it there and then, so a failure is answered here, with a
 * retry — the page itself just shows what the sheet won.
 */
@Composable
internal fun LyricsTextSheet(lines: List<String>, synced: Boolean, onDismiss: () -> Unit) {
    val settings by AppSettings.lyrics.collectAsState()
    val scope = rememberCoroutineScope()
    var picked by remember {
        mutableStateOf(settings.translateTo.ifBlank { settings.translateTarget.ifBlank { defaultTarget() } })
    }
    var working by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    FloatingCard(
        onDismiss = onDismiss,
        title = "Translate",
        icon = ImageVector.vectorResource(AppIcons.Translate),
        subtitle = if (settings.translateTo.isNotBlank()) {
            "Showing the translation into ${LyricsTranslator.languageName(settings.translateTo) ?: settings.translateTo}"
        } else {
            "A translation under every line"
        },
        // The language list is long: open at half height, pulled up to the whole page.
        expandable = true,
        fitContent = false,
        fullFraction = 1f,
        actions = {
            if (settings.translateTo.isNotBlank()) {
                Box(Modifier.weight(1f)) {
                    SheetButton("Show original", tone = SheetTone.Neutral, onClick = {
                        AppSettings.updateLyrics { it.copy(translateTo = "") }
                        onDismiss()
                    })
                }
            }
            Box(Modifier.weight(1.4f)) {
                SheetButton("Translate", enabled = !working, loading = working, spinnerOnly = true, tone = SheetTone.Primary, onClick = {
                    working = true
                    failed = false
                    val target = picked
                    scope.launch {
                        val won = LyricsTranslator.translate(lines, target).isSuccess
                        working = false
                        if (won) {
                            AppSettings.updateLyrics { it.copy(translateTo = target, translateTarget = target) }
                            onDismiss()
                        } else {
                            failed = true
                        }
                    }
                })
            }
        },
    ) {
        // One lazy list for the whole body: the ninety-odd languages compose only
        // as they show, instead of every row being built and measured up front.
        LazyColumn(Modifier.weight(1f)) {
            if (synced && Romanizer.available) {
                item {
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Romanized lyrics")
                            Text(
                                "Latin letters under each line in another script",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = settings.romanize,
                            onCheckedChange = { v -> AppSettings.updateLyrics { it.copy(romanize = v) } },
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                }
            }
            item {
                Text(
                    "Translate to",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            items(LyricsTranslator.LANGUAGES, key = { it.code }) { lang ->
                Row(
                    Modifier.fillMaxWidth().clickable { picked = lang.code }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        lang.name,
                        Modifier.weight(1f),
                        fontWeight = if (lang.code == picked) androidx.compose.ui.text.font.FontWeight.SemiBold else null,
                    )
                    if (lang.code == picked) Icon(ImageVector.vectorResource(AppIcons.Check), null, Modifier.size(20.dp))
                }
            }
            if (failed) {
                item {
                    Text(
                        "Couldn't translate just now. Check your connection and try again.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }
    }
}
