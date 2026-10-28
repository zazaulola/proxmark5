package io.github.zazaulola.proxmark5.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zazaulola.proxmark5.parse.CommandNode
import io.github.zazaulola.proxmark5.parse.CommandTree

// The groups of the Functions section, in the order they are shown.
private val GROUP_ORDER = listOf(
    "prefs", "analyse", "data", "hw", "emv", "hf", "lf", "mad",
    "nfc", "piv", "smart", "mqtt", "reveng", "script", "trace", "wiegand",
)

// Client commands that make no sense inside the app.
private val HIDDEN = setOf("quit", "exit")

/**
 * Everything the client can do, as the tree it has itself: the groups (prefs, analyse, data,
 * hw, emv, hf, lf, ...) and, inside them, the commands. A command runs in the living session;
 * its output streams to the terminal and the Status screen follows it.
 */
@Composable
fun FunctionsSection(vm: AppViewModel) {
    val tree by vm.commandTree.collectAsStateWithLifecycle()

    var path by rememberSaveable { mutableStateOf("") } // "hf mf", or "" for the top
    var query by rememberSaveable { mutableStateOf("") }
    var open by rememberSaveable { mutableStateOf<String?>(null) } // line of the command whose runner is open

    val names = if (path.isEmpty()) emptyList() else path.split(' ')
    val root = tree

    BackHandler(enabled = query.isNotEmpty() || path.isNotEmpty()) {
        if (query.isNotEmpty()) {
            query = ""
            open = null
        } else {
            path = path.substringBeforeLast(' ', "")
            open = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            // the runner that was open belongs to the list that was on screen: close it with the list
            onValueChange = { query = it; open = null },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            singleLine = true,
            placeholder = { Text("Поиск команды, напр. mf dump") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = ""; open = null }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Очистить поиск")
                    }
                }
            },
            // the list filters as you type, so Done just puts the keyboard away
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
        )

        val group = root?.find(names)?.takeIf { it.isGroup }
        when {
            root == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text("Загружаю команды клиента…")
                }
            }

            query.isNotBlank() -> SearchResults(vm, root, query, open, onOpen = { open = if (open == it) null else it })

            group == null || names.isEmpty() -> FunctionsHome(
                vm = vm,
                root = root,
                open = open,
                onOpenGroup = { path = it.line; open = null },
                onToggleLeaf = { open = if (open == it) null else it },
            )

            else -> GroupBrowser(
                vm = vm,
                group = group,
                open = open,
                onUp = { path = path.substringBeforeLast(' ', ""); open = null },
                onOpenGroup = { path = it.line; open = null },
                onToggleLeaf = { open = if (open == it) null else it },
            )
        }
    }
}

