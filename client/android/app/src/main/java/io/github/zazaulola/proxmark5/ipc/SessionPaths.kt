package io.github.zazaulola.proxmark5.ipc

import android.content.Context
import java.io.File

/**
 * Where the session keeps things. The UI and the service are two processes of one app and compute
 * the same paths, so nothing needs to be sent over binder to agree on them.
 */
object SessionPaths {
    /** $HOME of the client: its resources are unpacked under it. */
    fun home(ctx: Context): File = File(ctx.filesDir, "pm3home").apply { mkdirs() }

    /**
     * Where the client saves dumps. The app's external files dir is reachable from a file manager
     * (Android/data/<pkg>/files/dumps); without external storage it falls back to a folder under $HOME.
     */
    fun dumpDir(ctx: Context): File =
        (ctx.getExternalFilesDir(null)?.let { File(it, "dumps") } ?: File(home(ctx), "dumps")).apply { mkdirs() }

    /** Where the app saves terminal logs; the Library lists them. */
    fun logsDir(ctx: Context): File =
        (ctx.getExternalFilesDir(null)?.let { File(it, "logs") } ?: File(home(ctx), "logs")).apply { mkdirs() }

    /** Session files that must not be backed up: the output log of the client and the exit marker. */
    fun sessionDir(ctx: Context): File = File(ctx.noBackupFilesDir, "pm3").apply { mkdirs() }

    /** The client's stdout/stderr (and the app's notes) of the service process with this [epoch]. */
    fun logFile(ctx: Context, epoch: Long): File = File(sessionDir(ctx), "out-$epoch.log")

    /** Written by the service right before it kills its own process (parsers: ExitMarker). */
    fun exitMarker(ctx: Context): File = File(sessionDir(ctx), "exit.json")
}
