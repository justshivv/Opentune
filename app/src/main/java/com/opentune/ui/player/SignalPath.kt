package com.opentune.ui.player

import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentune.data.NerdStats
import com.opentune.data.settings.AppSettings
import com.opentune.playback.AudioFormatInfo
import com.opentune.playback.BitPerfectUsb
import com.opentune.ui.components.FloatingDialog
import com.opentune.ui.components.SheetButton
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Every stage the sound passes through, from the stream to the speaker, as
 * BitChord lays it out: track info, decoder, resampler, DSP and output, each
 * a node on one rail with what the player reports about it. A light runs down
 * the rail, goes once and a half round each node's ring, and moves on.
 */
@Composable
fun SignalPathDialog(format: AudioFormatInfo?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val am = remember { context.getSystemService(AudioManager::class.java) }
    val eq by AppSettings.equalizer.collectAsState()
    val sound by AppSettings.sound.collectAsState()
    val pb by AppSettings.playback.collectAsState()
    val source by NerdStats.playbackSource.collectAsState()
    val lossless by NerdStats.externalSource.collectAsState()
    val gain by NerdStats.loudnessGainDb.collectAsState()
    val speakerHold by NerdStats.loudnessOffOnSpeaker.collectAsState()
    val pipe by NerdStats.pipeline.collectAsState()
    val device = rememberOutputDeviceName()
    val route = rememberOutputRoute()
    val bitPerfect by BitPerfectUsb.status.collectAsState()
    val mixerRate = am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
    val volume = am?.getStreamVolume(AudioManager.STREAM_MUSIC)
    val maxVolume = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    val codec = format?.codec
    val isLossless = isHighestQuality(source, format)
    val bits = format?.bitDepth ?: source?.bits
    val rate = format?.sampleRateHz ?: source?.sampleRate
    val channels = format?.channels
    val kbps = format?.bitrateKbps ?: source?.kbps
    val pcmKbps = if (isLossless && bits != null && rate != null && channels != null) bits.toLong() * rate * channels / 1000 else null
    val trackRate = pipe.outputSampleRateHz ?: rate
    val resampled = trackRate != null && mixerRate != null && trackRate != mixerRate

    val dspStages = buildList {
        if (eq.enabled) add("Equalizer")
        if (sound.bassBoost > 0) add("Bass boost")
        if (pb.spatialAudio) add("Stereo widening")
        if (sound.space != com.opentune.data.settings.Space3DSpeed.OFF) add("3D sound")
        else if (sound.eightD != com.opentune.data.settings.EightD.OFF) add("8D")
        if (pb.clarity) add("Clarity")
        if (eq.balance != 0f) add("Balance")
    }

    val stages = listOf(
        Stage(
            Icons.Rounded.Description, "Track info",
            emptyList(),
            content = {
                TrackInfo(
                    source = source?.provider?.removePrefix("YouTube · ") ?: "Waiting for the stream",
                    specs = listOfNotNull(
                        codec,
                        if (isLossless) bits?.let { "$it-bit" } else null,
                        rate?.let(::khz),
                        kbps?.takeIf { it > 0 }?.let { "$it kbps" },
                        channels?.let(::channelLabel),
                    ),
                    pcmKbps = pcmKbps,
                    picked = source?.selectionDetail,
                )
            },
        ),
        Stage(
            Icons.Rounded.Memory, "Decoder",
            listOfNotNull(
                "Decoder Name" to (pipe.decoderName ?: if (pipe.offload) "Hardware offload" else "Starting…"),
                pipe.decoderOutput?.let { "Format" to it },
            ),
        ),
        Stage(
            Icons.Rounded.Tune, "Resampler",
            listOf(
                "I/O Rate" to (if (trackRate != null) "${khz(trackRate)} → ${khz(mixerRate ?: trackRate)}" else "Unknown"),
                "Type" to if (resampled) "Android mixer resampler" else "None",
                "Cutoff" to "—",
                "Quality" to if (resampled) "Resampled" else "Passthrough",
            ),
        ),
        Stage(
            Icons.Rounded.GraphicEq, "DSP",
            listOfNotNull(
                "PCM Format" to if (pb.floatOutput) "Float32" else "16-bit PCM",
                "Effects" to (if (dspStages.isEmpty()) "Bypassed" else dspStages.joinToString(", ")),
                "Tempo" to (if (sound.speed == 1f && sound.pitch == 1f) "1.00x" else "%.2fx speed, %.2fx pitch".format(Locale.ROOT, sound.speed, sound.pitch)),
                "Loudness" to when {
                    !pb.loudnessNormalization -> "Off"
                    lossless != null -> "Not measured for this master"
                    speakerHold && (gain ?: 0f) > 0.05f -> "%+.1f dB, phone speaker".format(Locale.ROOT, gain)
                    speakerHold -> "Full level on the phone speaker"
                    gain == null -> "Measuring"
                    else -> "%+.1f dB".format(Locale.ROOT, gain)
                },
                "Dither" to if (pb.floatOutput) "Bypassed (float)" else "TPDF, noise shaped",
                "System effects" to if (pb.systemEffects) "Open" else "Closed",
                pipe.bufferMs?.let { ms -> "Buffer" to "$ms ms" + (pipe.bufferFrames?.let { " ($it frames)" } ?: "") },
            ),
        ),
        Stage(
            if (route == OutputRoute.PHONE) Icons.Rounded.Speaker else route.icon,
            "Output",
            listOfNotNull(
                "Device" to device,
                "AudioTrack" to listOfNotNull(pipe.outputEncoding ?: if (pb.floatOutput) "Float32" else "16-bit PCM", trackRate?.let(::khz)).joinToString(" / "),
                mixerRate?.let { "System" to "AudioFlinger mixer, ${khz(it)}" },
                when (val bp = bitPerfect) {
                    is BitPerfectUsb.Status.Active -> "Bit-perfect" to "${bp.device}, ${khz(bp.sampleRate)} ${bp.bits}-bit"
                    is BitPerfectUsb.Status.NoMatch -> "Bit-perfect" to "DAC lacks ${khz(bp.sampleRate)}"
                    BitPerfectUsb.Status.NoDac -> "Bit-perfect" to "Waiting for a USB DAC"
                    BitPerfectUsb.Status.Unsupported -> "Bit-perfect" to "Needs Android 14"
                    BitPerfectUsb.Status.Off -> null
                },
                if (volume != null && maxVolume != null) "Volume" to "$volume of $maxVolume" else null,
            ),
        ),
    )

    FloatingDialog(
        onDismissRequest = onDismiss,
        title = { Text("Audio Pipeline") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Live signal path, decoder to output",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 14.dp),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Timeline(stages)
            }
        },
        confirmButton = { SheetButton("Done", onClick = onDismiss, closes = true) },
    )
}

