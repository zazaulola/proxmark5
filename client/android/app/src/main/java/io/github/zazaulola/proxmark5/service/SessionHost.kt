package io.github.zazaulola.proxmark5.service

import android.content.Context
import android.os.Process
import android.util.Log
import io.github.zazaulola.proxmark5.core.NativeClient
import io.github.zazaulola.proxmark5.ipc.IPm3Listener
import io.github.zazaulola.proxmark5.ipc.Pm3Ipc
import io.github.zazaulola.proxmark5.session.ExitMarker
import io.github.zazaulola.proxmark5.session.IpcLimits
import io.github.zazaulola.proxmark5.session.LinkState
import io.github.zazaulola.proxmark5.session.ResultCode
import io.github.zazaulola.proxmark5.session.SessionIds
import io.github.zazaulola.proxmark5.session.SessionSnapshot
import io.github.zazaulola.proxmark5.session.StopPolicy
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The live session of process :pm3, and the only owner of its state. One per process ([get]): the
 * service shell comes and goes, the session does not (a second service instance must not run
 * nativeInit a second time, and closing the service must not close the session).
 *
 * Threads (design 3.1). Everything that changes the state (the link, the labels on the running
 * command, the flash lease, the pending connect) runs on the "pm3-host" thread, one event at a time,
 * and ends with a new immutable [SessionSnapshot] if anything changed. Binder threads only copy their
 * arguments and queue an event; the few things they do themselves (queue a command, cut the queue for
 * Stop) take a lock for a few instructions. JNI is the business of [ClientEngine], the USB device of
 * [UsbLink], the callbacks to the UI of [Notifier], Enter and BREAK of [ControlThread].
 */
internal class SessionHost private constructor(private val app: Context) {
    /** The service shell. Told, on the host thread, that the snapshot or the need for the foreground changed; it must only post. */
    interface Shell {
        fun changed()
    }

    /** Closing the session: where it ends up, what to tell, and whether the running command gets Enter and BREAK (a lost link: Enter only). */
    private class Teardown(val finalState: LinkState, val detail: String, val gentle: Boolean)

    private val epoch = Random.nextLong() ushr 1
    private val pid = Process.myPid()

    private val host = ScheduledThreadPoolExecutor(1, serviceThreads("pm3-host"))
    private val log = LogFile(app, epoch)
    private val usb = UsbLink(app)
    private val wakeLock = WakeLockHolder(app)
    private val notifier = Notifier({ owner -> ownerGone(owner) }, { post { onListenersGone() } })
    private val engine = ClientEngine(app, log, usb, notifier, changed = { requestSettle() }, idle = { clientIdle() })
    private val control by lazy { ControlThread(engine) { breakAllowed } }

    // ---- state: the host thread writes, the rest reads what is marked @Volatile ----

    @Volatile
    private var link = LinkState.IDLE
        set(value) {
            field = value
            // commands are turned away while the session closes or the flasher owns the device
            engine.refuse(value == LinkState.CLOSING || value == LinkState.FLASHING)
        }
    private var linkDetail = ""
    private var info = ""

    // a firmware write is under way or between its passes
    @Volatile
    private var flashLease = false

    // a flash or dfu task is queued or running
    private var flashActive = false

    // flashEnd came while that task was running: the chain is over when it returns
    private var endAsked = false
    private var leaseToken = 0L

    // the device of the session that is opening, open, closing, or was left or lost
    private var deviceName: String? = null

    // the cable is out or the link is dead: nothing is sent to the device any more
    @Volatile
    private var deviceLost = false
    private var pendingConnect: String? = null
    private var teardown: Teardown? = null
    private var linkMisses = 0
    private var deadlineToken = 0L
    private var deadlineAction: (() -> Unit)? = null
    private var ticking = false

    /** The last published state. */
    @Volatile
    var snapshot = SessionSnapshot(epoch = epoch, pid = pid)
        private set

    /** [snapshot] as the UI reads it. */
    @Volatile
    var snapshotJson: String = snapshot.toJson()
        private set

    /** The session needs the process to stay in the foreground (design 3.3). */
    @Volatile
    var needsFgs = false
        private set

    @Volatile
    var shell: Shell? = null

    // Stop, leaving the session and killing the process are not allowed while a firmware is written
    private val interruptible: Boolean get() = StopPolicy.mayInterrupt(link) && !flashLease

    // BREAK goes to a device that is still there
    private val breakAllowed: Boolean
        get() = !deviceLost && (link == LinkState.CONNECTED || link == LinkState.CLOSING || link == LinkState.FLASHING)

    // ---- calls from the UI (binder threads) ----

