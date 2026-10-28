package io.github.zazaulola.proxmark5.service

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

/**
 * The partial wake lock that keeps the CPU (and so the USB link and the client) running with the
 * screen off while a command runs. It times out on its own after six hours, so a lost release can
 * not hold the phone awake for ever; [set] arms it again while it is still wanted. Host thread only.
 */
internal class WakeLockHolder(app: Context) {
    private val lock = (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Proxmark5:session")
        .apply { setReferenceCounted(false) }

    /** elapsedRealtime of the last acquire, 0 while not held */
    private var armedAt = 0L

    fun set(wanted: Boolean) {
        try {
            if (!wanted) {
                if (armedAt != 0L) lock.release()
                armedAt = 0L
                return
            }
            val now = SystemClock.elapsedRealtime()
            if (armedAt == 0L || now - armedAt > REARM_MS) {
                lock.acquire(TIMEOUT_MS)
                armedAt = now
            }
        } catch (e: RuntimeException) {
            Log.w("pm3-service", "wake lock", e)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 6 * 60 * 60_000L
        const val REARM_MS = 5 * 60_000L
    }
}
