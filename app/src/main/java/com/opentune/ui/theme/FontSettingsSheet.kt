package com.opentune.ui.theme

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentune.data.settings.AppFont
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.TextWeight
import com.opentune.ui.components.FIELD_TINT
import com.opentune.ui.components.FloatingCard
import com.opentune.ui.components.launchSafely
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fonts & readability: a preview at the top that changes as you choose, the typefaces as tiles
 * drawn in their own face (yours too, once imported, with a tile to import one), and the text
 * weight as three steps, each written in its own weight. Lyrics follow the same font.
 */
@Composable
fun FontSettingsSheet(onDismiss: () -> Unit) {
    val theme by AppSettings.theme.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true; message = null; failed = false
            try {
                val (id, name) = withContext(Dispatchers.IO) { ImportedFonts.importFont(context, uri) }
                val previous = AppSettings.theme.value.customFontId
                AppSettings.updateTheme { it.copy(font = AppFont.CUSTOM, customFontId = id, customFontName = name) }
                withContext(Dispatchers.IO) { ImportedFonts.remove(context, previous) }
                message = com.opentune.i18n.Tr.f("Imported {0}", name)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                failed = true
                message = if (e is IllegalArgumentException) e.message else "Couldn't read this font. Try another .ttf or .otf file."
            }
            finally { importing = false }
        }
    }
    val c = MaterialTheme.colorScheme
    val family = rememberAppFont(theme)
    val weight = FontWeight(theme.textWeight.minimum)
    FloatingCard(
        onDismiss = onDismiss,
        title = "Fonts & readability",
        icon = Icons.Rounded.TextFields,
        expandable = true,
        fitContent = false,
        fullFraction = 1f,
        openFraction = com.opentune.ui.components.OPEN_FRACTION,
    ) {
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 4.dp).padding(bottom = 12.dp)) {
            // What it will look like, in the font and weight chosen now.
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                    .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(c.primary.copy(alpha = 0.22f), c.tertiary.copy(alpha = 0.10f))))
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                Text("MADE FOR YOU", style = MaterialTheme.typography.labelMedium, fontFamily = family, color = c.primary, letterSpacing = 1.sp)
                Text("Music feels closer.", style = MaterialTheme.typography.headlineSmall, fontFamily = family,
                    fontWeight = FontWeight(maxOf(600, theme.textWeight.minimum)), modifier = Modifier.padding(top = 4.dp))
                Text("And every word of it sings along with you", style = MaterialTheme.typography.titleMedium, fontFamily = family,
                    fontWeight = weight, color = c.onSurface.copy(alpha = 0.85f), modifier = Modifier.padding(top = 10.dp))
                Text("Quick picks · Your genres · 3:42", style = MaterialTheme.typography.bodyMedium, fontFamily = family,
                    fontWeight = weight, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }

            Label("Typeface")
            val fonts = AppFont.entries.filter { it != AppFont.CUSTOM || theme.customFontId.isNotEmpty() }
            // The fonts, then the import tile (null), two to a row.
            (fonts + listOf<AppFont?>(null)).chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { font ->
                        if (font == null) {
                            ImportTile(importing, Modifier.weight(1f)) { picker.launchSafely(context, arrayOf("*/*")) }
                        } else {
                            val available = font != AppFont.CUSTOM || ImportedFonts.file(context, theme.customFontId) != null
                            FontTile(
                                name = if (font == AppFont.CUSTOM) theme.customFontName.ifBlank { font.label } else font.label,
                                family = rememberAppFont(theme.copy(font = font)),
                                selected = theme.font == font,
                                enabled = available,
                                modifier = Modifier.weight(1f),
                            ) { AppSettings.updateTheme { it.copy(font = font) } }
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            message?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = if (failed) c.error else c.primary, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
            }

            Label("Text weight")
            WeightSteps(theme.textWeight, family) { w -> AppSettings.updateTheme { it.copy(textWeight = w) } }
            Text("Heavier text is easier to read on a bright screen or a small font size.", style = MaterialTheme.typography.bodySmall,
                color = c.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 8.dp))

            if (theme.customFontId.isNotEmpty()) {
                Label("Imported font")
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.onSurface.copy(alpha = FIELD_TINT)).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(theme.customFontName.ifBlank { "Imported font" }, style = MaterialTheme.typography.titleSmall,
                            fontFamily = rememberAppFont(theme.copy(font = AppFont.CUSTOM)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Kept on this phone", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    }
                    Box(
                        Modifier.clip(RoundedCornerShape(50)).border(1.dp, c.outlineVariant, RoundedCornerShape(50)).clickable(role = Role.Button) {
                            val previous = theme.customFontId
                            AppSettings.updateTheme { it.copy(font = if (it.font == AppFont.CUSTOM) AppFont.SYSTEM else it.font, customFontId = "", customFontName = "") }
                            scope.launch(Dispatchers.IO) { ImportedFonts.remove(context, previous) }
                            message = null
                        }.padding(horizontal = 14.dp, vertical = 7.dp),
                    ) { Text("Remove", style = MaterialTheme.typography.labelLarge, color = c.error) }
                }
            }
            Text("Import a TrueType (.ttf) or OpenType (.otf) file, up to 8 MB. Lyrics use the same font.",
                style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 14.dp))
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(com.opentune.ui.theme.tr(text).uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 10.dp))
}

