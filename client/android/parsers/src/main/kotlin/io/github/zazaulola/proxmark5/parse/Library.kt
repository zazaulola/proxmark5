package io.github.zazaulola.proxmark5.parse

/** The three shelves of the Library section. */
enum class LibraryKind { KEYS, DUMPS, LOGS }

object Library {
    private val KEY_NAME = Regex("(^|[-_])keys?([-_.]|$)")
    private val KEY_EXT = listOf(".dic", ".keys")
    private val LOG_EXT = listOf(".log", ".trace", ".txt")

    /**
     * Which shelf a file the client saved belongs to, judged by its name: key files
     * (`hf-mf-04A2B3C4-key.bin`, dictionaries), RF traces and logs, and everything else
     * as a dump. Null for hidden files.
     */
    fun classify(fileName: String): LibraryKind? {
        if (fileName.isEmpty() || fileName.startsWith(".")) return null
        val lower = fileName.lowercase()
        return when {
            KEY_EXT.any { lower.endsWith(it) } || KEY_NAME.containsMatchIn(lower.substringBeforeLast('.')) -> LibraryKind.KEYS
            LOG_EXT.any { lower.endsWith(it) } -> LibraryKind.LOGS
            else -> LibraryKind.DUMPS
        }
    }

    /** 1536 -> "1.5 КБ" */
    fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes Б"
        val units = listOf("КБ", "МБ", "ГБ")
        var v = bytes.toDouble()
        var i = -1
        while (v >= 1024 && i < units.lastIndex) {
            v /= 1024
            i++
        }
        return "%.1f %s".format(java.util.Locale.US, v, units[i])
    }

    /** "session-20261028-011530.log" for a terminal log saved at the given time. */
    fun logFileName(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): String =
        "session-%04d%02d%02d-%02d%02d%02d.log".format(year, month, day, hour, minute, second)
}
