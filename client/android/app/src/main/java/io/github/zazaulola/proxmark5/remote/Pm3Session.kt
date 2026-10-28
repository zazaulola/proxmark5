package io.github.zazaulola.proxmark5.remote

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.zazaulola.proxmark5.core.ExecResult
import io.github.zazaulola.proxmark5.core.ProcessInfo
import io.github.zazaulola.proxmark5.ipc.IPm3Listener
import io.github.zazaulola.proxmark5.ipc.IPm3Service
import io.github.zazaulola.proxmark5.ipc.Pm3Ipc
import io.github.zazaulola.proxmark5.ipc.SessionPaths
import io.github.zazaulola.proxmark5.parse.CommandNode
import io.github.zazaulola.proxmark5.parse.CommandTree
import io.github.zazaulola.proxmark5.parse.TerminalBuffer
import io.github.zazaulola.proxmark5.service.Pm3Service
import io.github.zazaulola.proxmark5.session.ExitMarker
import io.github.zazaulola.proxmark5.session.IpcLimits
import io.github.zazaulola.proxmark5.session.LinkState
import io.github.zazaulola.proxmark5.session.ResultCode
import io.github.zazaulola.proxmark5.session.SessionIds
import io.github.zazaulola.proxmark5.session.SessionSnapshot
import io.github.zazaulola.proxmark5.session.StopPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream

private const val TAG = "pm3-session"

private const val POLL_MS = 3_000L
private const val BIND_WAIT_MS = 5_000L
private const val EXIT_INFO_WAIT_MS = 300L
private const val WINDOW_MS = 60_000L
private const val MAX_REBINDS = 3
private const val MAX_RECONNECTS = 2
private const val MAX_TREE_BYTES = 16 * 1024 * 1024
private const val MAX_REASON_CHARS = 200

// ids are (nonce << 20) | counter: a nonce of 44 bits is what keeps the owner of an id equal to it
private const val NONCE_MASK = (1L shl 44) - 1
private const val NO_ID = 0L

private const val RESTARTED = "клиент перезапущен"
private const val DIED = "клиент завершился"
private const val UNKNOWN_REASON = "причина неизвестна"
private const val FGS_REFUSED = "Android не разрешил фоновую службу, откройте приложение и повторите"
private const val NO_SERVICE = "Не удалось связаться со службой клиента"
private const val TOO_MANY_REBINDS = "Не удаётся связаться со службой клиента: слишком много попыток за минуту, повторите позже"

// the links in which a service that dies has been doing something the user is waiting for
private val WORKING = setOf(LinkState.OPENING, LinkState.CONNECTED, LinkState.FLASHING)

/** The answer to a request: the client's code or a [ResultCode], and what came with it (the grabbed output, the gzip tree). */
private typealias Finish = (code: Int, bytes: ByteArray?) -> Unit

private fun threads(name: String) = ThreadFactory { r ->
    Thread(r, name).apply {
        isDaemon = true
        uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { t, e -> Log.e(TAG, "uncaught in ${t.name}", e) }
    }
}

private fun shorten(cmd: String) = if (cmd.length > 40) cmd.take(40) + "…" else cmd

private fun parseTree(gzip: ByteArray): CommandNode? = try {
    val text = ByteArrayOutputStream()
    GZIPInputStream(ByteArrayInputStream(gzip)).use { input ->
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            text.write(buf, 0, n)
            if (text.size() > MAX_TREE_BYTES) throw IOException("the command tree is too big")
        }
    }
    CommandTree.parse(text.toString(Charsets.UTF_8.name()))
} catch (e: IOException) {
    Log.w(TAG, "cannot read the command tree", e)
    null
}

