package io.github.zazaulola.proxmark5.parse.output

/**
 * Finds the data worth acting on in the client's output: file paths, card ids, keys, hex blocks and
 * the commands it suggests. Works on one line at a time, over the text with its ANSI colors already
 * stripped (so it never depends on colors, which prefs can turn off), and gives each item's place in
 * that line so the terminal can underline it where it stands.
 *
 * It is deliberately shy: a miss only means an item is not underlined (the user can still select the
 * text by hand), a false hit underlines something harmless, so the rules lean on the labels the
 * client prints ("UID", "Key A", "Saved ... file") rather than on hex alone, which is everywhere.
 */
object OutputScan {
    /** The client's own line prefix: `[+] `, `[=] `, `[-] `, `[?] `, `[!!] ` and the like. */
    private val PREFIX = Regex("""^\[[^\]]{1,4}\]\s?""")

    /** The echo line the app writes before a command runs: `[usb] pm3 --> hf 14a info`. */
    private val ECHO = Regex("""^\[[^\]]*\]\s*pm3\s*-->\s*(\S.*)$""")

    /** `` `...` ``: a path or a command the client puts in backticks. */
    private val BACKTICK = Regex("""`([^`]+)`""")

    /** `Saved ... file <path>` / `Loaded ... file <path>` without backticks (fileutils.c). */
    private val FILE_TRAILING = Regex("""(?i)\b(?:saved to|loaded|dumped to|saved)\b[^`]*?\bfile\s+(\S+\.\w+)\s*$""")

    /** A key table row: `000 | 003 | D1E2AA68E39A | 1 | FFFFFFFFFFFF | 1`. */
    private val KEY_ROW = Regex("""^\s*\d{1,3}\s*\|\s*\d{1,3}\s*\|\s*([0-9A-Fa-f]{12})\s*\|\s*\d?\s*\|\s*([0-9A-Fa-f]{12})""")

    /** A labelled value: `UID.............. 04 26 ...`, `CSN... 6D C2 ...`, `Key 00 (ver 00). 0102...`. */
    private val LABELLED = Regex("""^\s*([A-Za-z][A-Za-z0-9 /()#.-]{0,30}?)(?:[.:]{2,}|[.:]\s)\s*([0-9A-Fa-f]{2}(?:[ :]?[0-9A-Fa-f]{2}){1,63})\b""")

    /** `Raw: a0000000a084f052` (LF demods). */
    private val RAW = Regex("""\bRaw:\s*([0-9A-Fa-f]{6,})\b""")

    /** `EM 410x ID 010872E77C`, `Unique TAG ID 80104EE73E`. */
    private val TAG_ID = Regex("""\b(?:TAG ID|410x ID|ID)\s*:?\s*([0-9A-Fa-f]{8,})\b""")

    /** `Wiegand: 002F623AE` — the encoded value. */
    private val WIEGAND = Regex("""\bWiegand:\s*([0-9A-Fa-f]{6,})\b""")

    private val KEY_LABEL = Regex("""(?i)\b(key|kd|kc|pwd|password|pack|master|diversified|des key|aes key|ultralight pwd)\b""")
    private val UID_LABEL = Regex("""(?i)(\buid\b|\bcsn\b|\bidm\b|\bnuid\b|\btag id\b|unique tag|\braw\b)""")
    private val HEX_SPLIT = Regex("""[ :]""")

    /** The command words a backtick must start with to be taken for a command the client suggests. */
    private val GROUPS = setOf(
        "prefs", "analyse", "data", "hf", "hw", "lf", "mad", "mem", "nfc", "piv", "smart",
        "mqtt", "reveng", "script", "trace", "usart", "wiegand", "emv", "iclass",
    )

