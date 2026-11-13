package com.opentune.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import com.opentune.ui.theme.Text
import com.opentune.ui.theme.Icon
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.opentune.data.addons.*
import com.opentune.data.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AudioAddonsSheet(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { AddonRefresh.refresh() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        AudioAddonsPage(onDismiss, onAdd = { adding = true }, notice = notice, onNotice = { notice = it })
    }
    if (adding) AlertDialog(onDismissRequest = { if (!working) adding = false }, title = { com.opentune.ui.components.FrostBehind(enabled = !com.opentune.data.settings.AppSettings.ui.collectAsState().value.reduceBlur); Text("Add audio source") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Paste a service root or manifest URL. OpenTune loads its capabilities without installing executable code.")
            OutlinedTextField(input, { input = it.take(2048) }, label = { Text("Add-on URL (HTTPS, or http on this device)") }, singleLine = true)
            if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
            notice?.let { Text(it) }
        }
    }, confirmButton = { TextButton(enabled = input.isNotBlank() && !working, onClick = { scope.launch {
        working = true; notice = null
        try { val base = AddonProtocol.normalize(input); AddonStore.add(base, AddonClient.forBase(base).manifest()); input = ""; adding = false; notice = "Source added" }
        catch (e: CancellationException) { throw e }
        catch (e: AddonHttp.Failure) { notice = com.opentune.i18n.Tr.f("Service returned HTTP {0}", e.status) }
        catch (_: Exception) { notice = "Could not load this manifest. Check the URL and connection." }
        finally { working = false }
    } }) { Text(com.opentune.ui.theme.tr("Add source")) } }, dismissButton = { TextButton(enabled = !working, onClick = { adding = false }) { Text(com.opentune.ui.theme.tr("Cancel")) } })
}

/**
 * Where a dragged source belongs, given each row's height, where it started and where its
 * top is now. It passes a row above once its top edge is above that row's middle, and a row
 * below once its bottom edge is past that row's middle. Edges, not its centre: the drag
 * stops at the list's top, so a row a little taller than the first one (by a divider, or a
 * status that wraps) could never get its centre above the first row's and stayed second.
 */
internal fun sourceDropIndex(heights: List<Int>, from: Int, top: Float): Int {
    if (from !in heights.indices) return from
    val bottom = top + heights[from]
    var cursor = 0f
    var target = from
    heights.forEachIndexed { position, h ->
        val middle = cursor + h / 2f
        if (position < from && top < middle) target = minOf(target, position)
        if (position > from && bottom > middle) target = maxOf(target, position)
        cursor += h
    }
    return target
}

/** How a source is doing, as a coloured dot beside its status line. */
private enum class Tone { Good, Waiting, Off, Bad }

