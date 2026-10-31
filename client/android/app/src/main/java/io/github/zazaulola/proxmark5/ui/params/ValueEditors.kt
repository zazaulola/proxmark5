package io.github.zazaulola.proxmark5.ui.params

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.zazaulola.proxmark5.parse.Library
import io.github.zazaulola.proxmark5.parse.LibraryKind
import io.github.zazaulola.proxmark5.parse.params.FileRole
import io.github.zazaulola.proxmark5.parse.params.ParamSpec
import io.github.zazaulola.proxmark5.parse.params.Problem
import io.github.zazaulola.proxmark5.parse.params.Severity
import io.github.zazaulola.proxmark5.parse.params.TagText
import io.github.zazaulola.proxmark5.parse.params.ValueType
import io.github.zazaulola.proxmark5.parse.params.Values
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.math.BigInteger

/**
 * Where file values come from: the folder the client saves dumps into (the Library lists it)
 * and the key dictionaries shipped with the client.
 */
class FilePlaces(val dumps: File, val dictionaries: File)

/** A few words on what a parameter takes, for the editor and the list of parameters. */
internal fun typeText(type: ValueType): String = when (type) {
    ValueType.Flag -> "флаг, без значения"
    is ValueType.Number -> buildString {
        append(if (type.fraction) "число" else "целое число")
        rangeText(type)?.let { append(", ").append(it) }
        type.unit?.let { append(", ").append(it) }
    }
    is ValueType.Hex -> when {
        type.asInteger -> "число в hex"
        else -> type.exact?.let { "hex, ${it.describe()} байт" } ?: "hex"
    }
    ValueType.Bits -> "биты, 0 и 1"
    is ValueType.File -> when (type.role) {
        FileRole.SAVE -> "файл для записи"
        FileRole.DICTIONARY -> "словарь ключей"
        FileRole.LOAD, FileRole.ANY -> "файл"
    }
    is ValueType.Choice -> "один из ${type.values.size}"
    is ValueType.Text -> if (type.digitsOnly) "цифры" else "текст"
}

private fun rangeText(t: ValueType.Number): String? = when {
    t.min != null && t.max != null -> "${t.min}…${t.max}"
    t.min != null -> "от ${t.min}"
    t.max != null -> "до ${t.max}"
    else -> null
}