/** One typeface: "Aa" and its name, both in it; outlined, with a tick, when it's the one in use. */
@Composable
private fun FontTile(name: String, family: FontFamily, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(
        modifier.height(104.dp).clip(RoundedCornerShape(20.dp))
            .background(if (selected) c.primary.copy(alpha = 0.14f) else c.onSurface.copy(alpha = FIELD_TINT))
            .then(if (selected) Modifier.border(2.dp, c.primary, RoundedCornerShape(20.dp)) else Modifier)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(14.dp),
    ) {
        Column(Modifier.align(Alignment.BottomStart)) {
            Text("Aa", fontFamily = family, fontSize = 30.sp, fontWeight = FontWeight.SemiBold,
                color = if (enabled) c.onSurface else c.onSurface.copy(alpha = 0.4f))
            Text(name, fontFamily = family, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (enabled) c.onSurfaceVariant else c.onSurfaceVariant.copy(alpha = 0.4f))
        }
        if (selected) Box(Modifier.align(Alignment.TopEnd).size(22.dp).clip(CircleShape).background(c.primary), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Check, "In use", tint = c.onPrimary, modifier = Modifier.size(14.dp))
        }
    }
}

/** Import a font: a tile like the others, with a + (a spinner while it's read). */
@Composable
private fun ImportTile(importing: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(
        modifier.height(104.dp).clip(RoundedCornerShape(20.dp)).border(1.5.dp, c.outlineVariant, RoundedCornerShape(20.dp))
            .clickable(enabled = !importing, role = Role.Button, onClick = onClick).padding(14.dp),
    ) {
        Box(Modifier.align(Alignment.TopStart).size(34.dp).clip(CircleShape).background(c.onSurface), contentAlignment = Alignment.Center) {
            if (importing) CircularProgressIndicator(Modifier.size(18.dp), color = c.surface, strokeWidth = 2.dp)
            else Icon(Icons.Rounded.Add, null, tint = c.surface, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.align(Alignment.BottomStart)) {
            Text(if (importing) "Importing…" else "Import a font", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(".ttf or .otf", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
    }
}

/** Regular, Medium, Bold in one row, each written in its own weight; the one in use filled. */
@Composable
private fun WeightSteps(current: TextWeight, family: FontFamily, onPick: (TextWeight) -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.onSurface.copy(alpha = FIELD_TINT)).padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        TextWeight.entries.forEach { w ->
            val on = w == current
            Box(
                Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(14.dp))
                    .then(if (on) Modifier.background(c.onSurface) else Modifier)
                    .clickable(role = Role.RadioButton) { onPick(w) },
                contentAlignment = Alignment.Center,
            ) {
                Text(w.label, fontFamily = family, fontWeight = FontWeight(w.minimum), style = MaterialTheme.typography.titleSmall,
                    color = if (on) c.surface else c.onSurface)
            }
        }
    }
}
