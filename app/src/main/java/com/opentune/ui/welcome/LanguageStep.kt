package com.opentune.ui.welcome

import android.content.Context
import android.content.res.Resources
import android.text.TextUtils
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.opentune.ui.components.FocusRing
import com.opentune.ui.theme.AppIcons
import com.opentune.ui.theme.AppLanguage
import com.opentune.ui.theme.AppLanguages
import com.opentune.ui.theme.Icon
import com.opentune.ui.theme.Text
import java.util.Locale

/**
 * The welcome's first question: which language the app speaks. A choice is tried
 * out at once ([preview]) so the rest of the welcome reads in it, and kept
 * ([keep]) when the listener moves on, through the same [AppLanguages.select]
 * Settings uses.
 */
internal object WelcomeLanguage {
    /** The phone's own language, whatever the app is set to. */
    fun phoneLocale(): Locale = Resources.getSystem().configuration.locales[0]

    /** Which of the app's languages [locale] is, if it is one of them. */
    fun match(locale: Locale): AppLanguage? {
        val lang = when (locale.language) { "in" -> "id"; "iw" -> "he"; else -> locale.language }
        if (lang == "zh") {
            val traditional = locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO")
            return if (traditional) AppLanguage.CHINESE_TRADITIONAL else AppLanguage.CHINESE_SIMPLIFIED
        }
        return AppLanguage.entries.firstOrNull { it != AppLanguage.SYSTEM && it.tag == lang }
    }

    /**
     * The list: the phone's language first (kept as "follow the phone"), then English,
     * then the rest by their English names, so the order never moves while choosing.
     * The phone's language isn't listed twice.
     */
    fun options(phone: Locale): List<AppLanguage> {
        val own = match(phone)
        val rest = AppLanguage.entries
            .filter { it != AppLanguage.SYSTEM && it != own && it != AppLanguage.ENGLISH }
            .sortedBy { nameIn(it, phone, Locale.ENGLISH) ?: it.label }
        return listOf(AppLanguage.SYSTEM) + (if (own != AppLanguage.ENGLISH) listOf(AppLanguage.ENGLISH) else emptyList()) + rest
    }

    fun locale(language: AppLanguage, phone: Locale): Locale =
        if (language == AppLanguage.SYSTEM) phone else Locale.forLanguageTag(language.tag)

    /** [language] named in its own words; the phone's by the app's name for it, or the phone's. */
    fun nativeName(language: AppLanguage, phone: Locale): String =
        if (language != AppLanguage.SYSTEM) language.label
        else match(phone)?.label ?: phone.getDisplayLanguage(phone).replaceFirstChar { it.titlecase(phone) }.ifBlank { "English" }

    /** [language]'s name in [reader]'s language ("Spanish", "Espagnol"), or null when that's its own name. */
    fun nameIn(language: AppLanguage, phone: Locale, reader: Locale): String? {
        val l = locale(language, phone)
        val name = when {
            language == AppLanguage.CHINESE_SIMPLIFIED -> Locale.forLanguageTag("zh-Hans").getDisplayName(reader)
            language == AppLanguage.CHINESE_TRADITIONAL -> Locale.forLanguageTag("zh-Hant").getDisplayName(reader)
            else -> l.getDisplayLanguage(reader)
        }.replaceFirstChar { it.titlecase(reader) }
        return name.takeIf { it.isNotBlank() && !it.equals(nativeName(language, phone), ignoreCase = true) }
    }