/**
 * The editor of one parameter's value, picked by its type: a number with dec/hex and ±, hex
 * bytes grouped as they are typed, 0/1 bits, a choice, a file from the dumps, keys or
 * dictionaries (or brought in from the phone), free text. A flag only shows what it does.
 * [spec] null: a raw piece of the line the help does not describe, edited as text.
 *
 * Done is refused only when the value can not be put on the command line at all, or is empty;
 * what the help merely suggests (a length, a range) is a warning, because the help is sometimes
 * wrong and the client is the judge. A tap outside does not close it: typed hex is not lost.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ValueEditorDialog(
    command: String,
    spec: ParamSpec?,
    initial: String,
    isNew: Boolean,
    places: FilePlaces,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
    onEarlier: (() -> Unit)? = null,
    onLater: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    val type = spec?.type
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(startText(type, initial), TextRange(startText(type, initial).length)))
    }
    // a number typed in hex; hex typed as plain text (for the few <hex> that take something else)
    var numberInHex by rememberSaveable { mutableStateOf(false) }
    var hexAsText by rememberSaveable { mutableStateOf(type is ValueType.Hex && !looksHex(initial)) }
    val focus = remember { FocusRequester() }

    fun value(): String {
        val t = field.text.trim()
        return when {
            t.isEmpty() -> ""
            type is ValueType.Number && numberInHex -> "0x$t"
            // a bare name to save under: into the dumps folder, where the Library sees it
            type is ValueType.File && type.role == FileRole.SAVE && '/' !in t && !t.startsWith("~") -> File(places.dumps, t).path
            else -> t
        }
    }

    val v = value()
    val problem: Problem? = when {
        spec == null -> Values.representable(v)
        spec.isFlag -> null
        else -> Values.check(spec, v, command)
    }
    val canDone = when {
        spec == null -> problem?.severity != Severity.ERROR
        spec.isFlag -> true
        else -> v.isNotEmpty() && problem?.severity != Severity.ERROR
    }
    val done = { if (canDone) onDone(v) }
    val takesText = type != null && type != ValueType.Flag && type !is ValueType.Choice

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = {
            Column {
                Text(spec?.label ?: field.text.ifBlank { "параметр" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (spec == null) "не описан в справке, уйдёт как есть" else typeText(spec.type),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // the keyboard comes up with the dialog; here, where the field is composed
                if (takesText || spec == null) {
                    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                }
                if (spec != null) {
                    if (spec.description.isNotBlank()) Text(spec.description, style = MaterialTheme.typography.bodyMedium)
                    val names = spec.names
                    if (names.size > 1) {
                        Text("Имена: " + names.joinToString(", "), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                    if (spec.required) Text("Обязательный", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }

                when (type) {
                    null -> ValueField(
                        value = field,
                        onValueChange = { field = it },
                        focus = focus,
                        onDone = done,
                        keyboard = KeyboardType.Ascii,
                        supporting = "Одно слово; с пробелом уйдёт в кавычках. Пусто — убрать.",
                    )
                    ValueType.Flag -> Text(
                        "Флаг включает то, что описано выше, сам по себе: значения у него нет.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    is ValueType.Number -> NumberEditor(type, field, { field = it }, numberInHex, { hex ->
                        field = convertNumber(field.text, toHex = hex).let { TextFieldValue(it, TextRange(it.length)) }
                        numberInHex = hex
                    }, focus, done)
                    is ValueType.Hex -> HexEditor(type, field, { field = it }, hexAsText, { asText ->
                        if (!asText) field = filtered(field, TagText::cleanHex)
                        hexAsText = asText
                    }, focus, done)
                    ValueType.Bits -> {
                        ValueField(
                            value = field,
                            onValueChange = { field = filtered(it) { s -> s.filter { c -> c == '0' || c == '1' } } },
                            focus = focus,
                            onDone = done,
                            keyboard = KeyboardType.Number,
                            supporting = "${field.text.length} бит",
                        )
                    }
                    is ValueType.Choice -> ChoiceEditor(type, field.text) { field = TextFieldValue(it, TextRange(it.length)) }
                    is ValueType.File -> FileEditor(type, field, { field = it }, places, focus, done)
                    is ValueType.Text -> {
                        ValueField(
                            value = field,
                            onValueChange = { field = it },
                            focus = focus,
                            onDone = done,
                            keyboard = if (type.digitsOnly) KeyboardType.Number else KeyboardType.Ascii,
                            placeholder = type.hint,
                        )
                        if (type.suggestions.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (s in type.suggestions) {
                                    AssistChip(onClick = { field = TextFieldValue(s, TextRange(s.length)) }, label = { Text(s, fontFamily = FontFamily.Monospace) })
                                }
                            }
                        }
                    }
                }

                if (problem != null && (v.isNotEmpty() || !isNew)) {
                    Text(
                        problem.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (problem.severity == Severity.ERROR) MaterialTheme.colorScheme.error else warningColor(),
                    )
                }

                if (onEarlier != null || onLater != null || onRemove != null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (onEarlier != null) TextButton(onClick = onEarlier) { Text("← Раньше") }
                        if (onLater != null) TextButton(onClick = onLater) { Text("Позже →") }
                        if (onRemove != null) {
                            TextButton(onClick = onRemove) { Text("Убрать", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = done, enabled = canDone) { Text(if (isNew) "Добавить" else "Готово") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** What the field shows first: hex without its separators, a choice as given. */
private fun startText(type: ValueType?, initial: String): String = when (type) {
    is ValueType.Hex -> if (looksHex(initial)) TagText.cleanHex(initial) else initial
    // `0x1F` from an example or a typed line: the field is decimal
    is ValueType.Number -> Values.numberToDecimal(initial.trim()) ?: initial
    else -> initial
}

