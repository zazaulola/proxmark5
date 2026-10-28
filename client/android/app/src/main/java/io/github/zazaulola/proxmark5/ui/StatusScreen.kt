package io.github.zazaulola.proxmark5.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zazaulola.proxmark5.parse.Activities
import io.github.zazaulola.proxmark5.parse.ActivityKind
import io.github.zazaulola.proxmark5.parse.Ansi

/** What the device is doing right now: idle, a command with its progress, a sniffer, an emulation. */
@Composable
fun StatusScreen(vm: AppViewModel) {
    val activity by vm.activity.collectAsStateWithLifecycle()
    val conn by vm.connection.collectAsStateWithLifecycle()
    val lines by vm.terminal.collectAsStateWithLifecycle()
    val now by rememberNow(activity.running)

    val lastLine = lines.lastOrNull { it.isNotBlank() }?.let { Ansi.strip(it).trim() }.orEmpty()
    val percent = Activities.percent(lastLine)
    val color = activityColorOnSurface(activity.kind)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(color))
                    Text(kindTitle(activity.kind), style = MaterialTheme.typography.headlineSmall, color = color)
                }

                if (activity.running) {
                    Text(
                        activity.command,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        "Идёт ${Activities.elapsed(now - activity.startedAtMs)}" +
                            if (activity.queued > 0) ", в очереди: ${activity.queued}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (percent != null) {
                        LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                    } else if (activity.kind == ActivityKind.COMMAND) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    if (lastLine.isNotEmpty()) {
                        Text(
                            lastLine,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 2,
                        )
                    }
                    Button(onClick = { vm.stop() }) {
                        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Остановить")
                    }
                } else {
                    Text(
                        kindHint(conn),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Text(
            "Команды запускаются в живую сессию и не блокируют приложение: долгие (sniff, sim, hardnested) просто идут, " +
                "их вывод виден в терминале сверху, а остановить можно здесь или кнопкой в его полосе.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
    }
}

private fun kindTitle(kind: ActivityKind): String = when (kind) {
    ActivityKind.IDLE -> "Ожидание (idle)"
    ActivityKind.COMMAND -> "Выполняется команда"
    ActivityKind.SNIFFER -> "Sniffer слушает эфир"
    ActivityKind.EMULATION -> "Идёт эмуляция"
}

private fun kindHint(conn: Connection): String = when (conn) {
    is Connection.Connected -> "Устройство подключено и ждёт команд."
    Connection.Connecting -> "Подключаюсь к устройству…"
    Connection.Flashing -> "Устройство занято прошивкой."
    Connection.Left -> "Вы вышли из сессии. Начать новую можно на вкладке Connect."
    is Connection.Lost -> "Связь потеряна: сессия закрыта."
    else -> "Устройство не подключено: команды клиента, не требующие железа, всё равно работают."
}
