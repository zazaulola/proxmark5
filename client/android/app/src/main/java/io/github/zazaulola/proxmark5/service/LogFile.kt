package io.github.zazaulola.proxmark5.service

import android.content.Context
import android.util.Log
import io.github.zazaulola.proxmark5.ipc.SessionPaths
import java.io.File
import java.io.FileFilter
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * The session log: the file the client's stdout and stderr are appended to (the native side opens
 * it O_APPEND, see nativeInit) and the lines the service adds to it itself. A line is one write,
 * so the client's output can not tear it. The UI process tails this file.
 */
internal class LogFile(private val app: Context, private val epoch: Long) {
    // the directory is made on first use, which is on a service thread, not on the main one
    private val file: File by lazy { SessionPaths.logFile(app, epoch) }

    val path: String get() = file.absolutePath

    /** What the client was asked to run. FlashLog looks for "pm3 --> flash" and "pm3 --> dfu" in these. */
    fun echo(cmd: String) = append("\n[\u001b[32musb\u001b[0m] pm3 --> $cmd\n")

    /** A line of the session's own: connected, link lost, killed, ... */
    fun note(text: String) = append("\n$PREFIX $text\n")

    /** Empties the file; the client keeps appending to it from the start. */
    fun clear() {
        try {
            RandomAccessFile(file, "rw").use { it.setLength(0) }
        } catch (e: IOException) {
            Log.w(TAG, "cannot clear the log", e)
        }
    }

    /** A phone should not fill up with a log nobody reads: past 32 MiB it starts again. */
    fun trimIfTooBig() {
        if (file.length() <= MAX_BYTES) return
        clear()
        append("$PREFIX журнал обрезан по размеру\n")
    }

    /** Deletes the logs of earlier lives of the service but the newest: it holds the last words of a client that died. */
    fun pruneOld() {
        val others = file.parentFile?.listFiles(FileFilter { it.name.startsWith("out-") && it.name.endsWith(".log") && it != file })
            ?: return
        others.sortedByDescending { it.lastModified() }.drop(1).forEach { it.delete() }
    }

    private fun append(text: String) {
        try {
            FileOutputStream(file, true).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        } catch (e: IOException) {
            Log.w(TAG, "cannot write to the log", e)
        }
    }

    private companion object {
        const val TAG = "pm3-log"
        const val MAX_BYTES = 32L * 1024 * 1024
        const val PREFIX = "[\u001b[36mсессия\u001b[0m]"
    }
}
