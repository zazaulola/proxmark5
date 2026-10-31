package io.github.zazaulola.proxmark5.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zazaulola.proxmark5.history.HistoryEntry
import io.github.zazaulola.proxmark5.history.HistoryTime
import io.github.zazaulola.proxmark5.history.RunOutcome
import io.github.zazaulola.proxmark5.parse.output.FoundKind
import io.github.zazaulola.proxmark5.parse.output.FoundRef

/** The color of how a run ended: green done, red error, amber stopped, blue running, grey not known. */
fun outcomeColor(o: RunOutcome): Color = when (o) {
    RunOutcome.OK -> Color(0xFF6BE675)
    RunOutcome.FAILED -> Color(0xFFFF6B6B)
    RunOutcome.STOPPED -> Color(0xFFFFD166)
    RunOutcome.PENDING -> Color(0xFF6CA0FF)
    RunOutcome.UNKNOWN -> Color(0xFF8A8A8A)
}

fun outcomeText(e: HistoryEntry): String = when (e.outcome) {
    RunOutcome.OK -> "выполнена"
    RunOutcome.FAILED -> "ошибка" + (e.code?.let { " $it" } ?: "")
    RunOutcome.STOPPED -> "остановлена"
    RunOutcome.PENDING -> "выполняется"
    RunOutcome.UNKNOWN -> "итог неизвестен"
}

@Composable
private fun OutcomeDot(o: RunOutcome, size: Int = 8) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(outcomeColor(o)))
}

/** What can be done with a line of the history, wherever it is shown. */
class HistoryActions(
    /** Put the line in the command line, to change it or send it as it is. */
    val fill: (String) -> Unit,
    val run: (String) -> Unit,
    /** Open the line in the tag editor of its command. */
    val tags: (String) -> Unit,
    val pin: (String, Boolean) -> Unit,
    val remove: (String) -> Unit,
    val copy: (String) -> Unit,
)

@Composable
private fun HistoryMenu(entry: HistoryEntry, open: Boolean, onClose: () -> Unit, actions: HistoryActions) {
    DropdownMenu(expanded = open, onDismissRequest = onClose) {
        DropdownMenuItem(text = { Text("Выполнить сразу") }, onClick = { onClose(); actions.run(entry.line) })
        DropdownMenuItem(text = { Text("В строку команды") }, onClick = { onClose(); actions.fill(entry.line) })
        DropdownMenuItem(text = { Text("Открыть в редакторе тегов") }, onClick = { onClose(); actions.tags(entry.line) })
        DropdownMenuItem(
            text = { Text(if (entry.pinned) "Открепить" else "Закрепить") },
            onClick = { onClose(); actions.pin(entry.line, !entry.pinned) },
        )
        DropdownMenuItem(text = { Text("Копировать строку") }, onClick = { onClose(); actions.copy(entry.line) })
        DropdownMenuItem(
            text = { Text("Удалить из истории", color = MaterialTheme.colorScheme.error) },
            onClick = { onClose(); actions.remove(entry.line) },
        )
    }
}

