package io.github.zazaulola.proxmark5.parse

/** The colors the client uses (include/ansi.h). */
enum class AnsiColor {
    BLACK, RED, GREEN, YELLOW, BLUE, MAGENTA, CYAN, WHITE,
    BRIGHT_BLACK, BRIGHT_RED, BRIGHT_GREEN, BRIGHT_YELLOW,
    BRIGHT_BLUE, BRIGHT_MAGENTA, BRIGHT_CYAN, BRIGHT_WHITE,
}

/** Style of [start, end) in [StyledLine.text]. */
data class StyleSpan(
    val start: Int,
    val end: Int,
    val fg: AnsiColor? = null,
    val bg: AnsiColor? = null,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val underline: Boolean = false,
)

data class StyledLine(val text: String, val spans: List<StyleSpan>)

private data class SgrState(
    val fg: AnsiColor? = null,
    val bg: AnsiColor? = null,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val underline: Boolean = false,
) {
    val isPlain get() = fg == null && bg == null && !bold && !dim && !underline

    fun apply(params: List<Int>): SgrState {
        var s = this
        val list = params.ifEmpty { listOf(0) }
        for (p in list) {
            s = when (p) {
                0 -> SgrState()
                1 -> s.copy(bold = true)
                2 -> s.copy(dim = true)
                4 -> s.copy(underline = true)
                22 -> s.copy(bold = false, dim = false)
                24 -> s.copy(underline = false)
                in 30..37 -> s.copy(fg = AnsiColor.entries[p - 30])
                39 -> s.copy(fg = null)
                in 40..47 -> s.copy(bg = AnsiColor.entries[p - 40])
                49 -> s.copy(bg = null)
                in 90..97 -> s.copy(fg = AnsiColor.entries[p - 90 + 8])
                in 100..107 -> s.copy(bg = AnsiColor.entries[p - 100 + 8])
                else -> s
            }
        }
        return s
    }
}

object Ansi {
    private val ESCAPE = Regex("\u001b(?:\\[([0-9;?]*)([A-Za-z])|\\][^\u0007\u001b]*(?:\u0007|\u001b\\\\)|[()][A-Za-z0-9]|[=>])")

    /** Removes every escape sequence. */
    fun strip(s: String): String = ESCAPE.replace(s, "")

    /** Splits one line (no '\n') into text and color spans; non-SGR sequences are dropped. */
    fun parse(line: String): StyledLine {
        val out = StringBuilder(line.length)
        val spans = ArrayList<StyleSpan>()
        var state = SgrState()
        var spanStart = 0

        fun flush() {
            if (out.length > spanStart && !state.isPlain) {
                spans += StyleSpan(spanStart, out.length, state.fg, state.bg, state.bold, state.dim, state.underline)
            }
            spanStart = out.length
        }

        var pos = 0
        for (m in ESCAPE.findAll(line)) {
            out.append(line, pos, m.range.first)
            pos = m.range.last + 1
            if (m.groupValues[2] == "m") {
                flush()
                val params = m.groupValues[1].split(';').filter { it.isNotEmpty() && it[0] != '?' }
                    .mapNotNull { it.toIntOrNull() }
                state = state.apply(params)
            }
        }
        out.append(line, pos, line.length)
        flush()
        return StyledLine(out.toString(), spans)
    }
}