/**
 * The UI process's end of the live session. The session itself is in the service of process :pm3;
 * this is what the ViewModel talks to (design 5.1, A.2): it binds to the service, mirrors its
 * snapshot in [state], tails its log file into [terminal] and sends it the user's wishes.
 *
 * Threads. Everything that has state runs on the one thread "pm3-ui-session", one event at a time:
 * binder callbacks, death notices and timers only copy what they got and post to it. Calls to the
 * service go out through a queue of their own ("pm3-ui-ops"); the synchronous ones (attach,
 * snapshot) and the system call for the exit record go through a third thread, so nothing waits on
 * binder on the session thread or on the main one.
 *
 * Each bindService makes a [Conn]; whatever comes back from the service, or happens to it, names its
 * Conn and is ignored unless that is the current one. A request is sent only on the Conn it was
 * queued for: after a death it completes as [ResultCode.TRANSPORT_LOST] and is never replayed.
 * Only a death notice means the service died; a binder call that fails just fails.
 */
class Pm3Session private constructor(context: Context) {
    private val app = context.applicationContext

    // ---- what the ViewModel sees (design A.2) ----

    private val _state = MutableStateFlow(SessionSnapshot())

    /** The only published truth about the session: the service's last snapshot, or one made here while the service is out of reach. */
    val state: StateFlow<SessionSnapshot> = _state.asStateFlow()

    private val _bound = MutableStateFlow(false)

    /** The service answered and can be talked to. */
    val bound: StateFlow<Boolean> = _bound.asStateFlow()

    /** The client's output, filled from the log file of the service. Lives in the UI process, so it survives the service. */
    val terminal = TerminalBuffer()

    /** Where the client saves dumps, and where terminal logs go: known before the service is bound. */
    val dumpDir: String = SessionPaths.dumpDir(app).absolutePath
    val logsDir: String = SessionPaths.logsDir(app).absolutePath

    private val stopCount = AtomicInteger()

