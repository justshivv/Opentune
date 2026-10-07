package com.opentune.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import java.util.Locale
import com.opentune.BuildConfig
import com.opentune.data.settings.AppSettings
import com.opentune.ui.PageMotion
import com.opentune.ui.components.GroupCard
import com.opentune.ui.components.GroupLabel
import com.opentune.ui.components.NavRow
import com.opentune.ui.components.PageHeader
import com.opentune.ui.components.RowDivider

internal enum class SettingsCategory(val title: String, val summary: String, val group: String) {
    APPEARANCE("Appearance", "Presets, theme, colors and player design", "Customize"),
    MOTION("Motion & dock", "Animation profiles, navigation and touch feedback", "Customize"),
    ACCESSIBILITY("Accessibility & comfort", "Reduced motion, solid surfaces and refresh rate", "Customize"),
    PLAYBACK("Playback & audio", "Queue, sound, streaming quality and USB output", "Listening"),
    LYRICS("Lyrics", "Text, synced words, styling and providers", "Listening"),
    LIBRARY("Library & downloads", "Offline songs, local files and discovery", "Listening"),
    ACCOUNTS("Accounts & services", "YouTube Music, music servers and scrobbling", "Manage"),
    DATA("Storage & data", "Cache, listening history and backups", "Manage"),
    ABOUT("About & updates", "App version, source code and troubleshooting", "Manage"),
}

internal class Entry(
    val title: String,
    val summary: String = "",
    val standalone: Boolean = false,
    val content: @Composable () -> Unit,
)

internal class Section(val category: SettingsCategory, val title: String, val entries: List<Entry>)

private val searchPunctuation = Regex("[^\\p{L}\\p{N}\\s]")
private fun searchable(text: String) = text.lowercase(Locale.ROOT).replace(searchPunctuation, "")

/** Search keeps every setting accessible, even when its category is not open. */
internal fun matchingSettings(sections: List<Section>, query: String, category: SettingsCategory?): List<Section> {
    val terms = searchable(query).trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    return sections.mapNotNull { section ->
        if (category != null && section.category != category) return@mapNotNull null
        val entries = section.entries.filter { entry ->
            val text = searchable("${section.category.title} ${section.title} ${entry.title} ${entry.summary}")
            terms.all { text.contains(it) }
        }
        entries.takeIf { it.isNotEmpty() }?.let { Section(section.category, section.title, it) }
    }
}

/** A compact overview with focused subpages; opening a page never changes a preference. */
@Composable
internal fun SettingsBrowser(sections: List<Section>, contentPadding: PaddingValues, onBack: () -> Unit) {
    var categoryName by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val category = SettingsCategory.entries.firstOrNull { it.name == categoryName }
    val stateHolder = rememberSaveableStateHolder()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val ui by AppSettings.ui.collectAsState()

    fun open(page: SettingsCategory) {
        focus.clearFocus()
        keyboard?.hide()
        query = ""
        categoryName = page.name
    }
    fun back() {
        focus.clearFocus()
        keyboard?.hide()
        query = ""
        if (category == null) onBack() else categoryName = null
    }
    BackHandler(enabled = category != null || query.isNotEmpty()) {
        if (query.isNotEmpty()) {
            focus.clearFocus()
            keyboard?.hide()
            query = ""
        } else back()
    }

    AnimatedContent(
        targetState = category,
        transitionSpec = {
            if (targetState == null) (PageMotion.popEnter(ui.pageTransition, ui.reduceAnimation) togetherWith PageMotion.popExit(ui.pageTransition, ui.reduceAnimation)).using(null)
            else (PageMotion.enter(ui.pageTransition, ui.reduceAnimation) togetherWith PageMotion.exit(ui.pageTransition, ui.reduceAnimation)).using(null)
        },
        label = "settingsPage",
    ) { page ->
        stateHolder.SaveableStateProvider(page?.name ?: "overview") {
            val listState = rememberLazyListState()
            val visible = matchingSettings(sections, query, page)
            LaunchedEffect(query) { if (query.isNotBlank()) listState.scrollToItem(0) }
            LazyColumn(
                state = listState,
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxSize().testTag("settings:${page?.name ?: "overview"}"),
            ) {
                item(key = "header") {
                    PageHeader(page?.title ?: "Settings", onBack = ::back, subtitle = page?.summary ?: "Your music, your setup.")
                }
                item(key = "search") {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text(if (page == null) "Search all settings" else "Search this page") },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear search") }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        shape = MaterialTheme.shapes.medium,
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("settingsSearch"),
                    )
                }
                if (page == null && query.isBlank()) {
                    SettingsCategory.entries.groupBy { it.group }.forEach { (group, pages) ->
                        item(key = "overview:$group") {
                            GroupLabel(group)
                            GroupCard {
                                pages.forEachIndexed { index, destination ->
                                    if (index > 0) RowDivider()
                                    NavRow(destination.title, { open(destination) }, summary = destination.summary, icon = destination.icon)
                                }
                            }
                        }
                    }
                } else {
                    if (query.isNotBlank()) {
                        item(key = "results") {
                            val count = visible.sumOf { it.entries.size }
                            Text("$count ${if (count == 1) "setting" else "settings"} found", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
                        }
                    }
                    if (visible.isEmpty()) {
                        item(key = "empty") {
                            Text("No settings match \"${query.trim()}\". Try a name such as lyrics, crossfade or download quality.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
                        }
                    }
                    visible.forEach { section ->
                        val key = "${section.category.name}:${section.title}"
                        val panels = section.entries.filter { it.standalone }
                        panels.forEach { entry -> item(key = "$key:${entry.title}") { entry.content() } }
                        val rows = section.entries.filterNot { it.standalone }
                        if (rows.isNotEmpty()) {
                            item(key = "label:$key") { GroupLabel(if (page == null) "${section.category.title} · ${section.title}" else section.title) }
                            item(key = "card:$key") {
                                GroupCard {
                                    rows.forEachIndexed { index, entry ->
                                        if (index > 0) RowDivider()
                                        entry.content()
                                    }
                                }
                            }
                        }
                    }
                }
                item(key = "footer") {
                    Text("OpenTune ${BuildConfig.VERSION_NAME} · Free and open source", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(28.dp))
                }
            }
        }
    }
}

private val SettingsCategory.icon: ImageVector
    get() = when (this) {
        SettingsCategory.APPEARANCE -> Icons.Rounded.Palette
        SettingsCategory.MOTION -> Icons.Rounded.Animation
        SettingsCategory.ACCESSIBILITY -> Icons.Rounded.Visibility
        SettingsCategory.PLAYBACK -> Icons.Rounded.Headphones
        SettingsCategory.LYRICS -> Icons.Rounded.Lyrics
        SettingsCategory.LIBRARY -> Icons.Rounded.Download
        SettingsCategory.ACCOUNTS -> Icons.Rounded.AccountCircle
        SettingsCategory.DATA -> Icons.Rounded.Storage
        SettingsCategory.ABOUT -> Icons.Rounded.Info
    }
