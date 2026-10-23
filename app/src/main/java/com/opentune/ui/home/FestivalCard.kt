package com.opentune.ui.home

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opentune.data.Festivals
import com.opentune.data.radio.ApproxLocation
import kotlinx.coroutines.launch

/**
 * Home's festival corner: once, a question about using the phone's rough location
 * to know the country; after that, on a festival day there, a greeting for it.
 */
@Composable
internal fun FestivalCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(!Festivals.asked(context)) }
    var festival by remember { mutableStateOf<Festivals.Festival?>(null) }
    LaunchedEffect(Unit) { festival = Festivals.today(context) }
    fun answer(allowed: Boolean) {
        asking = false
        scope.launch {
            Festivals.answered(context, allowed)
            festival = Festivals.today(context)
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { answer(it) }

    AnimatedVisibility(asking, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        Banner {
            Column(Modifier.weight(1f)) {
                Text("Festival wishes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "OpenTune can greet you on the festivals where you live. It uses your rough location once to know your country, and keeps only that.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        if (ApproxLocation.granted(context)) answer(true) else permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    }) { Text("Allow") }
                    TextButton(onClick = { answer(false) }) { Text("Not now") }
                }
            }
        }
    }
    val today = festival
    AnimatedVisibility(today != null && !asking, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        if (today != null) {
            Banner {
                Column(Modifier.weight(1f)) {
                    Text("Happy ${today.name}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Wishing you and your family a joyful day.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
                }
                IconButton(onClick = { Festivals.dismiss(context, today); festival = null }) {
                    Icon(Icons.Rounded.Close, "Close")
                }
            }
        }
    }
}

@Composable
private fun Banner(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(scheme.primaryContainer, scheme.tertiaryContainer)))
            .padding(start = 18.dp, end = 6.dp, top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Rounded.Celebration, null, Modifier.size(30.dp), tint = scheme.onPrimaryContainer)
        Spacer(Modifier.width(14.dp))
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides scheme.onPrimaryContainer) {
            content()
        }
    }
}
