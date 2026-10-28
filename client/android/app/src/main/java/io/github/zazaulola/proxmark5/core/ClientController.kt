package io.github.zazaulola.proxmark5.core

import android.content.Context
import android.util.Log
import io.github.zazaulola.proxmark5.parse.CommandNode
import io.github.zazaulola.proxmark5.parse.CommandTree
import io.github.zazaulola.proxmark5.parse.TerminalBuffer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Result of one client command. */
data class ExecResult(val code: Int, val grabbed: String) {
    val ok get() = code == 0
}

/**
 * Owns the single client thread and the live console text. Every native call is
 * posted to that one thread, matching the client's single-threaded design.
 *
 * Process-wide: the native client keeps global state and redirects the process
 * stdout, so there is one controller for the whole app ([get]).
 */
class ClientController private constructor(context: Context) {

    private val dispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "pm3-client") }.asCoroutineDispatcher()

    /** The whole console as it streams in, including colors. */
    val terminal = TerminalBuffer()

    private val running = AtomicBoolean(false)
    private val home = File(context.filesDir, "pm3home").apply { mkdirs() }

    /**
     * Where the client saves dumps. The app's external files dir is reachable from a file
     * manager (Android/data/<pkg>/files/dumps); if there is no external storage we fall back
     * to a folder under $HOME. Shown to the user so they know where a read card lands.
     */
    val dumpDir: String = (context.getExternalFilesDir(null)?.let { File(it, "dumps") }
        ?: File(home, "dumps")).apply { mkdirs() }.absolutePath

    /** Where the app saves terminal logs; the Library lists them. */
    val logsDir: String = (context.getExternalFilesDir(null)?.let { File(it, "logs") }
        ?: File(home, "logs")).apply { mkdirs() }.absolutePath

    @Volatile
    private var started = false

    private val _ready = MutableStateFlow(false)

    /** Flips to true once the client is up and [clientVersion] / [clientArmSrc] are known. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    @Volatile
    var clientVersion: String = ""
        private set

    /** See [NativeClient.nativeClientArmSrc]. */
    @Volatile
    var clientArmSrc: String = ""
        private set

    /** Starts the client and the output reader. Safe to call more than once. */
    suspend fun ensureStarted() = withContext(dispatcher) {
        if (started) return@withContext
        ResourceUnpacker.unpack(home)
        val fd = NativeClient.nativeInit(home.absolutePath, dumpDir)
        check(fd >= 0) { "native init failed" }
        clientVersion = NativeClient.nativeClientVersion()
        clientArmSrc = NativeClient.nativeClientArmSrc()
        startReader(fd)
        started = true
        _ready.value = true
    }

    private fun startReader(fd: Int) {
        // Drain the client's stdout pipe into the terminal. Read RAW BYTES and decode UTF-8
        // incrementally, appending each chunk the moment read() returns. Do NOT wrap the fd in
        // an InputStreamReader: on Android its StreamDecoder treats a FileInputStream as always
        // "ready", so read(char[]) blocks until it has filled the whole buffer (or hits EOF,
        // which never comes on a live pipe) and strands normal sub-buffer output — the terminal
        // stayed empty. The carry ByteBuffer keeps a multibyte glyph split across reads intact.
        val stream = NativeClient.outputStream(fd)
        Thread({
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
            val raw = ByteArray(8192)
            val bb = ByteBuffer.allocate(raw.size * 2)
            val cb = CharBuffer.allocate(raw.size * 2)
            Log.i("pm3-output", "reader started, fd=$fd")
            var total = 0L
            try {
                while (true) {
                    val n = stream.read(raw)
                    if (n < 0) break
                    if (total == 0L) Log.i("pm3-output", "first $n bytes from client")
                    total += n
                    bb.put(raw, 0, n)
                    bb.flip()
                    decoder.decode(bb, cb, false)
                    cb.flip()
                    if (cb.hasRemaining()) terminal.append(cb.toString())
                    cb.clear()
                    bb.compact()
                }
                Log.i("pm3-output", "reader hit EOF after $total bytes")
            } catch (e: Exception) {
                Log.w("pm3-output", "reader stopped after $total bytes", e)
            } finally {
                // Close the read end so any later client write fails fast with EPIPE instead of
                // blocking forever on a full pipe: a dead reader must never wedge the client.
                runCatching { stream.close() }
            }
        }, "pm3-output").apply { isDaemon = true }.start()
    }

    /**
     * The client's command tree. Queued behind a running command like any native call, and
     * what is available depends on the connection, so ask again when that changes.
     */
    suspend fun commandTree(): CommandNode = withContext(dispatcher) {
        ensureStarted()
        CommandTree.parse(String(NativeClient.nativeCommandTree(), StandardCharsets.UTF_8))
    }

    /** A line of the app's own in the log (connected, link lost, ...), apart from the client's output. */
    fun note(text: String) {
        terminal.append("\n[\u001b[36msession\u001b[0m] $text\n")
    }

    suspend fun connect(port: String): Boolean = withContext(dispatcher) {
        ensureStarted()
        NativeClient.nativeOpen(port)
    }

    suspend fun isConnected(): Boolean = withContext(dispatcher) {
        started && NativeClient.nativeIsConnected()
    }

    suspend fun disconnect() = withContext(dispatcher) {
        if (started) NativeClient.nativeClose()
    }

    /**
     * Runs one command on the client thread. [grab] keeps the ANSI-free output for the
     * caller; it always streams to [terminal] too. Rejects re-entrant calls.
     */
    suspend fun exec(cmd: String, grab: Boolean = true): ExecResult = withContext(dispatcher) {
        ensureStarted()
        if (!running.compareAndSet(false, true)) {
            return@withContext ExecResult(-1, "")
        }
        try {
            terminal.append("\n[\u001b[32musb\u001b[0m] pm3 --> $cmd\n")
            Log.i("pm3-exec", "run: $cmd (grab=$grab)")
            val code = NativeClient.nativeExec(cmd, grab)
            Log.i("pm3-exec", "done: $cmd -> $code")
            val grabbed = if (grab) String(NativeClient.nativeTakeGrabbed(), StandardCharsets.UTF_8) else ""
            ExecResult(code, grabbed)
        } finally {
            running.set(false)
        }
    }

    /**
     * Flashes ELF images, see [NativeClient.nativeFlash]. The client must not be connected:
     * the flasher opens [port] itself. Returns -1 while another command runs.
     */
    suspend fun flash(port: String, files: List<String>, unlockBootloader: Boolean, force: Boolean): Int =
        exclusive("flash ${files.joinToString(" ") { it.substringAfterLast('/') }}") {
            NativeClient.nativeFlash(port, files.toTypedArray(), unlockBootloader, force)
        }

    /** Writes a raw image through the PM5 DFU loader, see [NativeClient.nativeDfuFlash]. */
    suspend fun dfuFlash(port: String, file: String, address: Int = NativeClient.DFU_ADDRESS): Int =
        exclusive("dfu ${file.substringAfterLast('/')}") {
            NativeClient.nativeDfuFlash(port, file, address)
        }

    private suspend fun exclusive(label: String, block: () -> Int): Int = withContext(dispatcher) {
        ensureStarted()
        if (!running.compareAndSet(false, true)) {
            return@withContext -1
        }
        try {
            terminal.append("\n[\u001b[32musb\u001b[0m] pm3 --> $label\n")
            block()
        } finally {
            running.set(false)
        }
    }

    /** True while a command is running. */
    val isBusy get() = running.get()

    /** Interrupts a long-running command, like pressing Enter in the console. */
    fun abort() {
        if (started) NativeClient.nativeAbort()
    }

    companion object {
        @Volatile
        private var instance: ClientController? = null

        fun get(context: Context): ClientController =
            instance ?: synchronized(this) {
                instance ?: ClientController(context.applicationContext).also { instance = it }
            }
    }
}