/**
 * The page itself, apart from its window so it can be drawn on its own: the lossless sources
 * in the order they're tried, then the two that are always there, then adding your own.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AudioAddonsPage(onDismiss: () -> Unit, onAdd: () -> Unit, notice: String?, onNotice: (String?) -> Unit) {
    val addons by AddonStore.addons.collectAsState()
    val playback by AppSettings.playback.collectAsState()
    val ui by AppSettings.ui.collectAsState()
    val rowHeights = remember { mutableStateMapOf<String, Int>() }
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var draggedTop by remember { mutableFloatStateOf(0f) }
    val availability by AddonRefresh.availability.collectAsState()
    val scope = rememberCoroutineScope()
    val haptics = com.opentune.ui.components.rememberHaptics()
    var expanded by remember { mutableStateOf<String?>(null) }
    var checks by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val c = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxSize(), color = c.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { scope.launch { onNotice(if (AddonRefresh.refresh(force = true)) "Audio sources refreshed" else "Couldn't refresh. Your saved sources are still available.") } }) { Icon(Icons.Rounded.Refresh, "Refresh audio sources") }
            }
            Text("Audio sources", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 24.dp))
            Text(
                "Music starts straight away, then OpenTune looks for a better copy. Lossless quality checks your sources from the top down.",
                style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp),
            )
            notice?.let {
                Row(
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
                        .background(c.secondaryContainer).clickable { onNotice(null) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Info, null, tint = c.onSecondaryContainer, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = c.onSecondaryContainer, modifier = Modifier.weight(1f))
                    Icon(Icons.Rounded.Close, "Dismiss", tint = c.onSecondaryContainer, modifier = Modifier.size(18.dp))
                }
            }

            com.opentune.ui.components.GroupLabel("Lossless, in order")
            com.opentune.ui.components.GroupCard {
                if (addons.isEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Extension, null, tint = c.onSurfaceVariant, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("No lossless sources yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Add one with its URL. Until then, songs play from the sources below.", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                addons.forEachIndexed { index, addon ->
                  key(addon.key) {
                    val top = addons.take(index).sumOf { rowHeights[it.key] ?: 100 }.toFloat()
                    val dragging = draggingKey == addon.key
                    val visualTop by animateFloatAsState(
                        targetValue = if (dragging) draggedTop else top,
                        animationSpec = if (dragging || ui.reduceAnimation) snap() else spring(dampingRatio = 1f, stiffness = 450f),
                        label = "Source position",
                    )
                    val lift by animateFloatAsState(
                        if (dragging) 1f else 0f,
                        animationSpec = if (ui.reduceAnimation) snap() else spring(dampingRatio = 1f),
                        label = "Source lift",
                    )
                    val blocked = addon.manifest.outputRequired && !AddonStore.outputAllowed()
                    val paused = com.opentune.data.addons.AddonResolver.paused(addon.key)
                    val checked = checks[addon.key]
                    val status = when {
                        addon.ownerOff -> "Turned off by the admin for now"
                        availability[addon.key] == false -> "Service unavailable"
                        checked != null -> checked
                        paused -> "Paused by its owner for now"
                        blocked -> "Needs a supported wired, USB or HDMI output"
                        !addon.enabled -> "Off"
                        else -> com.opentune.i18n.Tr.f("Lossless · {0}", if (addon.manifest.downloads) "Streaming & downloads" else "Streaming only")
                    }
                    val tone = when {
                        addon.ownerOff || availability[addon.key] == false -> Tone.Bad
                        checked != null -> if (checked.startsWith("Available")) Tone.Good else if (checked.startsWith("Checking")) Tone.Off else Tone.Bad
                        paused || blocked -> Tone.Waiting
                        !addon.enabled -> Tone.Off
                        else -> Tone.Good
                    }
                    val on = addon.enabled && !addon.ownerOff
                    Column(Modifier.fillMaxWidth()
                        .onSizeChanged { rowHeights[addon.key] = it.height }
                        .zIndex(if (dragging) 1f else 0f)
                        .offset { IntOffset(0, ((if (dragging) draggedTop else visualTop) - top).roundToInt()) }
                        .graphicsLayer {
                            shadowElevation = 10.dp.toPx() * lift
                            shape = RoundedCornerShape(20.dp)
                            clip = false
                        }
                        .background(if (dragging) c.surfaceContainerHighest else c.surfaceContainer, RoundedCornerShape(20.dp))) {
                        if (index > 0) com.opentune.ui.components.RowDivider()
                        Row(
                            Modifier.fillMaxWidth().clickable { expanded = if (expanded == addon.key) null else addon.key }.padding(start = 20.dp, end = 4.dp, top = 14.dp, bottom = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Its place in the order, filled while it's in use.
                            Box(
                                Modifier.size(32.dp).clip(CircleShape).background(if (on && tone != Tone.Bad) c.onSurface else c.surfaceContainerHighest),
                                contentAlignment = Alignment.Center,
                            ) {
                                androidx.compose.material3.Text("${index + 1}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                                    color = if (on && tone != Tone.Bad) c.surface else c.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(addon.manifest.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    if (addon.admin) {
                                        Text("Admin", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = c.tertiary,
                                            modifier = Modifier.padding(start = 8.dp).background(c.tertiary.copy(alpha = 0.16f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
                                    }
                                }
                                Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.Top) {
                                    Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(when (tone) {
                                        Tone.Good -> Color(0xFF2FA35B)
                                        Tone.Waiting -> Color(0xFFE0A030)
                                        Tone.Off -> c.outline
                                        Tone.Bad -> c.error
                                    }))
                                    Spacer(Modifier.width(6.dp))
                                    Text(status, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            com.opentune.ui.components.AppSwitch(on, { AddonStore.enable(addon.key, it) }, enabled = !addon.ownerOff)
                            Icon(Icons.Rounded.DragHandle, null, Modifier.size(48.dp)
                                .semantics {
                                    contentDescription = com.opentune.i18n.Tr.f("Reorder {0}", addon.manifest.name)
                                    customActions = listOf(
                                        CustomAccessibilityAction("Move up") { AddonStore.move(addon.key, -1); true },
                                        CustomAccessibilityAction("Move down") { AddonStore.move(addon.key, 1); true },
                                    )
                                }
                                .pointerInput(addon.key) {
                                    detectVerticalDragGestures(
                                        onDragStart = {
                                            val current = AddonStore.addons.value
                                            draggedTop = current.takeWhile { it.key != addon.key }.sumOf { rowHeights[it.key] ?: 100 }.toFloat()
                                            draggingKey = addon.key
                                            haptics.press()
                                        },
                                        onDragEnd = { draggingKey = null },
                                        onDragCancel = { draggingKey = null },
                                    ) { change, distance ->
                                        change.consume()
                                        val current = AddonStore.addons.value
                                        val from = current.indexOfFirst { it.key == addon.key }
                                        if (from < 0) return@detectVerticalDragGestures
                                        val height = rowHeights[addon.key] ?: 100
                                        val total = current.sumOf { rowHeights[it.key] ?: 100 }
                                        draggedTop = (draggedTop + distance).coerceIn(0f, (total - height).coerceAtLeast(0).toFloat())
                                        val target = sourceDropIndex(current.map { rowHeights[it.key] ?: 100 }, from, draggedTop)
                                        if (target != from) { AddonStore.move(addon.key, target - from); haptics.tick() }
                                    }
                                }.padding(12.dp), tint = c.onSurfaceVariant)
                        }
                        if (expanded == addon.key) Column(Modifier.padding(start = 68.dp, end = 20.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(if (addon.admin) "Added by the admin" else AddonProtocol.url(addon.base).host, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                SmallPill("Check now") { scope.launch {
                                    if (!AddonStore.usable(addon)) { checks = checks + (addon.key to "Off, or waiting for a supported output"); return@launch }
                                    checks = checks + (addon.key to "Checking…")
                                    try { AddonStore.refreshed(addon.key, AddonClient.forBase(addon.base).manifest()); checks = checks + (addon.key to "Available · recording support varies") }
                                    catch (e: CancellationException) { throw e }
                                    catch (e: AddonHttp.Failure) { checks = checks + (addon.key to com.opentune.i18n.Tr.f("HTTP {0}", e.status)) }
                                    catch (_: Exception) { checks = checks + (addon.key to "Service unavailable") }
                                } }
                                if (index > 0) SmallPill("Move up") { AddonStore.move(addon.key, -1) }
                                if (index < addons.lastIndex) SmallPill("Move down") { AddonStore.move(addon.key, 1) }
                                if (!addon.admin) SmallPill("Remove", danger = true) { AddonStore.remove(addon.key); expanded = null }
                            }
                        }
                    }
                  }
                }
            }
            Text(
                "Drag to change the order, or tap a source for more. If one has no match or can't be reached, the next is tried.",
                style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant,
                modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 10.dp),
            )

            com.opentune.ui.components.GroupLabel("Always available")
            com.opentune.ui.components.GroupCard {
                FixedSource(Icons.Rounded.GraphicEq, "JioSaavn", "Fast 320 kbps fallback · lossy") {
                    com.opentune.ui.components.AppSwitch(playback.jioSaavnQuality, { AppSettings.updatePlayback { p -> p.copy(jioSaavnQuality = it) } })
                }
                com.opentune.ui.components.RowDivider()
                FixedSource(null, "YouTube Music", "Catalog, radio and the last fallback") {
                    Text("Always on", style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant)
                }
            }

            Row(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp))
                    .border(1.dp, c.outlineVariant, RoundedCornerShape(28.dp)).clickable(onClick = onAdd).padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(c.onSurface), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Add, null, tint = c.surface)
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Add your own source", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Paste a service URL. Nothing is installed.", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                }
            }
            Text(
                "Signal path shows the format that is actually decoded. Bluetooth may still compress the output.",
                style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 16.dp),
            )
        }
    }
}

@Composable
private fun FixedSource(icon: androidx.compose.ui.graphics.vector.ImageVector?, name: String, detail: String, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        // No icon: the service's own mark.
        if (icon == null) ServiceMark(ServiceKind.YouTubeMusic, 32.dp)
        else Box(Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

@Composable
private fun SmallPill(label: String, danger: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
    ) { Text(label, style = MaterialTheme.typography.labelLarge, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
}