/** Hex digits with only the separators people write between bytes: the hex field can take it. */
private fun looksHex(s: String): Boolean = s.replace(Regex("""[\s:\-]"""), "").removePrefix("0x").removePrefix("0X").all {
    it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F'
}

/** [tfv] with its text passed through [keep]; the cursor stays after what it was after. */
private fun filtered(tfv: TextFieldValue, keep: (String) -> String): TextFieldValue {
    val text = keep(tfv.text)
    val cursor = keep(tfv.text.take(tfv.selection.end.coerceIn(0, tfv.text.length))).length.coerceAtMost(text.length)
    return TextFieldValue(text, TextRange(cursor))
}

private fun convertNumber(text: String, toHex: Boolean): String {
    val t = text.trim()
    return if (toHex) {
        t.toBigIntegerOrNull()?.takeIf { it.signum() >= 0 }?.toString(16)?.uppercase() ?: ""
    } else {
        t.toBigIntegerOrNull(16)?.toString() ?: ""
    }
}

@Composable
private fun ValueField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    focus: FocusRequester,
    onDone: () -> Unit,
    keyboard: KeyboardType,
    modifier: Modifier = Modifier.fillMaxWidth(),
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    prefix: String? = null,
    suffix: String? = null,
    placeholder: String? = null,
    supporting: String? = null,
    visual: VisualTransformation = VisualTransformation.None,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.focusRequester(focus),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        prefix = prefix?.let { { Text(it, fontFamily = FontFamily.Monospace) } },
        suffix = suffix?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it, fontFamily = FontFamily.Monospace) } },
        supportingText = supporting?.let { { Text(it) } },
        visualTransformation = visual,
        // ASCII keys (the hex field also turns Cyrillic А В С Е into Latin), no autocorrect
        keyboardOptions = KeyboardOptions(
            capitalization = capitalization,
            autoCorrectEnabled = false,
            keyboardType = keyboard,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NumberEditor(
    type: ValueType.Number,
    field: TextFieldValue,
    onField: (TextFieldValue) -> Unit,
    inHex: Boolean,
    onInHex: (Boolean) -> Unit,
    focus: FocusRequester,
    onDone: () -> Unit,
) {
    val integer = !type.fraction
    fun step(delta: Long) {
        val cur = if (inHex) field.text.trim().toBigIntegerOrNull(16) else field.text.trim().toBigIntegerOrNull()
        var n = if (cur == null) BigInteger.valueOf(type.min ?: 0) else cur + BigInteger.valueOf(delta)
        type.min?.let { if (n < BigInteger.valueOf(it)) n = BigInteger.valueOf(it) }
        type.max?.let { if (n > BigInteger.valueOf(it)) n = BigInteger.valueOf(it) }
        if (type.min == null && n.signum() < 0) n = BigInteger.ZERO
        val s = if (inHex) n.toString(16).uppercase() else n.toString()
        onField(TextFieldValue(s, TextRange(s.length)))
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (integer) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = !inHex, onClick = { if (inHex) onInHex(false) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("десятичное") }
                SegmentedButton(selected = inHex, onClick = { if (!inHex) onInHex(true) }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("hex") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (integer) IconButton(onClick = { step(-1) }) { Icon(Icons.Filled.Remove, contentDescription = "Меньше на 1") }
            val negative = (type.min ?: 0) < 0
            ValueField(
                value = field,
                onValueChange = { tfv ->
                    onField(
                        if (inHex) {
                            filtered(tfv, TagText::cleanHex)
                        } else {
                            filtered(tfv) { s -> s.filter { it.isDigit() || it == '-' || (type.fraction && (it == '.' || it == ',')) } }
                        },
                    )
                },
                focus = focus,
                onDone = onDone,
                modifier = Modifier.weight(1f),
                keyboard = when {
                    inHex -> KeyboardType.Ascii
                    type.fraction -> KeyboardType.Decimal
                    // not every number keyboard has a minus
                    negative -> KeyboardType.Ascii
                    else -> KeyboardType.Number
                },
                capitalization = if (inHex) KeyboardCapitalization.Characters else KeyboardCapitalization.None,
                prefix = if (inHex) "0x" else null,
                suffix = type.unit,
            )
            if (integer) IconButton(onClick = { step(1) }) { Icon(Icons.Filled.Add, contentDescription = "Больше на 1") }
        }
        val notes = listOfNotNull(
            rangeText(type)?.let { "допустимо $it" },
            if (inHex) field.text.trim().toBigIntegerOrNull(16)?.let { "= $it" } else null,
        )
        if (notes.isNotEmpty()) {
            Text(notes.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HexEditor(
    type: ValueType.Hex,
    field: TextFieldValue,
    onField: (TextFieldValue) -> Unit,
    asText: Boolean,
    onAsText: (Boolean) -> Unit,
    focus: FocusRequester,
    onDone: () -> Unit,
) {
    val digits = field.text.length
    val info = when {
        asText -> "Как текст: уйдёт ровно так, как набрано."
        type.asInteger -> null
        else -> buildString {
            append(digits / 2).append(" байт")
            if (digits % 2 != 0) append(" и ещё полбайта")
            type.exact?.let { append(" · нужно ").append(it.describe()) }
                ?: type.hinted?.let { append(" · по описанию ").append(it.describe()) }
        }
    }
    ValueField(
        value = field,
        onValueChange = { if (asText) onField(it) else onField(filtered(it, TagText::cleanHex)) },
        focus = focus,
        onDone = onDone,
        keyboard = KeyboardType.Ascii,
        capitalization = if (asText) KeyboardCapitalization.None else KeyboardCapitalization.Characters,
        prefix = if (type.asInteger && !asText) "0x" else null,
        supporting = info,
        visual = if (asText || type.asInteger) VisualTransformation.None else ByteGroups,
    )
    TextButton(onClick = { onAsText(!asText) }) { Text(if (asText) "Вводить как hex" else "Вводить как текст") }
}

/** "A0A1A2" shown as "A0 A1 A2"; the value itself keeps no spaces. */
private object ByteGroups : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val n = text.length
        if (n <= 2) return TransformedText(text, OffsetMapping.Identity)
        val spaces = (n - 1) / 2
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = offset + minOf(offset / 2, spaces)
            override fun transformedToOriginal(offset: Int): Int = (offset - offset / 3).coerceIn(0, n)
        }
        return TransformedText(AnnotatedString(TagText.groupBytes(text.text)), mapping)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceEditor(type: ValueType.Choice, selected: String, onSelect: (String) -> Unit) {
    val values = type.values
    val compact = values.size <= 4 && values.all { it.length <= 8 }
    if (compact) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            values.forEachIndexed { i, v ->
                SegmentedButton(
                    selected = v.equals(selected, ignoreCase = true),
                    onClick = { onSelect(v) },
                    shape = SegmentedButtonDefaults.itemShape(i, values.size),
                ) { Text(v, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        type.default?.let {
            Text("По умолчанию: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        Column(Modifier.selectableGroup()) {
            for (v in values) {
                val on = v.equals(selected, ignoreCase = true)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = on, onClick = { onSelect(v) }, role = Role.RadioButton)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = on, onClick = null)
                    Text(v, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(start = 8.dp))
                    if (v == type.default) {
                        Text("  по умолчанию", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private enum class Shelf(val title: String) { DUMPS("Дампы"), KEYS("Ключи"), DICTIONARIES("Словари") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileEditor(
    type: ValueType.File,
    field: TextFieldValue,
    onField: (TextFieldValue) -> Unit,
    places: FilePlaces,
    focus: FocusRequester,
    onDone: () -> Unit,
) {
    val saving = type.role == FileRole.SAVE
    ValueField(
        value = field,
        onValueChange = onField,
        focus = focus,
        onDone = onDone,
        keyboard = KeyboardType.Uri,
        placeholder = if (saving) "имя файла" else "имя или путь",
    )
    if (saving) {
        Text(
            "Имя без пути: файл ляжет в папку дампов, её видно в Библиотеке. Расширение клиент обычно добавит сам.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var shelf by rememberSaveable { mutableStateOf(if (type.role == FileRole.DICTIONARY) Shelf.DICTIONARIES else Shelf.DUMPS) }
    var reload by remember { mutableIntStateOf(0) }
    var importNote by remember { mutableStateOf<String?>(null) }
    val files by produceState<List<File>?>(null, shelf, reload) {
        value = null
        value = withContext(Dispatchers.IO) { listShelf(places, shelf) }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                importNote = "Копирую…"
                val r = withContext(Dispatchers.IO) { runCatching { importFile(context, uri, places.dumps) } }
                r.onSuccess { f ->
                    importNote = "Скопирован в папку дампов: ${f.name}"
                    onField(TextFieldValue(f.path, TextRange(f.path.length)))
                    shelf = if (Library.classify(f.name) == LibraryKind.KEYS) Shelf.KEYS else Shelf.DUMPS
                    reload++
                }.onFailure { importNote = "Не удалось взять файл: ${it.message ?: it.javaClass.simpleName}" }
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (s in Shelf.entries) {
            FilterChip(selected = shelf == s, onClick = { shelf = s }, label = { Text(s.title) })
        }
    }
    when (val list = files) {
        null -> CircularProgressIndicator(Modifier.size(24.dp).padding(4.dp))
        else -> if (list.isEmpty()) {
            Text("Здесь пусто", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                for (f in list.take(MAX_LISTED)) {
                    val chosen = field.text == f.path
                    Text(
                        f.name,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onField(TextFieldValue(f.path, TextRange(f.path.length))) }
                            .padding(vertical = 8.dp),
                    )
                }
                if (list.size > MAX_LISTED) {
                    Text("и ещё ${list.size - MAX_LISTED}: наберите имя", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }) { Text("Взять с телефона…") }
    importNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

private const val MAX_LISTED = 200

/** A file brought in from the phone is copied; a dump is small, a bigger file is surely a wrong pick. */
private const val MAX_IMPORT_BYTES = 32L * 1024 * 1024

private fun listShelf(places: FilePlaces, shelf: Shelf): List<File> = when (shelf) {
    Shelf.DICTIONARIES -> places.dictionaries.listFiles()
        ?.filter { it.isFile && !it.name.startsWith(".") }
        ?.sortedBy { it.name.lowercase() }
        .orEmpty()
    Shelf.DUMPS, Shelf.KEYS -> {
        val kind = if (shelf == Shelf.KEYS) LibraryKind.KEYS else LibraryKind.DUMPS
        places.dumps.listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") && Library.classify(it.name) == kind }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }
}

/**
 * Copies [uri] into [dir] under its own name made safe for a command line (no spaces or
 * quotes), with -1, -2... added when that name is taken, and returns the copy.
 */
private fun importFile(context: Context, uri: Uri, dir: File): File {
    val shown = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
    val name = safeName(shown ?: "import.bin")
    if (!dir.isDirectory && !dir.mkdirs()) throw IOException("нет папки ${dir.path}")
    val dot = name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var target = File(dir, name)
    var i = 1
    while (target.exists()) target = File(dir, "$base-${i++}$ext")
    val input = context.contentResolver.openInputStream(uri) ?: throw IOException("файл не открылся")
    try {
        input.use { src ->
            target.outputStream().use { dst ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_IMPORT_BYTES) throw IOException("больше ${MAX_IMPORT_BYTES / 1024 / 1024} МБ — это не дамп")
                    dst.write(buf, 0, n)
                }
            }
        }
    } catch (e: Exception) {
        target.delete()
        throw e
    }
    return target
}

private fun safeName(name: String): String =
    name.map { if (it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') it else '_' }
        .joinToString("")
        .trim('.')
        .take(100)
        .ifEmpty { "import.bin" }
