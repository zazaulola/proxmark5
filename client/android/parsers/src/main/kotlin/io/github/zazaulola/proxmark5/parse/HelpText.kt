package io.github.zazaulola.proxmark5.parse

/** What an option's value looks like, as far as its placeholder in the help says. */
enum class ArgKind {
    /** The option takes no value. */
    FLAG,

    /** `<hex>`, `<4hex>`: bytes written in hexadecimal. */
    HEX,

    /** `<dec>`, `<int>`, `<ms>`, `<8..255>`: a number. */
    NUMBER,

    /** `<fn>`, `<path>`: a file name. */
    FILE,

    /** `<a|b|c>`: one of a fixed set, see [HelpOption.choices]. */
    CHOICE,

    /** Anything else (`<str>`, `<type>`, `<format>`, ...). */
    TEXT,
}

/** One option of a command, as the client's own `-h` lists it. */
class HelpOption(
    /** `-f`, or null when the option has only a long name. */
    val short: String?,
    /** `--file`, or null when the option has only a short name. */
    val long: String?,
    /** The placeholder inside `<...>` (`fn`, `native|niso|iso`, `8..255`), or null for a flag. */
    val arg: String?,
    val description: String,
    /** The usage line shows it outside `[...]`: the command fails without it. */
    val required: Boolean,
) {
    /** The form to type on the command line: the long name when there is one. */
    val name: String get() = long ?: short ?: ""

    val kind: ArgKind
        get() {
            val a = arg ?: return ArgKind.FLAG
            return when {
                '|' in a -> ArgKind.CHOICE
                a.contains("hex", ignoreCase = true) -> ArgKind.HEX
                NUMBER_ARG.matches(a) -> ArgKind.NUMBER
                a.equals("fn", ignoreCase = true) || a.equals("path", ignoreCase = true) -> ArgKind.FILE
                else -> ArgKind.TEXT
            }
        }

    /** The allowed values of a [ArgKind.CHOICE] option, in the order the client lists them. */
    val choices: List<String> get() = if (kind == ArgKind.CHOICE) arg.orEmpty().split('|') else emptyList()

    private companion object {
        val NUMBER_ARG = Regex("""(?i)(dec|decimal|int|n|idx|ms|us|dbl|\d+\.\.\d+)""")
    }
}

/** An argument given by position rather than by name, e.g. the `<hex>` of `hf felica raw`. */
class HelpPositional(val name: String, val description: String, val required: Boolean)

/** A line of the examples/notes block: the text and, when the client explains it with `->`, the note. */
class HelpExample(val text: String, val note: String?)

/** The parsed `-h` text of one command. */
class CommandHelp(
    val description: String,
    /** The usage lines, continuation lines already joined. */
    val usage: List<String>,
    /** Options without the `-h, --help` every command has. */
    val options: List<HelpOption>,
    val positionals: List<HelpPositional>,
    val examples: List<HelpExample>,
    /** From the full dump only: works without a connected Proxmark. Null when the text doesn't say. */
    val offline: Boolean? = null,
) {
    val hasParameters: Boolean get() = options.isNotEmpty() || positionals.isNotEmpty()
}

/**
 * Reads the help the client prints for a command (`<command> -h`, or the full dump of
 * `proxmark3 --fulltext`). Every command builds it from the same argument tables, so the text
 * has one shape: a description, `usage:`, `options:` and `examples/notes:`. Nothing here is
 * guessed from the command; what the help doesn't say, this doesn't know.
 */
object HelpText {
    private val NAMES = Regex("""^(-[^\s,-])?(?:,\s*)?(--[\w-]+)?""")
    private val SEPARATOR = Regex("""(?m)^-{40,}\s*$""")
    private val ARROW = Regex("""\s+->\s+""")

    /** Parses the `-h` text of one command. */
    fun parse(text: String): CommandHelp = parseSection(Ansi.strip(text).replace("\r", "").lines(), offline = null)

    /** Parses a full dump into the help of each command, by command line (`hf mf dump`). */
    fun parseDump(text: String): Map<String, CommandHelp> {
        val out = LinkedHashMap<String, CommandHelp>()
        // the first piece is the banner before the first separator
        for (chunk in Ansi.strip(text).replace("\r", "").split(SEPARATOR).drop(1)) {
            val lines = chunk.lines().dropWhile { it.isBlank() }
            val name = lines.firstOrNull()?.trim().orEmpty()
            if (name.isEmpty()) continue
            var body = lines.drop(1)
            var offline: Boolean? = null
            val flag = body.indexOfFirst { it.isNotBlank() }
            if (flag >= 0 && body[flag].trim().startsWith(OFFLINE)) {
                offline = body[flag].trim().removePrefix(OFFLINE).trim() == "yes"
                body = body.drop(flag + 1)
            }
            out[name] = parseSection(body, offline)
        }
        return out
    }

    private const val OFFLINE = "available offline:"

