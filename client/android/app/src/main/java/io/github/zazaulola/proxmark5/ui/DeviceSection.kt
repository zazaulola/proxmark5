package io.github.zazaulola.proxmark5.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

private enum class DeviceTab(val label: String) {
    Connect("Connect"),
    Metadata("Metadata"),
    Firmware("Firmware"),
    UserData("User data"),
    Status("Status"),
}

/** The device itself: how it is connected, what it is, its firmware, what it stores, what it is doing. */
@Composable
fun DeviceSection(vm: AppViewModel) {
    var tab by rememberSaveable { mutableStateOf(DeviceTab.Connect) }
    val openFirmware = { tab = DeviceTab.Firmware }

    Column(Modifier.fillMaxSize()) {
        SubTabs(DeviceTab.entries.map { it.label }, tab.ordinal, onSelect = { tab = DeviceTab.entries[it] })
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (tab) {
                DeviceTab.Connect -> ConnectScreen(vm, onOpenFirmware = openFirmware)
                DeviceTab.Metadata -> MetadataScreen(vm, onOpenFirmware = openFirmware)
                DeviceTab.Firmware -> FirmwareScreen(vm)
                DeviceTab.UserData -> UserDataScreen(vm)
                DeviceTab.Status -> StatusScreen(vm)
            }
        }
    }
}
