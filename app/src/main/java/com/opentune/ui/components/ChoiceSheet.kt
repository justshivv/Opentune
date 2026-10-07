package com.opentune.ui.components

import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.opentune.data.settings.AppSettings
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pick one of a few options, in a [FloatingCard]. Each option is a tile
 * with its name and, after " · " in [label], a line about it. A tinted lens marks the current choice; tapping
 * another slides the lens there on a spring, pops a check in and closes a
 * moment later, so the change is seen to land.
 */
@Composable
fun <T> ChoiceSheet(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val ui by AppSettings.ui.collectAsState()
    val still = ui.reduceAnimation
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf(selected) }
    var done by remember { mutableStateOf(false) }
    FloatingCard(onDismiss = onDismiss, title = title, contentPadding = PaddingValues(0.dp)) {
        val card = LocalFloatingCard.current
        Options(options, picked, label, still) { o ->
            if (done) return@Options
            done = true
            if (o != picked) {
                haptics.tick()
                picked = o
                onSelect(o)
            }
            scope.launch {
                if (!still) delay(SETTLE_MS)
                card?.close() ?: onDismiss()
            }
        }
    }
}

@Composable
private fun <T> Options(options: List<T>, picked: T, label: (T) -> String, still: Boolean, onPick: (T) -> Unit) {
    val density = LocalDensity.current
    val tops = remember { mutableStateMapOf<Int, Float>() }
    val heights = remember { mutableStateMapOf<Int, Float>() }
    val lensTop = remember { Animatable(0f) }
    val lensHeight = remember { Animatable(0f) }
    var placed by remember { mutableStateOf(false) }
    val index = options.indexOf(picked)
    LaunchedEffect(index, tops[index], heights[index]) {
        val top = tops[index] ?: return@LaunchedEffect
        val height = heights[index] ?: return@LaunchedEffect
        if (!placed || still) {
            lensTop.snapTo(top)
            lensHeight.snapTo(height)
            placed = true
        } else {
            launch { lensTop.animateTo(top, spring(dampingRatio = 0.72f, stiffness = 420f)) }
            launch { lensHeight.animateTo(height, spring(dampingRatio = 0.9f, stiffness = 420f)) }
        }
    }
    Box(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState())) {
        if (placed) {
            Box(
                Modifier
                    .offset { IntOffset(0, lensTop.value.roundToInt()) }
                    .fillMaxWidth()
                    .height(with(density) { lensHeight.value.toDp() })
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(22.dp)),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            options.forEachIndexed { i, o ->
                OptionRow(
                    text = label(o),
                    selected = o == picked,
                    index = i,
                    still = still,
                    modifier = Modifier.onGloballyPositioned {
                        tops[i] = it.positionInParent().y
                        heights[i] = it.size.height.toFloat()
                    },
                ) { onPick(o) }
            }
        }
    }
}

@Composable
private fun OptionRow(text: String, selected: Boolean, index: Int, still: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val (name, about) = text.split(" · ", limit = 2).let { it[0] to it.getOrNull(1) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.97f else 1f, spring(dampingRatio = 0.6f, stiffness = 700f), label = "press")
    // Rows rise in one after another as the sheet opens.
    val appear = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (still) return@LaunchedEffect
        delay(60L + index * 28L)
        appear.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 320f))
    }
    val check by animateFloatAsState(if (selected) 1f else 0f, if (still) snap() else spring(dampingRatio = 0.5f, stiffness = 520f), label = "check")
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = appear.value.coerceIn(0f, 1f)
                translationY = (1f - appear.value) * 18.dp.toPx()
                scaleX = press
                scaleY = press
            }
            .clip(RoundedCornerShape(22.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { role = Role.RadioButton; this.selected = selected }
            .heightIn(min = 60.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) scheme.primary else scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (about != null) {
                Text(
                    about,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) scheme.onSurface.copy(alpha = 0.8f) else scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        // An empty ring that fills with the accent and a check as it's chosen.
        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.matchParentSize().clip(CircleShape)
                    .border(2.dp, scheme.onSurfaceVariant.copy(alpha = 0.45f * (1f - check)), CircleShape),
            )
            Box(
                Modifier.matchParentSize()
                    .graphicsLayer { scaleX = check; scaleY = check; alpha = check.coerceIn(0f, 1f) }
                    .clip(CircleShape).background(scheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Check, null,
                    tint = scheme.onPrimary,
                    modifier = Modifier.size(17.dp).graphicsLayer { rotationZ = (1f - check) * -40f },
                )
            }
        }
    }
}

/** How long the lens and check get to land before the sheet closes. */
private const val SETTLE_MS = 300L