    fun direction(locale: Locale): LayoutDirection =
        if (TextUtils.getLayoutDirectionFromLocale(locale) == View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr

    /**
     * Tries [language] out: the app's words, and the language YouTube is asked for
     * (Innertube reads it from the default locale), so the artists that load next are
     * that language's. Nothing is saved yet. True when the app's words changed.
     */
    fun preview(context: Context, language: AppLanguage, phone: Locale = phoneLocale()): Boolean {
        val before = com.opentune.i18n.Tr.language
        com.opentune.i18n.Tr.use(context, language.tag)
        Locale.setDefault(locale(language, phone))
        return com.opentune.i18n.Tr.language != before
    }

    /**
     * Keeps [language] as the app's, as Settings would (which also starts Home afresh
     * and, where Android needs it, redraws the activity in it). True when it changed.
     */
    fun keep(context: Context, language: AppLanguage): Boolean {
        if (language == AppLanguages.selected(context)) return false
        AppLanguages.select(context, language)
        return true
    }
}

/** A grid of the app's languages, each in its own words; [chosen] is ringed. */
@Composable
internal fun LanguageStep(
    chosen: AppLanguage,
    phone: Locale,
    state: LazyGridState,
    reduceMotion: Boolean,
    onChoose: (AppLanguage) -> Unit,
) {
    val options = remember(phone) { WelcomeLanguage.options(phone) }
    val reader = WelcomeLanguage.locale(chosen, phone)
    // On a TV or with a keyboard, the chosen language starts focused, so Enter keeps it.
    val focus = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current
    LaunchedEffect(Unit) {
        if (inputMode.inputMode == InputMode.Keyboard) runCatching { focus.requestFocus() }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(156.dp),
        state = state,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = topInset(), bottom = bottomInset() + 12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "title", contentType = "title") {
            // The grid's own padding already holds half the title's margin.
            StepTitle(
                "Choose your language",
                "OpenTune's words and the music it suggests follow it. You can change it any time in Settings.",
                horizontal = 12.dp,
            )
        }
        items(options, key = { it.name }, contentType = { "language" }) { language ->
            val selected = language == chosen
            LanguageTile(
                name = WelcomeLanguage.nativeName(language, phone),
                line = if (language == AppLanguage.SYSTEM) null else WelcomeLanguage.nameIn(language, phone, reader),
                phoneLanguage = language == AppLanguage.SYSTEM,
                selected = selected,
                reduceMotion = reduceMotion,
                modifier = if (selected) Modifier.focusRequester(focus) else Modifier,
            ) { onChoose(language) }
        }
    }
}

@Composable
private fun LanguageTile(
    name: String,
    /** The language's name in the one being read, when that differs. */
    line: String?,
    phoneLanguage: Boolean,
    selected: Boolean,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val scheme = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) scheme.primaryContainer else scheme.surfaceContainer, label = "languageBg")
    val edge by animateColorAsState(if (selected) scheme.primary else scheme.onSurface.copy(alpha = 0.06f), label = "languageEdge")
    Row(
        modifier
            .padding(4.dp)
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .tile(shape, reduceMotion, onClick)
            .background(bg)
            .border(if (selected) 2.dp else 1.dp, edge, shape)
            .semantics {
                this.selected = selected
                role = Role.RadioButton
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            // A language's own name is never translated: that's how its readers find it.
            androidx.compose.material3.Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = scheme.onSurfaceVariant.takeUnless { selected } ?: scheme.onPrimaryContainer.copy(alpha = 0.75f)
            if (phoneLanguage) {
                Text("Phone language", style = MaterialTheme.typography.bodySmall, color = sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else if (line != null) {
                androidx.compose.material3.Text(line, style = MaterialTheme.typography.bodySmall, color = sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        AnimatedVisibility(
            selected,
            enter = if (reduceMotion) fadeIn(snap()) else scaleIn(spring(dampingRatio = 0.5f, stiffness = 500f)) + fadeIn(),
            exit = if (reduceMotion) fadeOut(snap()) else scaleOut() + fadeOut(),
        ) {
            Box(Modifier.padding(start = 8.dp).size(24.dp).clip(CircleShape).background(scheme.primary), contentAlignment = Alignment.Center) {
                Icon(ImageVector.vectorResource(AppIcons.Check), null, Modifier.size(16.dp), tint = scheme.onPrimary)
            }
        }
    }
}

/**
 * A tile's click: it sinks a little under a finger (not with motion reduced) and is
 * ringed in its own [shape] while a remote or keyboard has it focused.
 */
@Composable
internal fun Modifier.tile(shape: Shape, reduceMotion: Boolean, onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed && !reduceMotion) 0.97f else 1f,
        if (reduceMotion) snap() else spring(dampingRatio = 0.6f, stiffness = 600f),
        label = "tile",
    )
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(shape)
        .clickable(interactionSource = interaction, indication = FocusRing(shape), onClick = onClick)
}
