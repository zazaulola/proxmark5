package io.github.zazaulola.proxmark5.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import io.github.zazaulola.proxmark5.ipc.IPm3Listener
import io.github.zazaulola.proxmark5.ipc.IPm3Service
import io.github.zazaulola.proxmark5.ipc.Pm3Ipc

/**
 * The thin Android shell of the session (process :pm3): it is bound by the UI, started in the
 * foreground by an Intent, and shows the notification. All the logic is in [SessionHost], which is
 * the process's and outlives this service; destroying the service does not touch the session.
 *
 * The main thread does lifecycle calls and the notification only: no JNI, no USB, no disk.
 */
class Pm3Service : Service() {
    private lateinit var host: SessionHost
    private lateinit var notification: ServiceNotification

    private val main = Handler(Looper.getMainLooper())

    // in the foreground since a startForegroundService intent; main thread only, like the rest of these
    private var foreground = false
    private var idleArmed = false
    private var lastStartId = 0

    private val refreshTask = Runnable { refresh() }
    private val idleTask = Runnable {
        idleArmed = false
        if (host.needsFgs) refresh() else stopWork()
    }

    private val shell = object : SessionHost.Shell {
        override fun changed() {
            main.post(refreshTask)
        }
    }

    // the whole interface: every call is handed to the host, which copies and queues
    private val binder = object : IPm3Service.Stub() {
        override fun attach(listener: IPm3Listener?): String = host.attach(listener)
        override fun detach(listener: IPm3Listener?) = host.detach(listener)
        override fun snapshot(): String = host.snapshotJson
        override fun submit(id: Long, cmd: String?, grab: Boolean) = host.submit(id, cmd, grab)
        override fun commandTree(id: Long) = host.commandTree(id)
        override fun stop() = host.stop()
        override fun disconnect() = host.disconnect()
        override fun restartClient(reason: String?) = host.restartClient(reason)
        override fun flash(id: Long, deviceName: String?, files: Array<String>?, unlockBootloader: Boolean, force: Boolean) =
            host.flash(id, deviceName, files, unlockBootloader, force)

        override fun dfuFlash(id: Long, deviceName: String?, image: String?) = host.dfuFlash(id, deviceName, image)
        override fun flashEnd() = host.flashEnd()
        override fun note(text: String?) = host.note(text)
        override fun clearLog() = host.clearLog()
    }

    override fun onCreate() {
        super.onCreate()
        host = SessionHost.get(this)
        notification = ServiceNotification(this)
        host.shell = shell
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // A start that arrives as the idle timer runs out must not be stopped before the host has seen it
        disarmIdle()
        if (intent != null) {
            when (intent.action) {
                Pm3Ipc.ACTION_BEGIN -> begin(intent)
                Pm3Ipc.ACTION_STOP -> host.stop()
                Pm3Ipc.ACTION_EXIT -> host.disconnect()
            }
        }
        // nothing to hold after this start (a stray button, a superfluous Begin): the service goes away shortly
        refresh()
        // a restart by the system would run without a Begin and with no foreground to show for it
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (host.shell === shell) host.shell = null
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // 3.3: every startForegroundService must reach startForeground, first thing, whether or not the work turns out to be needed
    private fun begin(intent: Intent) {
        val refused = enterForeground()
        if (refused != null) {
            host.foregroundRefused(refused)
            // Android wants a startForeground or a stop soon after startForegroundService; a service that
            // is in the foreground already (a live session) is not touched
            if (!foreground) stopSelf(lastStartId)
            return
        }
        host.begin(intent.getIntExtra(Pm3Ipc.EXTRA_KIND, Pm3Ipc.KIND_CONNECT), intent.getStringExtra(Pm3Ipc.EXTRA_DEVICE))
    }

    /** Null when the service is in the foreground, else why Android refused. */
    private fun enterForeground(): String? = try {
        ServiceCompat.startForeground(
            this, ServiceNotification.ID, notification.build(host.snapshot), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        foreground = true
        null
    } catch (e: RuntimeException) {
        // SecurityException (the prerequisites of the type), IllegalStateException (a start from the background), ...
        e.message ?: e.javaClass.simpleName
    }

    // Runs on every change of the snapshot: shows it and keeps the foreground only while it is needed.
    // The service leaves it five seconds after the need ends, so a command chain does not make it flicker.
    private fun refresh() {
        if (host.needsFgs) {
            disarmIdle()
        } else if (lastStartId != 0 && !idleArmed) {
            // (a service that was only bound has nothing to stop)
            idleArmed = true
            main.postDelayed(idleTask, IDLE_DELAY_MS)
        }
        if (foreground) notification.update(host.snapshot)
    }

    private fun disarmIdle() {
        main.removeCallbacks(idleTask)
        idleArmed = false
    }

    private fun stopWork() {
        if (foreground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        // not a start that came later than the one we know of
        stopSelf(lastStartId)
    }

    private companion object {
        const val IDLE_DELAY_MS = 5_000L
    }
}
