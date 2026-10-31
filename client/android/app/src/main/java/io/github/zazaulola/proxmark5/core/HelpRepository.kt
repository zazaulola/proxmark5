package io.github.zazaulola.proxmark5.core

import android.content.Context
import android.util.Log
import android.util.LruCache
import io.github.zazaulola.proxmark5.parse.CommandHelp
import io.github.zazaulola.proxmark5.parse.HelpText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

/**
 * The help of every client command, from `proxmark3 --fulltext` run on a host build of this same
 * tree when the APK was built (CI puts it in assets/pm3-help). It is read in the UI process,
 * without the client: the client's queue runs one command at a time, so `-h` would wait behind a
 * running sniff. The dump is kept as one string with an index; a command is parsed when opened.
 */
object HelpRepository {
    private const val TAG = "pm3-help"
    const val ASSET = "pm3-help/fulltext.txt"

    private class Dump(val text: String, val index: Map<String, IntRange>)

    sealed interface Lookup {
        class Found(val help: CommandHelp) : Lookup

        /** The APK has no help dump (a local build without -Ppm3.helpDump). */
        data object NoDump : Lookup

        /** The dump has no section for this command (the host build lacks it). */
        data object NotInDump : Lookup
    }

    // outlives every screen, so leaving one does not cancel the read and start it again
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loading: Deferred<Dump?>? = null
    private val parsed = LruCache<String, CommandHelp>(32)

    private fun dump(context: Context): Deferred<Dump?> = synchronized(this) {
        loading ?: scope.async { read(context.applicationContext) }.also { loading = it }
    }

    private fun read(context: Context): Dump? = try {
        val raw = context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val text = HelpText.cleanDump(raw)
        Dump(text, HelpText.indexDump(text))
    } catch (e: FileNotFoundException) {
        Log.i(TAG, "no $ASSET in this APK")
        null
    } catch (t: Throwable) {
        Log.e(TAG, "cannot read $ASSET", t)
        null
    }

    /** Starts reading the dump, so the first command opens without a wait. */
    fun warmUp(context: Context) {
        dump(context)
    }

    suspend fun lookup(context: Context, command: String): Lookup {
        parsed.get(command)?.let { return Lookup.Found(it) }
        val d = dump(context).await() ?: return Lookup.NoDump
        val range = d.index[command] ?: return Lookup.NotInDump
        val help = withContext(Dispatchers.Default) { HelpText.parseIndexed(d.text, range) } ?: return Lookup.NotInDump
        parsed.put(command, help)
        return Lookup.Found(help)
    }
}
