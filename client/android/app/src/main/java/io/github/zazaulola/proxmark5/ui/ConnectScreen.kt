package io.github.zazaulola.proxmark5.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zazaulola.proxmark5.parse.ActivityKind

/** How the app reaches the Proxmark, and the state of the session. */
@Composable
fun ConnectScreen(vm: AppViewModel, onOpenFirmware: () -> Unit) {
    val conn by vm.connection.collectAsStateWithLifecycle()
    val clientReady by vm.clientReady.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TransportPicker()
        StateCard(conn)

        when (val c = conn) {
            is Connection.NoDevice -> {
                InfoCard(
                    "Подключите Proxmark5",
                    "Вставьте устройство в OTG-кабель (кабель со стороны кнопки). " +
                        "Приложение откроется само, когда вы разрешите доступ к USB.",
                )
                Button(onClick = { vm.searchDevice() }) { Text("Искать устройство") }
            }

            Connection.Left -> {
                InfoCard(
                    "Вы вышли из сессии",
                    "Клиент отключён от устройства. Новая сессия начнётся, когда вы нажмёте кнопку " +
                        "или заново подключите кабель.",
                )
                Button(onClick = { vm.searchDevice() }) { Text("Подключиться") }
            }

            is Connection.NeedPermission -> {
                InfoCard("Нужно разрешение", "Разрешите приложению доступ к подключённому устройству.")
                Button(onClick = { vm.requestPermission(c.device) }) { Text("Разрешить доступ к USB") }
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
                InfoCard("Идёт прошивка", "Устройство занято прошивкой, ход виден на вкладке Firmware.")
                OutlinedButton(onClick = onOpenFirmware) { Text("Открыть Firmware") }
            }

            Connection.Connecting -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Подключение…")
                }
            }

            is Connection.Failed -> {
                InfoCard("Ошибка", c.reason)
                Button(onClick = { vm.searchDevice() }) { Text("Повторить") }
            }

            is Connection.Lost -> {
                InfoCard("Связь потеряна", c.reason)
                Button(onClick = { vm.searchDevice() }) { Text("Искать устройство") }
            }

            is Connection.Connected -> {
                InfoCard(
                    "Сессия открыта",
                    "Клиент работает, пока вы не выйдете, не оборвётся связь или он не завершится сам. " +
                        "Команды идут в него из раздела Functions и из терминала сверху, а вывод идёт в лог.",
                )
                OutlinedButton(onClick = { vm.disconnect() }) { Text("Выйти из сессии") }
                if (c.version?.firmwareMismatch == true) {
                    Button(onClick = onOpenFirmware) { Text("Обновить прошивку") }
                }
            }
        }

        if (clientReady && vm.clientVersion.isNotBlank()) {
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
private fun TransportPicker() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Транспорт", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = true,
                onClick = {},
                label = { Text("USB") },
                leadingIcon = { Icon(Icons.Filled.Usb, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
            FilterChip(
                selected = false,
                onClick = {},
                enabled = false,
                label = { Text("BT") },
                leadingIcon = { Icon(Icons.Filled.Bluetooth, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
            FilterChip(
                selected = false,
                onClick = {},
                enabled = false,
                label = { Text("BLE") },
                leadingIcon = { Icon(Icons.Filled.Bluetooth, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
        Text(
            "Работает USB через OTG. BT и BLE появятся, когда в Proxmark5 будет беспроводной модуль (в вашем его нет).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StateCard(conn: Connection) {
    val (title, color) = when (conn) {
        is Connection.Connected -> "Подключено" to MaterialTheme.colorScheme.primary
        Connection.Connecting -> "Подключение…" to MaterialTheme.colorScheme.secondary
        is Connection.Dfu -> "Режим DFU" to activityColorOnSurface(ActivityKind.SNIFFER)
        Connection.Flashing -> "Прошивка…" to MaterialTheme.colorScheme.tertiary
        is Connection.Failed -> "Ошибка" to MaterialTheme.colorScheme.error
        is Connection.Lost -> "Связь потеряна" to MaterialTheme.colorScheme.error
        Connection.Left -> "Вы вышли из сессии" to MaterialTheme.colorScheme.onSurfaceVariant
        else -> "Нет устройства" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Proxmark5", style = MaterialTheme.typography.titleMedium)
            Text(title, style = MaterialTheme.typography.headlineSmall, color = color)
        }
    }
}