@Composable
private fun FunctionsHome(
    vm: AppViewModel,
    root: CommandNode,
    open: String?,
    onOpenGroup: (CommandNode) -> Unit,
    onToggleLeaf: (String) -> Unit,
) {
    val byName = root.children.associateBy { it.name }
    val main = GROUP_ORDER.mapNotNull { byName[it] }
    val rest = root.children.filter { it.name !in GROUP_ORDER && it.name !in HIDDEN }
    // a top-level command that is not a group (reveng, clear, ...) opens its runner in a dialog,
    // so it shows wherever on the screen its tile is
    val openLeaf = open?.let { root.child(it) }?.takeIf { !it.isGroup }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { CardSearchPanel(vm) }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text("Группы команд", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        }
        items(main, key = { it.name }) { node ->
            GroupTile(node) { if (node.isGroup) onOpenGroup(node) else onToggleLeaf(node.line) }
        }
        if (rest.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Прочее", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            }
            items(rest, key = { it.name }) { node ->
                GroupTile(node) { if (node.isGroup) onOpenGroup(node) else onToggleLeaf(node.line) }
            }
        }
    }

    if (openLeaf != null) {
        AlertDialog(
            onDismissRequest = { onToggleLeaf(openLeaf.line) },
            confirmButton = { TextButton(onClick = { onToggleLeaf(openLeaf.line) }) { Text("Закрыть") } },
            title = { Text(openLeaf.line, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace) },
            text = { CommandRunner(vm, openLeaf, onSent = { onToggleLeaf(openLeaf.line) }) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class) // Card(onClick) is experimental in older Material 3
@Composable
private fun GroupTile(node: CommandNode, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                node.name,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                color = if (node.available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                node.help,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun GroupBrowser(
    vm: AppViewModel,
    group: CommandNode,
    open: String?,
    onUp: () -> Unit,
    onOpenGroup: (CommandNode) -> Unit,
    onToggleLeaf: (String) -> Unit,
) {
    // subgroups first, then commands, each in the client's own order
    val children = group.children.sortedBy { if (it.isGroup) 0 else 1 }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onUp) { Icon(Icons.Filled.ArrowBack, contentDescription = "Назад") }
            Column(Modifier.weight(1f)) {
                Text(group.line, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                if (group.help.isNotBlank()) {
                    Text(group.help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxSize()) {
            items(children, key = { it.line }) { node ->
                if (node.isGroup) {
                    GroupRow(node) { onOpenGroup(node) }
                } else {
                    CommandRow(vm, node, label = node.name, isOpen = open == node.line) { onToggleLeaf(node.line) }
                }
            }
        }
    }
}

@Composable
private fun SearchResults(vm: AppViewModel, root: CommandNode, query: String, open: String?, onOpen: (String) -> Unit) {
    val found = remember(root, query) { CommandTree.search(root, query).filter { it.line !in HIDDEN } }
    if (found.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
            Text("Ничего не найдено", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(found, key = { it.line }) { node ->
            CommandRow(vm, node, label = node.line, isOpen = open == node.line) { onOpen(node.line) }
        }
    }
}

@Composable
private fun GroupRow(node: CommandNode, onClick: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    node.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (node.help.isNotBlank()) {
                    Text(node.help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // a group opens sideways, unlike a command, which opens in place
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider()
    }
}

@Composable
private fun CommandRow(vm: AppViewModel, node: CommandNode, label: String, isOpen: Boolean, onToggle: () -> Unit) {
    val nameColor = if (node.available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = nameColor)
                if (node.help.isNotBlank()) {
                    Text(node.help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(
                if (isOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isOpen) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) { CommandRunner(vm, node) }
        }
        HorizontalDivider()
    }
}

/**
 * Parameters and the buttons that send the command to the session. [onSent] runs after a
 * command went out, for a runner that should close itself (the dialog).
 */
@Composable
private fun CommandRunner(vm: AppViewModel, node: CommandNode, onSent: (() -> Unit)? = null) {
    var args by rememberSaveable(node.line) { mutableStateOf("") }
    var sent by remember(node.line) { mutableStateOf(false) }
    val connected = vm.connection.collectAsStateWithLifecycle().value is Connection.Connected

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = args,
            onValueChange = { args = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Параметры") },
            prefix = { Text(node.line + " ", fontFamily = FontFamily.Monospace) },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            // arguments, hex included: no capitals, no autocorrect, plain ASCII keys
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            ),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    vm.runCommand("${node.line} ${args}")
                    sent = true
                    onSent?.invoke()
                },
                enabled = node.available,
            ) { Text("Запустить") }
            // the client answers -h for any command, available or not
            OutlinedButton(
                onClick = {
                    vm.runCommand("${node.line} -h")
                    sent = true
                    onSent?.invoke()
                },
            ) { Text("Справка") }
        }
        if (!node.available) {
            Text(
                if (connected) {
                    "Недоступна: это устройство или его прошивка не поддерживает команду."
                } else {
                    "Сейчас недоступна: нужен подключённый Proxmark."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (sent) {
            Text(
                "Отправлено в сессию — вывод в терминале сверху, ход на Device → Status.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
