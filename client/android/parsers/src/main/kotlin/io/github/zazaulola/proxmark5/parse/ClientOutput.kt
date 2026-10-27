package io.github.zazaulola.proxmark5.parse

/** Message level, from the prefix PrintAndLogEx puts in front of a line (client/src/ui.c). */
enum class Level { SUCCESS, INFO, WARNING, ERROR, FAILED, DEBUG, HINT, NORMAL }

data class OutputLine(val level: Level, val text: String)

/** One "key....... value" or "key: value" line. */
data class Field(val key: String, val value: String)

object ClientOutput {
    private val PREFIX = Regex("^\\[(\\+|=|!!|!|-|#|\\?|[|/\\\\])\\]\\s?")
    private val SECTION = Regex("^\\s*\\[\\s*([^\\]]+?)\\s*\\]\\s*$")
    private val FIELD = Regex("^\\s*(?:--=\\s*)?([A-Za-z][A-Za-z0-9 /#()_+-]{0,40}?)\\s*(?:\\.{2,}|:)\\s+(\\S.*?)\\s*$")

    /** Splits client output into lines, without ANSI codes; spinner lines are dropped. */
    fun lines(text: String): List<OutputLine> {
        val out = ArrayList<OutputLine>()
        for (raw in Ansi.strip(text).split('\n')) {
            // text overwritten after a '\r' keeps only what came last
            val line = raw.split('\r').lastOrNull { it.isNotBlank() }.orEmpty().trimEnd()
            val m = PREFIX.find(line)
            if (m == null) {
                out += OutputLine(Level.NORMAL, line)
                continue
            }
            val level = when (m.groupValues[1]) {
                "+" -> Level.SUCCESS
                "=" -> Level.INFO
                "!" -> Level.WARNING
                "!!" -> Level.ERROR
                "-" -> Level.FAILED
                "#" -> Level.DEBUG
                "?" -> Level.HINT
                else -> null // spinner [|] [/] [\]
            } ?: continue
            out += OutputLine(level, line.substring(m.range.last + 1))
        }
        return out
    }

    /** "[ Title ]" header, or null. */
    fun section(text: String): String? = SECTION.find(text)?.groupValues?.get(1)

    fun field(text: String): Field? {
        val m = FIELD.find(text) ?: return null
        return Field(m.groupValues[1].trim(), m.groupValues[2])
    }
}