    fun attach(listener: IPm3Listener?): String {
        if (listener != null) notifier.register(listener)
        return snapshotJson
    }

    fun detach(listener: IPm3Listener?) {
        if (listener != null) notifier.unregister(listener)
    }

    fun submit(id: Long, cmd: String?, grab: Boolean) {
        notifier.claim(SessionIds.owner(id))
        val line = cmd?.trim().orEmpty()
        if (line.isEmpty() || line.length > IpcLimits.MAX_COMMAND_CHARS) {
            notifier.result(id, ResultCode.DROPPED, NO_BYTES)
            return
        }
        engine.submit(id, line, grab)
    }

    fun commandTree(id: Long) {
        notifier.claim(SessionIds.owner(id))
        engine.commandTree(id)
    }

    /**
     * Stop. The queue is cut here and now, in the order the calls arrived: a command the UI sends after
     * Stop must not be dropped with the ones before it. Ending the running command is the host's.
     */
    fun stop() {
        if (!interruptible) {
            post { log.note("идёт прошивка, остановить нельзя") }
            return
        }
        val run = engine.cut()
        post { onStop(run) }
    }

    fun disconnect() = post { onDisconnect() }

    fun restartClient(reason: String?) = post { onRestart(reason.orEmpty()) }

    fun flash(id: Long, deviceName: String?, files: Array<String>?, unlockBootloader: Boolean, force: Boolean) {
        notifier.claim(SessionIds.owner(id))
        val images = files?.toList().orEmpty()
        post { onFlash(id, deviceName.orEmpty(), images, unlockBootloader, force) }
    }

    fun dfuFlash(id: Long, deviceName: String?, image: String?) {
        notifier.claim(SessionIds.owner(id))
        post { onDfuFlash(id, deviceName.orEmpty(), image.orEmpty()) }
    }

    fun flashEnd() = post { onFlashEnd() }

    fun note(text: String?) {
        if (!text.isNullOrEmpty()) post { log.note(text.take(MAX_NOTE_CHARS)) }
    }

    fun clearLog() = post { log.clear() }

    // ---- calls from the service shell (main thread) ----

    /** A startForegroundService intent arrived and the service is in the foreground. */
    fun begin(kind: Int, device: String?) = post { onBegin(kind, device) }

    /** A startForegroundService intent arrived and Android refused startForeground. */
    fun foregroundRefused(reason: String) = post { onForegroundRefused(reason) }

    // ---- events of the host thread: one function for each ----

    private fun onBegin(kind: Int, device: String?) {
        when (kind) {
            Pm3Ipc.KIND_CONNECT -> connect(device)
            // The intent only brings the service to the foreground (the shell did that) and keeps a lease that runs. FLASHING itself is
            // entered by the first flash()/dfuFlash(), which travels the binder that flashEnd travels too: an intent can arrive late,
            // after its chain ended, and must not be able to open a chain nobody ends.
            Pm3Ipc.KIND_FLASH -> if (link == LinkState.FLASHING) armLease()
            // KIND_RUN: the commands come with submit, and needsFgs keeps the process for them
        }
        // whatever came of it, the UI that asked gets a fresh look
        notifier.changed()
    }

    private fun onForegroundRefused(reason: String) {
        val text = "Android не разрешил фоновую службу: $reason"
        log.note(text)
        if (!link.holdsService) {
            link = LinkState.FAILED
            linkDetail = text
        }
    }

    // 3.4 step 1: which connect requests are taken
    private fun connect(name: String?) {
        if (name.isNullOrEmpty()) return
        when (link) {
            LinkState.IDLE, LinkState.FAILED, LinkState.LOST, LinkState.LEFT -> startOpening(name)
            // the last name asked for wins, and is opened once the session in the way is gone
            LinkState.OPENING, LinkState.CLOSING -> if (name != deviceName) pendingConnect = name
            LinkState.CONNECTED, LinkState.FLASHING -> {}
        }
    }

    // 3.4 steps 2 and 3: the client thread opens the device and talks to it, the host thread hears the outcome
    private fun startOpening(name: String) {
        deviceName = name
        deviceLost = false
        pendingConnect = null
        info = ""
        link = LinkState.OPENING
        linkDetail = if (engine.ready) "Открываю устройство…" else "Подготавливаю клиент…"
        engine.open(name) { result -> post { onOpened(name, result) } }
    }

