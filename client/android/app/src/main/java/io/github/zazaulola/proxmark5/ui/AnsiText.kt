package io.github.zazaulola.proxmark5.ui

import android.content.ClipData
import android.view.View
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import io.github.zazaulola.proxmark5.parse.Ansi
import io.github.zazaulola.proxmark5.parse.output.Found
import io.github.zazaulola.proxmark5.parse.output.FoundKind
import io.github.zazaulola.proxmark5.parse.output.OutputScan

/**
 * One line of client output, rendering its ANSI colors. With [onFound] given, the useful items in
 * the line (file paths, UIDs, keys, hex, the commands the client suggests) are underlined in a
 * colour of their kind; a tap on one hands it to [onFound] (to copy, drop into the command line or
 * run), and a long press starts a drag of its value so it can be dropped into the command line or
 * another app. A long press away from an item copies the whole line through [onCopyLine].
 *
 * The gestures live in one [dragAndDropSource] per line, not a clickable per item, because a tap
 * target would swallow the touch and leave no way to drag or to long-press. A scroll still beats
 * the long-press timeout, so the log scrolls as before.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AnsiText(
    line: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontFamily: FontFamily? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    onFound: ((Found) -> Unit)? = null,
    onCopyLine: ((String) -> Unit)? = null,
) {
    val base = LocalContentColor.current
    val styled = remember(line) { Ansi.parse(line) }
    val found = remember(line, onFound != null) {
        if (onFound != null) OutputScan.scanLine(styled.text) else emptyList()
    }
    val annotated: AnnotatedString = remember(styled, base, found) {
        buildAnnotatedString {
            var pos = 0
            for (span in styled.spans) {
                if (span.start > pos) append(styled.text.substring(pos, span.start))
                withStyle(
                    SpanStyle(
                        color = span.fg?.toColor() ?: base,
                        fontWeight = if (span.bold) FontWeight.Bold else null,
                        textDecoration = if (span.underline) TextDecoration.Underline else null,
                    ),
                ) {
                    append(styled.text.substring(span.start, span.end.coerceAtMost(styled.text.length)))
                }
                pos = span.end
            }
            if (pos < styled.text.length) append(styled.text.substring(pos))
            for (f in found) {
                val end = f.end.coerceAtMost(length)
                if (f.start < end) {
                    addStyle(SpanStyle(color = foundColor(f.kind), textDecoration = TextDecoration.Underline), f.start, end)
                }
            }
        }
    }

    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val gesture = if (onFound != null && (found.isNotEmpty() || onCopyLine != null)) {
        Modifier.dragAndDropSource {
            detectTapGestures(
                onTap = { at ->
                    val off = layout?.getOffsetForPosition(at) ?: return@detectTapGestures
                    found.firstOrNull { off >= it.start && off < it.end }?.let { onFound(it) }
                },
                onLongPress = { at ->
                    val off = layout?.getOffsetForPosition(at)
                    val hit = off?.let { o -> found.firstOrNull { o >= it.start && o < it.end } }
                    if (hit != null) {
                        val clip = ClipData.newPlainText(foundKindName(hit.kind), hit.value)
                        // DRAG_FLAG_GLOBAL lets the value drop into another app, not only our input
                        startTransfer(DragAndDropTransferData(clip, flags = View.DRAG_FLAG_GLOBAL))
                    } else {
                        onCopyLine?.invoke(styled.text)
                    }
                },
            )
        }
    } else {
        Modifier
    }

    Text(
        annotated,
        modifier = modifier.then(gesture),
        fontSize = fontSize,
        fontFamily = fontFamily,
        maxLines = maxLines,
        overflow = overflow,
        onTextLayout = { layout = it },
    )
}

private fun foundColor(kind: FoundKind) = when (kind) {
    FoundKind.FILE -> TerminalFile
    FoundKind.UID -> TerminalUid
    FoundKind.KEY -> TerminalKey
    FoundKind.HEX -> TerminalHex
    FoundKind.COMMAND -> TerminalCommand
}
