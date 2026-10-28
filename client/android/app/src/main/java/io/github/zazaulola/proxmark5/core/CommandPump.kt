package io.github.zazaulola.proxmark5.core

import io.github.zazaulola.proxmark5.parse.Activities
import io.github.zazaulola.proxmark5.parse.ActivityKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/** What the live session is doing right now. */
data class Activity(
    val kind: ActivityKind = ActivityKind.IDLE,
    val command: String = "",
    val startedAtMs: Long = 0L,
    /** Commands waiting behind the running one. */
    val queued: Int = 0,
) {
    val running get() = kind != ActivityKind.IDLE
}

/**
 * Feeds the one client session: commands go in a queue and run one after another, and the
 * caller never waits for them. A long command (sniff, emulation, hardnested) just runs;
 * [activity] says what it is, and [stop] ends it, like pressing Enter in the console. Its
 * output streams to the terminal on its own.
 */
class CommandPump(scope: CoroutineScope, private val client: ClientController) {
    private class Entry(val cmd: String, val grab: Boolean, val done: CompletableDeferred<ExecResult>)

    private val queue = Channel<Entry>(Channel.UNLIMITED)
    private val waiting = AtomicInteger(0)
    private val stops = AtomicInteger(0)

    private val _activity = MutableStateFlow(Activity())
    val activity: StateFlow<Activity> = _activity.asStateFlow()

    init {
        scope.launch {
            for (entry in queue) {
                waiting.decrementAndGet()
                _activity.value = Activity(
                    kind = Activities.of(entry.cmd),
                    command = entry.cmd,
                    startedAtMs = System.currentTimeMillis(),
                    queued = waiting.get(),
                )
                val result = try {
                    client.exec(entry.cmd, entry.grab)
                } catch (e: CancellationException) {
                    entry.done.cancel()
                    throw e
                } catch (e: Exception) {
                    ExecResult(-1, "")
                }
                entry.done.complete(result)
                _activity.value = Activity(queued = waiting.get())
            }
        }
    }

    /**
     * Puts [cmd] behind whatever is running. The result arrives when it has run; it is
     * `ExecResult(-1, "")` if the command was dropped by [stop] or the client was busy.
     * [grab] also keeps the ANSI-free output for the caller.
     */
    fun submit(cmd: String, grab: Boolean = false): Deferred<ExecResult> {
        val entry = Entry(cmd.trim(), grab, CompletableDeferred())
        waiting.incrementAndGet()
        _activity.update { it.copy(queued = waiting.get()) }
        queue.trySend(entry)
        return entry.done
    }

    /**
     * Counts [stop] calls. A caller that chains commands reads it before the first one and
     * compares after each: if it changed, the chain was cut short and must not go on.
     */
    val stopCount: Int get() = stops.get()

    /** Ends the running command and drops everything still waiting. */
    fun stop() {
        stops.incrementAndGet()
        drain()
        client.abort()
    }

    private fun drain() {
        while (true) {
            val entry = queue.tryReceive().getOrNull() ?: break
            waiting.decrementAndGet()
            entry.done.complete(ExecResult(-1, ""))
        }
        _activity.update { it.copy(queued = waiting.get()) }
    }
}