    private fun onOpened(name: String, result: OpenResult) {
        // a disconnect or a flash came first: its own pipeline closes whatever was opened
        if (link != LinkState.OPENING) return
        when {
            // the reason is in the snapshot's error; there is no session to fail
            result.clientDown -> {
                link = LinkState.IDLE
                linkDetail = ""
            }

            result.error != null -> {
                link = LinkState.FAILED
                linkDetail = result.error
                log.note("не удалось подключиться: ${result.error}")
            }

            else -> {
                link = LinkState.CONNECTED
                linkDetail = ""
                info = result.info
                linkMisses = 0
                pendingConnect = null
                log.note("сессия открыта: $name")
            }
        }
        if (link != LinkState.CONNECTED) startPending()
    }

    private fun startPending() {
        val name = pendingConnect ?: return
        pendingConnect = null
        startOpening(name)
    }

    // 3.6 (a): the receiver of UsbLink reported a device that left
    private fun onDetached(name: String) {
        if (name != deviceName) return
        when (link) {
            LinkState.CONNECTED -> lose()
            // the cable that was left is out: the next device is a new start
            LinkState.LEFT -> {
                link = LinkState.IDLE
                linkDetail = ""
            }

            else -> {}
        }
    }

    // 3.6 (b): every 500 ms while the session is up; also the log's size
    private fun onPoll() {
        if (link == LinkState.CONNECTED) {
            // `hw connect` passes through "no device", so one miss is not a loss
            if (engine.linkState() == NativeClient.LINK_UP) linkMisses = 0 else if (++linkMisses >= LOSS_MISSES) lose()
        }
        log.trimIfTooBig()
    }

    // 3.6: the detach broadcast and the poll may both see the loss; only the first finds CONNECTED, the teardown it starts makes the rest return
    private fun lose() {
        if (link != LinkState.CONNECTED) return
        deviceLost = true
        beginTeardown(Teardown(LinkState.LOST, LOST_TEXT, gentle = false))
    }

    // 3.5: Stop. The queue is already cut (stop()); this ends the command that was running.
    private fun onStop(run: Run?) {
        if (run == null || teardown != null || !interruptible) return
        if (engine.current() !== run || run.stopping) return
        run.stopping = true
        // the deadline first: the first native call may be the one that hangs
        armDeadline(StopPolicy.graceMs(run.cmd)) { onOverdue(run, reconnect = link == LinkState.CONNECTED) }
        control.interrupt(run)
    }

    // 3.5: the command had its time to notice Stop
    private fun onOverdue(run: Run, reconnect: Boolean) {
        if (engine.current() !== run) return
        if (StopPolicy.mayKillAutomatically(run.cmd)) {
            hardKill("Команда «${shorten(run.cmd)}» не останавливается, клиент перезапущен", reconnect)
        } else {
            run.stuck = true
        }
    }

    // 3.5: leaving the session is Stop, and then closing
    private fun onDisconnect() {
        if (!interruptible) {
            log.note("идёт прошивка, выйти из сессии нельзя")
            return
        }
        pendingConnect = null
        when (link) {
            LinkState.OPENING, LinkState.CONNECTED -> beginTeardown(Teardown(LinkState.LEFT, "", gentle = true))
            // there is no device to close, only the label to change
            LinkState.FAILED, LinkState.LOST -> {
                link = LinkState.LEFT
                linkDetail = ""
            }

            // nothing to leave, or already on the way out
            LinkState.IDLE, LinkState.LEFT, LinkState.CLOSING, LinkState.FLASHING -> {}
        }
    }

    // 3.5 and 3.6: the queue is cut, the running command is asked to end (with the device gone: by Enter only) and the session is closed
    private fun beginTeardown(td: Teardown) {
        teardown = td
        link = LinkState.CLOSING
        linkDetail = if (td.gentle) "Выхожу из сессии…" else "Связь потеряна, закрываю сессию…"
        val run = engine.cut()
        if (td.gentle) {
            if (run != null) {
                run.stopping = true
                armDeadline(StopPolicy.graceMs(run.cmd)) { onOverdue(run, reconnect = false) }
                control.interrupt(run)
            }
        } else {
            // the client thread has this long to let go of the command; then its close gets a budget of its own (onClientIdle)
            armDeadline(LOSS_WAIT_MS) { hardKill("Связь потеряна, клиент завис", reconnect = false) }
            // The device is gone, so no BREAK (deviceLost keeps pm3-ctl from sending it), but Enter is a write to a pipe of our
            // own: without it the commands that poll the keyboard spin on the dead link until the deadline above kills the process.
            if (run != null) {
                run.stopping = true
                control.interrupt(run)
            }
        }
        // queued behind the command that runs: the client thread closes its side first, then the USB link
        engine.close { post { onClosed(td) } }
    }

