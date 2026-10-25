package com.opentune.ui.theme

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.opentune.data.settings.AppFont
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.TextWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FontSettingsSheet(onDismiss: () -> Unit) {
    val theme by AppSettings.theme.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true; message = null
            try {
                val (id, name) = withContext(Dispatchers.IO) { ImportedFonts.import(context, uri) }
                val previous = AppSettings.theme.value.customFontId
                AppSettings.updateTheme { it.copy(font = AppFont.CUSTOM, customFontId = id, customFontName = name) }
                withContext(Dispatchers.IO) { ImportedFonts.remove(context, previous) }
                message = "Imported $name"
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { message = if (e is IllegalArgumentException) e.message else "Could not read this font. Try another .ttf or .otf file." }
            finally { importing = false }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("Fonts & readability", style = MaterialTheme.typography.headlineSmall)
            Text("Choose your typeface and text weight. Lyrics follow the same font.", Modifier.padding(vertical = 12.dp))
            for (font in AppFont.entries.filter { it != AppFont.CUSTOM || theme.customFontId.isNotEmpty() }) {
                val available = font != AppFont.CUSTOM || ImportedFonts.file(context, theme.customFontId) != null
                TextButton(onClick = { AppSettings.updateTheme { it.copy(font = font) } }, enabled = available, modifier = Modifier.fillMaxWidth()) {
                    RadioButton(selected = theme.font == font, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text(if (font == AppFont.CUSTOM) theme.customFontName.ifBlank { font.label } else font.label, Modifier.weight(1f), maxLines = 2,
                        fontFamily = rememberAppFont(theme.copy(font = font)))
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextWeight.entries.forEach { weight -> FilterChip(theme.textWeight == weight, { AppSettings.updateTheme { it.copy(textWeight = weight) } }, label = { Text(weight.label) }) }
            }
            Card(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Text("Music feels closer.", style = MaterialTheme.typography.headlineSmall)
                    Text("Quick picks · Made for you", style = MaterialTheme.typography.bodyLarge)
                    Text("Your next favorite is one play away.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !importing, modifier = Modifier.fillMaxWidth()) { Text(if (importing) "Importing…" else "Import a font file") }
            Text("TrueType (.ttf) or OpenType (.otf), up to 8 MB. Saved on this device.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
            if (theme.customFontId.isNotEmpty()) TextButton(onClick = {
                val previous = theme.customFontId
                AppSettings.updateTheme { it.copy(font = if (it.font == AppFont.CUSTOM) AppFont.SYSTEM else it.font, customFontId = "", customFontName = "") }
                scope.launch(Dispatchers.IO) { ImportedFonts.remove(context, previous) }
            }) { Text("Remove imported font") }
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
