package io.github.zazaulola.proxmark5.history

/** The command line being typed, after a piece of text was put into it. [cursor] is where the caret goes. */
data class Edited(val text: String, val cursor: Int)

/** Editing of the terminal's command line that is not plain typing. */
object InputText {
    /**
     * Puts [piece] (a UID, a key, a path dragged or tapped in the output) in place of the selection
     * [selStart]..[selEnd] of [text], so that it stands as a word of its own: a space before it when
     * it would stick to the word in front, a space after it when it would stick to the word behind.
     * A piece with a blank in it goes in double quotes, as the client reads a value with a space.
     */
    fun insert(text: String, selStart: Int, selEnd: Int, piece: String): Edited {
        val word = quoteIfNeeded(piece.trim())
        if (word.isEmpty()) return Edited(text, selEnd.coerceIn(0, text.length))
        val a = minOf(selStart, selEnd).coerceIn(0, text.length)
        val b = maxOf(selStart, selEnd).coerceIn(0, text.length)
        val before = text.substring(0, a)
        val after = text.substring(b)
        val lead = if (before.isNotEmpty() && !before.last().isWhitespace()) " " else ""
        val trail = if (after.isEmpty() || !after.first().isWhitespace()) " " else ""
        val out = before + lead + word + trail + after
        // the caret after the piece and the one space that follows it
        val cursor = before.length + lead.length + word.length + (if (trail.isNotEmpty() || after.isNotEmpty()) 1 else 0)
        return Edited(out, cursor.coerceAtMost(out.length))
    }

    private fun quoteIfNeeded(s: String): String =
        if (s.any { it == ' ' || it == '\t' } && !s.startsWith("\"") && '"' !in s) "\"$s\"" else s
}

/** "только что", "5 мин", "3 ч", "вчера", "4 дн", "12.10.26": how long ago a line was run. */
object HistoryTime {
    fun ago(nowMs: Long, atMs: Long): String {
        val s = (nowMs - atMs) / 1000
        return when {
            s < 60 -> "только что"
            s < 3600 -> "${s / 60} мин"
            s < 86_400 -> "${s / 3600} ч"
            s < 2 * 86_400 -> "вчера"
            s < 30 * 86_400 -> "${s / 86_400} дн"
            else -> {
                val c = java.util.Calendar.getInstance().apply { timeInMillis = atMs }
                "%02d.%02d.%02d".format(c.get(java.util.Calendar.DAY_OF_MONTH), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.YEAR) % 100)
            }
        }
    }
}
