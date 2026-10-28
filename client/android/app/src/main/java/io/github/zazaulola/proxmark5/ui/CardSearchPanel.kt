package io.github.zazaulola.proxmark5.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zazaulola.proxmark5.parse.Band
import io.github.zazaulola.proxmark5.parse.CardHit
import io.github.zazaulola.proxmark5.parse.SearchResult

/**
 * Quick action on top of Functions: find out what card is on the antenna (`hf search`, then
 * `lf search`) and show it as cards instead of console text. The commands run in the living
 * session like any other, so their output is in the terminal too.
 */
@Composable
fun CardSearchPanel(vm: AppViewModel) {
    val conn by vm.connection.collectAsStateWithLifecycle()
    val read by vm.read.collectAsStateWithLifecycle()
    val connected = conn is Connection.Connected

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = { vm.detectCard() },
            enabled = connected && !read.running,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (read.running) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(10.dp))
                Text("Ищу карту…")
            } else {
                Text("Определить карту")
            }
        }
        Text(
            if (connected) {
                "Распознаёт карту (hf search и lf search): тип и UID. Ничего не сохраняет — " +
                    "дамп снимают командами ниже, файл появится в Library."
            } else {
                "Подключите Proxmark5 на вкладке Device → Connect."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        read.hf?.let { BandResult("13.56 МГц (HF)", it) }
        read.lf?.let { BandResult("125 кГц (LF)", it) }

        if (!read.running && read.hf != null && read.lf != null &&
            read.hf?.found != true && read.lf?.found != true
        ) {
            Text("Карта не обнаружена. Поднесите карту к антенне и попробуйте ещё раз.")
        }
    }
}

@Composable
private fun BandResult(title: String, result: SearchResult) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    if (!result.found) {
        result.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        if (result.errors.isEmpty()) {
            Text("Ничего не найдено", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    result.hits.forEach { HitCard(it) }
}

@Composable
private fun HitCard(hit: CardHit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(hit.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            hit.id?.let {
                Spacer(Modifier.height(4.dp))
                val label = if (hit.band == Band.HF) "UID" else "ID"
                Text(
                    "$label: $it",
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (hit.types.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("Тип: " + hit.types.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
            }
            hit.fields.forEach { f ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Text(
                        f.key,
                        Modifier.weight(0.42f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        f.value,
                        Modifier.weight(0.58f),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}
