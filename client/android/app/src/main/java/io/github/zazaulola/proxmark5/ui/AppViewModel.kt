package io.github.zazaulola.proxmark5.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.zazaulola.proxmark5.core.Activity
import io.github.zazaulola.proxmark5.core.ExecResult
import io.github.zazaulola.proxmark5.core.LibraryRepository
import io.github.zazaulola.proxmark5.core.NativeClient
import io.github.zazaulola.proxmark5.firmware.FirmwareChoice
import io.github.zazaulola.proxmark5.firmware.FirmwareController
import io.github.zazaulola.proxmark5.ipc.Pm3Ipc
import io.github.zazaulola.proxmark5.parse.Ansi
import io.github.zazaulola.proxmark5.parse.Band
import io.github.zazaulola.proxmark5.parse.CardSearch
import io.github.zazaulola.proxmark5.parse.CommandNode
import io.github.zazaulola.proxmark5.parse.FlashLog
import io.github.zazaulola.proxmark5.parse.HwVersion
import io.github.zazaulola.proxmark5.parse.SearchResult
import io.github.zazaulola.proxmark5.remote.Pm3Session
import io.github.zazaulola.proxmark5.remote.SessionEvent
import io.github.zazaulola.proxmark5.session.IpcLimits
import io.github.zazaulola.proxmark5.session.LinkState
import io.github.zazaulola.proxmark5.session.ResultCode
import io.github.zazaulola.proxmark5.session.SessionSnapshot
import io.github.zazaulola.proxmark5.usb.Pm3Ids
import io.github.zazaulola.proxmark5.usb.UsbTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

/**
 * The connection the screens show: what the service says about its session, and while it has none
 * ([LinkState.IDLE]) what looking at the USB bus found ([found]).
 */
