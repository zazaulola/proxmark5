package io.github.zazaulola.proxmark5.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zazaulola.proxmark5.core.Activity
import io.github.zazaulola.proxmark5.parse.Activities
import io.github.zazaulola.proxmark5.parse.ActivityKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun activityColor(kind: ActivityKind): Color = when (kind) {
    ActivityKind.IDLE -> ActivityIdleColor
    ActivityKind.COMMAND -> ActivityCommandColor
    ActivityKind.SNIFFER -> ActivitySnifferColor
    ActivityKind.EMULATION -> ActivityEmulationColor
}

/** The current time, refreshed twice a second while [active]; for elapsed-time displays. */
@Composable
fun rememberNow(active: Boolean): State<Long> = produceState(System.currentTimeMillis(), active) {
    value = System.currentTimeMillis()
    while (active) {
        delay(500)
        value = System.currentTimeMillis()
    }
}

/**
 * The live log of the one client session, with a line to type commands into. Collapsed
 * ([expanded] = false) it is a strip that shows what the session is doing and the last line
 * of output; opened, the whole log scrolls under it. Stays dark whatever the app theme.
 * [onInputFocus] reports whether the command line has focus, so the shell can keep room for it.
 */
@Composable
fun TerminalPanel(
    vm: AppViewModel,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    onInputFocus: (Boolean) -> Unit = {},
) {
    val lines by vm.terminal.collectAsStateWithLifecycle()
    val activity by vm.activity.collectAsStateWithLifecycle()
    val conn by vm.connection.collectAsStateWithLifecycle()
    val now by rememberNow(activity.running)

    CompositionLocalProvider(LocalContentColor provides TerminalForeground) {
        Column(modifier.background(TerminalBackground)) {
            // nothing may stop a firmware write: no Stop while the flasher owns the device
            val canStop = activity.running && conn != Connection.Flashing
            StatusBar(vm, activity, now, lines.lastOrNull { it.isNotBlank() }.orEmpty(), expanded, canStop)
            if (expanded) {
                LogView(lines, Modifier.weight(1f).fillMaxWidth())
                CommandInput(vm, onInputFocus)
            }
        }
    }
}

private fun statusText(a: Activity, now: Long): String {
    if (!a.running) return "idle"
    val kind = when (a.kind) {
        ActivityKind.SNIFFER -> "sniffer"
        ActivityKind.EMULATION -> "emulation"
        else -> "run"
    }
    val queued = if (a.queued > 0) "  +${a.queued}" else ""
    return "$kind ${Activities.elapsed(now - a.startedAtMs)}  ${a.command}$queued"
}

@Composable
private fun StatusBar(vm: AppViewModel, activity: Activity, now: Long, lastLine: String, expanded: Boolean, canStop: Boolean) {
    val color = activityColor(activity.kind)
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Row(
        Modifier
            .fillMaxWidth()
            .height(if (expanded) 40.dp else 46.dp)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                statusText(activity, now),
                color = color,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!expanded) {
                AnsiText(
                    lastLine,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (canStop) {
            IconButton(onClick = { vm.stop() }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Stop, contentDescription = "Остановить", tint = ActivitySnifferColor)
            }
        }
        if (expanded) {
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(vm.terminalText()))
                    Toast.makeText(context, "Лог скопирован", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(40.dp),
            ) { Icon(Icons.Filled.ContentCopy, contentDescription = "Копировать лог") }
            IconButton(
                onClick = {
                    scope.launch {
                        val file = withContext(Dispatchers.IO) { vm.saveLog() }
                        val msg = if (file != null) "Сохранено в Library → Logs: ${file.name}" else "Не удалось сохранить лог"
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.size(40.dp),
            ) { Icon(Icons.Filled.Save, contentDescription = "Сохранить лог") }
            IconButton(onClick = { vm.clearTerminal() }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Clear, contentDescription = "Очистить")
            }
        }
    }
}

@Composable
private fun LogView(lines: List<String>, modifier: Modifier = Modifier) {
    val state = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }

    // Follow the end of the log until the user scrolls away from it; follow again once they
    // are back at the end. Only a finished scroll counts: new lines growing the list don't.
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) follow = !state.canScrollForward
        }
    }
    LaunchedEffect(lines, follow) {
        if (follow && lines.isNotEmpty()) state.scrollToItem(lines.lastIndex)
    }

    Box(modifier) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
            itemsIndexed(lines) { _, line ->
                AnsiText(
                    line,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (!follow) {
            SmallFloatingActionButton(
                onClick = { follow = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            ) { Icon(Icons.Filled.ArrowDownward, contentDescription = "К концу лога") }
        }
    }
}

@Composable
private fun CommandInput(vm: AppViewModel, onFocus: (Boolean) -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { onFocus(false) } }
    fun send() {
        vm.runCommand(input)
        input = ""
    }

    val dim = Color(0xFF8A8A8A)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.weight(1f).onFocusChanged { onFocus(it.isFocused) },
            singleLine = true,
            placeholder = { Text("команда, напр. hw status") },
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            // commands and hex arguments: no capitals, no autocorrect, plain ASCII keys
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(onSend = { send() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TerminalForeground,
                unfocusedTextColor = TerminalForeground,
                cursorColor = TerminalForeground,
                focusedBorderColor = ActivityCommandColor,
                unfocusedBorderColor = Color(0xFF3B3B3B),
                focusedPlaceholderColor = dim,
                unfocusedPlaceholderColor = dim,
            ),
        )
        IconButton(onClick = { send() }, enabled = input.isNotBlank()) {
            Icon(Icons.Filled.Send, contentDescription = "Отправить")
        }
    }
}
