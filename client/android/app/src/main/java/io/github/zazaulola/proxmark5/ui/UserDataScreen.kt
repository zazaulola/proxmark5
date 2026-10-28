package io.github.zazaulola.proxmark5.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/** What lives on the device (its flash memory) and where the app keeps what it saved on the phone. */
@Composable
fun UserDataScreen(vm: AppViewModel) {
    val conn by vm.connection.collectAsStateWithLifecycle()
    val connected = conn is Connection.Connected
    val scope = rememberCoroutineScope()
    var info by rememberSaveable { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("На устройстве", style = MaterialTheme.typography.titleMedium)
        Text(
            "Флэш-память Proxmark: словари ключей, сохранённые данные, настройки. Читается командой mem info.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        loading = true
                        // null: Stop cut the command short, so keep what was shown before
                        vm.query("mem info")?.let { info = it.trim() }
                        loading = false
                    }
                },
                enabled = connected && !loading,
            ) { Text("Прочитать mem info") }
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        if (!connected) {
            Text(
                "Подключите Proxmark на вкладке Connect.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        info?.let { text ->
            Card(Modifier.fillMaxWidth()) {
                Text(
                    text.ifBlank { "Команда ничего не вывела: возможно, во флэш-память нечего читать или она недоступна." },
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("На телефоне", style = MaterialTheme.typography.titleMedium)
        PathRow("Дампы и ключи", vm.dumpDir)
        PathRow("Логи терминала", vm.logsDir)
        Text(
            "Файлы открываются в разделе Library, оттуда их можно отправить.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PathRow(title: String, path: String) {
    Column {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Text(
            path,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
