package io.github.zazaulola.proxmark5.ui

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/** The three sections of the app. */
private enum class Section(val label: String, val icon: ImageVector) {
    Device("Device", Icons.Filled.Memory),
    Functions("Functions", Icons.Filled.Dashboard),
    Library("Library", Icons.Filled.Folder),
}

/**
 * The shell: the section below, the terminal on top of every section (a strip that the slider
 * opens to any part of the screen), and the three sections in the bottom bar.
 */
@Composable
fun Pm3App(vm: AppViewModel) {
    Pm3Theme {
        Surface(modifier = Modifier.fillMaxSize()) {
            var section by rememberSaveable { mutableStateOf(Section.Device) }
            // each section keeps its own place (subsection, scroll, search) while another is shown
            val holder: SaveableStateHolder = rememberSaveableStateHolder()

            Scaffold(
                bottomBar = {
                    NavigationBar {
                        Section.entries.forEach { s ->
                            NavigationBarItem(
                                selected = section == s,
                                onClick = { section = s },
                                icon = { Icon(s.icon, contentDescription = s.label) },
                                label = { Text(s.label) },
                            )
                        }
                    }
                }
            ) { padding ->
                SplitShell(vm, Modifier.padding(padding).consumeWindowInsets(padding).imePadding()) {
                    holder.SaveableStateProvider(section.name) {
                        when (section) {
                            Section.Device -> DeviceSection(vm)
                            Section.Functions -> FunctionsSection(vm)
                            Section.Library -> LibrarySection(vm)
                        }
                    }
                }
            }
        }
    }
}
