package io.github.zazaulola.proxmark5.ui.params

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.zazaulola.proxmark5.ipc.SessionPaths
import io.github.zazaulola.proxmark5.parse.CommandNode
import io.github.zazaulola.proxmark5.parse.params.CommandDraft
import io.github.zazaulola.proxmark5.parse.params.CommandLine
import io.github.zazaulola.proxmark5.parse.params.DraftCheck
import io.github.zazaulola.proxmark5.parse.params.ParamSpec
import io.github.zazaulola.proxmark5.parse.params.ParamTag
import io.github.zazaulola.proxmark5.parse.params.Severity
import io.github.zazaulola.proxmark5.parse.params.ValueType
import io.github.zazaulola.proxmark5.ui.AppViewModel
import io.github.zazaulola.proxmark5.ui.Connection
import java.io.File

/**
 * One command, ready to send to the living session: its parameters as tags (from the client's own
 * help), the line they make, and Run. Tags are added from the list of the command's parameters,
 * edited by type, moved by a long press or from the editor, taken away with ×; an example from
 * the help or a line typed by hand reads back into tags. Nothing waits here: the output goes to
 * the terminal and the Status screen, so a long sniff never holds the screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CommandScreen(vm: AppViewModel, node: CommandNode, onBack: () -> Unit, params: ParamsViewModel = viewModel()) {
    val cmd = node.line
    LaunchedEffect(cmd) { params.load(cmd) }
    val state = params.helpState(cmd)
    val draft = params.draft(cmd)
    val specs = (state as? HelpState.Ready)?.specs.orEmpty()
    val connected = vm.connection.collectAsStateWithLifecycle().value is Connection.Connected
    val context = LocalContext.current
    val places = remember(vm.dumpDir) {
        FilePlaces(File(vm.dumpDir), File(File(SessionPaths.home(context), ".proxmark3"), "dictionaries"))
    }

    // "t:<tag id>" edits a tag, "n:<parameter key>" adds a parameter with the value typed
    var editing by rememberSaveable(cmd) { mutableStateOf<String?>(null) }
    var picking by rememberSaveable(cmd) { mutableStateOf(false) }
    var typing by rememberSaveable(cmd) { mutableStateOf(false) }
    var aboutOpen by rememberSaveable(cmd) { mutableStateOf(false) }
    // the draft before the last change that is easy to regret (a removal, an example, a reset)
    var undo by remember(cmd) { mutableStateOf<Pair<String, CommandDraft>?>(null) }
    var sent by remember(cmd) { mutableStateOf<String?>(null) }

    fun change(next: CommandDraft, undoable: String? = null) {
        val before = params.draft(cmd)
        undo = if (undoable != null && before != null) undoable to before else null
        params.update(next)
    }

    fun remove(tag: ParamTag) {
        val d = params.draft(cmd) ?: return
        change(d.remove(tag.id), "Убран ${tag.label}")
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
            Column(Modifier.weight(1f)) {
                Text(cmd, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                if (node.help.isNotBlank()) {
                    Text(node.help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!node.available) {
                Note(if (connected) "Недоступна: это устройство или его прошивка не поддерживает команду." else "Сейчас недоступна: нужен подключённый Proxmark.")
            }

            when (state) {
                HelpState.Loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Читаю справку команды…", style = MaterialTheme.typography.bodySmall)
                }
                is HelpState.Missing -> Note(
                    if (state.noDump) {
                        "В этой сборке нет справки команд, поэтому параметры только текстом. «Справка» покажет её в терминале."
                    } else {
                        "Этой команды нет в справке, вложенной в приложение. Параметры только текстом; «Справка» покажет их в терминале."
                    },
                )
                is HelpState.Ready -> {
                    val about = state.help.description.trim()
                    if (about.isNotEmpty()) {
                        val long = about.lines().size > 3 || about.length > 240
                        Text(
                            about,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = if (aboutOpen || !long) Int.MAX_VALUE else 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (long) {
                            TextButton(onClick = { aboutOpen = !aboutOpen }, contentPadding = TextButtonPaddingSmall) {
                                Text(if (aboutOpen) "Свернуть" else "Подробнее")
                            }
                        }
                    }
                }
            }

            if (draft != null) {
                val problems = remember(draft, specs) { DraftCheck.problems(draft, specs) }
                // the worse problem of a tag colors it
                val byTag = remember(problems) {
                    problems.filter { it.tagId != null }
                        .groupBy { it.tagId!! }
                        .mapValues { (_, list) -> list.minBy { it.problem.severity.ordinal }.problem }
                }
                val noParams = state is HelpState.Ready && specs.isEmpty()

                Text("Параметры", style = MaterialTheme.typography.titleSmall)
                if (noParams && draft.tags.isEmpty()) {
                    Text("У команды нет параметров.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    TagFlow(
                        tags = draft.tags,
                        problems = byTag,
                        onEdit = { editing = "t:${it.id}" },
                        onRemove = { remove(it) },
                        onMove = { id, to -> params.draft(cmd)?.let { d -> change(d.move(id, to)) } },
                        // without the help there is nothing to pick from: the line is typed
                        onAdd = { if (specs.isEmpty()) typing = true else picking = true },
                    )
                }

                undo?.let { (what, before) ->
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(what, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = {
                                params.update(before)
                                undo = null
                            }) { Text("Вернуть") }
                        }
                    }
                }

                // what the tags can't show: a required parameter missing, a too long line
                val general = problems.filter { it.tagId == null }
                val tagged = problems.filter { it.tagId != null }
                if (general.isNotEmpty() || tagged.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (p in general + tagged) {
                            val who = p.tagId?.let { id -> draft.tags.firstOrNull { it.id == id }?.label }
                            Text(
                                (if (who != null) "$who: " else "") + p.problem.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (p.problem.severity == Severity.ERROR) MaterialTheme.colorScheme.error else warningColor(),
                            )
                        }
                    }
                }

                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    SelectionContainer {
                        Text(
                            CommandLine.build(draft, placeholders = true),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
                        onClick = {
                            val line = CommandLine.build(draft)
                            vm.runCommand(line)
                            sent = line
                        },
                        enabled = node.available && DraftCheck.canRun(problems),
                    ) { Text("Запустить") }
                    OutlinedButton(onClick = { typing = true }) { Text("Текстом") }
                    // the client answers -h for any command, available or not
                    OutlinedButton(onClick = {
                        vm.runCommand("$cmd -h")
                        sent = "$cmd -h"
                    }) { Text("Справка") }
                    if (state is HelpState.Ready) {
                        TextButton(onClick = { change(CommandDraft.start(cmd, specs), "Параметры сброшены") }) { Text("Сбросить") }
                    }
                }
                sent?.let {
                    Text(
                        "Отправлено в сессию: $it\nВывод — в терминале сверху, ход — на Device → Status.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (state is HelpState.Ready) {
                    val examples = state.help.examples.filter { it.text == cmd || it.text.startsWith("$cmd ") }
                    if (examples.isNotEmpty()) {
                        Text("Примеры из справки", style = MaterialTheme.typography.titleSmall)
                        Text("Нажмите пример, чтобы взять его параметры.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        for (ex in examples) {
                            Surface(
                                onClick = { change(CommandLine.parse(cmd, ex.text, specs), "Взяты параметры примера") },
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    Text(ex.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                    ex.note?.takeIf { it.isNotBlank() }?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val d = draft ?: return

    if (picking && specs.isNotEmpty()) {
        ParamPicker(
            command = cmd,
            specs = specs,
            draft = d,
            onPick = { spec ->
                picking = false
                val cur = params.draft(cmd) ?: return@ParamPicker
                val there = cur.tags.firstOrNull { it.spec?.key == spec.key }
                when {
                    // there already and may not repeat: open what is there
                    there != null && !spec.repeatable -> editing = "t:${there.id}"
                    spec.isFlag -> change(cur.add(spec))
                    else -> editing = "n:${spec.key}"
                }
            },
            onDismiss = { picking = false },
        )
    }

    editing?.let { what ->
        val close = { editing = null }
        if (what.startsWith("t:")) {
            val id = what.removePrefix("t:").toLongOrNull()
            val index = d.tags.indexOfFirst { it.id == id }
            val tag = d.tags.getOrNull(index)
            if (tag == null) {
                LaunchedEffect(what) { editing = null }
            } else {
                // a new key per tag: the dialog's own state starts again for another tag
                key(what) {
                    ValueEditorDialog(
                        command = cmd,
                        spec = tag.spec,
                        initial = if (tag.spec != null) tag.value else tag.raw.orEmpty(),
                        isNew = false,
                        places = places,
                        onDone = { v ->
                            params.draft(cmd)?.let { cur -> change(if (tag.spec != null) cur.setValue(tag.id, v) else cur.setRaw(tag.id, v)) }
                            close()
                        },
                        onDismiss = close,
                        onEarlier = if (index > 0) ({ params.draft(cmd)?.let { cur -> change(cur.moveBy(tag.id, -1)) } }) else null,
                        onLater = if (index < d.tags.lastIndex) ({ params.draft(cmd)?.let { cur -> change(cur.moveBy(tag.id, 1)) } }) else null,
                        onRemove = {
                            remove(tag)
                            close()
                        },
                    )
                }
            }
        } else {
            val spec: ParamSpec? = specs.firstOrNull { it.key == what.removePrefix("n:") }
            if (spec == null) {
                LaunchedEffect(what) { editing = null }
            } else {
                key(what) {
                    ValueEditorDialog(
                        command = cmd,
                        spec = spec,
                        initial = (spec.type as? ValueType.Choice)?.default.orEmpty(),
                        isNew = true,
                        places = places,
                        onDone = { v ->
                            params.draft(cmd)?.let { cur -> change(cur.add(spec, v)) }
                            close()
                        },
                        onDismiss = close,
                    )
                }
            }
        }
    }

    if (typing) {
        LineEditorDialog(
            command = cmd,
            initial = CommandLine.build(d),
            canRun = node.available,
            onParse = { text ->
                change(CommandLine.parse(cmd, text, specs), "Строка разобрана заново")
                typing = false
            },
            onRun = { text ->
                vm.runCommand(text)
                sent = text.trim()
                typing = false
            },
            onDismiss = { typing = false },
        )
    }
}

private val TextButtonPaddingSmall = PaddingValues(horizontal = 0.dp, vertical = 0.dp)

@Composable
private fun Note(text: String) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
    }
}

/**
 * The whole line as text, for what is quicker typed than tapped, or pasted. "Разобрать" reads it
 * back into tags (what the help does not know stays as raw tags); "Запустить как есть" sends it
 * exactly as typed.
 */
@Composable
private fun LineEditorDialog(
    command: String,
    initial: String,
    canRun: Boolean,
    onParse: (String) -> Unit,
    onRun: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val words = command.split(' ')
    val own = CommandLine.tokenize(text).take(words.size).map { it.lowercase() } == words.map { it.lowercase() }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text("Строка команды") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.replace('\n', ' ') },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                    ),
                )
                if (!own) {
                    Text(
                        "Строка начинается не с «$command»: при разборе все её слова станут параметрами этой команды.",
                        style = MaterialTheme.typography.bodySmall,
                        color = warningColor(),
                    )
                }
                if (canRun) {
                    TextButton(onClick = { onRun(text) }, enabled = text.isNotBlank()) { Text("Запустить как есть") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onParse(text) }) { Text("Разобрать") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
