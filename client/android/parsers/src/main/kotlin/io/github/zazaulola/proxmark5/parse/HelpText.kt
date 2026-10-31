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
    /** More long names for the same option (`--file`, `--rpk` after `-k, --key`), as the help lists them. */
    val aliases: List<String> = emptyList(),
    /** The usage line shows `...` after it: the option may be given more than once. */
    val repeatable: Boolean = false,
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
class HelpPositional(
    val name: String,
    val description: String,
    val required: Boolean,
    /** The usage line shows `...` after it (`<hex> [<hex>]...`): more than one value may follow. */
    val repeatable: Boolean = false,
)

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
    // the names in front of an option row: `-k`, `--key`, `-s, -S, --select`, `-k, --key, --file, --rpk`
    // `--1k+` (hf mf cload) is a long name of its own, not `--1k` with a stray `+`
    private val NAMES = Regex("""^(?:--[\w+-]+|-[^\s,-])(?:,\s*(?:--[\w+-]+|-[^\s,-]))*""")
    private val SEPARATOR = Regex("""(?m)^-{40,}\s*$""")
    private val ARROW = Regex("""\s+->\s+""")

    /** Parses the `-h` text of one command. */
    fun parse(text: String): CommandHelp = parseSection(Ansi.strip(text).replace("\r", "").lines(), offline = null)

    /** Parses a full dump into the help of each command, by command line (`hf mf dump`). */
    fun parseDump(text: String): Map<String, CommandHelp> {
        val out = LinkedHashMap<String, CommandHelp>()
        // the first piece is the banner before the first separator
        for (chunk in Ansi.strip(text).replace("\r", "").split(SEPARATOR).drop(1)) {
            parseChunk(chunk)?.let { (name, help) -> out[name] = help }
        }
        return out
    }

    /**
     * Where each command's section starts and ends in a full dump, without parsing any: the app
     * keeps the dump as one string and parses a command when it is opened. [text] must already be
     * free of ANSI codes and `\r` (see [cleanDump]).
     */
    fun indexDump(text: String): Map<String, IntRange> {
        val out = HashMap<String, IntRange>()
        val seps = SEPARATOR.findAll(text).map { it.range }.toList()
        for ((i, sep) in seps.withIndex()) {
            val start = sep.last + 1
            val end = if (i + 1 < seps.size) seps[i + 1].first else text.length
            if (start >= end) continue
            val name = text.substring(start, end).lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            if (name.isNotEmpty()) out[name] = start until end
        }
        return out
    }

    /** The dump as [indexDump] wants it. */
    fun cleanDump(text: String): String = Ansi.strip(text).replace("\r", "")

    /** Parses one section that [indexDump] found. */
    fun parseIndexed(text: String, range: IntRange): CommandHelp? = parseChunk(text.substring(range))?.second

    private fun parseChunk(chunk: String): Pair<String, CommandHelp>? {
        val lines = chunk.lines().dropWhile { it.isBlank() }
        val name = lines.firstOrNull()?.trim().orEmpty()
        if (name.isEmpty()) return null
        var body = lines.drop(1)
        var offline: Boolean? = null
        val flag = body.indexOfFirst { it.isNotBlank() }
        if (flag >= 0 && body[flag].trim().startsWith(OFFLINE)) {
            offline = body[flag].trim().removePrefix(OFFLINE).trim() == "yes"
            body = body.drop(flag + 1)
        }
        return name to parseSection(body, offline)
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
        val repeats = usage.firstOrNull()?.let { repeatableIn(it) } ?: Required(emptySet(), emptySet())

        val options = ArrayList<HelpOption>()
        val positionals = ArrayList<HelpPositional>()
        if (optionsAt >= 0) {
            for ((head, desc) in optionEntries(block(lines, optionsAt))) {
                if (head.startsWith("<")) {
                    val end = head.indexOf('>')
                    if (end < 0) continue
                    val name = head.substring(1, end)
                    val text = (head.substring(end + 1).trim() + " " + desc).trim()
                    positionals += HelpPositional(name, text, name in required.positionals, name in repeats.positionals)
                } else {
                    parseOption(head, desc, required.options, repeats.options)?.let { options += it }
                }
            }
        }
        // some commands name a positional argument in the usage line only, without an options row
        for (name in required.positionals) {
            if (positionals.none { it.name == name }) positionals += HelpPositional(name, "", true, name in repeats.positionals)
        }
        usage.firstOrNull()?.let { fillFromUsage(it, options, required.options, repeats.options) }
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

    private fun parseOption(head: String, wrapped: String, required: Set<String>, repeatable: Set<String>): HelpOption? {
        val m = NAMES.find(head) ?: return null
        val names = m.value.split(',').map { it.trim() }
        val short = names.firstOrNull { !it.startsWith("--") }
        val long = names.firstOrNull { it.startsWith("--") }
        if (short == null && long == null) return null
        // `-s, -S, --select`, `-k, --key, --file, --rpk`: the other names are aliases
        val aliases = names.filter { it != short && it != long }
        var rest = head.substring(m.value.length).trimStart()

        var arg: String? = null
        if (rest.startsWith("<")) {
            val end = rest.indexOf('>')
            if (end > 0) {
                arg = rest.substring(1, end)
                rest = rest.substring(end + 1)
                // `<num>[,<num>[,...]]`: a list, the bracket belongs to the placeholder
                if (rest.startsWith("[")) {
                    val tail = rest.substringBefore(' ')
                    arg += tail.replace("<", "").replace(">", "")
                    rest = rest.substring(tail.length)
                }
            }
        } else if (rest.startsWith("[")) {
            // `[<x>]`, `[=<x>]`, or a value list the code writes in brackets (`--smode [none|drop|min]`)
            val end = rest.indexOf(']')
            if (end > 0) {
                arg = rest.substring(1, end).trim('<', '>', '=', ' ')
                rest = rest.substring(end + 1)
            }
        }
        val description = (rest.trim() + " " + wrapped).trim()
        // every command has -h; it isn't a parameter
        if (short == "-h" && long == "--help") return null
        fun named(set: Set<String>) = (short != null && short in set) || (long != null && long in set) || aliases.any { it in set }
        return HelpOption(short, long, arg, description, named(required), aliases, named(repeatable))
    }

    /**
     * The usage line names a few values the options block leaves out: a placeholder written
     * without `<>` (`-u hex` of hf 14b sim, `-d data`), or a whole option without a glossary
     * (`[-k Replace default key for NDEF]` of hf mfu ndefread has no options row). Takes them from there.
     */
    private fun fillFromUsage(usage: String, options: MutableList<HelpOption>, required: Set<String>, repeatable: Set<String>) {
        for ((name, value) in usageOptions(usage)) {
            val i = options.indexOfFirst { it.short == name || it.long == name || name in it.aliases }
            if (i >= 0) {
                val o = options[i]
                if (o.arg != null || value == null) continue
                val desc = o.description.removePrefix(value).trim()
                options[i] = HelpOption(o.short, o.long, value, desc, o.required, o.aliases, o.repeatable)
            } else if (name != "-h" && name != "--help") {
                val short = name.takeIf { !it.startsWith("--") }
                val long = name.takeIf { it.startsWith("--") }
                options += HelpOption(short, long, value, "", name in required, emptyList(), name in repeatable)
            }
        }
    }

    private val USAGE_NAME = Regex("""--?[A-Za-z0-9@*][\w+-]*""")

    /** The options of a usage line with the value written after each, if any: `[-k <hex>]`, `-u hex`, `[-hlv]`. */
    private fun usageOptions(usage: String): List<Pair<String, String?>> {
        val out = ArrayList<Pair<String, String?>>()
        // drop the command words: everything before the first `[`, `<` or `-`
        val start = usage.indexOfFirst { it == '[' || it == '<' || it == '-' }
        if (start < 0) return out
        var i = start
        fun value(raw: String): String? = raw.trim().removePrefix("<").removeSuffix(">").removePrefix("[").removeSuffix("]").trim().ifEmpty { null }
        while (i < usage.length) {
            val c = usage[i]
            when {
                c == '[' -> {
                    var depth = 0
                    var j = i
                    while (j < usage.length) {
                        if (usage[j] == '[') depth++
                        if (usage[j] == ']' && --depth == 0) break
                        j++
                    }
                    val inner = usage.substring(i + 1, minOf(j, usage.length)).trim()
                    if (inner.startsWith("--") || (inner.startsWith("-") && (inner.length == 2 || inner[2] == ' '))) {
                        val name = inner.substringBefore(' ')
                        out += name to (if (' ' in inner) value(inner.substringAfter(' ')) else null)
                    } else if (inner.startsWith("-") && ' ' !in inner) {
                        // a cluster of flags: [-hlv]
                        for (ch in inner.drop(1)) out += "-$ch" to null
                    }
                    i = j + 1
                }

                c == '-' -> {
                    // `-y[h]` (hf mf gen3freeze): the name ends where the bracket starts
                    val name = USAGE_NAME.find(usage, i)?.takeIf { it.range.first == i }?.value ?: usage.substring(i).substringBefore(' ')
                    var j = i + name.length
                    while (j < usage.length && usage[j] == ' ') j++
                    var v: String? = null
                    if (j < usage.length && usage[j] != '[' && usage[j] != '-') {
                        val end = if (usage[j] == '<') usage.indexOf('>', j).let { if (it < 0) usage.length else it + 1 } else usage.indexOf(' ', j).let { if (it < 0) usage.length else it }
                        v = value(usage.substring(j, end))
                        j = end
                    }
                    out += name to v
                    i = j
                }

                c == '<' -> i = usage.indexOf('>', i).let { if (it < 0) usage.length else it + 1 }
                else -> i++
            }
        }
        return out
    }

    /**
     * What the usage line marks with `...`: `[-k <hex>]...` (the option may repeat) and
     * `<hex> [<hex>]...` (the positional takes more than one value).
     */
    private fun repeatableIn(usage: String): Required {
        val options = HashSet<String>()
        val positionals = HashSet<String>()
        var at = usage.indexOf("...")
        while (at > 0) {
            val element = elementBefore(usage, at)
            if (element != null) {
                val inner = element.trim().removePrefix("[").removeSuffix("]").trim()
                when {
                    inner.startsWith("<") -> positionals += inner.substring(1).substringBefore('>')
                    inner.startsWith("--") -> options += inner.substringBefore(' ')
                    // a single short option, not a cluster of flags like -hv
                    inner.startsWith("-") && inner.substringBefore(' ').length == 2 -> options += inner.substringBefore(' ')
                }
            }
            at = usage.indexOf("...", at + 3)
        }
        // argtable writes an option that may come twice as two copies (`[-v] [-v]`), not with `...`
        for ((name, value) in usageOptions(usage).groupBy { it }.filterValues { it.size > 1 }.keys) {
            if (value == null || name.startsWith("-")) options += name
        }
        return Required(options, positionals)
    }

    /** The bracketed `[...]` or `<...>` element that ends right before [end], or null. */
    private fun elementBefore(s: String, end: Int): String? {
        val last = end - 1
        if (last < 0) return null
        val (open, close) = when (s[last]) {
            ']' -> '[' to ']'
            '>' -> '<' to '>'
            else -> return null
        }
        var depth = 0
        var i = last
        while (i >= 0) {
            when (s[i]) {
                close -> depth++
                open -> if (--depth == 0) return s.substring(i, end)
            }
            i--
        }
        return null
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

    // comments the help writes after an example command: `<-- credit key`, `(pm3 test key)`, a bare `->`
    private val TRAILING_NOTE = Regex("""\s+<--\s*(.*)$|\s{2,}\((.*)\)\s*$|\s+->\s*$""")

    private fun examplesIn(block: List<String>): List<HelpExample> =
        block.map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
            val parts = line.split(ARROW, limit = 2)
            var text = parts[0].trim()
            var note = parts.getOrNull(1)?.trim()
            TRAILING_NOTE.find(text)?.let { m ->
                val comment = (m.groups[1]?.value ?: m.groups[2]?.value)?.trim()?.ifEmpty { null }
                text = text.substring(0, m.range.first).trim()
                if (note == null) note = comment
            }
            HelpExample(text, note)
        }
}
