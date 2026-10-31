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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zazaulola.proxmark5.parse.CommandNode
import io.github.zazaulola.proxmark5.parse.CommandTree
import io.github.zazaulola.proxmark5.ui.params.CommandScreen

// The groups of the Functions section, in the order they are shown.
private val GROUP_ORDER = listOf(
    "prefs", "analyse", "data", "hw", "emv", "hf", "lf", "mad",
    "nfc", "piv", "smart", "mqtt", "reveng", "script", "trace", "wiegand",
)

// Client commands that make no sense inside the app.
private val HIDDEN = setOf("quit", "exit")

/**
 * Everything the client can do, as the tree it has itself: the groups (prefs, analyse, data,
 * hw, emv, hf, lf, ...) and, inside them, the commands. A command opens its own screen, where
 * its parameters are put together as tags from its help and the line is sent to the living
 * session; its output streams to the terminal and the Status screen follows it.
 */
@Composable
fun FunctionsSection(vm: AppViewModel) {
    val tree by vm.commandTree.collectAsStateWithLifecycle()

    var path by rememberSaveable { mutableStateOf("") } // "hf mf", or "" for the top
    var query by rememberSaveable { mutableStateOf("") }
    var open by rememberSaveable { mutableStateOf<String?>(null) } // line of the command whose screen is open
    val names = if (path.isEmpty()) emptyList() else path.split(' ')
    val root = tree

    // kept here, so coming back from a command finds the list where it was left
    val homeGrid = rememberLazyGridState()
    val groupList = rememberSaveable(path, saver = LazyListState.Saver) { LazyListState() }
    val foundList = rememberSaveable(query, saver = LazyListState.Saver) { LazyListState() }

    // Back: out of a command first, then out of the search, then up a level
    BackHandler(enabled = open != null || query.isNotEmpty() || path.isNotEmpty()) {
        when {
            open != null -> open = null
            query.isNotEmpty() -> query = ""
            else -> path = path.substringBeforeLast(' ', "")
        }
    }

    val command = open?.let { line -> root?.find(line.split(' ')) }?.takeIf { !it.isGroup }
    if (command != null) {
        CommandScreen(vm, command, onBack = { open = null })
        return
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            singleLine = true,
            placeholder = { Text("Поиск команды, напр. mf dump") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
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

            query.isNotBlank() -> SearchResults(root, query, foundList, onOpen = { open = it })

            group == null || names.isEmpty() -> FunctionsHome(
                vm = vm,
                root = root,
                state = homeGrid,
                onOpenGroup = { path = it.line },
                onOpenCommand = { open = it },
            )

            else -> GroupBrowser(
                group = group,
                state = groupList,
                onUp = { path = path.substringBeforeLast(' ', "") },
                onOpenGroup = { path = it.line },
                onOpenCommand = { open = it },
            )
        }
    }
}

@Composable
private fun FunctionsHome(
    vm: AppViewModel,
    root: CommandNode,
    state: LazyGridState,
    onOpenGroup: (CommandNode) -> Unit,
    onOpenCommand: (String) -> Unit,
) {
    val byName = root.children.associateBy { it.name }
    val main = GROUP_ORDER.mapNotNull { byName[it] }
    val rest = root.children.filter { it.name !in GROUP_ORDER && it.name !in HIDDEN }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        state = state,
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { CardSearchPanel(vm) }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text("Группы команд", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        }
        items(main, key = { it.name }) { node ->
            GroupTile(node) { if (node.isGroup) onOpenGroup(node) else onOpenCommand(node.line) }
        }
        if (rest.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Прочее", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            }
            items(rest, key = { it.name }) { node ->
                GroupTile(node) { if (node.isGroup) onOpenGroup(node) else onOpenCommand(node.line) }
            }
        }
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
    group: CommandNode,
    state: LazyListState,
    onUp: () -> Unit,
    onOpenGroup: (CommandNode) -> Unit,
    onOpenCommand: (String) -> Unit,
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
        LazyColumn(Modifier.fillMaxSize(), state = state) {
            items(children, key = { it.line }) { node ->
                if (node.isGroup) {
                    GroupRow(node) { onOpenGroup(node) }
                } else {
                    CommandRow(node, label = node.name) { onOpenCommand(node.line) }
                }
            }
        }
    }
}

@Composable
private fun SearchResults(root: CommandNode, query: String, state: LazyListState, onOpen: (String) -> Unit) {
    val found = remember(root, query) { CommandTree.search(root, query).filter { it.line !in HIDDEN } }
    if (found.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
            Text("Ничего не найдено", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), state = state) {
        items(found, key = { it.line }) { node ->
            CommandRow(node, label = node.line) { onOpen(node.line) }
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
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider()
    }
}

@Composable
private fun CommandRow(node: CommandNode, label: String, onOpen: () -> Unit) {
    val nameColor = if (node.available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = nameColor)
                if (node.help.isNotBlank()) {
                    Text(node.help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider()
    }
}
