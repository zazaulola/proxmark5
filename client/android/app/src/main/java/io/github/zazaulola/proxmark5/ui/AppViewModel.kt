package io.github.zazaulola.proxmark5.ui

import android.app.Application
import android.hardware.usb.UsbDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.zazaulola.proxmark5.core.ClientController
import io.github.zazaulola.proxmark5.core.ExecResult
import io.github.zazaulola.proxmark5.core.NativeClient
import io.github.zazaulola.proxmark5.firmware.FirmwareChoice
import io.github.zazaulola.proxmark5.firmware.FirmwareController
import io.github.zazaulola.proxmark5.parse.Band
import io.github.zazaulola.proxmark5.parse.CardSearch
import io.github.zazaulola.proxmark5.parse.FlashLog
import io.github.zazaulola.proxmark5.parse.HwVersion
import io.github.zazaulola.proxmark5.parse.SearchResult
import io.github.zazaulola.proxmark5.usb.Pm3Ids
import io.github.zazaulola.proxmark5.usb.UsbSession
import io.github.zazaulola.proxmark5.usb.UsbTransport
import kotlinx.coroutines.CancellationException
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

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

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
        if (flashing) return
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
        if (flashing) return
        if (usb.hasPermission(device)) refreshDevice() else requestPermission(device)
    }

    fun requestPermission(device: UsbDevice) = viewModelScope.launch {
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
                else -> throw FlashError("Прошивка не удалась (код $code), подробности на вкладке «Терминал»")
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
        if (code != 0) throw FlashError("Запись через DFU не удалась (код $code), подробности на вкладке «Терминал»")
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