    private fun parseSection(lines: List<String>, offline: Boolean?): CommandHelp {
        val usageAt = lines.indexOfFirst { it.trim() == "usage:" }
        val optionsAt = lines.indexOfFirst { it.trim() == "options:" }
        val examplesAt = lines.indexOfFirst { it.trim() == "examples/notes:" }
        val firstHeader = listOf(usageAt, optionsAt, examplesAt).filter { it >= 0 }.minOrNull() ?: lines.size

        val description = lines.subList(0, firstHeader).joinToString("\n") { it.trimEnd() }.trim()
        val usage = if (usageAt >= 0) usageLines(block(lines, usageAt)) else emptyList()
        val required = usage.firstOrNull()?.let { requiredIn(it) } ?: Required(emptySet(), emptySet())

        val options = ArrayList<HelpOption>()
        val positionals = ArrayList<HelpPositional>()
        if (optionsAt >= 0) {
            for ((head, desc) in optionEntries(block(lines, optionsAt))) {
                if (head.startsWith("<")) {
                    val end = head.indexOf('>')
                    if (end < 0) continue
                    val name = head.substring(1, end)
                    val text = (head.substring(end + 1).trim() + " " + desc).trim()
                    positionals += HelpPositional(name, text, name in required.positionals)
                } else {
                    parseOption(head, desc, required.options)?.let { options += it }
                }
            }
        }
        // some commands name a positional argument in the usage line only, without an options row
        for (name in required.positionals) {
            if (positionals.none { it.name == name }) positionals += HelpPositional(name, "", true)
        }
        val examples = if (examplesAt >= 0) examplesIn(block(lines, examplesAt)) else emptyList()
        return CommandHelp(description, usage, options, positionals, examples, offline)
    }

    /** The lines under the header at [at], up to the next header or the end (blank lines kept inside). */
    private fun block(lines: List<String>, at: Int): List<String> {
        val out = ArrayList<String>()
        var i = at + 1
        while (i < lines.size) {
            val l = lines[i]
            // a header sits at the margin and ends with a colon
            if (l.isNotBlank() && !l[0].isWhitespace() && l.trimEnd().endsWith(":")) break
            out += l
            i++
        }
        return out
    }

    private fun indentOf(s: String) = s.length - s.trimStart().length

    /** A usage line starts at the block's margin, the lines indented further continue it. */
    private fun usageLines(block: List<String>): List<String> {
        // a line of only spaces is not an end: the client leaves some inside its blocks
        val body = block.filter { it.isNotBlank() }
        if (body.isEmpty()) return emptyList()
        val margin = body.minOf { indentOf(it) }
        val out = ArrayList<String>()
        for (l in body) {
            if (indentOf(l) == margin || out.isEmpty()) out += l.trim() else out[out.lastIndex] = out.last() + " " + l.trim()
        }
        return out
    }

    /**
     * The option rows as (names, argument and description on the first line) with the wrapped
     * description lines joined on. A row at the margin that is neither an option nor a positional
     * (a heading like "FM11RF08S specific options:") is skipped together with what wraps under it.
     */
    private fun optionEntries(block: List<String>): List<Pair<String, String>> {
        val body = block.filter { it.isNotBlank() }
        if (body.isEmpty()) return emptyList()
        val margin = body.minOf { indentOf(it) }
        val heads = ArrayList<String>()
        val descs = ArrayList<String>()
        var open = false
        for (l in body) {
            val t = l.trim()
            if (indentOf(l) == margin) {
                open = t.startsWith("-") || t.startsWith("<")
                if (open) {
                    heads += t
                    descs += ""
                }
            } else if (open) {
                descs[descs.lastIndex] = (descs.last() + " " + t).trim()
            }
        }
        return heads.zip(descs)
    }

    private fun parseOption(head: String, wrapped: String, required: Set<String>): HelpOption? {
        val m = NAMES.find(head) ?: return null
        val short = m.groupValues[1].ifEmpty { null }
        val long = m.groupValues[2].ifEmpty { null }
        if (short == null && long == null) return null
        var rest = head.substring(m.value.length).trimStart()

        var arg: String? = null
        if (rest.startsWith("<")) {
            val end = rest.indexOf('>')
            if (end > 0) {
                arg = rest.substring(1, end)
                rest = rest.substring(end + 1)
            }
        } else if (rest.startsWith("[<") || rest.startsWith("[=")) {
            val end = rest.indexOf(']')
            if (end > 0) {
                arg = rest.substring(1, end).trim('<', '>', '=', ' ')
                rest = rest.substring(end + 1)
            }
        }
        val description = (rest.trim() + " " + wrapped).trim()
        // every command has -h; it isn't a parameter
        if (short == "-h" && long == "--help") return null
        val isRequired = (short != null && short in required) || (long != null && long in required)
        return HelpOption(short, long, arg, description, isRequired)
    }

    private class Required(val options: Set<String>, val positionals: Set<String>)

    /**
     * What the usage line shows outside `[...]`: those options and positional arguments are
     * required. `[-hv]` clusters, optional parts and the command words are ignored.
     */
    private fun requiredIn(usage: String): Required {
        val tokens = ArrayList<String>()
        val sb = StringBuilder()
        var depth = 0
        var inAngle = false
        fun flush() {
            if (sb.isNotEmpty()) tokens += sb.toString()
            sb.setLength(0)
        }
        for (c in usage) {
            when {
                depth > 0 && c == ']' -> depth--
                depth > 0 && c == '[' -> depth++
                depth > 0 -> {}
                c == '[' -> {
                    flush()
                    depth++
                }
                inAngle -> {
                    sb.append(c)
                    if (c == '>') inAngle = false
                }
                c == '<' -> {
                    sb.append(c)
                    inAngle = true
                }
                c.isWhitespace() -> flush()
                else -> sb.append(c)
            }
        }
        flush()

        val options = HashSet<String>()
        val positionals = HashSet<String>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            when {
                t.startsWith("--") || (t.length == 2 && t[0] == '-') -> {
                    options += t
                    // the next <...> is this option's value, not a positional
                    if (i + 1 < tokens.size && tokens[i + 1].startsWith("<")) i++
                }

                t.startsWith("<") && t.endsWith(">") -> positionals += t.substring(1, t.length - 1)
            }
            i++
        }
        return Required(options, positionals)
    }

    private fun examplesIn(block: List<String>): List<HelpExample> =
        block.map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
            val parts = line.split(ARROW, limit = 2)
            HelpExample(parts[0].trim(), parts.getOrNull(1)?.trim())
        }
}
