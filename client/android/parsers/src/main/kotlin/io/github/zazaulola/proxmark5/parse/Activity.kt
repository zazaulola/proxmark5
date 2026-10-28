package io.github.zazaulola.proxmark5.parse

/** What the device is busy with, as far as the command line tells. */
enum class ActivityKind { IDLE, COMMAND, SNIFFER, EMULATION }

object Activities {
    private val WS = Regex("\\s+")
    private val PERCENT = Regex("(?<![\\d.])(\\d{1,3})\\s?%")

    /** How many leading words can name a command; the rest are arguments. */
    private const val COMMAND_WORDS = 4

    /**
     * Sniffing, emulating (`sim`, `simask`, ...) or any other command, from its command line.
     * Only the words before the first option count: `hf mf dump -f sim` dumps to a file called sim.
     */
    fun of(cmd: String): ActivityKind {
        val words = cmd.trim().lowercase().split(WS)
            .takeWhile { !it.startsWith("-") }
            .filter { it.isNotEmpty() }
            .take(COMMAND_WORDS)
        return when {
            words.any { it.startsWith("sniff") || it.startsWith("snoop") } -> ActivityKind.SNIFFER
            words.any { it == "sim" || it.startsWith("sim") } -> ActivityKind.EMULATION
            else -> ActivityKind.COMMAND
        }
    }

    /** A percentage the client printed on [line] (ANSI codes allowed), or null. */
    fun percent(line: String): Int? {
        val m = PERCENT.find(Ansi.strip(line)) ?: return null
        val v = m.groupValues[1].toInt()
        return if (v in 0..100) v else null
    }

    /** 75 s -> "1:15", 3725 s -> "1:02:05" */
    fun elapsed(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
