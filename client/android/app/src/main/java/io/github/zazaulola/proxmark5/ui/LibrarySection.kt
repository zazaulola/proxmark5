package io.github.zazaulola.proxmark5.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zazaulola.proxmark5.core.LibraryFile
import io.github.zazaulola.proxmark5.parse.Library
import io.github.zazaulola.proxmark5.parse.LibraryKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private fun title(kind: LibraryKind) = when (kind) {
    LibraryKind.KEYS -> "Keys"
    LibraryKind.DUMPS -> "Dumps"
    LibraryKind.LOGS -> "Logs"
}

private fun emptyHint(kind: LibraryKind) = when (kind) {
    LibraryKind.KEYS -> "Здесь появятся файлы ключей, которые сохраняет клиент (например, после hf mf autopwn), и словари."
    LibraryKind.DUMPS -> "Здесь появятся дампы карт, сохранённые командами клиента (hf mf dump, hf mf autopwn и другие)."
    LibraryKind.LOGS -> "Сохраняйте лог кнопкой с дискетой в панели терминала — файл появится здесь."
}

/** What was saved: keys, dumps and logs, each on its own shelf. */
@Composable
fun LibrarySection(vm: AppViewModel) {
    var kind by rememberSaveable { mutableStateOf(LibraryKind.KEYS) }

    Column(Modifier.fillMaxSize()) {
        SubTabs(LibraryKind.entries.map { title(it) }, kind.ordinal, onSelect = { kind = LibraryKind.entries[it] })
        Shelf(vm, kind, Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
private fun Shelf(vm: AppViewModel, kind: LibraryKind, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activity by vm.activity.collectAsStateWithLifecycle()
    var files by remember(kind) { mutableStateOf<List<LibraryFile>?>(null) }
    var reload by remember { mutableStateOf(0) }
    var toDelete by remember { mutableStateOf<LibraryFile?>(null) }

    // Look again when the shelf changes, on request, and whenever a command ends: it may have saved a file.
    LaunchedEffect(kind, reload, activity.running) {
        files = withContext(Dispatchers.IO) { vm.library.list(kind) }
    }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (kind == LibraryKind.LOGS) vm.logsDir else vm.dumpDir,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
            IconButton(onClick = { reload++ }) { Icon(Icons.Filled.Refresh, contentDescription = "Обновить") }
        }
        HorizontalDivider()

        val list = files
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
                Text(emptyHint(kind), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.file.path }) { f ->
                    FileRow(f, onShare = { shareFile(context, f.file) }, onDelete = { toDelete = f })
                    HorizontalDivider()
                }
            }
        }
    }

    toDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Удалить файл?") },
            text = { Text("${f.name} будет удалён с телефона без возможности восстановления.") },
            confirmButton = {
                Button(onClick = {
                    toDelete = null
                    scope.launch {
                        withContext(Dispatchers.IO) { vm.library.delete(f) }
                        reload++
                    }
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun FileRow(f: LibraryFile, onShare: () -> Unit, onDelete: () -> Unit) {
    val date = remember(f.modified) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(f.modified))
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(f.name, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, maxLines = 2)
            Text(
                "${Library.humanSize(f.size)} · $date",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onShare) { Icon(Icons.Filled.Share, contentDescription = "Отправить") }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Удалить") }
    }
}
