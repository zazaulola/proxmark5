package io.github.zazaulola.proxmark5.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.zazaulola.proxmark5.R
import io.github.zazaulola.proxmark5.ipc.Pm3Ipc
import io.github.zazaulola.proxmark5.session.LinkState
import io.github.zazaulola.proxmark5.session.SessionSnapshot

/**
 * The notification of the foreground service: what the session is doing, "Стоп" and "Выйти" buttons
 * (none while a firmware is written: nothing may interrupt that), a tap opens the app. Built from a
 * snapshot only. Main thread of the service.
 */
internal class ServiceNotification(private val ctx: Context) {
    private val manager = NotificationManagerCompat.from(ctx)
    private var channelMade = false

    // what the notification on the screen says, to skip updates that would change nothing
    private var shown: String? = null

    fun build(snap: SessionSnapshot): Notification {
        makeChannel()
        shown = key(snap)
        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(text(snap))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        openApp()?.let { builder.setContentIntent(it) }
        if (!flashing(snap)) {
            builder.addAction(0, "Стоп", service(REQUEST_STOP, Pm3Ipc.ACTION_STOP))
            builder.addAction(0, "Выйти", service(REQUEST_EXIT, Pm3Ipc.ACTION_EXIT))
        }
        return builder.build()
    }

    /** Replaces the notification in place (same id), if the snapshot changed what it shows. Only while the service is in the foreground. */
    @SuppressLint("MissingPermission")
    fun update(snap: SessionSnapshot) {
        if (key(snap) == shown) return
        // without the notification permission this does nothing, and the service runs all the same
        manager.notify(ID, build(snap))
    }

    private fun flashing(snap: SessionSnapshot) = snap.link == LinkState.FLASHING || snap.flashLease

    private fun key(snap: SessionSnapshot) = "${flashing(snap)}|${text(snap)}"

    private fun text(snap: SessionSnapshot): String = when {
        flashing(snap) -> "Идёт прошивка — не отключайте кабель"
        snap.link == LinkState.OPENING -> "Подключаюсь к Proxmark…"
        // the buttons below can do no more for it; the app can (restart the client)
        snap.activity.stuck -> "Команда не останавливается — откройте приложение"
        snap.link == LinkState.CLOSING -> "Закрываю сессию…"
        snap.activity.running -> "Выполняется: " + snap.activity.command.take(MAX_COMMAND_CHARS)
        snap.link == LinkState.CONNECTED -> "Подключено"
        else -> "Сессия не активна"
    }

    private fun makeChannel() {
        if (channelMade) return
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName("Сессия с Proxmark")
            .setShowBadge(false)
            .build()
        manager.createNotificationChannel(channel)
        channelMade = true
    }

    // the launcher intent of the app, which is its main screen: no need to name a UI class here
    private fun openApp(): PendingIntent? {
        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return null
        return PendingIntent.getActivity(ctx, REQUEST_OPEN, intent, FLAGS)
    }

    private fun service(request: Int, action: String): PendingIntent =
        PendingIntent.getService(ctx, request, Intent(ctx, Pm3Service::class.java).setAction(action), FLAGS)

    companion object {
        const val ID = 1
        private const val CHANNEL_ID = "pm3_session"
        private const val REQUEST_OPEN = 0
        private const val REQUEST_STOP = 1
        private const val REQUEST_EXIT = 2
        private const val MAX_COMMAND_CHARS = 60
        private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    }
}
