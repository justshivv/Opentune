package com.opentune.ui.components

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.opentune.ui.theme.Icon
import com.opentune.ui.theme.Text

/**
 * A card for typing or pasting a web address: what it's for in a line, the link field with
 * Paste beside it (or an X to clear it), and under it the rule for what's allowed, a "checking"
 * line while it's tried, or, if it can't be used, why. [extra] sits under that (a "use the
 * standard one" choice, say). The confirm button shows a spinner while [working].
 */
@Composable
internal fun LinkEntryCard(
    title: String,
    description: String,
    input: String,
    onInput: (String) -> Unit,
    working: Boolean,
    error: String?,
    hint: String,
    workingLine: String,
    confirm: String,
    confirming: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    icon: ImageVector = Icons.Rounded.Link,
    /** A blank field may be confirmed (it means the standard address). */
    blankAllowed: Boolean = false,
    extra: (@Composable () -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val t = input.trim()
    val usable = (blankAllowed && t.isEmpty()) ||
        t.startsWith("https://", true) || t.startsWith("http://", true) || (t.contains('.') && !t.contains(' '))
    FloatingCard(
        onDismiss = onDismiss,
        title = title,
        icon = icon,
        dismissible = !working,
        actions = {
            Box(Modifier.weight(1f)) { SheetButton("Cancel", onClick = onDismiss, enabled = !working, tone = SheetTone.Neutral, closes = true) }
            Box(Modifier.weight(1f)) {
                SheetButton(if (working) confirming else confirm, onClick = onConfirm, enabled = usable && !working, loading = working, tone = SheetTone.Primary)
            }
        },
    ) {
        Column(Modifier.padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(description, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            Row(
                Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(18.dp)).background(c.onSurface.copy(alpha = FIELD_TINT))
                    .then(if (error != null) Modifier.border(1.5.dp, c.error, RoundedCornerShape(18.dp)) else Modifier)
                    .padding(start = 16.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Link, null, tint = c.onSurfaceVariant, modifier = Modifier.size(22.dp))
                Box(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    if (input.isEmpty()) Text("https://…", style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
                    BasicTextField(
                        input, onInput, singleLine = true, enabled = !working,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onSurface),
                        cursorBrush = SolidColor(c.primary),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (usable && !working) onConfirm() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                if (input.isEmpty()) {
                    // Paste straight from the clipboard: links are long and fiddly to type.
                    Box(
                        Modifier.clip(RoundedCornerShape(50)).background(c.onSurface)
                            .clickable { clipboard.getText()?.text?.trim()?.let(onInput) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) { Text("Paste", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = c.surface) }
                } else if (!working) {
                    IconButton(onClick = { onInput("") }) { Icon(Icons.Rounded.Close, "Clear", Modifier.size(20.dp)) }
                }
            }
            when {
                error != null -> Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.errorContainer).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.ErrorOutline, null, tint = c.onErrorContainer, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(error, style = MaterialTheme.typography.bodyMedium, color = c.onErrorContainer)
                }
                working -> Text(workingLine, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
                else -> Text(hint, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
            }
            extra?.invoke()
        }
    }
}