/** One node on the pipeline: an icon, its name, and what the player says about it. */
private data class Stage(
    val icon: ImageVector,
    val title: String,
    val rows: List<Pair<String, String>>,
    /** Drawn in place of [rows], for a stage laid out its own way. */
    val content: (@Composable () -> Unit)? = null,
)

/** What one source said about the song: who, what, and how it went. */
internal data class SourceAnswer(val who: String?, val said: String, val outcome: Outcome) {
    enum class Outcome { CHOSEN, NONE, TROUBLE }
}

/**
 * The lines of a "picked" report as answers: "Qobuz: no matching recording"
 * is Qobuz saying no; a line with no name before a colon stands on its own.
 * The add-ons' summary line is named as theirs.
 */
internal fun sourceAnswers(detail: String): List<SourceAnswer> = detail.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
    val m = Regex("""^([^:]{1,40}):\s+(.+)$""").find(line)
    val who = m?.groupValues?.get(1)?.let { if (it == "FLAC") "Add-ons" else it }
    val said = m?.groupValues?.get(2) ?: line
    val lower = said.lowercase(Locale.ROOT)
    val outcome = when {
        listOf("verified", "selected", "picked", "higher quality", "switched").any { it in lower } && "no verified" !in lower -> SourceAnswer.Outcome.CHOSEN
        listOf("rate limit", "http", "timed out", "time out", "error", "didn't answer", "did not answer", "couldn't", "failed", "unreachable", "refused")
            .any { it in lower } -> SourceAnswer.Outcome.TROUBLE
        else -> SourceAnswer.Outcome.NONE
    }
    SourceAnswer(who, said.replaceFirstChar { it.uppercase() }, outcome)
}

