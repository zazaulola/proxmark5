package io.github.zazaulola.proxmark5.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zazaulola.proxmark5.BuildConfig
import io.github.zazaulola.proxmark5.firmware.BuildRequest
import io.github.zazaulola.proxmark5.firmware.BuildStatus
import io.github.zazaulola.proxmark5.firmware.BundledFirmware
import io.github.zazaulola.proxmark5.firmware.FirmwareChoice
import io.github.zazaulola.proxmark5.firmware.FirmwareSettings
import io.github.zazaulola.proxmark5.firmware.FirmwareSource
import io.github.zazaulola.proxmark5.firmware.FirmwareUi
import io.github.zazaulola.proxmark5.parse.BuildOptions
import io.github.zazaulola.proxmark5.parse.FirmwareNaming
import io.github.zazaulola.proxmark5.parse.FirmwareRelease
import io.github.zazaulola.proxmark5.parse.FirmwareVariant

@Composable
fun FirmwareScreen(vm: AppViewModel) {
    val fw = vm.firmware
    val ui by fw.ui.collectAsStateWithLifecycle()
    val settings by fw.settings.collectAsStateWithLifecycle()
    val flash by vm.flash.collectAsStateWithLifecycle()
    val conn by vm.connection.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val clientReady by vm.clientReady.collectAsStateWithLifecycle()
    val terminal by vm.terminal.collectAsStateWithLifecycle()

    // what the confirmation dialog is about
    var toFlash by remember { mutableStateOf<FirmwareChoice?>(null) }
    var toDfu by remember { mutableStateOf<FirmwareChoice?>(null) }

    LaunchedEffect(Unit) {
        if (ui.releases.isEmpty() && !ui.loading) fw.refresh()
    }

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) toFlash = FirmwareChoice.Local(uris)
    }
    val pickRecovery = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) toDfu = FirmwareChoice.Local(listOf(uri))
    }

    val inDfu = conn is Connection.Dfu
    val canFlash = !flash.active && !busy
    val clientArmSrc = if (clientReady) vm.clientArmSrc else ""

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        VersionsCard(conn, if (clientReady) vm.clientVersion else "", clientArmSrc)

        if (flash !is FlashState.Idle) {
            FlashCard(flash, terminal.takeLast(4), onDismiss = vm::dismissFlash)
        }

        if (inDfu) {
            DfuCard(
                bundled = fw.bundled,
                enabled = canFlash,
                onBundled = { b -> toDfu = FirmwareChoice.Bundled(b) },
                onFile = { pickRecovery.launch(arrayOf("*/*")) },
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Готовые прошивки", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (ui.loading) {
                CircularProgressIndicator(Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = { fw.refresh() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Обновить список")
                }
            }
        }

        fw.bundled?.let { b ->
            BundledCard(b, clientArmSrc, enabled = canFlash) { toFlash = FirmwareChoice.Bundled(b) }
        }

        ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        ui.releases.forEach { rel ->
            ReleaseCard(
                rel = rel,
                clientArmSrc = clientArmSrc,
                enabled = canFlash,
                inDfu = inDfu,
                onFlash = { v -> toFlash = FirmwareChoice.Release(rel, v) },
                onDfu = { v -> toDfu = FirmwareChoice.Release(rel, v) },
            )
        }
        if (!ui.loading && ui.releases.isEmpty() && ui.error == null) {
            Hint("В ${settings.repo} пока нет собранных прошивок. Соберите нужную версию ниже.")
        }

        OutlinedButton(onClick = { pickImages.launch(arrayOf("*/*")) }, enabled = canFlash) {
            Text("Прошить .elf с телефона…")
        }

        Spacer(Modifier.height(4.dp))
        Text("Собрать прошивку", style = MaterialTheme.typography.titleMedium)
        BuildCard(
            ui = ui,
            settings = settings,
            onBuild = fw::startBuild,
            onFlashBuilt = { tag, variant -> fw.findChoice(tag, variant)?.let { toFlash = it } },
            onClear = fw::clearBuild,
            canFlash = canFlash,
        )
        SettingsCard(settings, onSave = fw::saveSettings)
    }

    toFlash?.let { choice ->
        FlashDialog(
            choice = choice,
            clientArmSrc = clientArmSrc,
            onDismiss = { toFlash = null },
            onConfirm = { opts ->
                toFlash = null
                vm.flashFirmware(choice, opts)
            },
        )
    }
    toDfu?.let { choice ->
        AlertDialog(
            onDismissRequest = { toDfu = null },
            title = { Text("Записать через DFU?") },
            text = {
                Text(
                    "${choice.title}: полный образ (загрузчик и прошивка) будет записан с адреса 0x08000000 " +
                        "через загрузчик микроконтроллера. Держите кнопку нажатой и не отключайте кабель до конца."
                )
            },
            confirmButton = {
                Button(onClick = {
                    toDfu = null
                    vm.dfuRecover(choice)
                }) { Text("Записать") }
            },
            dismissButton = { TextButton(onClick = { toDfu = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun VersionsCard(conn: Connection, clientVersion: String, clientArmSrc: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val device = when (conn) {
                is Connection.Connected -> conn.version?.os ?: "читаю версию…"
                is Connection.Dfu -> "режим DFU (загрузчик микроконтроллера)"
                Connection.Flashing -> "идёт прошивка"
                else -> "не подключено"
            }
            Field("На устройстве", device)
            Field("Клиент приложения", clientVersion.ifBlank { "—" })
            Field("ARM-исходники клиента", clientArmSrc.ifBlank { "—" })
            if (conn is Connection.Connected && conn.version?.firmwareMismatch == true) {
                Text(
                    "Прошивка устройства не совпадает с клиентом: прошейте совместимую (отмечена ✓).",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun FlashCard(state: FlashState, tail: List<String>, onDismiss: () -> Unit) {
    val (container, onContainer) = when (state) {
        is FlashState.Failed -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    }
    Card(colors = CardDefaults.cardColors(containerColor = container, contentColor = onContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                is FlashState.Working -> {
                    Text(state.title, style = MaterialTheme.typography.titleMedium)
                    Text(state.detail)
                    val pct = state.percent
                    if (pct != null) {
                        LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
                        Text("$pct%", style = MaterialTheme.typography.bodySmall)
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    tail.forEach { AnsiText(it, fontSize = 11.sp, modifier = Modifier.fillMaxWidth()) }
                }

                is FlashState.Done -> {
                    Text("Готово", style = MaterialTheme.typography.titleMedium)
                    Text(state.message)
                    TextButton(onClick = onDismiss) { Text("OK") }
                }

                is FlashState.Failed -> {
                    Text("Ошибка прошивки", style = MaterialTheme.typography.titleMedium)
                    Text(state.message)
                    TextButton(onClick = onDismiss) { Text("Закрыть") }
                }

                FlashState.Idle -> {}
            }
        }
    }
}

@Composable
private fun DfuCard(bundled: BundledFirmware?, enabled: Boolean, onBundled: (BundledFirmware) -> Unit, onFile: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0x33FFD166))) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Восстановление через DFU", style = MaterialTheme.typography.titleMedium)
            Text(
                "Proxmark5 в режиме загрузчика микроконтроллера (AT32 DFU). Сюда записывается полный образ " +
                    "recovery.bin — так оживляют устройство со стёртым или испорченным загрузчиком.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (bundled?.hasRecovery == true) {
                    Button(onClick = { onBundled(bundled) }, enabled = enabled) { Text("Встроенный образ") }
                }
                OutlinedButton(onClick = onFile, enabled = enabled) { Text("Файл .bin…") }
            }
            Hint("Образ из релиза — кнопка «DFU» в карточке релиза ниже.")
        }
    }
}

@Composable
private fun BundledCard(b: BundledFirmware, clientArmSrc: String, enabled: Boolean, onFlash: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Встроенная в приложение", style = MaterialTheme.typography.titleSmall)
            if (b.version.isNotBlank()) Mono(b.version)
            Compat(b.armsrc.ifBlank { null }, clientArmSrc)
            Hint("Собрана вместе с этим приложением, работает без интернета.")
            Button(onClick = onFlash, enabled = enabled) { Text("Прошить") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReleaseCard(
    rel: FirmwareRelease,
    clientArmSrc: String,
    enabled: Boolean,
    inDfu: Boolean,
    onFlash: (FirmwareVariant) -> Unit,
    onDfu: (FirmwareVariant) -> Unit,
) {
    var selected by remember(rel.tag) { mutableStateOf(rel.variants.first().name) }
    val variant = rel.variant(selected) ?: rel.variants.first()
    val uri = LocalUriHandler.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(rel.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (rel.prerelease) {
                    Text("dev", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
            val info = rel.info
            val from = if (info != null) "${info.repo} @ ${info.ref.take(12)}" else rel.tag
            Text("$from · ${rel.publishedAt.take(10)}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Compat(info?.armsrc, clientArmSrc)
            if (rel.variants.size > 1) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rel.variants.forEach { v ->
                        FilterChip(selected = v.name == variant.name, onClick = { selected = v.name }, label = { Text(v.name) })
                    }
                }
            } else {
                Mono(variant.name)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onFlash(variant) }, enabled = enabled) { Text("Прошить") }
                if (inDfu && variant.recovery != null) {
                    OutlinedButton(onClick = { onDfu(variant) }, enabled = enabled) { Text("DFU") }
                }
                if (rel.htmlUrl.isNotBlank()) {
                    TextButton(onClick = { uri.openUri(rel.htmlUrl) }) { Text("Релиз на GitHub") }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BuildCard(
    ui: FirmwareUi,
    settings: FirmwareSettings,
    onBuild: (BuildRequest) -> Unit,
    onFlashBuilt: (String, String) -> Unit,
    onClear: () -> Unit,
    canFlash: Boolean,
) {
    var source by rememberSaveable { mutableStateOf(FirmwareSource.Upstream) }
    // "" = the newest upstream release
    var upstreamRef by rememberSaveable { mutableStateOf("") }
    var forkRef by rememberSaveable { mutableStateOf("master") }
    var standalone by rememberSaveable { mutableStateOf("") }
    var extras by remember { mutableStateOf(emptySet<String>()) }
    var skip by remember { mutableStateOf(emptySet<String>()) }

    val upstreamChoices = ui.upstream.map { it.tag } + MASTER
    val upRef = upstreamRef.ifEmpty { upstreamChoices.first() }
    val options = BuildOptions(extras = extras, standalone = standalone, skip = skip)
    val running = ui.build is BuildStatus.Running
    val uri = LocalUriHandler.current

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Hint(
                "На телефоне прошивку не собрать: её собирает GitHub Actions в ${settings.repo} " +
                    "и публикует релизом, обычно за 5–10 минут. Готовая сборка появится в списке выше."
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FirmwareSource.entries.forEach { s ->
                    FilterChip(selected = source == s, onClick = { source = s }, label = { Text(s.label) })
                }
            }
            if (source == FirmwareSource.Upstream) {
                Picker(
                    label = "Версия RfidResearchGroup/proxmark3",
                    value = upRef,
                    items = upstreamChoices.map { it to if (it == MASTER) "master (последний коммит)" else it },
                    onPick = { upstreamRef = it },
                )
                Hint("PM5 поддерживается с v4.${FirmwareNaming.FIRST_PM5_RELEASE}; более старые версии под него не собираются.")
            } else {
                OutlinedTextField(
                    value = forkRef,
                    onValueChange = { forkRef = it.trim() },
                    label = { Text("Ветка, тег или коммит ${settings.repo}") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Picker(
                label = "Standalone-режим",
                value = standalone,
                items = listOf(
                    "" to "по умолчанию (${FirmwareNaming.DEFAULT_STANDALONE})",
                    FirmwareNaming.NO_STANDALONE to "без standalone-режима",
                ) + STANDALONE_MODES.map { it to it },
                onPick = { standalone = it },
            )

            Text("Опции платформы PM5", style = MaterialTheme.typography.labelLarge)
            FirmwareNaming.PM5_EXTRAS.forEach { e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = e in extras, onCheckedChange = { on -> extras = if (on) extras + e else extras - e })
                    Text(EXTRA_LABELS[e] ?: e, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Text("Исключить модули (по умолчанию в прошивке всё)", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FirmwareNaming.SKIPPABLE.forEach { m ->
                    FilterChip(
                        selected = m in skip,
                        onClick = { skip = if (m in skip) skip - m else skip + m },
                        label = { Text(m) },
                    )
                }
            }

            Text("Сборка: ${FirmwareNaming.variant(options)}", fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall)
            if (!settings.hasToken) {
                Hint("Для запуска сборки нужен GitHub-токен (настройки ниже). Уже собранную версию приложение найдёт и без него.")
            }
            Button(
                onClick = {
                    val req = if (source == FirmwareSource.Upstream) {
                        BuildRequest(source, upRef, isTag = upRef != MASTER, options = options)
                    } else {
                        BuildRequest(source, forkRef.ifBlank { "master" }, isTag = false, options = options)
                    }
                    onBuild(req)
                },
                enabled = !running,
            ) { Text("Собрать и опубликовать") }

            when (val b = ui.build) {
                null -> {}
                is BuildStatus.Running -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(b.note)
                    if (b.tag.isNotEmpty()) Mono("${b.tag} / ${b.variant}")
                    b.run?.let { r -> TextButton(onClick = { uri.openUri(r.htmlUrl) }) { Text("Открыть сборку на GitHub") } }
                }

                is BuildStatus.Ready -> {
                    Text(
                        if (b.alreadyBuilt) "Эта сборка уже есть: ${b.tag} / ${b.variant}"
                        else "Собрано: ${b.tag} / ${b.variant}",
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onFlashBuilt(b.tag, b.variant) }, enabled = canFlash) { Text("Прошить её") }
                        TextButton(onClick = onClear) { Text("Скрыть") }
                    }
                }

                is BuildStatus.Failed -> {
                    Text(b.message, color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        b.url?.let { u -> TextButton(onClick = { uri.openUri(u) }) { Text("Журнал сборки") } }
                        TextButton(onClick = onClear) { Text("Скрыть") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(settings: FirmwareSettings, onSave: (String, String, String?) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    var repo by remember(settings) { mutableStateOf(settings.repo) }
    var branch by remember(settings) { mutableStateOf(settings.branch) }
    var token by remember(settings) { mutableStateOf("") }
    val uri = LocalUriHandler.current

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Настройки GitHub", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${settings.repo} · ${settings.branch} · " + if (settings.hasToken) "токен сохранён" else "без токена",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { open = !open }) { Text(if (open) "Свернуть" else "Изменить") }
            }
            if (open) {
                OutlinedTextField(repo, { repo = it }, label = { Text("Репозиторий с workflow (owner/name)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(branch, { branch = it }, label = { Text("Ветка workflow") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    token, { token = it },
                    label = { Text(if (settings.hasToken) "Новый токен (пусто — оставить)" else "GitHub-токен") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint(
                    "Fine-grained токен только на этот репозиторий, права: Actions — Read and write, " +
                        "Contents — Read. Хранится только на телефоне, в резервные копии не попадает."
                )
                FlowRowButtons(
                    onCreate = { uri.openUri("https://github.com/settings/personal-access-tokens/new") },
                    onSave = {
                        onSave(repo, branch, token.ifBlank { null })
                        open = false
                    },
                    onForget = if (settings.hasToken) ({ onSave(repo, branch, ""); open = false }) else null,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowButtons(onCreate: () -> Unit, onSave: () -> Unit, onForget: (() -> Unit)?) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onSave) { Text("Сохранить") }
        OutlinedButton(onClick = onCreate) { Text("Создать токен") }
        if (onForget != null) TextButton(onClick = onForget) { Text("Удалить токен") }
    }
}

@Composable
private fun FlashDialog(
    choice: FirmwareChoice,
    clientArmSrc: String,
    onDismiss: () -> Unit,
    onConfirm: (FlashOptions) -> Unit,
) {
    val hasBootrom = when (choice) {
        is FirmwareChoice.Bundled -> choice.fw.hasBootrom
        is FirmwareChoice.Release -> choice.variant.bootrom != null
        is FirmwareChoice.Local -> true
    }
    val matches = choice.armsrc != null && choice.armsrc == clientArmSrc
    var withBootrom by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Прошить Proxmark5?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(choice.title, fontWeight = FontWeight.SemiBold)
                if (!matches) {
                    Text(
                        if (choice.armsrc == null) "Неизвестно, из каких исходников собрана эта прошивка. "
                        else "Эта прошивка собрана из других исходников, чем клиент приложения. " +
                            "Приложение может работать с ней не полностью; полностью совместима встроенная прошивка.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (hasBootrom) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = withBootrom, onCheckedChange = { withBootrom = it })
                        Text(
                            if (choice is FirmwareChoice.Local) "Среди файлов есть загрузчик (bootrom)"
                            else "Обновить и загрузчик (bootrom)",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Hint("Загрузчик стоит обновлять, если флешер предупреждает, что он старый. Испорченный загрузчик чинится через DFU.")
                }
                Hint(
                    "Не отключайте кабель. Устройство перезагрузится в загрузчик и обратно, " +
                        "Android при этом спросит доступ к USB — разрешите."
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(FlashOptions(includeBootrom = withBootrom, force = !matches)) }) {
                Text("Прошить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun Picker(label: String, value: String, items: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(items.firstOrNull { it.first == value }?.second ?: value.ifEmpty { "—" })
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                items.forEach { (key, text) ->
                    DropdownMenuItem(text = { Text(text) }, onClick = {
                        onPick(key)
                        open = false
                    })
                }
            }
        }
    }
}

@Composable
private fun Compat(armsrc: String?, clientArmSrc: String) {
    val (text, color) = when {
        armsrc == null -> "совместимость с клиентом неизвестна" to MaterialTheme.colorScheme.onSurfaceVariant
        armsrc == clientArmSrc -> "✓ совпадает с клиентом приложения" to MaterialTheme.colorScheme.primary
        else -> "другие исходники, чем у клиента приложения" to MaterialTheme.colorScheme.tertiary
    }
    Text(text, color = color, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun Field(key: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(key, Modifier.weight(0.42f), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(0.58f), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun Mono(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private const val MASTER = "master"

private val STANDALONE_MODES: List<String> =
    BuildConfig.STANDALONE_MODES.split(',').filter { it.isNotBlank() }

private val EXTRA_LABELS = mapOf(
    "BWM" to "BWM — модуль батареи и беспроводной связи",
    "NO_LOWBATT_BEEP" to "Без сигнала при разряде батареи",
    "NO_LOWBATT_SHUTDOWN" to "Без отключения при разряде батареи",
)