    /** Everything found in [line] (ANSI already stripped), by place in the line. Echo lines give the command only. */
    fun scanLine(line: String): List<Found> {
        ECHO.find(line)?.let { m ->
            val g = m.groups[1]!!
            return listOf(Found(FoundKind.COMMAND, g.range.first, g.range.last + 1, g.value.trim()))
        }
        val out = ArrayList<Found>()
        val taken = ArrayList<IntRange>()
        fun free(r: IntRange) = taken.none { it.first <= r.last && r.first <= it.last }
        fun add(f: Found) {
            val r = f.start until f.end
            if (free(r)) {
                out += f
                taken += r
            }
        }

        // the line prefix (`[+] `) is not part of the data; anchored rules run on the rest, their offsets shifted back
        val pfx = PREFIX.find(line)?.value?.length ?: 0
        val bodyStr = line.substring(pfx)

        for (m in BACKTICK.findAll(line)) {
            val inner = m.groupValues[1].trim()
            val s = m.range.first + 1
            val kind = classifyBacktick(line, m.range.first, inner) ?: continue
            add(Found(kind, s, s + m.groupValues[1].length, inner))
        }
        FILE_TRAILING.find(line)?.let { m ->
            val g = m.groups[1]!!
            add(Found(FoundKind.FILE, g.range.first, g.range.last + 1, g.value))
        }
        KEY_ROW.find(bodyStr)?.let { m ->
            for (i in 1..2) m.groups[i]?.let { g -> add(Found(FoundKind.KEY, pfx + g.range.first, pfx + g.range.last + 1, g.value.uppercase(), if (i == 1) "key A" else "key B")) }
        }
        LABELLED.find(bodyStr)?.let { m ->
            val label = m.groupValues[1].trim()
            val g = m.groups[2]!!
            val value = g.value.replace(HEX_SPLIT, "").uppercase()
            // a run of "00"s is not an id worth offering; a single byte (SAK) is hex, not an id
            val kind = when {
                KEY_LABEL.containsMatchIn(label) -> FoundKind.KEY
                UID_LABEL.containsMatchIn(label) && value.length >= 6 -> FoundKind.UID
                else -> FoundKind.HEX
            }
            if (!(kind != FoundKind.KEY && value.all { it == '0' })) {
                add(Found(kind, pfx + g.range.first, pfx + g.range.last + 1, value, label.ifBlank { null }))
            }
        }
        WIEGAND.find(line)?.let { m -> val g = m.groups[1]!!; add(Found(FoundKind.HEX, g.range.first, g.range.last + 1, g.value.uppercase(), "Wiegand")) }
        RAW.find(line)?.let { m -> val g = m.groups[1]!!; add(Found(FoundKind.UID, g.range.first, g.range.last + 1, g.value.uppercase(), "Raw")) }
        TAG_ID.find(line)?.let { m ->
            val g = m.groups[1]!!
            // skip when the match is only the generic "ID" inside a word already taken (e.g. a DEZ line)
            add(Found(FoundKind.UID, g.range.first, g.range.last + 1, g.value.uppercase(), "ID"))
        }
        return out.sortedBy { it.start }
    }

    private fun classifyBacktick(line: String, at: Int, inner: String): FoundKind? {
        val before = line.substring(0, at).lowercase()
        val looksPath = '/' in inner || '\\' in inner || Regex("""\.\w{2,5}$""").containsMatchIn(inner)
        val fileContext = Regex("""(?i)\b(load|loaded|saved|save|file|find|locked|dump|wrote|written|created)\b""").containsMatchIn(before)
        val firstWord = inner.trimStart().substringBefore(' ').lowercase()
        return when {
            looksPath || (fileContext && ' ' !in inner.trim()) -> FoundKind.FILE
            firstWord in GROUPS && inner.trim().contains(' ') -> FoundKind.COMMAND
            firstWord in GROUPS -> FoundKind.COMMAND
            else -> null
        }
    }

    /**
     * What the output of the last run of [commandLine] showed. The terminal holds the whole log; a run
     * is the span from its echo line (`pm3 --> <commandLine>`) to the next echo line. The command itself
     * is not offered as a found item (it is the line that ran).
     */
    fun foundForCommand(lines: List<String>, commandLine: String): List<Found> {
        val wanted = commandLine.trim()
        val echoAt = IntArray(lines.size) { -1 }
        var last = -1
        for (i in lines.indices) {
            val echo = ECHO.find(stripAnsi(lines[i]))?.groupValues?.get(1)?.trim() ?: continue
            echoAt[i] = i
            if (echo == wanted) last = i
        }
        if (last < 0) return emptyList()
        // from just after the matched echo to the next echo line (any command) or the end
        var to = lines.size
        for (i in last + 1 until lines.size) if (echoAt[i] >= 0) {
            to = i
            break
        }
        val out = ArrayList<Found>()
        for (i in last + 1 until to) {
            out += scanLine(stripAnsi(lines[i])).filter { it.kind != FoundKind.COMMAND }
        }
        return out
    }

    /** The line without its line prefix, for classifying (offsets stay against the whole line). */
    internal fun body(line: String): String = PREFIX.replaceFirst(line, "")

    // the parsers module ships Ansi already; call it without importing to keep this file self-contained in tests
    private fun stripAnsi(s: String): String = io.github.zazaulola.proxmark5.parse.Ansi.strip(s)
}