    private fun onClosed(td: Teardown) {
        // a flash took the device over meanwhile
        if (teardown !== td) return
        teardown = null
        clearDeadline()
        info = ""
        link = td.finalState
        linkDetail = td.detail
        log.note(if (td.finalState == LinkState.LOST) "связь потеряна: устройство отключено" else "выход из сессии")
        startPending()
    }

    // 3.8: the user's "restart the client"
    private fun onRestart(reason: String) {
        if (!interruptible) {
            log.note("идёт прошивка, перезапустить клиент нельзя")
            return
        }
        hardKill(reason.trim().take(MAX_REASON_CHARS).ifEmpty { "клиент перезапущен по просьбе пользователя" }, reconnect = false)
    }

    // 3.8: the only way the process is ever ended by us
    private fun hardKill(reason: String, reconnect: Boolean) {
        if (!interruptible) {
            log.note("идёт прошивка, перезапуск клиента отложен")
            return
        }
        log.note(reason)
        // the user was leaving when the process had to go: the UI stays in LEFT, and nothing reconnects by itself
        ExitMarkers.write(app, ExitMarker(epoch, pid, reason, reconnect, left = teardown?.finalState == LinkState.LEFT))
        wakeLock.set(false)
        Process.killProcess(pid)
    }

    private fun onClientIdle() {
        if (engine.current() != null) return
        val td = teardown
        when {
            // the command a Stop was waiting for is over: its timer has nothing left to do
            td == null -> clearDeadline()
            // A lost link: the command let go, and what is left is the close (nativeClose, the USB link). It has a fresh
            // budget, so the one the command used up can not kill a close that is about to finish.
            !td.gentle -> armDeadline(CLOSE_WAIT_MS) { hardKill("Связь потеряна, закрытие зависло", reconnect = false) }
        }
    }

    // 3.7: the UI is about to write a firmware: the lease holds the service and the device.
    // Only an idle engine is taken over: a command that runs would keep the flash task waiting, and with the link
    // in FLASHING nothing could stop it (Stop, leaving and the restart are refused then). False: refused, nothing changed.
    private fun beginFlash(): Boolean {
        if (link == LinkState.FLASHING) {
            armLease()
            return true
        }
        if (!engine.refuseIfIdle()) {
            log.note("Прошивка: сначала остановите команду")
            return false
        }
        enterFlashing()
        return true
    }

    private fun enterFlashing() {
        // a close in flight is overtaken: the flash task closes the session itself, without a gap
        teardown = null
        clearDeadline()
        pendingConnect = null
        info = ""
        link = LinkState.FLASHING
        linkDetail = "Идёт прошивка"
        armLease()
    }

    private fun onFlash(id: Long, name: String, files: List<String>, unlockBootloader: Boolean, force: Boolean) {
        if (!admitFlash(id)) return
        engine.flash(name, files, unlockBootloader, force) { code -> post { onFlashDone(id, code) } }
    }

    private fun onDfuFlash(id: Long, name: String, image: String) {
        if (!admitFlash(id)) return
        engine.dfuFlash(name, image) { code -> post { onFlashDone(id, code) } }
    }

    private fun admitFlash(id: Long): Boolean {
        if (flashActive) {
            notifier.result(id, ResultCode.BUSY, NO_BYTES)
            return false
        }
        if (!engine.ready) {
            notifier.result(id, ResultCode.DROPPED, NO_BYTES)
            return false
        }
        // the UI should have sent Begin(KIND_FLASH) first; if it did not, this does the same (and a command that runs refuses it)
        if (!beginFlash()) {
            notifier.result(id, ResultCode.BUSY, NO_BYTES)
            return false
        }
        flashActive = true
        return true
    }

    private fun onFlashDone(id: Long, code: Int) {
        flashActive = false
        // the UI ended its chain while this pass wrote: nobody calls again, whatever the pass came to
        val ended = endAsked
        endAsked = false
        notifier.result(id, code, NO_BYTES)
        if (!ended && code == NativeClient.FLASH_REOPEN_PORT && link == LinkState.FLASHING) {
            // the device went to its bootloader, a new USB device: the UI opens that one with the next call
            linkDetail = "ждём загрузчик"
            armLease()
        } else {
            leaveFlashing()
        }
    }

    private fun onFlashEnd() {
        // a write that is still running ends the lease itself when it returns (onFlashDone)
        if (flashActive) endAsked = true else leaveFlashing()
    }

    private fun armLease() {
        flashLease = true
        val token = ++leaseToken
        later(LEASE_MS) { onLeaseExpired(token) }
    }

