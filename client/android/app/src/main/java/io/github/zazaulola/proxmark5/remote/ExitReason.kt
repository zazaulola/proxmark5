package io.github.zazaulola.proxmark5.remote

import android.annotation.TargetApi
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import io.github.zazaulola.proxmark5.core.ProcessInfo

/** Why the service process ended, in words, from what Android keeps about the exits of the app's processes. */
internal object ExitReason {
    private const val MAX_RECORDS = 8

    private val SIGNALS = mapOf(4 to "SIGILL", 6 to "SIGABRT", 7 to "SIGBUS", 8 to "SIGFPE", 9 to "SIGKILL", 11 to "SIGSEGV", 15 to "SIGTERM")

    /**
     * The reason process [pid] of the service died, or null: before Android 11 there is no such record, and
     * right after a death it may not be written yet. A record older than [sinceMs] is another life of
     * a process that had this pid, and is never taken. Calls the system: not for the session thread.
     */
    fun find(ctx: Context, pid: Int, sinceMs: Long): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            lookup(ctx, pid, sinceMs)
        } catch (_: RuntimeException) {
            null
        }
    }

    @TargetApi(Build.VERSION_CODES.R)
    private fun lookup(ctx: Context, pid: Int, sinceMs: Long): String? {
        val manager = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val process = ProcessInfo.servicePackageProcess(ctx)
        return manager.getHistoricalProcessExitReasons(ctx.packageName, pid, MAX_RECORDS)
            .filter { it.pid == pid && it.processName == process && it.timestamp >= sinceMs }
            .maxByOrNull { it.timestamp }
            ?.let { describe(it) }
    }

    @TargetApi(Build.VERSION_CODES.R)
    private fun describe(info: ApplicationExitInfo): String = when (info.reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> "не хватило памяти"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "сбой в коде клиента (${signal(info.status)})"
        ApplicationExitInfo.REASON_CRASH -> "необработанное исключение"
        ApplicationExitInfo.REASON_SIGNALED -> "процесс убит (${signal(info.status)})"
        ApplicationExitInfo.REASON_EXIT_SELF -> "клиент завершил процесс сам (код ${info.status})"
        ApplicationExitInfo.REASON_ANR -> "служба не отвечала"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "процесс не удалось запустить"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "превышен лимит ресурсов"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "изменились разрешения приложения"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "завершился связанный процесс"
        ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED -> "остановлен пользователем"
        else -> "причина неизвестна"
    }

    private fun signal(n: Int) = SIGNALS[n]?.let { "сигнал $n, $it" } ?: "сигнал $n"
}
