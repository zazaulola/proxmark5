package io.github.zazaulola.proxmark5.ui.params

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.zazaulola.proxmark5.parse.params.Box as TagBox
import io.github.zazaulola.proxmark5.parse.params.ParamTag
import io.github.zazaulola.proxmark5.parse.params.Problem
import io.github.zazaulola.proxmark5.parse.params.Reorder
import io.github.zazaulola.proxmark5.parse.params.Severity
import io.github.zazaulola.proxmark5.parse.params.TagText
import io.github.zazaulola.proxmark5.parse.params.ValueType
import io.github.zazaulola.proxmark5.parse.params.Values
import kotlin.math.roundToInt

/** Warning color readable on both themes: Material 3 has no warning role. */
@Composable
internal fun warningColor(): Color = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFFFFC857) else Color(0xFF8A5A00)

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

/**
 * The parameters of a command as tags that wrap over lines. A tap opens the tag's editor, the ×
 * takes it away, a long press lifts it and dragging puts it elsewhere: a line shows where it will
 * land, and it moves there when let go (the tags don't shuffle under the finger). For TalkBack
 * every tag also has the actions "Раньше", "Позже" and "Убрать".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagFlow(
    tags: List<ParamTag>,
    problems: Map<Long, Problem>,
    onEdit: (ParamTag) -> Unit,
    onRemove: (ParamTag) -> Unit,
    onMove: (id: Long, to: Int) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val current by rememberUpdatedState(tags)
    // the drag handler outlives recompositions: it must call the latest callback
    val move by rememberUpdatedState(onMove)
    // where each tag is, in the FlowRow's own coordinates
    val bounds = remember { mutableStateMapOf<Long, TagBox>() }
    var dragging by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var frozen by remember { mutableStateOf<List<TagBox>>(emptyList()) }
    var slot by remember { mutableStateOf<Int?>(null) }

    fun endDrag(commit: Boolean) {
        val id = dragging
        val s = slot
        if (commit && id != null && s != null) {
            val from = current.indexOfFirst { it.id == id }
            if (from >= 0) {
                val to = Reorder.target(from, s)
                if (to != from) move(id, to)
            }
        }
        dragging = null
        slot = null
        dragOffset = Offset.Zero
    }

    Box(modifier.fillMaxWidth()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tags.forEachIndexed { index, tag ->
                key(tag.id) {
                    val lifted = dragging == tag.id
                    TagChip(
                        tag = tag,
                        problem = problems[tag.id],
                        // a long press lets go with an up the tap would also take: no editor then
                        onTap = { if (dragging == null) onEdit(tag) },
                        onRemove = { onRemove(tag) },
                        modifier = Modifier
                            .onGloballyPositioned { c ->
                                if (dragging == null) {
                                    val r = c.boundsInParent()
                                    bounds[tag.id] = TagBox(r.left, r.top, r.right, r.bottom)
                                }
                            }
                            .zIndex(if (lifted) 1f else 0f)
                            .graphicsLayer {
                                if (lifted) {
                                    translationX = dragOffset.x
                                    translationY = dragOffset.y
                                    scaleX = 1.05f
                                    scaleY = 1.05f
                                    shadowElevation = 8.dp.toPx()
                                }
                            }
                            .pointerInput(tag.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        frozen = current.mapNotNull { bounds[it.id] }
                                        dragging = tag.id
                                        dragOffset = Offset.Zero
                                        slot = null
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragOffset += amount
                                        val from = current.indexOfFirst { it.id == tag.id }
                                        val box = frozen.getOrNull(from)
                                        if (box != null && frozen.size == current.size) {
                                            val x = box.centerX + dragOffset.x
                                            val y = (box.top + box.bottom) / 2 + dragOffset.y
                                            slot = Reorder.slot(frozen, x, y)
                                        }
                                    },
                                    onDragEnd = { endDrag(commit = true) },
                                    onDragCancel = { endDrag(commit = false) },
                                )
                            }
                            .semantics {
                                customActions = listOfNotNull(
                                    if (index > 0) CustomAccessibilityAction("Раньше") { onMove(tag.id, index - 1); true } else null,
                                    if (index < tags.lastIndex) CustomAccessibilityAction("Позже") { onMove(tag.id, index + 1); true } else null,
                                    CustomAccessibilityAction("Убрать") { onRemove(tag); true },
                                )
                            },
                    )
                }
            }
            AddChip(onAdd)
        }

        // where the lifted tag will land
        val s = slot
        if (dragging != null && s != null && frozen.isNotEmpty()) {
            val at = frozen.getOrNull(s)
            val prev = frozen.getOrNull(s - 1)
            val (x, top, bottom) = when {
                at != null && (prev == null || prev.top != at.top) -> Triple(at.left - with(density) { 5.dp.toPx() }, at.top, at.bottom)
                prev != null -> Triple(prev.right + with(density) { 3.dp.toPx() }, prev.top, prev.bottom)
                at != null -> Triple(at.left - with(density) { 5.dp.toPx() }, at.top, at.bottom)
                else -> Triple(0f, 0f, 0f)
            }
            Box(
                Modifier
                    .offset { IntOffset(x.roundToInt(), top.roundToInt()) }
                    .size(width = 2.dp, height = with(density) { (bottom - top).toDp() })
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun TagChip(tag: ParamTag, problem: Problem?, onTap: () -> Unit, onRemove: () -> Unit, modifier: Modifier) {
    val spec = tag.spec
    val error = problem?.severity == Severity.ERROR
    val warning = problem?.severity == Severity.WARNING
    val borderColor = when {
        error -> MaterialTheme.colorScheme.error
        warning -> warningColor()
        spec == null -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.primary
    }
    val shown = when {
        spec == null -> ""
        spec.isFlag -> ""
        else -> Values.normalize(spec.type, tag.value).let { v ->
            val type = spec.type // a property of another module: no smart cast on it
            when {
                v.isEmpty() -> "?"
                type is ValueType.Hex && !type.asInteger -> TagText.middle(TagText.groupBytes(v), 28)
                // a picked file goes as a full path; its name is what tells it apart
                type is ValueType.File -> TagText.middle(v.substringAfterLast('/').ifEmpty { v }, 28)
                else -> TagText.middle(v, 28)
            }
        }
    }
    val described = buildString {
        append(tag.label)
        if (shown.isNotEmpty()) append(" ").append(if (shown == "?") "без значения" else shown)
        problem?.let { append(", ").append(it.message) }
    }
    Surface(
        onClick = onTap,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(if (problem != null) 2.dp else 1.dp, borderColor),
        color = if (spec == null) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        modifier = modifier
            .widthIn(max = 320.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = described
                onClick(label = "Изменить") { onTap(); true }
            },
    ) {
        Row(Modifier.padding(start = 10.dp, end = 2.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (problem != null) {
                Icon(
                    if (error) Icons.Filled.ErrorOutline else Icons.Filled.WarningAmber,
                    contentDescription = null,
                    tint = borderColor,
                    modifier = Modifier.size(16.dp).padding(end = 2.dp),
                )
            }
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = if (spec == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)) {
                        append(tag.label)
                    }
                    if (shown.isNotEmpty()) {
                        append(" ")
                        withStyle(SpanStyle(color = if (shown == "?") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)) {
                            append(shown)
                        }
                    }
                },
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            // the × has its own target; a removal can be undone right after
            Box(
                Modifier
                    .size(32.dp)
                    .clickable(onClickLabel = "Убрать ${tag.label}", onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Убрать ${tag.label}", modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun AddChip(onAdd: () -> Unit) {
    Surface(
        onClick = onAdd,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(" параметр", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
