package io.github.zazaulola.proxmark5.service

import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/**
 * "pm3-ctl": where Stop talks to the client. Enter and BREAK may wait for a write to the USB device for
 * seconds, so they come from here and from nowhere else: never a binder thread, the main thread or
 * the thread that runs commands. It repeats them until the command it was asked about is over.
 */
internal class ControlThread(private val engine: ClientEngine, private val canBreak: () -> Boolean) {
    private val thread = HandlerThread("pm3-ctl").apply {
        uncaughtExceptionHandler = logUncaught
        start()
    }
    private val handler = Handler(thread.looper)

    // runs being worked on, touched by the pm3-ctl thread only
    private val active = HashSet<Long>()

    /** Presses Enter for [run], and sends BREAK when the device is there to hear it, until [run] ends. */
    fun interrupt(run: Run) {
        handler.post {
            if (active.add(run.seq)) nudge(run)
        }
    }

    private fun nudge(run: Run) {
        val again = try {
            engine.signal(run, canBreak())
        } catch (t: Throwable) {
            Log.w(TAG, "cannot signal the client", t)
            false
        }
        if (again) handler.postDelayed({ nudge(run) }, RETRY_MS) else active.remove(run.seq)
    }

    private companion object {
        const val TAG = "pm3-ctl"
        const val RETRY_MS = 400L
    }
}
