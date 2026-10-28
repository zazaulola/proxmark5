package io.github.zazaulola.proxmark5.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.zazaulola.proxmark5.core.Activity
import io.github.zazaulola.proxmark5.core.ClientController
import io.github.zazaulola.proxmark5.core.CommandPump
import io.github.zazaulola.proxmark5.core.LibraryRepository
import io.github.zazaulola.proxmark5.core.NativeClient
import io.github.zazaulola.proxmark5.firmware.FirmwareChoice
import io.github.zazaulola.proxmark5.firmware.FirmwareController
import io.github.zazaulola.proxmark5.parse.Ansi
import io.github.zazaulola.proxmark5.parse.Band
import io.github.zazaulola.proxmark5.parse.CardSearch
import io.github.zazaulola.proxmark5.parse.CommandNode
import io.github.zazaulola.proxmark5.parse.FlashLog
import io.github.zazaulola.proxmark5.parse.HwVersion
import io.github.zazaulola.proxmark5.parse.SearchResult
import io.github.zazaulola.proxmark5.usb.Pm3Ids
import io.github.zazaulola.proxmark5.usb.UsbSession
import io.github.zazaulola.proxmark5.usb.UsbTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

sealed interface Connection {
    data object NoDevice : Connection
    data class NeedPermission(val device: UsbDevice) : Connection
    data object Connecting : Connection
    data class Connected(val version: HwVersion?) : Connection
    data class Dfu(val device: UsbDevice) : Connection
    data class Failed(val reason: String) : Connection

    /** The session was cut off (cable pulled, device reset); plugging it in again starts a new one. */
    data class Lost(val reason: String) : Connection

    /** The user left the session by hand: nothing reconnects until they ask or plug the cable in again. */
    data object Left : Connection

    /** The flasher owns the device: no auto-connect until it is done. */
    data object Flashing : Connection
}

/** Progress of a firmware write, shown on the firmware screen. */
sealed interface FlashState {
    data object Idle : FlashState
    data class Working(val title: String, val detail: String, val percent: Int? = null) : FlashState
    data class Done(val message: String) : FlashState
    data class Failed(val message: String) : FlashState

    val active get() = this is Working
}

/** How to flash: [includeBootrom] also rewrites the bootloader, [force] skips the version check. */
data class FlashOptions(val includeBootrom: Boolean = false, val force: Boolean = false)

