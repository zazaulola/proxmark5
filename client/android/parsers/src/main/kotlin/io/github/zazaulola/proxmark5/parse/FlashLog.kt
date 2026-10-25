package io.github.zazaulola.proxmark5.parse

/** What the flasher is writing, read from the console. */
data class FlashStatus(
    /** file name without its directory, null before writing starts */
    val file: String?,
    /** percentage of the current segment, null when the flasher prints none (small segments) */
    val percent: Int?,
)

/**
 * Follows `flash_write()` (client/src/flash.c) in the console. When stdout is not a
 * terminal, as in the app, it prints "Writing segments for file: <path>" per file and an
 * in-place "Flashing  NN%" per large segment instead of its full-screen animation.
 */
object FlashLog {
    /** The app echoes "pm3 --> flash ..." / "pm3 --> dfu ..." before a write: older lines are stale. */
    const val FLASH_MARKER = "pm3 --> flash"
    const val DFU_MARKER = "pm3 --> dfu"

    private val FILE = Regex("""Writing segments for file: (\S+)""")
    private val PERCENT = Regex("""Flashing\s+(\d{1,3})%""")

    /** Status from console lines (oldest first), looking at most [lookback] lines back. */
    fun status(lines: List<String>, lookback: Int = 300): FlashStatus {
        var percent: Int? = null
        val stop = maxOf(0, lines.size - lookback)
        for (i in lines.indices.reversed()) {
            if (i < stop) break
            val text = Ansi.strip(lines[i])
            if (text.contains(FLASH_MARKER)) break
            if (percent == null) {
                PERCENT.find(text)?.let { percent = it.groupValues[1].toInt().coerceIn(0, 100) }
            }
            FILE.find(text)?.let {
                return FlashStatus(it.groupValues[1].substringAfterLast('/'), percent)
            }
        }
        return FlashStatus(null, null)
    }

    private val DFU = Regex("""(Erasing|Writing|Verifying)\s+(\d{1,3})%""")

    /** Phase ("Erasing", "Writing", "Verifying") and percentage of a DFU write (client/src/dfuse.c). */
    fun dfuStatus(lines: List<String>, lookback: Int = 50): Pair<String, Int>? {
        val stop = maxOf(0, lines.size - lookback)
        for (i in lines.indices.reversed()) {
            if (i < stop) break
            val text = Ansi.strip(lines[i])
            if (text.contains(DFU_MARKER)) break
            DFU.find(text)?.let {
                return it.groupValues[1] to it.groupValues[2].toInt().coerceIn(0, 100)
            }
        }
        return null
    }
}
