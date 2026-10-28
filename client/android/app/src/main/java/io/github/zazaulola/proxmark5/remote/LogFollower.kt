package io.github.zazaulola.proxmark5.remote

import android.content.Context
import android.util.Log
import io.github.zazaulola.proxmark5.ipc.SessionPaths
import io.github.zazaulola.proxmark5.parse.TerminalBuffer
import io.github.zazaulola.proxmark5.session.LogTail
import io.github.zazaulola.proxmark5.session.Utf8Chunker
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Feeds the terminal from the service's log file (design 5.1, "Лог"): one thread that follows
 * out-<epoch>.log with [LogTail] and decodes it with [Utf8Chunker]. It reads every [POLL_MS] while
 * [setReading] says the session is bound and the UI is visible, and sleeps otherwise; the rest of
 * its work (a new epoch, the last words of a dead service, a clear) comes as tasks and is done in
 * the order given, whatever the reading state.
 */
internal class LogFollower(private val app: Context, private val terminal: TerminalBuffer) {
    private val tasks = LinkedBlockingQueue<() -> Unit>()

    @Volatile
    private var active = false

    // the follower thread only
    private var epoch = 0L
    private var tail: LogTail? = null
    private val chunker = Utf8Chunker()
    private var skipped = 0L

    fun start() {
        Thread({ loop() }, "pm3-ui-log").apply {
            isDaemon = true
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { t, e -> Log.e(TAG, "uncaught in ${t.name}", e) }
        }.start()
    }

    /** Follow the log of the service life with this [epoch]. A new one finishes the old file first, then starts the new at its beginning. */
    fun follow(next: Long) = submit { switchTo(next) }

    /** Read as the file grows (true), or only when asked to (false). */
    fun setReading(on: Boolean) {
        active = on
        submit { }
    }

    /** Read to the end of the file now: the service is gone and this is the last it said. */
    fun drain() = submit { drainAll() }

    /** A line of the session's own, after everything the file has so far. */
    fun note(text: String) = submit {
        drainAll()
        terminal.append(noteText(text))
    }

    /** The log was cleared: what is in the file now is old, so the terminal does not show it. */
    fun clear() = submit {
        discard()
        terminal.clear()
    }

    private fun submit(task: () -> Unit) {
        tasks.offer(task)
    }

    private fun loop() {
        var more = false
        try {
            while (true) {
                var task = when {
                    more -> tasks.poll()
                    active -> tasks.poll(POLL_MS, TimeUnit.MILLISECONDS)
                    else -> tasks.take()
                }
                while (task != null) {
                    runTask(task)
                    task = tasks.poll()
                }
                more = false
                if (active) {
                    try {
                        more = pump()
                    } catch (t: Throwable) {
                        Log.e(TAG, "cannot read the log", t)
                    }
                }
            }
        } catch (_: InterruptedException) {
            // the thread is never interrupted; if it is, it ends
        }
    }

    private fun runTask(task: () -> Unit) {
        try {
            task()
        } catch (t: Throwable) {
            Log.e(TAG, "log task failed", t)
        }
    }

    private fun switchTo(next: Long) {
        if (next == epoch && tail != null) return
        if (tail != null) {
            drainAll()
            terminal.append(noteText("клиент перезапущен"))
        }
        epoch = next
        tail = LogTail(SessionPaths.logFile(app, next))
        chunker.reset()
        skipped = 0
    }

    // A bounded amount per pass, so a task never waits behind a flood. True when the budget ran out and more may wait.
    private fun pump(): Boolean {
        val t = tail ?: return false
        var budget = PUMP_BYTES
        while (budget > 0) {
            val chunk = t.read() ?: return false
            consume(chunk)
            budget -= maxOf(chunk.bytes.size, 1)
        }
        return true
    }

    private fun drainAll() {
        val t = tail ?: return
        while (true) consume(t.read() ?: break)
    }

    private fun discard() {
        val t = tail ?: return
        while (t.read() != null) {
            // the bytes written before the clear are not wanted
        }
        chunker.reset()
        skipped = 0
    }

    private fun consume(chunk: LogTail.Chunk) {
        // the file was emptied under us: a glyph that was half read has no other half
        if (chunk.truncated) chunker.reset()
        skipped += chunk.skipped
        if (chunk.bytes.isEmpty()) return
        if (skipped > 0) {
            terminal.append(noteText("пропущено $skipped байт лога"))
            skipped = 0
        }
        val text = chunker.feed(chunk.bytes)
        if (text.isNotEmpty()) terminal.append(text)
    }

    // the same look as the notes the service writes itself (service/LogFile.kt)
    private fun noteText(text: String) = "\n[\u001b[36mсессия\u001b[0m] $text\n"

    private companion object {
        const val TAG = "pm3-log"
        const val POLL_MS = 80L
        const val PUMP_BYTES = 1024 * 1024
    }
}
