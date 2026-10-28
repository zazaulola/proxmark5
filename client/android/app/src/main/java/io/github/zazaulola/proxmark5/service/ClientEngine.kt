package io.github.zazaulola.proxmark5.service

import android.content.Context
import android.util.Log
import io.github.zazaulola.proxmark5.core.NativeClient
import io.github.zazaulola.proxmark5.core.ResourceUnpacker
import io.github.zazaulola.proxmark5.ipc.SessionPaths
import io.github.zazaulola.proxmark5.parse.Activities
import io.github.zazaulola.proxmark5.parse.ActivityKind
import io.github.zazaulola.proxmark5.session.ActivitySnapshot
import io.github.zazaulola.proxmark5.session.IpcLimits
import io.github.zazaulola.proxmark5.session.ResultCode
import io.github.zazaulola.proxmark5.session.SessionIds
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.zip.GZIPOutputStream

/** One command on the client, from the moment it is published until it returns. */
internal class Run(val seq: Long, val cmd: String, val gen0: Int) {
    val kind: ActivityKind = Activities.of(cmd)
    val startedAtMs: Long = System.currentTimeMillis()

    /** Written by the host thread: Stop, or leaving the session, was asked for this command. */
    @Volatile
    var stopping = false

    /** Written by the host thread: Stop was asked, the command did not end, and Enter is all there is to try. */
    @Volatile
    var stuck = false
}

/** What opening a session came to. [error] says why it failed; [clientDown]: the client itself is not up (see [ClientEngine.error]). */
internal class OpenResult(val error: String? = null, val info: String = "", val clientDown: Boolean = false)

/**
 * The native client of this process: the "pm3-client" thread, the only one that calls JNI (but for
 * nativeAbort, nativeBreak and nativeLinkState, which pm3-ctl and the poller call), and the queue of
 * commands that feeds it. Commands, opening and closing a session, the command tree and flashing are
 * all tasks of that one thread, in the order they were asked for.
 *
 * The queue is tagged: every entry knows its owner, and the generation of Stop it was queued in. A
 * Stop ([cut]) drops what waits and counts a new generation; the worker checks that count before the
 * command starts (3.5 of the design), so a Stop can not be swallowed between the queue and the client.
 */
