package com.opentune.ui.together

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import com.opentune.ui.theme.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentune.data.settings.AppSettings
import com.opentune.data.together.RoomCode
import kotlinx.coroutines.delay

/**
 * The rules of the room code box, kept apart from the drawing so they can be
 * tested. The box holds the bare code ("K7QXM2PA9DTE"); the dashes are only
 * drawn.
 */
internal object CodeEntry {
    /** What the box holds after an edit, and whether part of it was turned away. */
    data class Edit(val raw: String, val rejected: Boolean = false)

    /**
     * The box held [current] and the keyboard, or a paste, made it [input].
     * A whole code or an invite link, pasted alone or after what was there,
     * fills the box. Otherwise letters are uppercased and dashes and spaces
     * dropped quietly; anything else the code never uses (O, I, 0, 1, other
     * links) is left out and marks the edit [Edit.rejected].
     */
    fun accept(current: String, input: String): Edit {
        RoomCode.parse(input)?.let { return Edit(it.raw) }
        if (input.startsWith(current)) RoomCode.parse(input.substring(current.length))?.let { return Edit(it.raw) }
        RoomCode.find(input)?.let { return Edit(it.raw) }
        // A link that isn't an invite would otherwise leave its letters behind.
        if ("://" in input) return Edit(current, rejected = true)
        val upper = input.uppercase()
        val kept = upper.filter { it in RoomCode.ALPHABET }.take(RoomCode.LENGTH)
        val stray = upper.any { it !in RoomCode.ALPHABET && it != '-' && !it.isWhitespace() }
        return Edit(kept, rejected = stray)
    }

    /** The character in cell [index], or null while it's empty. */
    fun cell(raw: String, index: Int): Char? = raw.getOrNull(index)

    /** The cell being typed in: the first empty one, or the last when the box is full. */
    fun active(raw: String): Int = raw.length.coerceAtMost(RoomCode.LENGTH - 1)

    /** Cells in a group, between the drawn dashes. */
    const val GROUP = 4

    /** A dash is drawn before this cell. */
    fun dashBefore(index: Int): Boolean = index > 0 && index % GROUP == 0

    /** "K7QX-M2" for a half-typed box, as the code is read aloud. */
    fun pretty(raw: String): String = raw.chunked(GROUP).joinToString("-")

    /** The room the box names, once it holds a whole code. */
    fun code(raw: String): RoomCode? = if (raw.length == RoomCode.LENGTH) RoomCode.parse(raw) else null
}

/**
 * A row of boxed cells for a room code, in groups of four with dashes
 * between. A text field the size of the row sits over it, unseen: it takes
 * the keyboard, paste and TalkBack, and the cells draw what it holds, with
 * the cell being typed in lit and a blinking cursor in it.
 *
 * [shake] goes up by one each time the row should shake and flash red: a
 * character the code doesn't use, or Go on a code that isn't whole.
 */
@Composable
internal fun CodeField(
    raw: String,
    onChange: (CodeEntry.Edit) -> Unit,
    onGo: () -> Unit,
    shake: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val still = AppSettings.ui.collectAsState().value.reduceAnimation
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var value by remember { mutableStateOf(TextFieldValue(raw, TextRange(raw.length))) }
    // The caller may change the code (a reset, an invite); the cursor stays at the end.
    val current = if (value.text == raw) value else TextFieldValue(raw, TextRange(raw.length))

    val offset = remember { Animatable(0f) }
    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(shake) {
        if (shake == 0) return@LaunchedEffect
        flash = true
        if (!still) {
            offset.snapTo(0f)
            offset.animateTo(0f, keyframes {
                durationMillis = 380
                -10f at 50
                9f at 110
                -7f at 170
                5f at 230
                -2f at 300
            })
        }
        delay(if (still) 1_200 else 900)
        flash = false
    }

    val blink = rememberInfiniteTransition(label = "cursor")
    val caret by blink.animateFloat(
        1f, 0f,
        infiniteRepeatable(keyframes { durationMillis = 1_000; 1f at 0; 1f at 499; 0f at 500; 0f at 999 }, RepeatMode.Restart),
        label = "caret",
    )
    val shown = if (still) 1f else caret
    val complete = raw.length == RoomCode.LENGTH

    BasicTextField(
        value = current,
        onValueChange = { next ->
            val edit = CodeEntry.accept(raw, next.text)
            // Kept as typed when nothing was changed, so the keyboard's composing text survives.
            value = if (edit.raw == next.text) next.copy(selection = TextRange(edit.raw.length)) else TextFieldValue(edit.raw, TextRange(edit.raw.length))
            onChange(edit)
        },
        enabled = enabled,
        singleLine = true,
        interactionSource = interaction,
        // The text itself is never seen; the cells draw it.
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.Transparent),
        cursorBrush = SolidColor(Color.Transparent),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { translationX = offset.value * density }
            .semantics {
                contentDescription = "Room code"
                stateDescription = if (raw.isEmpty()) "Empty, 12 characters" else com.opentune.i18n.Tr.f("{0}, {1} of 12", CodeEntry.pretty(raw), raw.length)
                if (flash) error("That isn't part of a room code")
            },
        decorationBox = { field ->
            Box(contentAlignment = Alignment.CenterStart) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    for (i in 0 until RoomCode.LENGTH) {
                        if (CodeEntry.dashBefore(i)) {
                            Box(Modifier.width(14.dp), contentAlignment = Alignment.Center) {
                                Box(Modifier.size(width = 7.dp, height = 2.dp).background(scheme.onSurfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(1.dp)))
                            }
                        } else if (i > 0) {
                            Box(Modifier.width(4.dp))
                        }
                        val active = focused && i == CodeEntry.active(raw)
                        val ch = CodeEntry.cell(raw, i)
                        val edge by animateColorAsState(
                            when {
                                flash -> scheme.error
                                active -> scheme.primary
                                complete -> scheme.primary.copy(alpha = 0.45f)
                                else -> scheme.outlineVariant
                            },
                            label = "cell",
                        )
                        Box(
                            Modifier.weight(1f).height(50.dp)
                                .background(
                                    if (active) scheme.primary.copy(alpha = 0.10f) else scheme.surfaceContainerHigh,
                                    CELL_SHAPE,
                                )
                                .border(if (active || flash) 2.dp else 1.dp, edge, CELL_SHAPE),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (ch != null) {
                                Text(
                                    ch.toString(),
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 20.sp,
                                    color = scheme.onSurface,
                                )
                            }
                            if (active && ch == null) {
                                Box(Modifier.size(width = 2.dp, height = 22.dp).graphicsLayer { alpha = shown }.background(scheme.primary))
                            }
                        }
                    }
                }
                // Laid out, so it takes focus and input, but drawn in clear.
                Box(Modifier.fillMaxWidth().height(1.dp)) { field() }
            }
        },
    )
}

private val CELL_SHAPE = RoundedCornerShape(10.dp)