    /**
     * Counts the times the session was cut short: Stop, leaving, a restart, the service dying, a command
     * dropped or lost. A caller that chains commands reads it before the first one and compares after
     * each: if it changed, the chain was cut and must not go on.
     */
    val generation: Int get() = stopCount.get()

    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 8)

    /** One-off news about the service; nothing is kept for a collector that comes late. */
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    // ---- plumbing ----

    private val exec = ScheduledThreadPoolExecutor(1, threads("pm3-ui-session")).apply { removeOnCancelPolicy = true }
    private val ops = Executors.newSingleThreadExecutor(threads("pm3-ui-ops"))
    private val reads = Executors.newSingleThreadExecutor(threads("pm3-ui-reads"))
    private val follower = LogFollower(app, terminal)

    // ids: the nonce says which UI process sent a command, so a new one can not be handed the result of an old one's
    private val uiNonce = SystemClock.elapsedRealtime() and NONCE_MASK
    private val owner = SessionIds.owner(SessionIds.make(uiNonce, 0))
    private val idCounter = AtomicLong()

    @Volatile
    private var uiVisible = false

    // ---- state of the session thread ----

    @Volatile
    private var current: Conn? = null
    private var connCounter = 0
    private val rebinds = ArrayDeque<Long>()
    private var started = false

    // what the service (or, with none, this class) says about the session, and the failure of ours laid over an idle one
    private var base = SessionSnapshot()
    private var localFailure: String? = null
    private var followedEpoch = 0L

    private val pending = HashMap<Long, Pending>()
    private val waiting = ArrayList<Waiting>()

    private var tick: ScheduledFuture<*>? = null
    private var snapshotBusy = false
    private var snapshotAgain = false
    private var readFailed = false

    private var backstop: ScheduledFuture<*>? = null
    private var backstopSeq = 0L
    private var ownKill: String? = null

    private var pendingReconnect = false
    private val reconnects = ArrayDeque<Long>()

    /** One bindService and what became of it. The session thread's, but for [connection], whose callbacks come on the main thread and only post. */
    private inner class Conn(val gen: Int) {
        var binder: IBinder? = null

        // the stub the service calls back on, held as long as the binding is
        var listener: IPm3Listener? = null
        var death: IBinder.DeathRecipient? = null

        /** Set when attach() has answered; from then on calls may be sent. */
        var service: IPm3Service? = null
        var bindIssued = false
        var unbound = false

        // who the service is, from its first snapshot: the exit marker is taken as ours only if it names them
        var epoch = 0L
        var pid = 0
        var attachedAtMs = 0L

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) = post { handleConnected(this@Conn, service) }
            override fun onServiceDisconnected(name: ComponentName?) = post { handleDeath(this@Conn) }
            override fun onBindingDied(name: ComponentName?) = post { handleDeath(this@Conn) }
            override fun onNullBinding(name: ComponentName?) = post { handleBindFailure(this@Conn, NO_SERVICE) }
        }
    }

    private inner class Listener(private val conn: Conn) : IPm3Listener.Stub() {
        override fun onChanged() = post { handleChanged(conn) }
        override fun onResult(id: Long, code: Int, utf8: ByteArray?) = post { handleAnswer(conn, id, code, utf8) }
        override fun onTree(id: Long, gzipUtf8: ByteArray?) = post { handleAnswer(conn, id, 0, gzipUtf8) }
    }

    /** A call to the service. [id] and [finish] are set for the ones that are answered by a callback. */
    private class Op(
        val id: Long = NO_ID,
        val finish: Finish? = null,
        /** With no binding it waits for one (submit, the tree, flash); the others are dropped. */
        val waitsForBinding: Boolean = false,
        /** Stop and leaving drop it while it still waits for the binding. */
        val cuttable: Boolean = false,
        val send: (IPm3Service) -> Unit,
    )

    private class Pending(val conn: Conn, val finish: Finish)

    private class Waiting(val op: Op) {
        var timeout: ScheduledFuture<*>? = null
    }

    // ---- API (design A.2) ----

    /** Binds to the service and starts following its log. Safe to call again. */
    fun start() = post {
        ensureStarted()
        bind(demand = false)
    }

    /** MainActivity.onStart / onStop. The log is read, and a connect started, only while the UI is visible. */
    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        post { handleVisible(visible) }
    }

    /**
     * Starts the foreground service on a piece of work (Pm3Ipc.KIND_*). Only from a visible UI, which is what
     * Android needs to allow it; false when it is not visible or Android refuses (then the state says FAILED).
     */
    fun begin(kind: Int, deviceName: String?): Boolean {
        if (!uiVisible) return false
        val intent = Intent(app, Pm3Service::class.java)
            .setAction(Pm3Ipc.ACTION_BEGIN)
            .putExtra(Pm3Ipc.EXTRA_KIND, kind)
        if (deviceName != null) intent.putExtra(Pm3Ipc.EXTRA_DEVICE, deviceName)
        try {
            ContextCompat.startForegroundService(app, intent)
        } catch (e: IllegalStateException) {
            return refused(e)
        } catch (e: SecurityException) {
            return refused(e)
        }
        post { handleBegin(kind) }
        return true
    }

    /** Queues a client command line. Always completes: with a [ResultCode] in the code when it could not run. */
    fun submit(cmd: String, grab: Boolean = false): Deferred<ExecResult> {
        val done = CompletableDeferred<ExecResult>()
        val finish: Finish = { code, bytes -> done.complete(ExecResult(code, bytes.asText())) }
        val line = cmd.trim()
        if (line.isEmpty() || line.length > IpcLimits.MAX_COMMAND_CHARS) {
            end(finish, ResultCode.DROPPED)
            return done
        }
        val id = nextId()
        post { enqueue(Op(id, finish, waitsForBinding = true, cuttable = true) { it.submit(id, line, grab) }) }
        return done
    }

    /** The client's command tree, or null when there is none (the client is not up, the service is out of reach). */
    suspend fun commandTree(): CommandNode? {
        val done = CompletableDeferred<ByteArray?>()
        val finish: Finish = { code, gzip -> done.complete(if (code == 0 && gzip != null && gzip.isNotEmpty()) gzip else null) }
        val id = nextId()
        post { enqueue(Op(id, finish, waitsForBinding = true) { it.commandTree(id) }) }
        val gzip = done.await() ?: return null
        return withContext(Dispatchers.Default) { parseTree(gzip) }
    }

    /** Ends the running command and drops the ones waiting behind it. */
    fun stop() {
        stopCount.incrementAndGet()
        post {
            cutWaiting()
            armBackstop()
            enqueue(Op { it.stop() })
        }
    }

    /** Leaves the session: Stop, then the link is closed. */
    fun disconnect() {
        stopCount.incrementAndGet()
        post {
            cutWaiting()
            enqueue(Op { it.disconnect() })
        }
    }

    /** Ends the client process; the session starts again clean. */
    fun restartClient(reason: String) {
        stopCount.incrementAndGet()
        post {
            cutWaiting()
            enqueue(Op { it.restartClient(reason.take(MAX_REASON_CHARS)) })
        }
    }

    /** Writes ELF images to the device named [deviceName]; the client's code, or a [ResultCode]. */
    suspend fun flash(deviceName: String, files: List<String>, unlockBootloader: Boolean, force: Boolean): Int {
        val images = files.toTypedArray()
        return call { id, service -> service.flash(id, deviceName, images, unlockBootloader, force) }
    }

    /** Writes a raw image through the PM5 DFU loader; the client's code, or a [ResultCode]. */
    suspend fun dfuFlash(deviceName: String, image: String): Int =
        call { id, service -> service.dfuFlash(id, deviceName, image) }

    /** The chain of flash calls is over, however it ended. */
    fun flashEnd() = post { enqueue(Op { it.flashEnd() }) }

    /** A line of the app's own in the log (the terminal gets it from the file like the rest). */
    fun note(text: String) {
        val line = text.take(IpcLimits.MAX_COMMAND_CHARS)
        post { enqueue(Op { it.note(line) }) }
    }

    /** Empties the log, and the terminal with it. */
    fun clearLog() {
        terminal.clear()
        follower.clear()
        post { enqueue(Op { it.clearLog() }) }
    }

    // ---- requests ----

    private fun nextId() = SessionIds.make(uiNonce, idCounter.incrementAndGet())

    private suspend fun call(send: (Long, IPm3Service) -> Unit): Int {
        val done = CompletableDeferred<Int>()
        val id = nextId()
        post { enqueue(Op(id, { code, _ -> done.complete(code) }, waitsForBinding = true) { send(id, it) }) }
        return done.await()
    }

    // Stamps the call with the Conn it goes out on; with none yet, a call that needs the service waits (5 s) for it, the others are dropped.
    private fun enqueue(op: Op) {
        val conn = current
        val service = conn?.service
        when {
            conn != null && service != null -> dispatch(conn, service, op)
            op.waitsForBinding -> {
                val w = Waiting(op)
                w.timeout = later(BIND_WAIT_MS) { handleWaitTimeout(w) }
                waiting += w
                bind(demand = true)
            }
            else -> Log.d(TAG, "no service: a call is dropped")
        }
    }

    private fun dispatch(conn: Conn, service: IPm3Service, op: Op) {
        op.finish?.let { pending[op.id] = Pending(conn, it) }
        ops.execute {
            val sent = conn === current && send(service, op)
            if (!sent) {
                Log.w(TAG, "not sent on binding #${conn.gen}")
                if (op.finish != null) post { handleNotSent(conn, op) }
            }
        }
    }

    // pm3-ui-ops: each call is its own try, a failing one fails only itself
    private fun send(service: IPm3Service, op: Op): Boolean = tryCall("call") {
        op.send(service)
        true
    } == true

    // A failed binder call is no proof of a death: a frozen peer or a full buffer fail the same way (DeadObjectException). The death notice is the proof.
    private fun <T> tryCall(what: String, block: () -> T): T? = try {
        block()
    } catch (e: RemoteException) {
        Log.w(TAG, "$what failed", e)
        null
    } catch (e: RuntimeException) {
        Log.w(TAG, "$what failed", e)
        null
    }

    private fun handleNotSent(conn: Conn, op: Op) {
        val p = pending[op.id] ?: return
        if (p.conn !== conn) return
        pending.remove(op.id)
        end(p.finish, ResultCode.TRANSPORT_LOST)
    }

    private fun handleWaitTimeout(w: Waiting) {
        if (!waiting.remove(w)) return
        w.op.finish?.let { end(it, ResultCode.TRANSPORT_LOST) }
        setLocalFailure(NO_SERVICE)
    }

    // the answer to a request: a result, or (code 0) a tree
    private fun handleAnswer(conn: Conn, id: Long, code: Int, bytes: ByteArray?) {
        if (conn !== current || SessionIds.owner(id) != owner) return
        val p = pending[id] ?: return
        if (p.conn !== conn) return
        pending.remove(id)
        end(p.finish, code, bytes)
    }

    // A request ends. Dropped and lost ones cut the chains built on them (generation).
    private fun end(finish: Finish, code: Int, bytes: ByteArray? = null) {
        if (ResultCode.isTransport(code)) stopCount.incrementAndGet()
        finish(code, bytes)
    }

    // Stop and leaving: what still waits for the binding never reached the service, so it is dropped here
    private fun cutWaiting() {
        val it = waiting.iterator()
        while (it.hasNext()) {
            val w = it.next()
            if (!w.op.cuttable) continue
            it.remove()
            w.timeout?.cancel(false)
            w.op.finish?.let { f -> end(f, ResultCode.DROPPED) }
        }
    }

    private fun failWaiting(): Boolean {
        if (waiting.isEmpty()) return false
        val all = waiting.toList()
        waiting.clear()
        for (w in all) {
            w.timeout?.cancel(false)
            w.op.finish?.let { end(it, ResultCode.TRANSPORT_LOST) }
        }
        return true
    }

    private fun sweepPending(conn: Conn): Boolean {
        var any = false
        val it = pending.values.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (p.conn !== conn) continue
            it.remove()
            any = true
            end(p.finish, ResultCode.TRANSPORT_LOST)
        }
        return any
    }

    private fun ByteArray?.asText() = if (this == null) "" else String(this, Charsets.UTF_8)

    // ---- binding ----

    private fun ensureStarted() {
        if (started) return
        started = true
        follower.start()
    }

    // At most MAX_REBINDS rebinds a minute. [demand]: somebody is waiting for the answer, so a refusal is reported.
    private fun bind(demand: Boolean) {
        if (current != null) return
        ensureStarted()
        val now = SystemClock.elapsedRealtime()
        while (rebinds.isNotEmpty() && now - rebinds.first() > WINDOW_MS) rebinds.removeFirst()
        if (connCounter > 0 && rebinds.size >= MAX_REBINDS) {
            Log.w(TAG, "not binding again: $MAX_REBINDS rebinds within a minute")
            if (demand) {
                failWaiting()
                setLocalFailure(TOO_MANY_REBINDS)
            }
            return
        }
        if (connCounter > 0) rebinds.addLast(now)
        val conn = Conn(++connCounter)
        current = conn
        val requested = try {
            // false does not release the connection either: unbind is due in any case
            app.bindService(Intent(app, Pm3Service::class.java), conn.connection, Context.BIND_AUTO_CREATE).also { conn.bindIssued = true }
        } catch (e: SecurityException) {
            false
        }
        if (!requested) handleBindFailure(conn, NO_SERVICE)
    }

    private fun unbind(conn: Conn) {
        if (!conn.bindIssued || conn.unbound) return
        conn.unbound = true
        try {
            app.unbindService(conn.connection)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "binding #${conn.gen} was not registered", e)
        }
    }

    private fun handleConnected(conn: Conn, binder: IBinder?) {
        // a binding that was given up on, or the same one announced a second time: the process behind it is not the one we knew
        if (conn !== current) {
            unbind(conn)
            return
        }
        if (conn.binder != null) {
            handleDeath(conn)
            return
        }
        val service = IPm3Service.Stub.asInterface(binder)
        if (binder == null || service == null) {
            handleBindFailure(conn, NO_SERVICE)
            return
        }
        val listener = Listener(conn)
        val death = IBinder.DeathRecipient { post { handleDeath(conn) } }
        conn.binder = binder
        conn.listener = listener
        conn.death = death
        try {
            binder.linkToDeath(death, 0)
        } catch (e: RemoteException) {
            // already dead
            handleDeath(conn)
            return
        }
        reads.execute {
            if (conn !== current) return@execute
            val json = tryCall("attach") { service.attach(listener) }
            post { handleAttached(conn, service, json) }
        }
    }

    private fun handleAttached(conn: Conn, service: IPm3Service, json: String?) {
        if (conn !== current) return
        val snap = SessionSnapshot.parse(json)
        if (snap == null) {
            handleBindFailure(conn, NO_SERVICE)
            return
        }
        conn.service = service
        conn.epoch = snap.epoch
        conn.pid = snap.pid
        conn.attachedAtMs = System.currentTimeMillis()
        localFailure = null
        applySnapshot(snap)
        // after the state: whoever acts on "bound" reads the service's snapshot, not the one from before
        _bound.value = true
        updateReading()
        // the service may have changed meanwhile and said so before this answer was handled
        requestSnapshot(conn)
        val queued = waiting.toList()
        waiting.clear()
        for (w in queued) {
            w.timeout?.cancel(false)
            dispatch(conn, service, w.op)
        }
    }

    private fun handleBindFailure(conn: Conn, text: String) {
        if (conn !== current) return
        Log.w(TAG, "binding #${conn.gen} failed: $text")
        release(conn)
        failWaiting()
        setLocalFailure(text)
    }

    // Lets go of a binding: nothing it brings is heard any more, and the system will not restart the service for it.
    private fun release(conn: Conn) {
        current = null
        val binder = conn.binder
        val death = conn.death
        if (binder != null && death != null) {
            try {
                binder.unlinkToDeath(death, 0)
            } catch (_: NoSuchElementException) {
                // the link was never made
            }
        }
        unbind(conn)
        tick?.cancel(false)
        tick = null
        backstop?.cancel(false)
        backstop = null
        snapshotBusy = false
        snapshotAgain = false
        readFailed = false
        updateReading()
    }

    // The log is read while a service is there to write it and somebody looks.
    private fun updateReading() = follower.setReading(current?.service != null && uiVisible)

    private fun handleVisible(visible: Boolean) {
        updateReading()
        if (!visible) return
        val conn = current
        if (conn != null) requestSnapshot(conn) else if (started) bind(demand = false)
        if (pendingReconnect) {
            pendingReconnect = false
            emitReconnect()
        }
    }

    private fun handleBegin(kind: Int) {
        // a new attempt: the failure of the last one is no longer news
        localFailure = null
        publish()
        if (kind == Pm3Ipc.KIND_CONNECT) pendingReconnect = false
        bind(demand = true)
    }

    private fun refused(e: RuntimeException): Boolean {
        Log.w(TAG, "startForegroundService refused", e)
        post { setLocalFailure(FGS_REFUSED) }
        return false
    }

    // ---- the state ----

    private fun handleChanged(conn: Conn) {
        if (conn === current) requestSnapshot(conn)
    }

    // One read at a time; a change that comes while one is out makes one more read after it.
    private fun requestSnapshot(conn: Conn) {
        if (conn.service == null) return
        if (snapshotBusy) {
            snapshotAgain = true
            return
        }
        snapshotBusy = true
        fetch(conn) {
            snapshotBusy = false
            if (snapshotAgain) {
                snapshotAgain = false
                requestSnapshot(conn)
            }
        }
    }

    // pm3-ui-reads: a synchronous binder call, which is why it is not on the session thread. [then] runs on the session thread, if the Conn still stands.
    private fun fetch(conn: Conn, then: () -> Unit) {
        val service = conn.service ?: return
        reads.execute {
            val json = tryCall("snapshot") { service.snapshot() }
            post {
                if (conn === current) {
                    val snap = SessionSnapshot.parse(json)
                    // a read that failed is tried again at the next tick, idle or not
                    readFailed = snap == null
                    if (snap != null) applySnapshot(snap) else armTick()
                    then()
                }
            }
        }
    }

    private fun applySnapshot(snap: SessionSnapshot) {
        base = snap
        publish()
        if (snap.epoch != followedEpoch) {
            followedEpoch = snap.epoch
            follower.follow(snap.epoch)
        }
        if (needsPoll(snap)) armTick()
    }

    // A failure of ours only stands in for a service with nothing going on; a session that is under way beats it.
    private fun publish() {
        val b = base
        val failure = localFailure
        if (failure != null && !b.link.holdsService && !b.activity.running) {
            _state.value = b.copy(link = LinkState.FAILED, linkDetail = failure)
        } else {
            localFailure = null
            _state.value = b
        }
    }

    private fun setLocalFailure(detail: String) {
        localFailure = detail
        publish()
    }

    // a lost "changed" must heal: while anything is going on the snapshot is read every 3 s
    private fun needsPoll(s: SessionSnapshot) = s.link != LinkState.IDLE || s.activity.running

    private fun armTick() {
        if (tick == null) tick = later(POLL_MS) { handleTick() }
    }

    private fun handleTick() {
        tick = null
        val conn = current ?: return
        if (conn.service == null || !(readFailed || needsPoll(base))) return
        requestSnapshot(conn)
        armTick()
    }

    // ---- the UI's own backstop (design 3.8) ----

    // After Stop on a running command: if the service still shows the same run once its own deadline and our margin are over, we end the process ourselves.
    private fun armBackstop() {
        val conn = current ?: return
        val snap = base
        val run = snap.activity
        if (conn.service == null || !run.running || snap.link == LinkState.FLASHING || snap.flashLease) return
        // the first Stop of a run sets the deadline, as it does in the service
        if (backstop != null && backstopSeq == run.seq) return
        backstop?.cancel(false)
        backstopSeq = run.seq
        val seq = run.seq
        backstop = later(StopPolicy.graceMs(run.command) + StopPolicy.UI_BACKSTOP_MS) { handleBackstop(conn, seq) }
    }

    private fun handleBackstop(conn: Conn, seq: Long) {
        backstop = null
        if (conn !== current) return
        // judged on a snapshot read now, not on the last one heard
        fetch(conn) {
            if (readFailed) return@fetch
            val snap = base
            val run = snap.activity
            val sure = run.running && run.seq == seq && snap.link != LinkState.FLASHING && !snap.flashLease
            if (sure && conn.pid > 0 && snap.pid == conn.pid && conn.pid != Process.myPid()) killService(snap)
        }
    }

    private fun killService(snap: SessionSnapshot) {
        val cmd = snap.activity.command
        val reason = "Команда «${shorten(cmd)}» не останавливается, клиент перезапущен"
        val reconnect = snap.link == LinkState.CONNECTED && StopPolicy.mayKillAutomatically(cmd)
        try {
            SessionPaths.exitMarker(app).writeText(ExitMarker(snap.epoch, snap.pid, reason, reconnect).toJson())
        } catch (e: IOException) {
            Log.w(TAG, "cannot write the exit marker", e)
        }
        ownKill = reason
        Process.killProcess(snap.pid)
    }

    // ---- the death of the service (design 5.1) ----

    // The binder died or the connection dropped. The only place a lost service becomes state, notes and events.
    private fun handleDeath(conn: Conn) {
        if (conn !== current) return
        val prev = base
        val attached = conn.service != null
        val marker = if (attached) takeMarker(conn) else null
        release(conn)
        stopCount.incrementAndGet()
        val hadPending = sweepPending(conn)
        val hadWaiting = failWaiting()

        // a service that was doing something for the user: a planned end is a restart, any other is a loss; an idle one going away is no news
        val working = attached && (prev.link in WORKING || hadPending)
        val dead = when {
            working && marker != null -> gone(prev, LinkState.IDLE, RESTARTED)
            working -> gone(prev, LinkState.LOST, DIED)
            else -> gone(prev, if (prev.link == LinkState.CLOSING) LinkState.IDLE else prev.link, prev.linkDetail)
        }
        base = dead
        localFailure = if (hadWaiting) NO_SERVICE else null
        publish()
        // after the state, as on attach
        _bound.value = false

        // what the client said last comes before anything we add
        follower.drain()
        if (marker != null) {
            ownKill?.let { follower.note(it) }
            if (marker.reconnect) reconnectAfterRestart()
        } else if (working) {
            reportDeath(conn, dead)
        }
        ownKill = null
    }

    // The note the service left when it ended itself, if it is about this very process; read once, whatever it says.
    private fun takeMarker(conn: Conn): ExitMarker? {
        val file = SessionPaths.exitMarker(app)
        val marker = try {
            ExitMarker.parse(file.readText())
        } catch (e: IOException) {
            null
        }
        file.delete()
        return marker?.takeIf { it.epoch == conn.epoch && it.pid == conn.pid }
    }

    // nothing of the service survives the death but what the client was told about the build
    private fun gone(prev: SessionSnapshot, link: LinkState, detail: String) = SessionSnapshot(
        clientVersion = prev.clientVersion,
        clientArmSrc = prev.clientArmSrc,
        link = link,
        linkDetail = detail,
    )

    // Android writes the exit record a moment after the death, so we give it that moment, and read it off the session thread.
    private fun reportDeath(conn: Conn, dead: SessionSnapshot) {
        val pid = conn.pid
        val since = conn.attachedAtMs
        later(EXIT_INFO_WAIT_MS) {
            reads.execute {
                val reason = ExitReason.find(app, pid, since) ?: UNKNOWN_REASON
                post { handleReason(dead, reason) }
            }
        }
    }

    private fun handleReason(dead: SessionSnapshot, reason: String) {
        follower.note("$DIED: $reason")
        // the state still says what the death made of it: add the reason; a newer one is left alone
        if (base === dead) {
            base = dead.copy(linkDetail = "$DIED: $reason")
            publish()
        }
        _events.tryEmit(SessionEvent.ServiceDied(reason))
    }

    // Seen: the UI may connect again, but not more than twice a minute and, when it is not visible, only once it is.
    private fun reconnectAfterRestart() {
        if (uiVisible) emitReconnect() else pendingReconnect = true
    }

    private fun emitReconnect() {
        val now = SystemClock.elapsedRealtime()
        while (reconnects.isNotEmpty() && now - reconnects.first() > WINDOW_MS) reconnects.removeFirst()
        if (reconnects.size >= MAX_RECONNECTS) {
            Log.w(TAG, "not reconnecting: $MAX_RECONNECTS restarts within a minute")
            return
        }
        reconnects.addLast(now)
        _events.tryEmit(SessionEvent.ReconnectAfterRestart)
    }

    // ---- the session thread ----

    private fun post(block: () -> Unit) {
        exec.execute { guarded(block) }
    }

    private fun later(delayMs: Long, block: () -> Unit): ScheduledFuture<*> =
        exec.schedule(Runnable { guarded(block) }, delayMs, TimeUnit.MILLISECONDS)

    // a failing event is logged and does not stop the thread
    private fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "session event failed", t)
        }
    }

    companion object {
        @Volatile
        private var instance: Pm3Session? = null

        /** The session of this process. For the UI only: the service process has the session itself and never needs a proxy. */
        fun get(ctx: Context): Pm3Session {
            check(!ProcessInfo.isService) { "Pm3Session is for the UI process; this is the service process (${ProcessInfo.name})" }
            return instance ?: synchronized(this) {
                instance ?: Pm3Session(ctx.applicationContext).also { instance = it }
            }
        }
    }
}