/**
 * Track info, laid out to scan: the source large, the format as small chips,
 * then what each source answered when the stream was picked, one per row with
 * a dot for how it went.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TrackInfo(source: String, specs: List<String>, pcmKbps: Long?, picked: String?) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
        Text(source, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
        if (specs.isNotEmpty()) {
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
            ) {
                specs.forEach { spec ->
                    Text(
                        spec,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurface,
                        modifier = Modifier
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                            .background(colors.onSurface.copy(alpha = 0.08f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
        if (pcmKbps != null) {
            Text("$pcmKbps kbps of PCM once decoded", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        val answers = picked?.let(::sourceAnswers).orEmpty()
        if (answers.isNotEmpty()) {
            Text(
                "HOW IT WAS CHOSEN",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                    .background(colors.onSurface.copy(alpha = 0.05f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
            ) {
                answers.forEach { a ->
                    Row(verticalAlignment = Alignment.Top) {
                        val dot = when (a.outcome) {
                            SourceAnswer.Outcome.CHOSEN -> Color(0xFF4ADE80)
                            SourceAnswer.Outcome.TROUBLE -> Color(0xFFFBBF24)
                            SourceAnswer.Outcome.NONE -> colors.onSurfaceVariant.copy(alpha = 0.6f)
                        }
                        Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(dot))
                        Column(Modifier.padding(start = 10.dp)) {
                            if (a.who != null) Text(a.who, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                            Text(a.said, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

private fun khz(hz: Int): String = "${String.format(Locale.ROOT, "%.1f", hz / 1000.0).removeSuffix(".0")} kHz"

private fun channelLabel(n: Int) = when (n) {
    1 -> "Mono"
    2 -> "Stereo"
    else -> "$n (Surround)"
}

private val RAIL_WIDTH = 44.dp
private val NODE_SIZE = 32.dp

/** The node's centre down from the top of its row. */
private val NODE_CENTER = 30.dp

/** How fast the light travels along the rail and round the rings. */
private const val BEAM_DP_PER_S = 260f

/** A rest between runs, with the rail dark. */
private const val BEAM_REST_MS = 700L

/** The share of the last turn the light takes to fade out over. */
private const val BEAM_FADE = 0.35f

/**
 * The nodes down one rail. The rail, the rings' glow and the light are all
 * drawn here, over the rows, from a clock read only while drawing, so a frame
 * of the animation redraws without recomposing the page.
 */
