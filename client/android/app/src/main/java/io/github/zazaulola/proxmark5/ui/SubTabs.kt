package io.github.zazaulola.proxmark5.ui

import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The subsections of a section, as a row of tabs that scrolls sideways when they don't fit. */
@Composable
fun SubTabs(titles: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    ScrollableTabRow(selectedTabIndex = selected, modifier = modifier, edgePadding = 8.dp) {
        titles.forEachIndexed { i, title ->
            Tab(
                selected = i == selected,
                onClick = { onSelect(i) },
                text = { Text(title, maxLines = 1) },
            )
        }
    }
}