    private fun onLeaseExpired(token: Long) {
        if (token != leaseToken || !flashLease) return
        if (flashActive) {
            armLease()
            return
        }
        log.note("прошивка: время ожидания вышло")
        leaveFlashing()
    }

    private fun leaveFlashing() {
        flashLease = false
        leaseToken++
        if (link == LinkState.FLASHING) {
            link = LinkState.IDLE
            linkDetail = ""
        }
    }

    // ---- plumbing of the host thread ----

    private fun post(block: () -> Unit) {
        host.execute { handle(block) }
    }

    private fun later(delayMs: Long, block: () -> Unit) {
        host.schedule(Runnable { handle(block) }, delayMs, TimeUnit.MILLISECONDS)
    }

    // Every event ends here: a failing handler is logged and does not stop the host, and what it did change is published.
    private fun handle(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "event failed", t)
        }
        try {
            settle()
        } catch (t: Throwable) {
            Log.e(TAG, "cannot publish the state", t)
        }
    }

    /** Something outside the host thread changed (a command started or ended, the client came up, the queue moved): look again. */
    private fun requestSettle() {
        post { }
    }

    private fun clientIdle() {
        post { onClientIdle() }
    }

    private fun ownerGone(owner: Long) {
        engine.dropOwner(owner)
    }

    // The last UI died. A flash chain it left between two passes has nobody to make the next pass or to end it, so it is ended here, as
    // the UI would have; a pass that writes ends the chain itself when it returns (endAsked). A UI that attached again meanwhile keeps it.
    private fun onListenersGone() {
        if (flashLease && notifier.listenerCount() == 0) onFlashEnd()
    }

    // At most one Stop timer or loss timer is armed; arming one makes the older one void.
    private fun armDeadline(delayMs: Long, action: () -> Unit) {
        val token = ++deadlineToken
        deadlineAction = action
        later(delayMs) {
            if (token == deadlineToken) {
                val due = deadlineAction
                deadlineAction = null
                due?.invoke()
            }
        }
    }

    private fun clearDeadline() {
        deadlineToken++
        deadlineAction = null
    }

    // Publishes the new snapshot if there is one (the UI hears "changed" through pm3-events), and
    // re-evaluates what the process must hold: the foreground, the wake lock, the poll.
    private fun settle() {
        val busy = engine.busy()
        val snap = SessionSnapshot(
            epoch = epoch,
            pid = pid,
            ready = engine.ready,
            error = engine.error,
            clientVersion = engine.version,
            clientArmSrc = engine.armSrc,
            link = link,
            linkDetail = linkDetail,
            info = info,
            flashLease = flashLease,
            activity = engine.activity(),
        )
        val needs = link.holdsService || flashLease || busy
        wakeLock.set(link == LinkState.OPENING || link == LinkState.FLASHING || flashLease || busy)
        val newSnapshot = snap != snapshot
        if (newSnapshot) {
            snapshot = snap
            snapshotJson = snap.toJson()
            notifier.changed()
        }
        val newNeed = needs != needsFgs
        needsFgs = needs
        if (newSnapshot || newNeed) shell?.changed()
        if (needs && !ticking) {
            ticking = true
            later(POLL_MS) {
                ticking = false
                onPoll()
            }
        }
    }

    private fun shorten(cmd: String) = if (cmd.length > 40) cmd.take(40) + "…" else cmd

    private fun start() {
        // first, and not from the host thread: a binder thread may queue a command as soon as the service is up,
        // and it must find the start of the client ahead of it on the client thread (else it is answered "dropped")
        engine.start()
        post {
            usb.watchDetach { name -> post { onDetached(name) } }
            log.pruneOld()
        }
    }

    companion object {
        private const val TAG = "pm3-host"
        private const val POLL_MS = 500L
        private const val LOSS_MISSES = 2
        private const val LOSS_WAIT_MS = 5_000L
        private const val CLOSE_WAIT_MS = 5_000L

        // The lease outlasts the UI's longest wait inside a flash chain (60 s for the USB permission, 60 s for the
        // bootloader), and the UI sends Begin(KIND_FLASH) before every pass; it only frees a service whose UI is gone.
        private const val LEASE_MS = 180_000L
        private const val MAX_NOTE_CHARS = 2_000
        private const val MAX_REASON_CHARS = 200
        private const val LOST_TEXT = "Устройство отключено. Подключите его снова, чтобы начать новую сессию."

        @Volatile
        private var instance: SessionHost? = null

        fun get(context: Context): SessionHost =
            instance ?: synchronized(this) {
                instance ?: SessionHost(context.applicationContext).also {
                    it.start()
                    instance = it
                }
            }
    }
}