@Composable
private fun Timeline(stages: List<Stage>) {
    val still = AppSettings.ui.collectAsState().value.reduceAnimation
    val clock = remember { mutableLongStateOf(-1L) }
    if (!still) LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (true) withFrameMillis { clock.longValue = it - start }
    }
    val nodeY = remember { mutableStateMapOf<Int, Float>() }
    // The box's own live coordinates, to place each node relative to it.
    val box = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val ink = MaterialTheme.colorScheme.onSurface
    val rail = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .fillMaxWidth()
            .onPlaced { box[0] = it }
            .drawWithContent {
                val centres = stages.indices.map { nodeY[it] ?: return@drawWithContent drawContent() }
                val cx = RAIL_WIDTH.toPx() / 2f
                val r = NODE_SIZE.toPx() / 2f
                // The rail, stopping at each ring.
                for (i in 0 until centres.lastIndex) {
                    drawLine(rail.copy(alpha = 0.26f), Offset(cx, centres[i] + r), Offset(cx, centres[i + 1] - r), 2.dp.toPx())
                }
                drawContent()
                val t = clock.longValue
                val path = BeamPath(centres, cx, r, lead = 26.dp.toPx())
                val head = if (t < 0) -1f else {
                    val runMs = (path.length / (BEAM_DP_PER_S * density) * 1000f).toLong().coerceAtLeast(1L)
                    val inRun = t % (runMs + BEAM_REST_MS)
                    if (inRun > runMs) -1f else inRun / runMs.toFloat() * path.length
                }
                // Each ring glows while the light is on it and fades as it leaves.
                val falloff = 30.dp.toPx()
                centres.forEachIndexed { i, cy ->
                    val (from, to) = path.orbit(i)
                    val off = when {
                        head < 0f -> Float.MAX_VALUE
                        head < from -> from - head
                        head > to -> head - to
                        else -> 0f
                    }
                    val act = (1f - off / falloff).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
                    drawRing(ink, Offset(cx, cy), r, act)
                }
                if (head >= 0f) drawBeam(path, head, ink, fade = path.remaining(head).let { left -> (left / BEAM_FADE).coerceIn(0f, 1f) })
            },
    ) {
        Column {
            stages.forEachIndexed { i, stage ->
                StageRow(stage, last = i == stages.lastIndex, reportNodeY = { node ->
                    val parent = box[0]
                    if (parent != null && parent.isAttached && node.isAttached) {
                        nodeY[i] = parent.localPositionOf(node, Offset(node.size.width / 2f, node.size.height / 2f)).y
                    }
                })
            }
        }
    }
}

/**
 * The light's route: down the rail into each ring at its top, one and a half
 * turns round it, out at the bottom, and on to the next. At the last ring it
 * goes once round and ends there, back at the top, rather than running on
 * down past the last step. Distances are in px.
 */
private class BeamPath(private val centres: List<Float>, private val cx: Float, private val r: Float, lead: Float) {
    /** Turns round ring [i], in half turns: three to pass through, two to end on. */
    private fun halfTurns(i: Int) = if (i == centres.lastIndex) 2 else 3
    private fun orbitLen(i: Int) = halfTurns(i) * PI.toFloat() * r
    /** Where each ring's orbit starts along the route. */
    private val orbitStart = FloatArray(centres.size)
    private val startY = (centres.first() - r - lead).coerceAtLeast(0f)
    val length: Float

    init {
        var d = 0f
        var y = startY
        centres.forEachIndexed { i, cy ->
            d += (cy - r) - y
            orbitStart[i] = d
            d += orbitLen(i)
            y = cy + r
        }
        length = d
    }

    fun orbit(i: Int) = orbitStart[i] to orbitStart[i] + orbitLen(i)

    /** How much of the last turn is left, 1 until the light reaches the last ring and 0 at the very end. */
    fun remaining(d: Float): Float {
        val s = orbitStart.last()
        return if (d <= s) 1f else (1f - (d - s) / orbitLen(centres.lastIndex)).coerceIn(0f, 1f)
    }

    fun at(d: Float): Offset {
        centres.forEachIndexed { i, cy ->
            val s = orbitStart[i]
            if (d < s) return Offset(cx, (cy - r) - (s - d))
            if (d <= s + orbitLen(i) || i == centres.lastIndex) {
                // From the top, clockwise on screen.
                val a = (-PI / 2 + (d - s).coerceAtMost(orbitLen(i)) / orbitLen(i) * halfTurns(i) * PI).toFloat()
                return Offset(cx + r * cos(a), cy + r * sin(a))
            }
        }
        return Offset(cx, centres.last() - r)
    }
}

