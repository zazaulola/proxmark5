package io.github.zazaulola.proxmark5.history

import io.github.zazaulola.proxmark5.parse.output.FoundKind
import io.github.zazaulola.proxmark5.parse.output.FoundRef
import io.github.zazaulola.proxmark5.session.ResultCode
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** How the last run of a history line ended. */
enum class RunOutcome {
    /** Sent, no answer yet (or the app went away before it came: read back as [UNKNOWN]). */
    PENDING,

    /** The client said PM3_SUCCESS. */
    OK,

    /** The client answered with an error code: bad parameters, no card, a failed operation. */
    FAILED,

    /** Stop, leaving the session or a pulled cable cut it short. */
    STOPPED,

    /** The client's process died or the answer was lost. */
    UNKNOWN;

    companion object {
        fun of(code: Int): RunOutcome = when (code) {
            0 -> OK
            ResultCode.DROPPED -> STOPPED
            ResultCode.TRANSPORT_LOST -> UNKNOWN
            else -> FAILED
        }
    }
}

/**
 * One command line the user ran, with how often and when, how its last run ended and what its
 * output showed (UIDs, keys, files: [found], a few at most). Identified by its [line].
 */
data class HistoryEntry(
    val line: String,
    val lastAt: Long,
    val count: Int = 1,
    val pinned: Boolean = false,
    val outcome: RunOutcome = RunOutcome.PENDING,
    val code: Int? = null,
    val found: List<FoundRef> = emptyList(),
)

/**
 * The command history: newest first, one entry per distinct line (running a line again moves it
 * to the top), pinned entries are never pushed out. Immutable: every change returns a new value.
 */
class CommandHistory private constructor(val entries: List<HistoryEntry>) {

    val isEmpty: Boolean get() = entries.isEmpty()

    /** [line] was sent: on top, counted, its outcome pending until [finish]. */
    fun record(line: String, now: Long): CommandHistory {
        val key = HistoryLine.normalize(line)
        if (key.isEmpty()) return this
        val old = entries.firstOrNull { it.line == key }
        val entry = old?.copy(lastAt = now, count = old.count + 1, outcome = RunOutcome.PENDING, code = null)
            ?: HistoryEntry(key, now)
        return CommandHistory(trim(listOf(entry) + entries.filter { it.line != key }))
    }

    /**
     * The run of [line] ended with [code]; [found] is what its output showed. An entry removed
     * meanwhile stays removed. Found items are kept from the run before when this one showed none
     * because it was cut short: a Stop must not wipe what the last good run found.
     */
    fun finish(line: String, code: Int, found: List<FoundRef>): CommandHistory {
        val key = HistoryLine.normalize(line)
        val outcome = RunOutcome.of(code)
        return map(key) { e ->
            val keep = if (found.isEmpty() && outcome != RunOutcome.OK) e.found else found.take(MAX_FOUND)
            e.copy(outcome = outcome, code = code.takeIf { outcome == RunOutcome.OK || outcome == RunOutcome.FAILED }, found = keep)
        }
    }

    fun pin(line: String, on: Boolean): CommandHistory = map(line) { it.copy(pinned = on) }

    fun remove(line: String): CommandHistory = CommandHistory(entries.filter { it.line != line })

    /** Forgets everything but the pinned lines. */
    fun clearUnpinned(): CommandHistory = CommandHistory(entries.filter { it.pinned })

    /** The quick row over the command input: pinned lines first, then the newest others. */
    fun quick(limit: Int): List<HistoryEntry> {
        val pinned = entries.filter { it.pinned }
        return (pinned + entries.filter { !it.pinned }).take(limit)
    }

    /** The previous runs of one command (its words, as in the Functions tree), newest first. */
    fun forCommand(command: String): List<HistoryEntry> {
        val words = command.trim().lowercase().split(WS).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return entries.filter { e ->
            val toks = HistoryLine.tokens(e.line).map { it.lowercase() }
            toks.size >= words.size && toks.subList(0, words.size) == words
        }
    }

    /** Every word of [query] in the line or in what it found, ignoring case; all entries for a blank query. */
    fun search(query: String): List<HistoryEntry> {
        val words = query.lowercase().split(WS).filter { it.isNotEmpty() }
        if (words.isEmpty()) return entries
        return entries.filter { e ->
            val hay = (e.line + " " + e.found.joinToString(" ") { it.value }).lowercase()
            words.all { hay.contains(it) }
        }
    }

