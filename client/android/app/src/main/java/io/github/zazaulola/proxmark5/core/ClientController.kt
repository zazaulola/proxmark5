package io.github.zazaulola.proxmark5.core

import android.content.Context
import io.github.zazaulola.proxmark5.parse.TerminalBuffer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
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

    @Volatile
    private var started = false

    @Volatile
    var clientVersion: String = ""
        private set

    /** Starts the client and the output reader. Safe to call more than once. */
    suspend fun ensureStarted() = withContext(dispatcher) {
        if (started) return@withContext
        ResourceUnpacker.unpack(home)
        val fd = NativeClient.nativeInit(home.absolutePath)
        check(fd >= 0) { "native init failed" }
        clientVersion = NativeClient.nativeClientVersion()
        startReader(fd)
        started = true
    }

    private fun startReader(fd: Int) {
        val stream = NativeClient.outputStream(fd)
        Thread({
            val buf = ByteArray(8192)
            try {
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    terminal.append(String(buf, 0, n, StandardCharsets.UTF_8))
                }
            } catch (_: Exception) {
            }
        }, "pm3-output").apply { isDaemon = true }.start()
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
            val code = NativeClient.nativeExec(cmd, grab)
            val grabbed = if (grab) String(NativeClient.nativeTakeGrabbed(), StandardCharsets.UTF_8) else ""
            ExecResult(code, grabbed)
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
