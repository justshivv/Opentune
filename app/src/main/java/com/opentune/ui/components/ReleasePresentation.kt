package com.opentune.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opentune.R

/** Original, theme-aware release artwork; no network image or baked-in version text. */
@Composable
internal fun ReleaseHero(version: String, status: String) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(28.dp)
    Column(Modifier.fillMaxWidth().clip(shape)
        .background(Brush.linearGradient(listOf(colors.primaryContainer, colors.secondaryContainer)))
        .border(1.dp, colors.onPrimaryContainer.copy(alpha = .12f), shape)
        .padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painterResource(com.opentune.core.R.drawable.ic_stat_opentune), null, Modifier.size(28.dp), tint = colors.onPrimaryContainer)
            Text("WEAV", style = MaterialTheme.typography.titleMedium, color = colors.onPrimaryContainer)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("VERSION", style = MaterialTheme.typography.labelSmall, color = colors.onPrimaryContainer.copy(alpha = .7f))
            Text(version, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold,
                color = colors.onPrimaryContainer, modifier = Modifier.semantics { heading() })
            Text(status, style = MaterialTheme.typography.bodyMedium, color = colors.onPrimaryContainer)
        }
        Canvas(Modifier.fillMaxWidth().height(56.dp)) {
            val ink = colors.onPrimaryContainer
            val center = Offset(size.width - 28.dp.toPx(), size.height / 2)
            listOf(25f, 19f, 13f).forEach { radius ->
                drawCircle(ink.copy(alpha = .28f), radius.dp.toPx(), center, style = Stroke(1.dp.toPx()))
            }
            drawCircle(ink, 4.dp.toPx(), center)
            val available = (size.width - 72.dp.toPx()).coerceAtLeast(0f)
            val heights = listOf(.16f, .26f, .42f, .3f, .56f, .78f, .48f, .9f, .64f, .4f, .7f, .5f, .32f, .56f, .22f, .38f, .18f, .28f)
            heights.forEachIndexed { i, h ->
                val x = 3.dp.toPx() + available * i / heights.size
                drawLine(ink.copy(alpha = .3f + h * .5f), Offset(x, size.height * (1-h)/2),
                    Offset(x, size.height * (1+h)/2), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
            }
        }
    }
}

/** Release text keeps its headings and paragraphs instead of becoming a tiny wall of text. */
@Composable
internal fun ReleaseNotes(text: String) {
    val lines = remember(text) { text.trim().lines().map { it.trim() }.filter { it.isNotBlank() } }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
        .background(MaterialTheme.colorScheme.surfaceContainer).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("In this update", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        if (lines.isEmpty()) Text("Release notes are available on GitHub.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        lines.forEach { line ->
            val isHeading = line.startsWith('#')
            val bullet = line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ")
            val content = line.trimStart('#').trim().let { if (bullet) it.drop(2) else it }.replace("**", "")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (bullet) Text("•", color = MaterialTheme.colorScheme.primary)
                Text(content, style = if (isHeading) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                    color = if (isHeading) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).then(if (isHeading) Modifier.semantics { heading() } else Modifier))
            }
        }
    }
}
