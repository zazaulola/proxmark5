package io.github.zazaulola.proxmark5.parse.params

/** A tag's place on screen, in pixels: left, top, right, bottom. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerX: Float get() = (left + right) / 2
}

/**
 * Where a dragged tag lands among tags laid out in wrapping rows. The boxes are measured when
 * the drag starts and not while it goes on: moving tags of different widths around under the
 * finger would make the rows jump and the target swing back and forth.
 */
object Reorder {
    /**
     * The gap the pointer at ([x], [y]) points to: 0 = before the first tag, `boxes.size` = after
     * the last. The row is the one whose height holds [y] (the nearest one above or below
     * otherwise); within it, the gap is before the first tag whose middle is right of [x].
     */
    fun slot(boxes: List<Box>, x: Float, y: Float): Int {
        if (boxes.isEmpty()) return 0
        // rows: runs of boxes with the same top
        val rows = ArrayList<IntRange>()
        var start = 0
        for (i in 1..boxes.size) {
            if (i == boxes.size || boxes[i].top >= boxes[i - 1].bottom - 1f || boxes[i].left < boxes[i - 1].left) {
                rows += start until i
                start = i
            }
        }
        val row = rows.firstOrNull { r -> y >= boxes[r.first].top && y < rowBottom(boxes, r) }
            ?: if (y < boxes[rows.first().first].top) rows.first() else rows.last()
        for (i in row) if (x < boxes[i].centerX) return i
        return row.last + 1
    }

    private fun rowBottom(boxes: List<Box>, row: IntRange) = row.maxOf { boxes[it].bottom }

    /** The index for [CommandDraft.move] of the tag at [from] dropped into gap [slot]. */
    fun target(from: Int, slot: Int): Int = if (slot > from) slot - 1 else slot
}

object TagText {
    /** "A0A1A2…E5E6E7": long values keep their start and end, the middle goes. */
    fun middle(s: String, max: Int): String {
        if (s.length <= max || max < 5) return s
        val keep = max - 1
        val head = (keep + 1) / 2
        val tail = keep - head
        return s.take(head) + "…" + s.takeLast(tail)
    }

    /** Hex shown in bytes: "A0A1A2" -> "A0 A1 A2". */
    fun groupBytes(hex: String): String = hex.chunked(2).joinToString(" ")

    /**
     * What the hex field keeps of what was typed or pasted: hex digits, with the Cyrillic letters
     * that look like A, B, C, E (a Russian keyboard gives them) turned into the Latin ones, and
     * `0x`, spaces, colons, dashes dropped.
     */
    fun cleanHex(input: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if ((c == '0') && i + 1 < input.length && (input[i + 1] == 'x' || input[i + 1] == 'X') &&
                (sb.isEmpty() || input.getOrNull(i - 1)?.let { it == ' ' || it == ',' } == true)
            ) {
                i += 2
                continue
            }
            val latin = when (c) {
                'А', 'а' -> 'A'
                'В', 'в' -> 'B'
                'С', 'с' -> 'C'
                'Е', 'е' -> 'E'
                else -> c
            }
            if (latin in '0'..'9' || latin in 'a'..'f' || latin in 'A'..'F') sb.append(latin.uppercaseChar())
            i++
        }
        return sb.toString()
    }
}

/**
 * Drafts saved across the death of the UI process (Android keeps these strings for the
 * activity). A tag is saved by its parameter's key, so it can be matched to the help again, and
 * with its id, which the screen also saves (the tag whose editor is open).
 */
object DraftCodec {
    private const val SEP = '\u0000'

    fun encode(draft: CommandDraft): ArrayList<String> = ArrayList(draft.tags.map { t ->
        if (t.spec != null) "k$SEP${t.id}$SEP${t.spec.key}$SEP${t.value}" else "r$SEP${t.id}$SEP${t.raw.orEmpty()}"
    })

    /** Rebuilds a draft of [command] against [specs]; a tag whose parameter is gone becomes raw text. */
    fun decode(command: String, saved: List<String>, specs: List<ParamSpec>): CommandDraft {
        val tags = ArrayList<ParamTag>()
        val used = HashSet<Long>()
        var fresh = (saved.mapNotNull { it.split(SEP).getOrNull(1)?.toLongOrNull() }.maxOrNull() ?: 0L) + 1
        fun id(text: String): Long {
            val n = text.toLongOrNull()
            return if (n != null && n > 0 && used.add(n)) n else fresh++.also { used.add(it) }
        }
        for (row in saved) {
            val kind = row.substringBefore(SEP)
            when (kind) {
                "k" -> {
                    val parts = row.split(SEP, limit = 4)
                    if (parts.size < 4) continue
                    val spec = specs.firstOrNull { it.key == parts[2] }
                    when {
                        spec != null -> tags += ParamTag(id(parts[1]), spec, parts[3])
                        parts[3].isEmpty() -> tags += ParamTag(id(parts[1]), null, "", parts[2])
                        else -> {
                            tags += ParamTag(id(parts[1]), null, "", parts[2])
                            tags += ParamTag(fresh++, null, "", parts[3])
                        }
                    }
                }
                "r" -> {
                    val parts = row.split(SEP, limit = 3)
                    if (parts.size == 3) tags += ParamTag(id(parts[1]), null, "", parts[2])
                }
            }
        }
        return CommandDraft(command, tags)
    }
}
