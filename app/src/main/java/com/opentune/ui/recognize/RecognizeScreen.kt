package com.opentune.ui.recognize

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.opentune.data.MusicRepository
import com.opentune.data.model.SearchFilter
import com.opentune.data.model.SearchResult
import com.opentune.data.model.Song
import com.opentune.data.recognize.Recognized
import com.opentune.data.recognize.Recognizer
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.Haptics
import com.opentune.ui.components.MARK_VIEWPORT
import com.opentune.ui.components.markPath
import com.opentune.ui.components.rememberHaptics
import com.opentune.ui.theme.songAccents
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.launch

/**
 * Finding out what's playing nearby. A round button with the OpenTune mark
 * in it; tapped, it listens, and while it does the button glows, wobbles
 * with the sound and sends rings out. What it finds comes up in its place,
 * ready to play, and stays in a list underneath.
 */
@Composable
fun RecognizeScreen(contentPadding: PaddingValues, onPlay: (Song) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Recognizer.init(context) }
    val state by Recognizer.state.collectAsState()
    val history by Recognizer.history.collectAsState()
    var denied by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        denied = !ok
        if (ok) Recognizer.start(context)
    }
    fun listen() {
        haptics.press()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            Recognizer.start(context)
        } else {
            ask.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    LaunchedEffect(state) { if (state is Recognizer.State.Found) haptics.pattern(Haptics.Pattern.DONE) }
    DisposableEffect(Unit) { onDispose { Recognizer.cancel() } }
    var finding by remember { mutableStateOf<String?>(null) }
    fun play(song: Recognized) {
        if (finding != null) return
        finding = song.title
        scope.launch {
            val found = runCatching { MusicRepository.search("${song.title} ${song.artist}", SearchFilter.SONGS) }.getOrNull()
                ?.firstNotNullOfOrNull { (it as? SearchResult.Track)?.song ?: (it as? SearchResult.TopTrack)?.song }
            finding = null
            if (found != null) onPlay(found)
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item(key = "top") {
            Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Text("What's this song?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
        }
        item(key = "stage") {
            AnimatedContent(
                targetState = state.stage(),
                transitionSpec = {
                    (fadeIn(tween(420, 120)) + scaleIn(spring(dampingRatio = 0.7f, stiffness = 260f), initialScale = 0.86f) + slideInVertically(tween(420)) { it / 10 }) togetherWith
                        fadeOut(tween(220))
                },
                contentAlignment = Alignment.TopCenter,
                label = "recognize",
            ) { stage ->
                Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    when (stage) {
                        Stage.FOUND -> (Recognizer.state.value as? Recognizer.State.Found)?.song?.let { song ->
                            FoundCard(song, finding == song.title, onPlay = { play(song) }, onAgain = { listen() })
                        }
                        else -> {
                            val listening = stage == Stage.LISTENING
                            ListenOrb(listening, { (Recognizer.state.value as? Recognizer.State.Listening)?.level ?: 0f }, Modifier.size(280.dp)) {
                                if (listening) Recognizer.cancel() else listen()
                            }
                            Spacer(Modifier.height(8.dp))
                            val seconds = (state as? Recognizer.State.Listening)?.seconds ?: 0f
                            Text(
                                when {
                                    listening && seconds > 8 -> "Still listening… hold the phone closer"
                                    listening -> "Listening…"
                                    stage == Stage.MISSED -> "Couldn't name that one"
                                    stage == Stage.FAILED -> (state as? Recognizer.State.Failed)?.reason ?: "Something went wrong"
                                    denied -> "OpenTune needs the microphone to listen"
                                    else -> "Tap to find the song playing near you"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 32.dp),
                            )
                            if (stage == Stage.MISSED || stage == Stage.FAILED) {
                                TextButton(onClick = { listen() }, modifier = Modifier.padding(top = 6.dp)) {
                                    Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Try again")
                                }
                            } else if (listening) {
                                TextButton(onClick = { Recognizer.cancel() }, modifier = Modifier.padding(top = 6.dp)) { Text("Stop") }
                            }
                        }
                    }
                }
            }
        }
        if (history.isNotEmpty()) {
            item(key = "historyTitle") {
                Text(
                    "Found lately",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            items(history, key = { "h${it.foundAt}" }) { song ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { haptics.tick(); play(song) }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(song.coverUrl, Modifier.size(52.dp), RoundedCornerShape(12.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(song.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(song.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { Recognizer.forget(song) }) { Icon(Icons.Rounded.Close, "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

private enum class Stage { READY, LISTENING, FOUND, MISSED, FAILED }

private fun Recognizer.State.stage(): Stage = when (this) {
    Recognizer.State.Idle -> Stage.READY
    is Recognizer.State.Listening -> Stage.LISTENING
    is Recognizer.State.Found -> Stage.FOUND
    Recognizer.State.NotFound -> Stage.MISSED
    is Recognizer.State.Failed -> Stage.FAILED
}

@Composable
private fun FoundCard(song: Recognized, finding: Boolean, onPlay: () -> Unit, onAgain: () -> Unit) {
    val context = LocalContext.current
    val rise = remember { Animatable(0f) }
    LaunchedEffect(song) { rise.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 180f)) }
    Box(contentAlignment = Alignment.Center) {
        // The cover's colours glowing out behind it.
        val (a, b) = songAccents()
        Canvas(Modifier.size(320.dp)) {
            drawCircle(Brush.radialGradient(listOf(a.copy(alpha = 0.45f), b.copy(alpha = 0.15f), Color.Transparent)), size.minDimension / 2f)
        }
        Artwork(
            song.coverUrl,
            Modifier
                .size(240.dp)
                .graphicsLayer {
                    val s = 0.8f + 0.2f * rise.value
                    scaleX = s
                    scaleY = s
                    rotationZ = -6f * (1f - rise.value)
                }
                .shadow(24.dp, RoundedCornerShape(28.dp)),
            RoundedCornerShape(28.dp),
        )
    }
    Spacer(Modifier.height(20.dp))
    Text(song.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
    Text(
        listOfNotNull(song.artist, song.album).joinToString(" · "),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
    )
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onPlay, enabled = !finding) {
            Icon(Icons.Rounded.PlayArrow, null)
            Spacer(Modifier.width(6.dp))
            Text(if (finding) "Finding it…" else "Play")
        }
        FilledTonalButton(onClick = onAgain) {
            Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Listen again")
        }
    }
    song.shazamUrl?.let { url ->
        TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }) {
            Text("Open in Shazam")
            Spacer(Modifier.width(6.dp))
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp))
        }
    }
}

/**
 * The button: a glowing disc with the mark in it. Listening, rings spread
 * from it, its edge ripples with how loud it is, and its colours turn
 * slowly; at rest it breathes.
 */
@Composable
private fun ListenOrb(listening: Boolean, level: () -> Float, modifier: Modifier, onClick: () -> Unit) {
    val mark = remember { markPath() }
    val (a, b) = songAccents()
    val clock = rememberInfiniteTransition(label = "orb")
    val t by clock.animateFloat(0f, 1f, infiniteRepeatable(tween(12_000, easing = LinearEasing), RepeatMode.Restart), label = "turn")
    val ring by clock.animateFloat(0f, 1f, infiniteRepeatable(tween(2_200, easing = LinearEasing), RepeatMode.Restart), label = "ring")
    val breathe by clock.animateFloat(0f, 1f, infiniteRepeatable(tween(2_600), RepeatMode.Reverse), label = "breathe")
    val on = remember { Animatable(0f) }
    LaunchedEffect(listening) { on.animateTo(if (listening) 1f else 0f, spring(dampingRatio = 0.7f, stiffness = 120f)) }
    // The level, eased so the edge moves smoothly rather than jumping each read.
    val loud = remember { Animatable(0f) }
    LaunchedEffect(listening) {
        while (listening) {
            loud.animateTo(level(), tween(120, easing = LinearEasing))
        }
        loud.animateTo(0f, tween(400))
    }
    val shape = remember { Path() }
    Canvas(
        modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
    ) {
        val c = center
        val base = size.minDimension * 0.27f * (1f + 0.03f * breathe * (1f - on.value))
        val o = on.value
        val l = loud.value
        // A soft glow behind it, bigger while listening.
        drawCircle(
            Brush.radialGradient(listOf(a.copy(alpha = 0.35f + 0.25f * o), b.copy(alpha = 0.12f * (1f + o)), Color.Transparent), c, size.minDimension / 2f),
            size.minDimension / 2f,
            c,
        )
        // Rings that spread out and fade, three at a time.
        if (o > 0.01f) {
            for (k in 0 until 3) {
                val p = (ring + k / 3f) % 1f
                val r = base * (1.05f + 0.85f * p) * (1f + 0.15f * l)
                drawCircle(a.copy(alpha = (1f - p) * 0.45f * o), r, c, style = Stroke(width = (2.5f + 4f * l) * density * (1f - p)))
            }
        }
        // The disc, its edge rippling with the sound.
        shape.reset()
        val points = 120
        val angle0 = 2f * PI.toFloat() * t
        for (i in 0..points) {
            val th = 2f * PI.toFloat() * i / points
            val wobble = o * (0.035f + 0.11f * l) * (
                sin(5 * th + angle0 * 3) * 0.6f + sin(3 * th - angle0 * 2) * 0.4f + cos(7 * th + angle0 * 5) * 0.25f
            )
            val r = base * (1f + wobble)
            val x = c.x + r * cos(th)
            val y = c.y + r * sin(th)
            if (i == 0) shape.moveTo(x, y) else shape.lineTo(x, y)
        }
        shape.close()
        drawPath(
            shape,
            Brush.linearGradient(
                listOf(a, b),
                start = Offset(c.x + base * cos(angle0), c.y + base * sin(angle0)),
                end = Offset(c.x - base * cos(angle0), c.y - base * sin(angle0)),
            ),
        )
        // The mark, white, beating a little with the sound.
        val markScale = base * 1.5f / MARK_VIEWPORT * (1f + 0.08f * l * o)
        translate(c.x - MARK_VIEWPORT / 2f * markScale, c.y - MARK_VIEWPORT / 2f * markScale) {
            scale(markScale, markScale, pivot = Offset.Zero) { drawPath(mark, Color.White) }
        }
    }
}
