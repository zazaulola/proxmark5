package io.github.zazaulola.proxmark5.ui.params

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.zazaulola.proxmark5.parse.params.CommandDraft
import io.github.zazaulola.proxmark5.parse.params.ParamSpec
import kotlinx.coroutines.launch

/**
 * The parameters a command takes, from its help, to add one: name, what it takes, what it does,
 * and whether it is required, may repeat or is already there. A flag is added at once; for a
 * parameter with a value the sheet closes and its editor opens ([onPick] decides).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParamPicker(command: String, specs: List<ParamSpec>, draft: CommandDraft, onPick: (ParamSpec) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(specs, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) specs else specs.filter { s ->
            s.names.any { q in it.lowercase() } || q in s.description.lowercase() || (s.placeholder?.lowercase()?.contains(q) == true)
        }
    }

    fun pick(spec: ParamSpec) {
        // close the sheet first: a dialog over a sheet that is still going away flickers
        scope.launch { sheet.hide() }.invokeOnCompletion { onPick(spec) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                "Параметры $command",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (specs.size > 6) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    singleLine = true,
                    placeholder = { Text("Найти: имя или слово из описания") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                )
            }
            if (shown.isEmpty()) {
                Text(
                    "Ничего не нашлось",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn {
                // no keys: two positionals of one command may share a name
                items(shown) { spec ->
                    SpecRow(spec, present = draft.has(spec)) { pick(spec) }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SpecRow(spec: ParamSpec, present: Boolean, onClick: () -> Unit) {
    // a parameter that is there and may not repeat opens its tag instead
    val dim = present && !spec.repeatable
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = if (dim) "Изменить" else "Добавить", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                buildString {
                    append(spec.label)
                    if (spec.short != null && spec.long != null) append(", ").append(spec.short)
                    if (!spec.positional && !spec.isFlag) append(" <").append(spec.placeholder ?: "?").append(">")
                },
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyLarge,
                color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Badge(typeText(spec.type))
        }
        if (spec.description.isNotBlank()) {
            Text(spec.description, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        val marks = listOfNotNull(
            "обязательный".takeIf { spec.required },
            "можно несколько".takeIf { spec.repeatable },
            "уже есть".takeIf { present },
        )
        if (marks.isNotEmpty()) {
            Text(
                marks.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = if (spec.required && !present) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Badge(text: String) {
    Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