/** A dark node with its ring: a faint outline at rest, a bright one with a halo when lit by [act]. */
private fun DrawScope.drawRing(ink: Color, c: Offset, r: Float, act: Float) {
    if (act > 0f) {
        drawCircle(
            Brush.radialGradient(listOf(ink.copy(alpha = 0.30f * act), ink.copy(alpha = 0f)), center = c, radius = r + 10.dp.toPx()),
            radius = r + 10.dp.toPx(), center = c, blendMode = BlendMode.Plus,
        )
        drawCircle(ink.copy(alpha = 0.22f * act), r + 1.2.dp.toPx(), c, style = Stroke(2.dp.toPx()))
    }
    drawCircle(ink.copy(alpha = 0.35f + 0.6f * act), r, c, style = Stroke(1.2.dp.toPx()))
}

/**
 * The light: a bright head with a tail that follows the route back, round a
 * ring as well as down the rail. [fade] takes it out over the end of its last
 * turn, so it ends on the last ring instead of stopping dead.
 */
private fun DrawScope.drawBeam(path: BeamPath, head: Float, ink: Color, fade: Float = 1f) {
    if (fade <= 0f) return
    val tail = 40.dp.toPx()
    val steps = 22
    for (k in steps downTo 0) {
        val d = head - tail * k / steps
        if (d < 0f) continue
        val f = 1f - k / steps.toFloat()
        val p = path.at(d)
        drawCircle(ink.copy(alpha = 0.10f * f * f * fade), 5.dp.toPx(), p, blendMode = BlendMode.Plus)
        drawCircle(ink.copy(alpha = 0.95f * f * f * fade), 1.3.dp.toPx(), p)
    }
    val h = path.at(head)
    drawCircle(Brush.radialGradient(listOf(ink.copy(alpha = 0.55f * fade), ink.copy(alpha = 0f)), center = h, radius = 8.dp.toPx()), 8.dp.toPx(), h, blendMode = BlendMode.Plus)
    drawCircle(Color.White.copy(alpha = fade), 2.dp.toPx(), h)
}

@Composable
private fun StageRow(stage: Stage, last: Boolean, reportNodeY: (LayoutCoordinates) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column {
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.width(RAIL_WIDTH)) {
                Box(
                    Modifier
                        .padding(top = NODE_CENTER - NODE_SIZE / 2)
                        .align(Alignment.TopCenter)
                        .onGloballyPositioned { reportNodeY(it) }
                        .size(NODE_SIZE)
                        .clip(CircleShape)
                        .background(Color(0xFF131315)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(stage.icon, null, tint = colors.onSurface.copy(alpha = 0.9f), modifier = Modifier.size(17.dp))
                }
            }
            Column(Modifier.padding(start = 10.dp, top = 20.dp, bottom = 16.dp)) {
                Text(
                    stage.title.uppercase(Locale.ROOT),
                    style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.sp),
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                stage.content?.invoke()
                stage.rows.forEach { (label, value) ->
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = colors.onSurface)) { append("$label: ") }
                            withStyle(SpanStyle(color = colors.onSurface.copy(alpha = 0.92f))) { append(value) }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
        if (!last) HorizontalDivider(Modifier.padding(start = RAIL_WIDTH + 10.dp), color = colors.outlineVariant.copy(alpha = 0.5f))
    }
}

/** Reports the selected media format; output mixer precision is shown separately. */
internal fun signalQuality(format: AudioFormatInfo?, source: NerdStats.Source?): String {
    val codec = format?.codec
    val bits = format?.bitDepth ?: source?.bits
    val rate = format?.sampleRateHz ?: source?.sampleRate
    val kbps = format?.bitrateKbps ?: source?.kbps
    return listOfNotNull(
        codec,
        bits?.let { "$it-bit" },
        rate?.let { "$it Hz" },
        format?.channels?.let { if (it == 2) "stereo" else "$it ch" },
        kbps?.let { "$it kbps" },
        if (codec?.contains("FLAC", true) == true) "lossless" else null,
        if (codec != null && kbps == null) "bitrate not reported" else null,
    ).joinToString(" · ").ifEmpty { "Waiting for the actual stream format" }
}