internal class ClientEngine(
    private val app: Context,
    private val log: LogFile,
    private val usb: UsbLink,
    private val notifier: Notifier,
    /** A command started, the queue changed or the client came up: look at the snapshot again. Any thread. */
    private val changed: () -> Unit,
    /** A command ended. Any thread. */
    private val idle: () -> Unit,
) {
    private enum class Phase { STARTING, READY, FAILED }

    private class Entry(val id: Long, val cmd: String, val grab: Boolean, val stopGen: Int) {
        val owner: Long get() = SessionIds.owner(id)
    }

    private class Taken(val entry: Entry, val run: Run?)

    private val clientThread = Executors.newSingleThreadExecutor(serviceThreads("pm3-client"))

    // Guards the queue, the Stop generation (writes), the run counter, pumpPending and refusing.
    // Held for a few instructions at a time, never over JNI or a callback: binder threads take it.
    private val lock = Any()

    // pm3-ctl signals a run only while it is the current one, and a run ends under this lock, so
    // neither Enter nor BREAK can reach the command after it. May be held for seconds (BREAK waits for USB).
    private val sendLock = Any()

    private val queue = ArrayDeque<Entry>()

    @Volatile
    private var stopGen = 0
    private var runSeq = 0L
    private var pumpPending = false
    private var refusing = false

    @Volatile
    private var currentRun: Run? = null

    @Volatile
    private var phase = Phase.STARTING

    // the native init was called: it may not be repeated, so a failure from here on is final
    private var nativeTried = false

    /** The client is up: its version is known and commands may run. */
    val ready: Boolean get() = phase == Phase.READY

    /** Why the client could not start; empty when it did or has not tried yet. */
    @Volatile
    var error = ""
        private set

    @Volatile
    var version = ""
        private set

    /** See [NativeClient.nativeClientArmSrc]. */
    @Volatile
    var armSrc = ""
        private set

    /** Starts the client on its thread: resources, native init, version. Does not block the caller. */
    fun start() {
        clientThread.execute(::bringUp)
    }

    // pm3-client
    private fun bringUp() {
        phase = Phase.STARTING
        try {
            val home = SessionPaths.home(app)
            ResourceUnpacker.unpack(app, home)
            nativeTried = true
            check(NativeClient.nativeInit(home.absolutePath, SessionPaths.dumpDir(app).absolutePath, log.path) == 0) {
                "не удалось подготовить стандартный вывод и ввод"
            }
            version = NativeClient.nativeClientVersion()
            armSrc = NativeClient.nativeClientArmSrc()
            error = ""
            phase = Phase.READY
        } catch (t: Throwable) {
            Log.e(TAG, "the client did not start", t)
            error = "Клиент не запустился: ${t.message ?: t.javaClass.simpleName}"
            phase = Phase.FAILED
        }
        changed()
        kick()
    }

    // ---- the command queue: binder threads, the host thread, pm3-client ----

    /** Queues a command line; its result goes to [notifier]. */
    fun submit(id: Long, cmd: String, grab: Boolean) {
        val queued = synchronized(lock) {
            if (refusing || phase == Phase.FAILED) {
                false
            } else {
                queue.addLast(Entry(id, cmd, grab, stopGen))
                true
            }
        }
        if (!queued) {
            notifier.result(id, ResultCode.DROPPED, NO_BYTES)
            return
        }
        changed()
        kick()
    }

    /**
     * Whether the queue takes commands. It does not while the session is closing or the flasher owns
     * the device: what comes then is answered "dropped". Turning it off and flushing ([cut]) are
     * ordered by the same lock, so nothing slips in between.
     */
    fun refuse(on: Boolean) {
        synchronized(lock) { refusing = on }
    }

    /**
     * Stop: counts a new generation, drops everything that waits (each gets "dropped") and returns the
     * command that runs right now, if any. The caller ends that one (Enter, BREAK, a timer).
     */
    fun cut(): Run? {
        val (dropped, run) = synchronized(lock) {
            stopGen++
            val all = queue.toList()
            queue.clear()
            all to currentRun
        }
        for (entry in dropped) notifier.result(entry.id, ResultCode.DROPPED, NO_BYTES)
        if (dropped.isNotEmpty()) changed()
        return run
    }

    /** The UI process of [owner] is gone: what it queued and did not start has nobody to report to. */
    fun dropOwner(owner: Long) {
        val removed = synchronized(lock) { queue.removeAll { it.owner == owner } }
        if (removed) changed()
    }

    /** The command that runs right now. */
    fun current(): Run? = currentRun

    /** A command runs or waits. */
    fun busy(): Boolean = currentRun != null || synchronized(lock) { queue.isNotEmpty() }

    fun activity(): ActivitySnapshot {
        val run = currentRun
        val queued = synchronized(lock) { queue.size }
        return if (run == null) {
            ActivitySnapshot(queued = queued)
        } else {
            ActivitySnapshot(run.kind, run.cmd, run.startedAtMs, queued, run.seq, run.stopping, run.stuck)
        }
    }

    private fun kick() {
        synchronized(lock) {
            if (pumpPending || queue.isEmpty()) return
            pumpPending = true
        }
        clientThread.execute(::runNext)
    }

    private fun takeNext(): Taken? {
        synchronized(lock) {
            pumpPending = false
            val entry = queue.removeFirstOrNull() ?: return null
            // a Stop since the command was queued, or a client that never came up: it does not run
            if (phase != Phase.READY || entry.stopGen != stopGen) return Taken(entry, null)
            val run = Run(++runSeq, entry.cmd, stopGen)
            currentRun = run
            return Taken(entry, run)
        }
    }

    // pm3-client: one command per task, so a flash or a close asked for meanwhile gets its turn
    private fun runNext() {
        val taken = takeNext() ?: return
        val entry = taken.entry
        val run = taken.run
        if (run == null) {
            notifier.result(entry.id, ResultCode.DROPPED, NO_BYTES)
            changed()
            kick()
            return
        }
        changed()
        var code = ResultCode.DROPPED
        var grabbed = NO_BYTES
        try {
            // an Enter left over from the last Stop must not end this command ...
            NativeClient.nativeDrainStdin()
            // ... and a Stop that came while we drained (it wrote its Enter, we ate it) must not be lost
            if (stopGen == run.gen0) {
                log.echo(entry.cmd)
                code = NativeClient.nativeExec(entry.cmd, entry.grab)
                if (entry.grab) grabbed = NativeClient.nativeTakeGrabbed()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "the command failed: ${entry.cmd}", t)
            code = PM3_EIO
        } finally {
            synchronized(sendLock) { currentRun = null }
        }
        notifier.result(entry.id, code, IpcLimits.clampResult(grabbed))
        idle()
        kick()
    }

    /** Enter, and with [withBreak] a BREAK to the device, for [run] only; false once [run] is over. pm3-ctl. */
    fun signal(run: Run, withBreak: Boolean): Boolean {
        synchronized(sendLock) {
            if (currentRun !== run) return false
            NativeClient.nativeAbort()
            if (withBreak) NativeClient.nativeBreak()
            return true
        }
    }

    /** See [NativeClient.LINK_UP]. No lock: the host thread asks while a command runs. */
    fun linkState(): Int = NativeClient.nativeLinkState()

    // ---- tasks of the client thread ----

    /** Opens the device [name] and talks to it; [done] is called on the client thread. */
    fun open(name: String, done: (OpenResult) -> Unit) {
        clientThread.execute {
            val result = try {
                // a failure before the native init is not final: the disk may have been full
                if (phase == Phase.FAILED && !nativeTried) bringUp()
                if (ready) openSession(name) else OpenResult(clientDown = true)
            } catch (t: Throwable) {
                Log.e(TAG, "cannot open $name", t)
                try {
                    releaseSession()
                } catch (_: Throwable) {
                }
                OpenResult("Не удалось подключиться: ${t.message ?: t.javaClass.simpleName}")
            }
            done(result)
        }
    }

    private fun openSession(name: String): OpenResult {
        try {
            retrying {
                val port = usb.open(name, UsbLink.Purpose.CONNECT)
                if (!NativeClient.nativeOpen(port)) {
                    // the client could not talk to it: let go of the device and try once more
                    if (NativeClient.nativeIsConnected()) NativeClient.nativeClose()
                    usb.close()
                    throw UsbLink.OpenException("Устройство не отвечает", retry = true)
                }
            }
        } catch (e: UsbLink.OpenException) {
            return OpenResult(e.message)
        }
        return OpenResult(info = readInfo())
    }

    // `hw version`, once, right after connecting: the UI shows what it finds
    private fun readInfo(): String {
        NativeClient.nativeDrainStdin()
        log.echo("hw version")
        NativeClient.nativeExec("hw version", true)
        return String(NativeClient.nativeTakeGrabbed(), Charsets.UTF_8).take(IpcLimits.MAX_INFO_CHARS)
    }

    /** Closes the client's side of the session and then the USB link; [done] is called on the client thread. */
    fun close(done: () -> Unit) {
        clientThread.execute {
            try {
                releaseSession()
            } catch (t: Throwable) {
                Log.e(TAG, "cannot close the session", t)
            }
            done()
        }
    }

    // The client first, the descriptor after: closing it under a client that still uses it crashes the process.
    private fun releaseSession() {
        try {
            if (NativeClient.nativeIsConnected()) NativeClient.nativeClose()
        } finally {
            usb.close()
        }
    }

    /** The command tree, gzip compressed, to [notifier]; empty when the client is not up. */
    fun commandTree(id: Long) {
        if (phase == Phase.FAILED) {
            notifier.tree(id, NO_BYTES)
            return
        }
        clientThread.execute {
            val gzip = try {
                if (ready) gzip(NativeClient.nativeCommandTree()) else NO_BYTES
            } catch (t: Throwable) {
                Log.e(TAG, "cannot read the command tree", t)
                NO_BYTES
            }
            notifier.tree(id, if (gzip.size <= IpcLimits.MAX_RESULT_BYTES) gzip else NO_BYTES)
        }
    }

    private fun gzip(text: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text) }
        return out.toByteArray()
    }

    /**
     * Writes ELF images. It closes the session that is open (the flasher takes the device), opens
     * [name] again and calls nativeFlash; [done] gets its result, FLASH_REOPEN_PORT included.
     */
    fun flash(name: String, files: List<String>, unlockBootloader: Boolean, force: Boolean, done: (Int) -> Unit) {
        val label = "flash " + files.joinToString(" ") { it.substringAfterLast('/') }
        exclusive(UsbLink.Purpose.FLASH, name, label, done) { port ->
            NativeClient.nativeFlash(port, files.toTypedArray(), unlockBootloader, force)
        }
    }

    /** Writes a raw image through the DFU loader. */
    fun dfuFlash(name: String, image: String, done: (Int) -> Unit) {
        exclusive(UsbLink.Purpose.DFU, name, "dfu ${image.substringAfterLast('/')}", done) { port ->
            NativeClient.nativeDfuFlash(port, image, NativeClient.DFU_ADDRESS)
        }
    }

    private fun exclusive(purpose: UsbLink.Purpose, name: String, label: String, done: (Int) -> Unit, write: (String) -> Int) {
        clientThread.execute {
            val code = try {
                releaseSession()
                val port = retrying { usb.open(name, purpose) }
                try {
                    log.echo(label)
                    write(port)
                } finally {
                    // the flasher is back: only now the descriptor may go
                    usb.close()
                }
            } catch (e: UsbLink.OpenException) {
                log.note("прошивка: ${e.message}")
                PM3_EIO
            } catch (t: Throwable) {
                Log.e(TAG, "$label failed", t)
                log.note("прошивка: ${t.message ?: t.javaClass.simpleName}")
                PM3_EIO
            }
            done(code)
        }
    }

    // After a killed client or a re-plug the interface may stay busy for a moment: try three times.
    private fun <T> retrying(block: () -> T): T {
        var attempt = 1
        while (true) {
            try {
                return block()
            } catch (e: UsbLink.OpenException) {
                if (!e.retry || attempt >= ATTEMPTS) throw e
                attempt++
                Thread.sleep(RETRY_PAUSE_MS)
            }
        }
    }

    private companion object {
        const val TAG = "pm3-client"
        const val ATTEMPTS = 3
        const val RETRY_PAUSE_MS = 400L

        /** include/pm3_cmd.h PM3_EIO: what a command or a flash gets when the service itself failed under it */
        const val PM3_EIO = -8
    }
}
