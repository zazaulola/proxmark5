package io.github.zazaulola.proxmark5.parse

/**
 * Screen text of the console, fed with the raw client output as it streams in.
 *
 * Lines keep their SGR color codes (render them with [Ansi.parse]). A '\r' that is
 * not part of "\r\n" makes the next text replace the current line, which is how the
 * client draws its spinners and progress. Other control sequences are dropped.
 */
class TerminalBuffer(private val maxLines: Int = 5000) {
    private val lines = ArrayDeque<String>()
    private val current = StringBuilder()
    private val escape = StringBuilder()
    private var pendingCr = false

    /** Bumped on every change, so a UI can tell when to redraw. */
    var revision = 0L
        private set

    @Synchronized
    fun append(chunk: CharSequence) {
        for (c in chunk) {
            if (escape.isNotEmpty()) {
                escape.append(c)
                if (escapeDone()) handleEscape()
                continue
            }
            when (c) {
                '\u001b' -> escape.append(c)
                '\r' -> pendingCr = true
                '\n' -> {
                    pendingCr = false
                    newline()
                }
                '\u0007', '\u0000' -> {}
                else -> {
                    if (pendingCr) {
                        pendingCr = false
                        current.setLength(0)
                    }
                    current.append(c)
                }
            }
        }
        revision++
    }

    @Synchronized
    fun clear() {
        lines.clear()
        current.setLength(0)
        escape.setLength(0)
        pendingCr = false
        revision++
    }

    /** Finished lines plus the line being written, if any. */
    @Synchronized
    fun snapshot(): List<String> {
        val out = ArrayList<String>(lines.size + 1)
        out.addAll(lines)
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    private fun newline() {
        lines.addLast(current.toString())
        current.setLength(0)
        while (lines.size > maxLines) lines.removeFirst()
    }

    private fun escapeDone(): Boolean {
        if (escape.length < 2) return false
        return when (escape[1]) {
            '[' -> escape.length > 2 && escape.last() in '@'..'~'
            ']' -> escape.last() == '\u0007' || escape.endsWith("\u001b\\")
            '(', ')' -> escape.length >= 3
            else -> true
        } || escape.length > 64
    }

    private fun handleEscape() {
        val seq = escape.toString()
        escape.setLength(0)
        if (seq.length < 3 || seq[1] != '[') return
        when (seq.last()) {
            'm' -> {
                if (pendingCr) {
                    pendingCr = false
                    current.setLength(0)
                }
                current.append(seq)
            }
            // erase line
            'K' -> if (seq == "\u001b[2K") current.setLength(0)
            else -> {}
        }
    }
}
