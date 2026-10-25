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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zazaulola.proxmark5.parse.HwVersion

@Composable
fun DeviceScreen(vm: AppViewModel, onOpenFirmware: () -> Unit) {
    val conn by vm.connection.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()

    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusCard(conn)

        when (conn) {
            is Connection.NoDevice -> {
                InfoCard(
                    "Подключите Proxmark5",
                    "Вставьте устройство в OTG-кабель (кабель со стороны кнопки). " +
                        "Приложение откроется само, когда вы разрешите доступ к USB.",
                )
                Button(onClick = { vm.refreshDevice() }) { Text("Искать устройство") }
            }

            is Connection.NeedPermission -> {
                val d = (conn as Connection.NeedPermission).device
                InfoCard("Нужно разрешение", "Разрешите приложению доступ к подключённому устройству.")
                Button(onClick = { vm.requestPermission(d) }) { Text("Разрешить доступ к USB") }
            }

            is Connection.Dfu -> {
                InfoCard(
                    "Режим загрузчика (DFU)",
                    "Устройство в режиме AT32 DFU. Обычный клиент тут не подключается — " +
                        "это режим для восстановления прошивки. Запишите образ recovery.bin " +
                        "или перезагрузите Proxmark в обычный режим.",
                )
                Button(onClick = onOpenFirmware) { Text("Восстановить через DFU") }
            }

            Connection.Flashing -> {
                InfoCard("Идёт прошивка", "Устройство занято прошивкой, ход виден на вкладке «Прошивка».")
                OutlinedButton(onClick = onOpenFirmware) { Text("Открыть «Прошивку»") }
            }

            Connection.Connecting -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.height(20.dp))
                    Spacer(Modifier.height(0.dp))
                    Text("  Подключение…")
                }
            }

            is Connection.Failed -> {
                InfoCard("Ошибка", (conn as Connection.Failed).reason)
                Button(onClick = { vm.refreshDevice() }) { Text("Повторить") }
            }

            is Connection.Connected -> {
                val version = (conn as Connection.Connected).version
                VersionView(version)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.refreshVersion() }) { Text("Обновить") }
                    OutlinedButton(onClick = { vm.disconnect() }) { Text("Отключить") }
                }
                if (version?.firmwareMismatch == true) {
                    Button(onClick = onOpenFirmware) { Text("Обновить прошивку") }
                }
            }
        }

        if (vm.clientVersion.isNotBlank()) {
            Text(
                "Клиент: ${vm.clientVersion}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusCard(conn: Connection) {
    val (title, color) = when (conn) {
        is Connection.Connected -> "Подключено" to MaterialTheme.colorScheme.primary
        Connection.Connecting -> "Подключение…" to MaterialTheme.colorScheme.secondary
        is Connection.Dfu -> "Режим DFU" to Color(0xFFFFD166)
        Connection.Flashing -> "Прошивка…" to MaterialTheme.colorScheme.tertiary
        is Connection.Failed -> "Ошибка" to MaterialTheme.colorScheme.error
        else -> "Нет устройства" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Proxmark5", style = MaterialTheme.typography.titleMedium)
            Text(title, style = MaterialTheme.typography.headlineSmall, color = color)
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
                        Text(f.key, Modifier.weight(0.42f), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(f.value, Modifier.weight(0.58f), style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace)
                    }
                }
                section.notes.forEach { n ->
                    Text(n, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