private fun connectionOf(snap: SessionSnapshot, found: Connection, reread: String?, writing: Boolean): Connection {
    if (writing) return Connection.Flashing
    // a device that waits for the user (a permission, DFU mode) is news even after a session ended badly
    val waiting = found is Connection.NeedPermission || found is Connection.Dfu
    return when (snap.link) {
        LinkState.CONNECTED -> Connection.Connected((reread ?: snap.info).takeIf { it.isNotBlank() }?.let { HwVersion.parse(it) })
        LinkState.OPENING, LinkState.CLOSING -> Connection.Connecting
        LinkState.FLASHING -> Connection.Flashing
        LinkState.LOST -> if (waiting) found else Connection.Lost(snap.linkDetail)
        LinkState.FAILED -> if (waiting) found else Connection.Failed(snap.linkDetail)
        LinkState.LEFT -> Connection.Left
        // a client that could not start leaves no session to fail: the reason is in the snapshot
        LinkState.IDLE -> if (snap.error.isNotEmpty()) Connection.Failed(snap.error) else found
    }
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    // The session lives in the service process and outlives this ViewModel: this only watches and steers it.
    private val session = Pm3Session.get(app)
    private val usb = UsbTransport(app)

    // what the last look at the USB bus found (NoDevice, NeedPermission, Dfu); shown while the service has no session
    private val discovery = MutableStateFlow<Connection>(Connection.NoDevice)

    // `hw version` read again by hand, for the session that is open now
    private val reread = MutableStateFlow<String?>(null)

    // this ViewModel's firmware chain (download, waiting for the bootloader, write) is under way
    private val flashing = MutableStateFlow(false)

    // a write was cut short by the death of the client: no auto-connect until the user acts
    private var noAutoConnect = false

    /** What the service says about the session: link, client, the running command. */
    val sessionState: StateFlow<SessionSnapshot> = session.state

    val connection: StateFlow<Connection> =
        combine(session.state, discovery, reread, flashing) { snap, found, text, writing ->
            connectionOf(snap, found, text, writing)
        }.stateIn(
            viewModelScope, SharingStarted.Eagerly,
            connectionOf(session.state.value, discovery.value, reread.value, flashing.value),
        )

    private val _read = MutableStateFlow(ReadState())
    val read: StateFlow<ReadState> = _read.asStateFlow()

    private val _terminal = MutableStateFlow<List<String>>(emptyList())
    val terminal: StateFlow<List<String>> = _terminal.asStateFlow()

    /** What the session is doing: idle, a command with its progress, a sniffer or an emulation. */
    val activity: StateFlow<Activity> = session.state.map { it.activity }
        .stateIn(viewModelScope, SharingStarted.Eagerly, session.state.value.activity)

    /** True while a command runs, the device is being connected or a firmware is being written. */
    val busy: StateFlow<Boolean> = combine(activity, connection) { a, c ->
        a.running || c is Connection.Connecting || c is Connection.Flashing
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _commandTree = MutableStateFlow<CommandNode?>(null)

    /** The client's commands, for the Functions section. Null until the client has started. */
    val commandTree: StateFlow<CommandNode?> = _commandTree.asStateFlow()
    private var treeJob: Job? = null

    /** The saved dumps, keys and logs. */
    val library = LibraryRepository(File(session.dumpDir), File(session.logsDir))

    /** True once the client has started and [clientVersion] / [clientArmSrc] are filled in. */
    val clientReady: StateFlow<Boolean> = session.state.map { it.ready }
        .stateIn(viewModelScope, SharingStarted.Eagerly, session.state.value.ready)

    val clientVersion: String get() = session.state.value.clientVersion

    /** ARM source hash of the embedded client: a firmware with the same one matches it. */
    val clientArmSrc: String get() = session.state.value.clientArmSrc

    /** Firmware catalog, builds on GitHub Actions and downloads. */
    val firmware = FirmwareController(app, viewModelScope)

    private val _flash = MutableStateFlow<FlashState>(FlashState.Idle)
    val flash: StateFlow<FlashState> = _flash.asStateFlow()

    private var flashJob: Job? = null

    /** Where saved dumps land. */
    val dumpDir: String get() = session.dumpDir

    /** Where saved terminal logs land. */
    val logsDir: String get() = session.logsDir

    // the cable pulled while no session is open: look again (an open session is the service's to end)
    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED && !flashing.value) look()
        }
    }

    init {
        session.start()
        ContextCompat.registerReceiver(
            app, detachReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        startTerminalTicker()
        // which commands exist depends on the device, so look again when it comes and goes (and once the client is up)
        viewModelScope.launch {
            combine(connection.map { it is Connection.Connected }, clientReady) { connected, ready -> connected to ready }
                .distinctUntilChanged().collect { refreshCommandTree() }
        }
        // a version read by hand belongs to the session it was read in
        viewModelScope.launch {
            session.state.map { it.link == LinkState.CONNECTED }.distinctUntilChanged().collect { reread.value = null }
        }
        // the service restarted the client on purpose (a Stop it could not do otherwise): connect again
        viewModelScope.launch {
            // SessionEvent.ServiceDied needs nothing here: the session has already published Lost
            session.events.collect { if (it is SessionEvent.ReconnectAfterRestart) reconnectAfterRestart() }
        }
        // connecting waits for MainActivity: only a visible app may start the service
        look()
    }

    override fun onCleared() {
        runCatching { getApplication<Application>().unregisterReceiver(detachReceiver) }
        // The session is the service's: it keeps running (a command, a sniffer, the link) when the
        // screen goes away, and a new ViewModel picks it up. Only Stop and leaving end it.
        super.onCleared()
    }

    private fun refreshCommandTree() {
        treeJob?.cancel()
        treeJob = viewModelScope.launch {
            val tree = runCatching { session.commandTree() }.getOrNull()
            if (tree != null) _commandTree.value = tree
        }
    }

    private fun startTerminalTicker() = viewModelScope.launch {
        var seen = -1L
        while (isActive) {
            val rev = session.terminal.revision
            if (rev != seen) {
                seen = rev
                _terminal.value = session.terminal.snapshot()
            }
            delay(120)
        }
    }

    /** Looks for a plugged-in Proxmark and reflects what it finds. */
    fun refreshDevice() {
        viewModelScope.launch {
            // On a cold start the service's state is not known yet: a session left by hand (or one that is
            // open) must not be taken for none.
            withTimeoutOrNull(3_000) { session.state.first { it.epoch != 0L || it.link != LinkState.IDLE } }
            findAndConnect(byUser = false)
        }
    }

    /** The "look for the device" buttons: an explicit ask also ends the "left by hand" state. */
    fun searchDevice() {
        findAndConnect(byUser = true)
    }

    fun onDeviceAttached(device: UsbDevice) {
        if (flashing.value) return
        if (usb.hasPermission(device)) findAndConnect(byUser = true) else requestPermission(device)
    }

    fun requestPermission(device: UsbDevice) = viewModelScope.launch {
        val granted = usb.requestPermission(device)
        if (flashing.value) return@launch
        if (granted != null) findAndConnect(byUser = true) else discovery.value = Connection.NeedPermission(device)
    }

    /**
     * Looks at the USB bus and, when no session is in the way, asks the service to connect to what it
     * finds. True when it asked. A refusal (Android said no) comes back as link FAILED in the session's state.
     */
    private fun findAndConnect(byUser: Boolean): Boolean {
        if (flashing.value) return false
        if (byUser) noAutoConnect = false
        val link = session.state.value.link
        // an open, opening or closing session and a firmware write are not touched
        if (link.holdsService) return false
        val device = look()
        // a session the user left by hand and a write that broke off are not resumed unasked
        val resume = byUser || (link != LinkState.LEFT && !noAutoConnect)
        return device != null && resume && session.begin(Pm3Ipc.KIND_CONNECT, device.deviceName)
    }

    /** Reflects what is on the USB bus; returns the device a session can be opened to right now, if any. */
    private fun look(): UsbDevice? {
        val device = usb.findProxmark()
        discovery.value = when {
            device == null -> Connection.NoDevice
            !usb.hasPermission(device) -> Connection.NeedPermission(device)
            usb.isDfuDevice(device) -> Connection.Dfu(device)
            else -> Connection.NoDevice
        }
        return device?.takeIf { usb.hasPermission(it) && !usb.isDfuDevice(it) }
    }

    private fun reconnectAfterRestart() {
        // the user left in the meantime: a restart is no reason to come back
        if (session.state.value.link == LinkState.LEFT) return
        if (!findAndConnect(byUser = false)) return
        // the note goes to the new service, which has to be there first (a call with no service is dropped)
        viewModelScope.launch {
            withTimeoutOrNull(5_000) { session.bound.first { it } }
            session.note("клиент перезапущен, подключаюсь заново")
        }
    }

    /** Reads `hw version` again; the service read it once when the session opened. */
    fun refreshVersion() = viewModelScope.launch {
        if (connection.value !is Connection.Connected) return@launch
        val stops = session.generation
        val res = submit("hw version", grab = true).await()
        // the session may have ended while the command waited its turn
        if (!cutShort(res, stops) && connection.value is Connection.Connected) reread.value = res.grabbed
    }

    /** Leaves the session by hand: the service ends what runs, drops what waits and closes the link. */
    fun disconnect() = session.disconnect()

    /** "Restart the client": ends the client's process for a command that does not stop. Not while flashing. */
    fun restartClient() = session.restartClient("по просьбе пользователя")

    /** Runs `hf search` then `lf search`, parsing each into the card-search panel. */
    fun detectCard() = viewModelScope.launch {
        if (_read.value.running) return@launch
        _read.value = ReadState(running = true)
        val stops = session.generation
        val hf = submit("hf search", grab = true).await()
        // Stop, leaving the session, a pulled cable or a dead client cut the search short: no result, no next command
        if (cutShort(hf, stops)) {
            _read.value = ReadState()
            return@launch
        }
        _read.value = _read.value.copy(hf = CardSearch.parse(hf.grabbed, Band.HF))
        val lf = submit("lf search", grab = true).await()
        if (cutShort(lf, stops)) {
            _read.value = ReadState(hf = _read.value.hf)
            return@launch
        }
        _read.value = ReadState(running = false, hf = _read.value.hf, lf = CardSearch.parse(lf.grabbed, Band.LF))
    }

    /** Sends a command line to the living session; its output streams to the terminal. */
    fun runCommand(cmd: String) {
        val line = cmd.trim()
        if (line.isEmpty()) return
        if (line.length > IpcLimits.MAX_COMMAND_CHARS) {
            session.note("команда длиннее ${IpcLimits.MAX_COMMAND_CHARS} символов, не отправлена")
            return
        }
        submit(line)
    }

    /**
     * Runs [cmd] and returns its text output without ANSI codes (it also streams to the terminal),
     * or null when a stop cut it short, so "stopped" is not mistaken for "printed nothing".
     */
    suspend fun query(cmd: String): String? {
        val stops = session.generation
        val res = submit(cmd, grab = true).await()
        return if (cutShort(res, stops)) null else res.grabbed
    }

    private fun submit(cmd: String, grab: Boolean = false): Deferred<ExecResult> {
        // without a session nothing else keeps the process up for a command that needs no device
        val snap = session.state.value
        if (!snap.link.holdsService && !snap.activity.running) session.begin(Pm3Ipc.KIND_RUN, null)
        return session.submit(cmd, grab)
    }

    private fun cutShort(res: ExecResult, stops: Int) = ResultCode.isTransport(res.code) || session.generation != stops

    /** Ends the running command and drops everything waiting behind it, like pressing Enter in the console. */
    fun stop() = session.stop()

    fun clearTerminal() {
        session.terminal.clear()
        session.clearLog()
        _terminal.value = emptyList()
    }

    /** The whole terminal as plain text. */
    fun terminalText(): String = session.terminal.snapshot().joinToString("\n") { Ansi.strip(it) }

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

    // The service owns the device from here to flashEnd. There is no disconnect first: the first
    // flash call closes the session itself, so nothing can reconnect in between.
    private fun takeDeviceForFlashing() {
        flashing.value = true
        noAutoConnect = false
        if (!session.begin(Pm3Ipc.KIND_FLASH, null)) {
            throw FlashError(session.state.value.linkDetail.ifBlank { "Не удалось запустить службу прошивки" })
        }
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
            ensurePermission(device)
            _flash.value = FlashState.Working("Прошивка", "Связь с устройством…")
            val ticker = viewModelScope.launch {
                while (true) {
                    val st = FlashLog.status(session.terminal.snapshot())
                    if (st.file != null) {
                        _flash.value = FlashState.Working("Прошивка", "Запись ${st.file}… Не отключайте кабель", st.percent)
                    }
                    delay(250)
                }
            }
            val code = try {
                session.flash(device.deviceName, files, opts.includeBootrom, opts.force)
            } finally {
                ticker.cancel()
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

                else -> failFlash(code, "Прошивка")
            }
        }
        throw FlashError("Устройство не перешло в режим загрузчика")
    }

    private suspend fun writeWithDfu(image: String) {
        takeDeviceForFlashing()
        val device = usb.findProxmark()?.takeIf { Pm3Ids.isDfu(it) }
            ?: throw FlashError("Устройство не в режиме DFU: зажмите кнопку и подключите кабель")
        ensurePermission(device)
        val ticker = viewModelScope.launch {
            while (true) {
                FlashLog.dfuStatus(session.terminal.snapshot())?.let { (phase, pct) ->
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
            session.dfuFlash(device.deviceName, image)
        } finally {
            ticker.cancel()
        }
        if (code != 0) failFlash(code, "Запись через DFU")
        _flash.value = FlashState.Done("Образ записан. Отпустите кнопку, отключите и снова подключите Proxmark5")
    }

    /** Ends a write that [code] says did not succeed. */
    private fun failFlash(code: Int, what: String): Nothing = when (code) {
        ResultCode.TRANSPORT_LOST -> {
            // the client's process died under the write: the device may be half written, so nothing connects to it unasked
            noAutoConnect = true
            throw FlashError(
                "Прошивка прервана: процесс клиента завершился. Не отключайте устройство, повторите; " +
                    "если не загружается — восстановление через DFU"
            )
        }

        ResultCode.BUSY, ResultCode.DROPPED -> throw FlashError("Клиент занят или ещё не запущен, повторите")
        else -> throw FlashError("$what не удалась (код $code), подробности в терминале (панель сверху)")
    }

    // The service opens the device itself (the permission is the app's, not the process's): only ask for it here.
    private suspend fun ensurePermission(device: UsbDevice) {
        if (!usb.hasPermission(device)) {
            _flash.value = FlashState.Working("Доступ к USB", "Разрешите приложению доступ к устройству")
            usb.requestPermission(device) ?: throw FlashError("Нет доступа к USB-устройству")
        }
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

    private suspend fun endFlashing() {
        if (!flashing.value) return
        // also when the chain was cancelled (the screen went away): the service must be told in any case
        withContext(NonCancellable) {
            session.flashEnd()
            // flashEnd is oneway: wait for the service to give the device back before looking for it
            withTimeoutOrNull(3_000) { session.state.first { it.link != LinkState.FLASHING } }
            flashing.value = false
        }
        if (viewModelScope.isActive) findAndConnect(byUser = false)
    }
}

private fun UsbTransport.isDfuDevice(device: UsbDevice) =
    io.github.zazaulola.proxmark5.usb.Pm3Ids.isDfu(device)
