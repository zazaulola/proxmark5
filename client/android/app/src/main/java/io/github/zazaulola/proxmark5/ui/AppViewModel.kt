package io.github.zazaulola.proxmark5.ui

import android.app.Application
import android.hardware.usb.UsbDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.zazaulola.proxmark5.core.ClientController
import io.github.zazaulola.proxmark5.core.ExecResult
import io.github.zazaulola.proxmark5.parse.Band
import io.github.zazaulola.proxmark5.parse.CardSearch
import io.github.zazaulola.proxmark5.parse.HwVersion
import io.github.zazaulola.proxmark5.parse.SearchResult
import io.github.zazaulola.proxmark5.usb.UsbSession
import io.github.zazaulola.proxmark5.usb.UsbTransport
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface Connection {
    data object NoDevice : Connection
    data class NeedPermission(val device: UsbDevice) : Connection
    data object Connecting : Connection
    data class Connected(val version: HwVersion?) : Connection
    data class Dfu(val device: UsbDevice) : Connection
    data class Failed(val reason: String) : Connection
}

data class ReadState(
    val running: Boolean = false,
    val hf: SearchResult? = null,
    val lf: SearchResult? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val client = ClientController.get(app)
    private val usb = UsbTransport(app)
    private var session: UsbSession? = null

    private val _connection = MutableStateFlow<Connection>(Connection.NoDevice)
    val connection: StateFlow<Connection> = _connection.asStateFlow()

    private val _read = MutableStateFlow(ReadState())
    val read: StateFlow<ReadState> = _read.asStateFlow()

    private val _terminal = MutableStateFlow<List<String>>(emptyList())
    val terminal: StateFlow<List<String>> = _terminal.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    val clientVersion get() = client.clientVersion

    /** Where saved dumps land (shown on the read screen). */
    val dumpDir get() = client.dumpDir

    init {
        viewModelScope.launch { client.ensureStarted() }
        startTerminalTicker()
        refreshDevice()
    }

    private fun startTerminalTicker() = viewModelScope.launch {
        var seen = -1L
        while (isActive) {
            val rev = client.terminal.revision
            if (rev != seen) {
                seen = rev
                _terminal.value = client.terminal.snapshot()
            }
            delay(120)
        }
    }

    /** Looks for a plugged-in Proxmark and reflects what it finds. */
    fun refreshDevice() {
        val cur = _connection.value
        if (cur is Connection.Connected || cur is Connection.Connecting) return
        val device = usb.findProxmark()
        _connection.value = when {
            device == null -> Connection.NoDevice
            !usb.hasPermission(device) -> Connection.NeedPermission(device)
            usb.isDfuDevice(device) -> Connection.Dfu(device)
            else -> cur.takeIf { it is Connection.Connected } ?: Connection.NoDevice
        }
        if (device != null && usb.hasPermission(device) && !usb.isDfuDevice(device)) {
            connect(device)
        }
    }

    fun onDeviceAttached(device: UsbDevice) {
        if (usb.hasPermission(device)) refreshDevice() else requestPermission(device)
    }

    fun requestPermission(device: UsbDevice) = viewModelScope.launch {
        val granted = usb.requestPermission(device)
        if (granted != null) refreshDevice() else _connection.value = Connection.NeedPermission(device)
    }

    private fun connect(device: UsbDevice) = viewModelScope.launch {
        _connection.value = Connection.Connecting
        val s = usb.open(device)
        if (s == null) {
            _connection.value = Connection.Failed("Не удалось открыть USB-устройство")
            return@launch
        }
        session = s
        val ok = runGuarded { client.connect(s.port) }
        if (ok != true) {
            s.close()
            session = null
            _connection.value = Connection.Failed("Устройство не отвечает")
            return@launch
        }
        _connection.value = Connection.Connected(null)
        loadVersion()
    }

    private suspend fun loadVersion() {
        val res = runGuarded { client.exec("hw version") } ?: return
        _connection.value = Connection.Connected(HwVersion.parse(res.grabbed))
    }

    fun refreshVersion() = viewModelScope.launch {
        if (_connection.value is Connection.Connected) loadVersion()
    }

    fun disconnect() = viewModelScope.launch {
        runGuarded { client.disconnect() }
        session?.close()
        session = null
        _connection.value = Connection.NoDevice
        refreshDevice()
    }

    /** Runs `hf search` then `lf search`, parsing each into the read screen. */
    fun detectCard() = viewModelScope.launch {
        if (_busy.value) return@launch
        _read.value = ReadState(running = true)
        val hf = runGuarded { client.exec("hf search") }
        _read.value = _read.value.copy(hf = hf?.let { CardSearch.parse(it.grabbed, Band.HF) })
        val lf = runGuarded { client.exec("lf search") }
        _read.value = ReadState(running = false, hf = _read.value.hf, lf = lf?.let { CardSearch.parse(it.grabbed, Band.LF) })
    }

    fun runCommand(cmd: String) = viewModelScope.launch {
        if (cmd.isBlank()) return@launch
        runGuarded { client.exec(cmd.trim(), grab = false) }
    }

    fun abort() = client.abort()

    fun clearTerminal() {
        client.terminal.clear()
        _terminal.value = emptyList()
    }

    private suspend fun <T> runGuarded(block: suspend () -> T): T? {
        _busy.value = true
        return try {
            block()
        } catch (e: Exception) {
            null
        } finally {
            _busy.value = false
        }
    }
}

private fun UsbTransport.isDfuDevice(device: UsbDevice) =
    io.github.zazaulola.proxmark5.usb.Pm3Ids.isDfu(device)
