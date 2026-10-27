package io.github.zazaulola.proxmark5.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

private enum class Tab(val label: String, val icon: ImageVector) {
    Device("Устройство", Icons.Filled.Memory),
    Read("Чтение", Icons.Filled.Nfc),
    Terminal("Терминал", Icons.Filled.Terminal),
}

@Composable
fun Pm3App(vm: AppViewModel) {
    Pm3Theme {
        Surface(modifier = Modifier.fillMaxSize()) {
            var tab by remember { mutableStateOf(Tab.Device) }
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = tab == t,
                                onClick = { tab = t },
                                icon = { Icon(t.icon, contentDescription = t.label) },
                                label = { Text(t.label) },
                            )
                        }
                    }
                }
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    when (tab) {
                        Tab.Device -> DeviceScreen(vm)
                        Tab.Read -> ReadScreen(vm)
                        Tab.Terminal -> TerminalScreen(vm)
                    }
                }
            }
        }
    }
}