/**
 * The last commands as a row over the terminal's command line: a tap puts the line into the
 * command line (then Send runs it as it is, or it is changed first), a long press offers the rest
 * (run at once, the tag editor, pin, delete). The last chip opens the whole history.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryStrip(entries: List<HistoryEntry>, actions: HistoryActions, onAll: () -> Unit, modifier: Modifier = Modifier) {
    LazyRow(
        modifier.fillMaxWidth().height(34.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(entries, key = { it.line }) { e ->
            var menu by remember { mutableStateOf(false) }
            Box {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(TerminalChip)
                        .combinedClickable(onClick = { actions.fill(e.line) }, onLongClick = { menu = true })
                        .heightIn(min = 28.dp)
                        .widthIn(max = 260.dp)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (e.pinned) {
                        Icon(Icons.Filled.PushPin, contentDescription = "закреплена", modifier = Modifier.size(12.dp), tint = TerminalDim)
                        Spacer(Modifier.width(4.dp))
                    }
                    OutcomeDot(e.outcome, 6)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        e.line,
                        color = TerminalForeground,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                HistoryMenu(e, menu, { menu = false }, actions)
            }
        }
        item(key = "\u0000all") {
            Row(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .combinedClickable(onClick = onAll)
                    .heightIn(min = 28.dp)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.size(14.dp), tint = TerminalDim)
                Spacer(Modifier.width(4.dp))
                Text("вся история", color = TerminalDim, fontSize = 12.sp)
            }
        }
    }
}

/**
 * The whole history: search, pinned lines on top, how each run ended and what its output showed.
 * A tap puts a line into the command line and closes the list; ▶ runs it at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySheet(vm: AppViewModel, actions: HistoryActions, onFound: (FoundRef) -> Unit, onDismiss: () -> Unit) {
    val history by vm.history.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val now = remember { System.currentTimeMillis() }
    val shown = remember(history, query) { history.search(query).sortedByDescending { it.pinned } }

    // a tap closes the list: the line is waiting in the command line
    val close = HistoryActions(
        fill = { actions.fill(it); onDismiss() },
        run = { actions.run(it); onDismiss() },
        tags = { actions.tags(it); onDismiss() },
        pin = actions.pin,
        remove = actions.remove,
        copy = actions.copy,
    )

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("История команд", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (history.entries.any { !it.pinned }) {
                    TextButton(onClick = { confirmClear = true }) { Text("Очистить") }
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                singleLine = true,
                placeholder = { Text("Поиск: команда, UID, ключ, файл") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, contentDescription = "Очистить поиск") }
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            )
            if (shown.isEmpty()) {
                Text(
                    if (history.isEmpty) "Здесь появятся команды, которые вы запускали: из терминала и из Functions." else "Ничего не найдено",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
            LazyColumn(Modifier.fillMaxWidth()) {
                items(shown, key = { it.line }) { e ->
                    HistoryRow(e, now, close, onFound)
                    HorizontalDivider()
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить историю?") },
            text = { Text("Закреплённые команды останутся.") },
            confirmButton = { TextButton(onClick = { vm.clearHistory(); confirmClear = false }) { Text("Очистить") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(e: HistoryEntry, now: Long, actions: HistoryActions, onFound: (FoundRef) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = { actions.fill(e.line) }, onLongClick = { menu = true })
            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(e.line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (e.pinned) {
                    Icon(Icons.Filled.PushPin, contentDescription = "закреплена", modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                }
                OutcomeDot(e.outcome)
                Spacer(Modifier.width(6.dp))
                val times = if (e.count > 1) " · ${e.count} раз" else ""
                Text(
                    "${outcomeText(e)} · ${HistoryTime.ago(now, e.lastAt)}$times",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (e.found.isNotEmpty()) {
                FoundRefRow(e.found, onFound)
            }
        }
        IconButton(onClick = { actions.run(e.line) }) { Icon(Icons.Filled.PlayArrow, contentDescription = "Выполнить сразу") }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Ещё") }
            HistoryMenu(e, menu, { menu = false }, actions)
        }
    }
}

/** What a run's output showed, as small chips: a tap does what [onFound] says (copy, by default). */
@Composable
fun FoundRefRow(found: List<FoundRef>, onFound: (FoundRef) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(found) { f ->
            Surface(
                onClick = { onFound(f) },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    foundChipText(f),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 220.dp).padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** Short text for a found item on a chip: "UID 04A23B1A", "ключ FFFFFFFFFFFF", the file's name. */
fun foundChipText(f: FoundRef): String {
    val label = f.label ?: foundKindName(f.kind)
    val value = if (f.kind == FoundKind.FILE) f.value.substringAfterLast('/') else f.value
    return "$label $value"
}

fun foundKindName(k: FoundKind): String = when (k) {
    FoundKind.FILE -> "файл"
    FoundKind.UID -> "UID"
    FoundKind.KEY -> "ключ"
    FoundKind.HEX -> "hex"
    FoundKind.COMMAND -> "команда"
}

/** Chips inside the always-dark terminal. */
val TerminalChip = Color(0xFF1C2433)
val TerminalDim = Color(0xFF8A8A8A)

/**
 * What to do with an item tapped in the output: drop it into the command line, copy it, or (for a
 * command the client suggested) run it. A key is copied as sensitive, so keyboards do not keep it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoundActionSheet(found: FoundRef, onInsert: () -> Unit, onCopy: () -> Unit, onRun: (() -> Unit)?, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(foundKindName(found.kind), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                found.value,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            HorizontalDivider()
            if (onRun != null) {
                SheetAction(Icons.Filled.PlayArrow, "Выполнить команду", onRun)
            }
            SheetAction(Icons.Filled.History, "В строку команды", onInsert)
            SheetAction(Icons.Filled.ContentCopy, "Копировать", onCopy)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SheetAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
