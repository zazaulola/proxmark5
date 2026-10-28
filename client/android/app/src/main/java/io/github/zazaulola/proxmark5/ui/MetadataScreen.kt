package io.github.zazaulola.proxmark5.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zazaulola.proxmark5.parse.HwVersion

/** What the device and the client in the app say about themselves (`hw version`). */
@Composable
fun MetadataScreen(vm: AppViewModel, onOpenFirmware: () -> Unit) {
    val conn by vm.connection.collectAsStateWithLifecycle()
    // the client's version is filled in once it has started: watch that to redraw
    val clientReady by vm.clientReady.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (val c = conn) {
            is Connection.Connected -> {
                VersionView(c.version)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.refreshVersion() }) { Text("Обновить") }
                    if (c.version?.firmwareMismatch == true) {
                        Button(onClick = onOpenFirmware) { Text("Обновить прошивку") }
                    }
                }
            }

            else -> InfoCard(
                "Нет данных об устройстве",
                "Метаданные читаются командой hw version у подключённого Proxmark. " +
                    "Подключитесь на вкладке Connect.",
            )
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Клиент в приложении", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
                Text(
                    (if (clientReady) vm.clientVersion else "").ifBlank { "запускается…" },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                if (clientReady && vm.clientArmSrc.isNotBlank()) {
                    Text(
                        "armsrc: ${vm.clientArmSrc}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun VersionView(version: HwVersion?) {
    if (version == null) {
        Text("Читаю hw version…")
        return
    }
    if (version.firmwareMismatch) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text(
                "Прошивка на устройстве не совпадает с версией клиента. " +
                    "Стоит перепрошить совместимой версией.",
                Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
    for (section in version.sections) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(section.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
                section.fields.forEach { f ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            f.key,
                            Modifier.weight(0.42f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            f.value,
                            Modifier.weight(0.58f),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                section.notes.forEach { n ->
                    Text(n, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