private class FlashError(message: String) : Exception(message)

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

    /** Feeds the one living client session: commands queue up here and never block the UI. */
    private val pump = CommandPump(viewModelScope, client)

    /** What the session is doing: idle, a command with its progress, a sniffer or an emulation. */
    val activity: StateFlow<Activity> = pump.activity

    // connect/disconnect, which run outside the pump
    private val guarded = MutableStateFlow(false)

    /** True while a command runs or the device is being connected. */
    val busy: StateFlow<Boolean> = combine(pump.activity, guarded) { a, g -> a.running || g }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _commandTree = MutableStateFlow<CommandNode?>(null)

    /** The client's commands, for the Functions section. Null until the client has started. */
    val commandTree: StateFlow<CommandNode?> = _commandTree.asStateFlow()
    private var treeJob: Job? = null

    /** The saved dumps, keys and logs. */
    val library = LibraryRepository(File(client.dumpDir), File(client.logsDir))

    /** True once the client has started and [clientVersion] / [clientArmSrc] are filled in. */
    val clientReady: StateFlow<Boolean> get() = client.ready

    val clientVersion get() = client.clientVersion

    /** ARM source hash of the embedded client: a firmware with the same one matches it. */
    val clientArmSrc get() = client.clientArmSrc

    /** Firmware catalog, builds on GitHub Actions and downloads. */
    val firmware = FirmwareController(app, viewModelScope)

    private val _flash = MutableStateFlow<FlashState>(FlashState.Idle)
    val flash: StateFlow<FlashState> = _flash.asStateFlow()

    @Volatile
    private var flashing = false
    private var flashJob: Job? = null

    /** Where saved dumps land. */
    val dumpDir get() = client.dumpDir

    /** Where saved terminal logs land. */
    val logsDir get() = client.logsDir

    // the cable pulled out from under a live session
    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            if (device != null) onDeviceDetached(device)
        }
    }

    init {
        viewModelScope.launch { client.ensureStarted() }
        ContextCompat.registerReceiver(
            app, detachReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        startTerminalTicker()
        // which commands exist depends on the device, so look again when it comes and goes
        viewModelScope.launch {
            connection.map { it is Connection.Connected }.distinctUntilChanged().collect { refreshCommandTree() }
        }
        refreshDevice()
    }

    override fun onCleared() {
        runCatching { getApplication<Application>().unregisterReceiver(detachReceiver) }
        // The client outlives this ViewModel (it is one per process) and viewModelScope is already
        // cancelled, so end the session here: stop what runs, then close the client's side and the
        // USB link, in that order. A new ViewModel then starts from a clean, disconnected client.
        if (!flashing) {
            val s = session
            session = null
            client.abort()
            CoroutineScope(Dispatchers.Default).launch {
                runCatching { client.disconnect() }
                s?.close()
            }
        }
        super.onCleared()
    }

    private fun refreshCommandTree() {
        treeJob?.cancel()
        treeJob = viewModelScope.launch {
            val tree = runCatching { client.commandTree() }.getOrNull()
            if (tree != null) _commandTree.value = tree
        }
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
        if (flashing) return
        val cur = _connection.value
        // after leaving by hand only an explicit ask (or a replug) starts a new session, not a resume
        if (cur is Connection.Connected || cur is Connection.Connecting || cur is Connection.Left) return
        val device = usb.findProxmark()
        _connection.value = when {
            // keep saying the link was lost until a device shows up again
            device == null -> cur.takeIf { it is Connection.Lost } ?: Connection.NoDevice
            !usb.hasPermission(device) -> Connection.NeedPermission(device)
            usb.isDfuDevice(device) -> Connection.Dfu(device)
            else -> cur.takeIf { it is Connection.Connected } ?: Connection.NoDevice
        }
        if (device != null && usb.hasPermission(device) && !usb.isDfuDevice(device)) {
            connect(device)
        }
    }

    /** The "look for the device" buttons: an explicit ask also ends the "left by hand" state. */
    fun searchDevice() {
        if (_connection.value is Connection.Left) _connection.value = Connection.NoDevice
        refreshDevice()
    }

    fun onDeviceAttached(device: UsbDevice) {
        if (flashing) return
        if (_connection.value is Connection.Left) _connection.value = Connection.NoDevice
        if (usb.hasPermission(device)) refreshDevice() else requestPermission(device)
    }

    fun requestPermission(device: UsbDevice) = viewModelScope.launch {
        if (_connection.value is Connection.Left) _connection.value = Connection.NoDevice
        val granted = usb.requestPermission(device)
        if (flashing) return@launch
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
        client.note("сессия открыта: ${s.port}")
        _connection.value = Connection.Connected(null)
        loadVersion()
    }

    private suspend fun loadVersion() {
        val res = pump.submit("hw version", grab = true).await()
        if (res.code == -1 && res.grabbed.isEmpty()) return
        // the session may have ended while the command waited its turn
        if (_connection.value is Connection.Connected) {
            _connection.value = Connection.Connected(HwVersion.parse(res.grabbed))
        }
    }

    fun refreshVersion() = viewModelScope.launch {
        if (_connection.value is Connection.Connected) loadVersion()
    }

    /** Leaves the session by hand: ends what runs, drops what waits, closes the link. */
    fun disconnect() = viewModelScope.launch {
        client.note("выход из сессии")
        pump.stop()
        runGuarded { client.disconnect() }
        session?.close()
        session = null
        _connection.value = Connection.Left
    }

    // The cable was pulled while a session was open. End what runs, close the client's side,
    // and say so; plugging the device back in connects again by itself.
    private fun onDeviceDetached(device: UsbDevice) {
        if (flashing) return
        val s = session ?: return
        if (s.device.deviceName != device.deviceName) return
        viewModelScope.launch {
            client.note("связь потеряна: устройство отключено")
            pump.stop()
            _connection.value = Connection.Lost("Устройство отключено. Подключите его снова, чтобы начать новую сессию.")
            runGuarded { client.disconnect() }
            s.close()
            if (session === s) session = null
        }
    }

    /** Runs `hf search` then `lf search`, parsing each into the card-search panel. */
    fun detectCard() = viewModelScope.launch {
        if (_read.value.running) return@launch
        _read.value = ReadState(running = true)
        val stops = pump.stopCount
        val hf = pump.submit("hf search", grab = true).await()
        // Stop, leaving the session or a pulled cable cut the search short: no result, no next command
        if (pump.stopCount != stops) {
            _read.value = ReadState()
            return@launch
        }
        _read.value = _read.value.copy(hf = CardSearch.parse(hf.grabbed, Band.HF))
        val lf = pump.submit("lf search", grab = true).await()
        if (pump.stopCount != stops) {
            _read.value = ReadState(hf = _read.value.hf)
            return@launch
        }
        _read.value = ReadState(running = false, hf = _read.value.hf, lf = CardSearch.parse(lf.grabbed, Band.LF))
    }

    /** Sends a command line to the living session; its output streams to the terminal. */
    fun runCommand(cmd: String) {
        val line = cmd.trim()
        if (line.isEmpty()) return
        pump.submit(line)
    }

    /**
     * Runs [cmd] and returns its text output without ANSI codes (it also streams to the terminal),
     * or null when a stop cut it short, so "stopped" is not mistaken for "printed nothing".
     */
    suspend fun query(cmd: String): String? {
        val stops = pump.stopCount
        val text = pump.submit(cmd, grab = true).await().grabbed
        return if (pump.stopCount != stops) null else text
    }

    /** Ends the running command and drops everything waiting behind it, like pressing Enter in the console. */
    fun stop() = pump.stop()

    fun clearTerminal() {
        client.terminal.clear()
        _terminal.value = emptyList()
    }

    /** The whole terminal as plain text. */
    fun terminalText(): String = client.terminal.snapshot().joinToString("\n") { Ansi.strip(it) }

    /** Saves the terminal as a log file the Library lists. Null when the folder isn't writable. */
    fun saveLog(): File? = runCatching { library.saveLog(terminalText()) }.getOrNull()

    /**
     * Downloads [choice] and writes it with the bootloader flasher. A usb: port is one USB
     * enumeration, so this follows the device through its reboots: the firmware drops it
     * into the bootloader (a new USB device, so a new permission prompt), the flasher writes
     * there, and the device then boots the new firmware (one more prompt to reconnect).
     */
    fun flashFirmware(choice: FirmwareChoice, opts: FlashOptions) {
        if (flashJob?.isActive == true) return
        flashJob = viewModelScope.launch {
            try {
                _flash.value = FlashState.Working("Подготовка", "Готовлю файлы…")
                val files = firmware.fetchImages(choice, opts.includeBootrom) { msg, pct ->
                    _flash.value = FlashState.Working("Загрузка", msg, pct)
                }
                writeWithBootloader(files.map { it.absolutePath }, opts)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _flash.value = FlashState.Failed(e.message ?: e.toString())
            } finally {
                endFlashing()
            }
        }
    }

    /** Writes recovery.bin through the PM5 AT32 ROM DFU loader (device in DFU mode). */
    fun dfuRecover(choice: FirmwareChoice) {
        if (flashJob?.isActive == true) return
        flashJob = viewModelScope.launch {
            try {
                _flash.value = FlashState.Working("Подготовка", "Готовлю образ…")
                val image = firmware.fetchRecovery(choice) { msg, pct ->
                    _flash.value = FlashState.Working("Загрузка", msg, pct)
                }
                writeWithDfu(image.absolutePath)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _flash.value = FlashState.Failed(e.message ?: e.toString())
            } finally {
                endFlashing()
            }
        }
    }

    fun dismissFlash() {
        if (!_flash.value.active) _flash.value = FlashState.Idle
    }

    private suspend fun takeDeviceForFlashing() {
        flashing = true
        _connection.value = Connection.Flashing
        // the flasher opens the port itself
        runCatching { client.disconnect() }
        session?.close()
        session = null
    }

    private suspend fun writeWithBootloader(files: List<String>, opts: FlashOptions) {
        takeDeviceForFlashing()
        var used: String? = null
        // 1st pass: the firmware reboots into the bootloader; 2nd pass: the bootloader writes
        repeat(3) {
            val device = awaitDevice(used, if (used == null) 5_000 else 30_000)
                ?: throw FlashError(
                    if (used == null) "Proxmark не найден, подключите его"
                    else "Устройство не появилось в режиме загрузчика. Отключите и подключите его снова, затем повторите"
                )
            if (Pm3Ids.isDfu(device)) {
                throw FlashError("Устройство в режиме DFU (загрузчик МК). Используйте «Восстановление через DFU»")
            }
            val s = openForFlashing(device)
            _flash.value = FlashState.Working("Прошивка", "Связь с устройством…")
            val ticker = viewModelScope.launch {
                while (true) {
                    val st = FlashLog.status(client.terminal.snapshot())
                    if (st.file != null) {
                        _flash.value = FlashState.Working("Прошивка", "Запись ${st.file}… Не отключайте кабель", st.percent)
                    }
                    delay(250)
                }
            }
            val code = try {
                client.flash(s.port, files, opts.includeBootrom, opts.force)
            } finally {
                ticker.cancel()
                s.close()
            }
            when (code) {
                0 -> {
                    _flash.value = FlashState.Working("Перезагрузка", "Готово, устройство запускает новую прошивку…")
                    reconnectAfter(device.deviceName)
                    _flash.value = FlashState.Done("Прошивка записана")
                    return
                }

                NativeClient.FLASH_REOPEN_PORT -> {
                    used = device.deviceName
                    _flash.value = FlashState.Working(
                        "Загрузчик",
                        "Устройство перезагружается в загрузчик. Разрешите доступ к USB, когда Android спросит",
                    )
                }

                -1 -> throw FlashError("Клиент занят другой командой, повторите")
                else -> throw FlashError("Прошивка не удалась (код $code), подробности в терминале (панель сверху)")
            }
        }
        throw FlashError("Устройство не перешло в режим загрузчика")
    }

    private suspend fun writeWithDfu(image: String) {
        takeDeviceForFlashing()
        val device = usb.findProxmark()?.takeIf { Pm3Ids.isDfu(it) }
            ?: throw FlashError("Устройство не в режиме DFU: зажмите кнопку и подключите кабель")
        val s = openForFlashing(device)
        val ticker = viewModelScope.launch {
            while (true) {
                FlashLog.dfuStatus(client.terminal.snapshot())?.let { (phase, pct) ->
                    val what = when (phase) {
                        "Erasing" -> "Стирание"
                        "Writing" -> "Запись"
                        else -> "Проверка"
                    }
                    _flash.value = FlashState.Working("DFU", "$what… Держите кнопку и не отключайте кабель", pct)
                }
                delay(250)
            }
        }
        val code = try {
            client.dfuFlash(s.port, image)
        } finally {
            ticker.cancel()
            s.close()
        }
        if (code != 0) throw FlashError("Запись через DFU не удалась (код $code), подробности в терминале (панель сверху)")
        _flash.value = FlashState.Done("Образ записан. Отпустите кнопку, отключите и снова подключите Proxmark5")
    }

    private suspend fun openForFlashing(device: UsbDevice): UsbSession {
        if (!usb.hasPermission(device)) {
            _flash.value = FlashState.Working("Доступ к USB", "Разрешите приложению доступ к устройству")
            usb.requestPermission(device) ?: throw FlashError("Нет доступа к USB-устройству")
        }
        return usb.open(device) ?: throw FlashError("Не удалось открыть USB-устройство")
    }

    /** Waits for a Proxmark that is not the enumeration [used] (its device node changes on reboot). */
    private suspend fun awaitDevice(used: String?, timeoutMs: Long): UsbDevice? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            usb.findProxmark()?.takeIf { it.deviceName != used }?.let { return it }
            delay(300)
        }
        return null
    }

    /** After a write the device boots the new firmware as a new USB device: ask for it again. */
    private suspend fun reconnectAfter(used: String) {
        val device = awaitDevice(used, 15_000) ?: return
        if (!usb.hasPermission(device)) {
            _flash.value = FlashState.Working("Переподключение", "Разрешите доступ к USB, чтобы подключиться к новой прошивке")
            usb.requestPermission(device)
        }
    }

    private fun endFlashing() {
        if (!flashing) return
        flashing = false
        _connection.value = Connection.NoDevice
        refreshDevice()
    }

    private suspend fun <T> runGuarded(block: suspend () -> T): T? {
        guarded.value = true
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            guarded.value = false
        }
    }
}

private fun UsbTransport.isDfuDevice(device: UsbDevice) =
    io.github.zazaulola.proxmark5.usb.Pm3Ids.isDfu(device)
