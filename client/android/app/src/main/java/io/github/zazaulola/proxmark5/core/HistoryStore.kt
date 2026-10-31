package io.github.zazaulola.proxmark5.core

import android.content.Context
import android.util.Log
import io.github.zazaulola.proxmark5.history.CommandHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * The command history of this device, kept in the UI process (the client's process never sees it)
 * in one small JSON file. Lines may hold card keys and passwords, so the file is in
 * noBackupFilesDir: Android's cloud backup does not take it.
 *
 * Changes are made on the main thread; the file is read once at start and written a moment after
 * the last change, off the main thread, through a temporary file so a crash never leaves half of it.
 * Changes made before the file was read are applied to what it held.
 */
class HistoryStore private constructor(context: Context) {
    private val file = File(context.noBackupFilesDir, FILE_NAME)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(CommandHistory.EMPTY)
    val state: StateFlow<CommandHistory> = _state.asStateFlow()

    // main thread only
    private var loaded = false
    private val early = ArrayList<(CommandHistory) -> CommandHistory>()
    private var saveJob: Job? = null

    // the writer: one save at a time, in order
    private val writeLock = Any()

    init {
        scope.launch {
            val read = readFile()
            launchMain {
                var h = read
                for (f in early) h = f(h)
                early.clear()
                loaded = true
                _state.value = h
                if (h !== read) scheduleSave()
            }
        }
    }

    /**
     * Runs [block] on the store's own scope, which outlives any one screen: the result of a command
     * (how it ended, what it found) must reach the history even if the screen that ran it has gone.
     */
    fun track(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    /** Changes the history; call on the main thread. */
    fun update(change: (CommandHistory) -> CommandHistory) {
        if (!loaded) early += change
        val next = change(_state.value)
        if (next === _state.value) return
        _state.value = next
        if (loaded) scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        val snapshot = _state.value
        saveJob = scope.launch {
            delay(SAVE_DELAY_MS)
            write(snapshot.toJson())
        }
    }

    private fun readFile(): CommandHistory = try {
        if (file.isFile) CommandHistory.fromJson(file.readText()) else CommandHistory.EMPTY
    } catch (e: IOException) {
        Log.w(TAG, "cannot read the history", e)
        CommandHistory.EMPTY
    }

    private fun write(text: String) = synchronized(writeLock) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        } catch (e: IOException) {
            Log.w(TAG, "cannot save the history", e)
        }
    }

    private fun launchMain(block: () -> Unit) {
        scope.launch(Dispatchers.Main) { block() }
    }

    companion object {
        private const val TAG = "pm3-history"
        private const val FILE_NAME = "command-history.json"
        private const val SAVE_DELAY_MS = 400L

        @Volatile
        private var instance: HistoryStore? = null

        fun get(context: Context): HistoryStore =
            instance ?: synchronized(this) { instance ?: HistoryStore(context.applicationContext).also { instance = it } }
    }
}