    fun toJson(): String {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject().apply {
                    put("line", e.line)
                    put("at", e.lastAt)
                    put("n", e.count)
                    if (e.pinned) put("pin", true)
                    put("out", e.outcome.name)
                    e.code?.let { put("code", it) }
                    if (e.found.isNotEmpty()) {
                        put("found", JSONArray().apply {
                            for (f in e.found) put(JSONObject().apply {
                                put("k", f.kind.name)
                                put("v", f.value)
                                f.label?.let { put("l", it) }
                            })
                        })
                    }
                }
            )
        }
        return JSONObject().put("v", VERSION).put("entries", arr).toString()
    }

    private fun map(line: String, f: (HistoryEntry) -> HistoryEntry) =
        CommandHistory(entries.map { if (it.line == line) f(it) else it })

    companion object {
        const val VERSION = 1

        /** Unpinned lines kept; the oldest go first. */
        const val MAX_ENTRIES = 300

        /** Pinned lines kept at most (a guard, nobody pins this many). */
        const val MAX_PINNED = 100

        /** Found items kept per entry. */
        const val MAX_FOUND = 8

        private val WS = Regex("\\s+")

        val EMPTY = CommandHistory(emptyList())

        private fun trim(list: List<HistoryEntry>): List<HistoryEntry> {
            var unpinned = 0
            var pinned = 0
            return list.filter { e ->
                if (e.pinned) ++pinned <= MAX_PINNED else ++unpinned <= MAX_ENTRIES
            }
        }

        /**
         * Reads what [toJson] wrote. A run that was pending when the app went away is UNKNOWN now;
         * unreadable entries are skipped, an unreadable file gives an empty history.
         */
        fun fromJson(text: String): CommandHistory {
            val root = try {
                JSONObject(text)
            } catch (e: JSONException) {
                return EMPTY
            }
            val arr = root.optJSONArray("entries") ?: return EMPTY
            val out = ArrayList<HistoryEntry>()
            val seen = HashSet<String>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val line = HistoryLine.normalize(o.optString("line", ""))
                if (line.isEmpty() || !seen.add(line)) continue
                val outcome = runCatching { RunOutcome.valueOf(o.optString("out", "")) }.getOrDefault(RunOutcome.UNKNOWN)
                val found = ArrayList<FoundRef>()
                o.optJSONArray("found")?.let { fa ->
                    for (j in 0 until fa.length()) {
                        val fo = fa.optJSONObject(j) ?: continue
                        val kind = runCatching { FoundKind.valueOf(fo.optString("k", "")) }.getOrNull() ?: continue
                        val v = fo.optString("v", "")
                        if (v.isEmpty()) continue
                        found += FoundRef(kind, v, if (fo.has("l") && !fo.isNull("l")) fo.optString("l") else null)
                    }
                }
                out += HistoryEntry(
                    line = line,
                    lastAt = o.optLong("at", 0L),
                    count = o.optInt("n", 1).coerceAtLeast(1),
                    pinned = o.optBoolean("pin", false),
                    outcome = if (outcome == RunOutcome.PENDING) RunOutcome.UNKNOWN else outcome,
                    code = if (o.has("code") && !o.isNull("code")) o.optInt("code") else null,
                    found = found.take(MAX_FOUND),
                )
            }
            return CommandHistory(trim(out.sortedByDescending { it.lastAt }))
        }
    }
}

/** How history sees a command line. */
object HistoryLine {
    /**
     * The line as history keeps it: trimmed, runs of blanks outside quotes made one space (the
     * client reads the same thing; the app never sends runs of blanks), nothing else changed.
     */
    fun normalize(line: String): String {
        val s = line.trim()
        if (s.isEmpty()) return ""
        val sb = StringBuilder(s.length)
        var quote: Char? = null
        var blank = false
        for (c in s) {
            if (quote == null && (c == ' ' || c == '\t')) {
                blank = true
                continue
            }
            if (blank) {
                sb.append(' ')
                blank = false
            }
            if (quote == null && (c == '"' || c == '\'') && (sb.isEmpty() || sb.last() == ' ')) {
                quote = c
            } else if (quote != null && (c == '"' || c == '\'')) {
                quote = null
            }
            sb.append(c)
        }
        return sb.toString()
    }

    /** Space-separated words, quotes kept: enough to match command words. */
    fun tokens(line: String): List<String> = line.trim().split(' ', '\t').filter { it.isNotEmpty() }
}
