package io.github.zazaulola.proxmark5.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

// all in dp
private const val PEEK = 46f // the collapsed terminal: status and the last line
private const val HANDLE = 28f // the slider between the terminal and the section
private const val SHOW_LOG_FROM = 110f // tall enough for the log and the input
private const val MIN_OPEN = 160f // a terminal opened by tap or left by a drag is at least this tall
private const val MIN_SECTION = 120f // the section never gets squeezed below this

/**
 * Splits the screen in two: the terminal on top, the current section below, and a slider
 * between them. The terminal starts collapsed to a strip; drag the slider down to open it to
 * any height, or tap it to collapse and restore.
 */
@Composable
fun SplitShell(vm: AppViewModel, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var terminalDp by rememberSaveable { mutableStateOf(PEEK) }
    var lastOpenDp by rememberSaveable { mutableStateOf(0f) }
    var inputFocused by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val total = maxHeight.value
        // On a short window (landscape, split screen) the section may shrink so the terminal can
        // still open, and while the terminal's own input has focus the section yields further, so
        // the keyboard never takes away the line being typed into.
        val minSection = MIN_SECTION.coerceAtMost(total * 0.3f)
        val maxTerminal = (total - HANDLE - minSection).coerceAtLeast(if (inputFocused) SHOW_LOG_FROM else PEEK)
        val minOpen = MIN_OPEN.coerceAtMost(maxTerminal)
        val height = terminalDp.coerceIn(PEEK, maxTerminal)

        Column(Modifier.fillMaxSize()) {
            TerminalPanel(
                vm,
                expanded = height >= SHOW_LOG_FROM,
                modifier = Modifier.fillMaxWidth().height(height.dp),
                onInputFocus = { inputFocused = it },
            )
            SplitHandle(
                onDrag = { dy -> terminalDp = (terminalDp + dy).coerceIn(PEEK, maxTerminal) },
                onDragEnd = {
                    if (terminalDp < minOpen) {
                        terminalDp = PEEK
                    } else {
                        lastOpenDp = terminalDp
                    }
                },
                onTap = {
                    if (height > PEEK) {
                        // remember what the user chose, not the height the keyboard clipped it to
                        if (terminalDp >= minOpen) lastOpenDp = terminalDp
                        terminalDp = PEEK
                    } else {
                        val back = if (lastOpenDp >= minOpen) lastOpenDp else total * 0.4f
                        terminalDp = back.coerceIn(minOpen, maxTerminal)
                    }
                },
            )
            Box(Modifier.fillMaxWidth().weight(1f)) { content() }
        }
    }
}

@Composable
private fun SplitHandle(onDrag: (Float) -> Unit, onDragEnd: () -> Unit, onTap: () -> Unit) {
    val density = LocalDensity.current.density
    Box(
        Modifier
            .fillMaxWidth()
            .height(HANDLE.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onTap)
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { px -> onDrag(px / density) },
                onDragStopped = { onDragEnd() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 44.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.outline)
        )
    }
}
