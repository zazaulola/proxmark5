package io.github.zazaulola.proxmark5.parse.params

/**
 * Turns tags into the line the client gets, and a line back into tags.
 *
 * The rules mirror the client (cliparser.c CLIParserParseStringEx, the getopt in argtable3.c),
 * tried on a host build: tokens are split on spaces and tabs; a `"` or `'` at the START of a token
 * opens quoting and either of them closes it (inside a token a quote is an ordinary character);
 * whatever follows a closing quote up to the next space is lost; there is no escape.
 * `--name value` and `--name=value` both work, as does a prefix of a long name that fits only one
 * name; `-x value` and `-xvalue` work, flags cluster (`-abv`), and a short option's value is the
 * next token even if it starts with `-`. Positionals may stand anywhere; after `--` everything is
 * positional.
 */
object CommandLine {

    /**
     * The line for [draft]: the command words, then every tag in the user's order, one space
     * apart. A value with a space goes in double quotes. With [placeholders], a tag that has no
     * value yet shows its placeholder (`--blk <dec>`), so the preview reads well; without, such a
     * tag is left out entirely (the draft check reports it anyway).
     *
     * A positional value that starts with `-` would be read as an option: then the options go
     * first, `--`, and the positionals after it, each group in the user's order. Commands that
     * read their arguments themselves ([Quirks]) get the tags exactly in the user's order.
     */
    fun build(draft: CommandDraft, placeholders: Boolean = false): String {
        val words = ArrayList<String>()
        val positional = ArrayList<String>()
        var dashed = false
        for (t in draft.tags) {
            val spec = t.spec
            if (spec == null) {
                t.raw?.takeIf { it.isNotBlank() }?.let { words += quoteIfNeeded(it) }
                continue
            }
            if (spec.isFlag) {
                spec.optionName?.let { words += it }
                continue
            }
            val v = Values.normalize(spec.type, t.value)
            val shown = when {
                v.isNotEmpty() -> quoteIfNeeded(v)
                placeholders -> "<${spec.placeholder ?: "?"}>"
                else -> continue
            }
            if (spec.positional) {
                if (v.startsWith("-")) dashed = true
                positional += shown
                words += POSITIONAL_MARK + shown
            } else {
                spec.optionName?.let { words += it }
                words += shown
            }
        }
        val parts = ArrayList<String>()
        parts += draft.command.trim()
        if (dashed && !Quirks.readsItsOwnArguments(draft.command)) {
            parts += words.filterNot { it.startsWith(POSITIONAL_MARK) }
            parts += "--"
            parts += positional
        } else {
            parts += words.map { it.removePrefix(POSITIONAL_MARK) }
        }
        return parts.joinToString(" ")
    }

    // marks a positional among the built words; a real word never starts with it
    private const val POSITIONAL_MARK = "\u0000"

    private fun quoteIfNeeded(v: String): String = if (v.any { it == ' ' || it == '\t' }) "\"$v\"" else v

    /**
     * Splits [text] the way the client does. Runs of spaces count as one: the app never sends
     * them (the client would read an empty argument between them).
     */
    fun tokenize(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inToken = false
        var quoted = false
        var lost = false // a quote closed inside this token: the rest of the token is dropped
        for (c in text) {
            val quote = c == '"' || c == '\''
            when {
                quoted -> if (quote) {
                    quoted = false
                    lost = true
                } else if (!lost) {
                    sb.append(c)
                }

                c == ' ' || c == '\t' -> {
                    if (inToken) out += sb.toString()
                    sb.setLength(0)
                    inToken = false
                    lost = false
                }

                // after a closing quote a new quote opens again (cliparser.c falls back to its
                // first-character state), but what it holds is lost too
                lost -> if (quote) quoted = true

                quote && !inToken -> {
                    quoted = true
                    inToken = true
                }

                else -> {
                    sb.append(c)
                    inToken = true
                }
            }
        }
        if (inToken) out += sb.toString()
        return out
    }

    /**
     * Reads [text] (a line from an example, the history or typed by hand) into a draft of
     * [command] against [specs]. The command words in front are skipped when they match.
     * A token that fits no parameter becomes a raw tag, so nothing is lost.
     */
    fun parse(command: String, text: String, specs: List<ParamSpec>): CommandDraft {
        val words = command.trim().split(Regex("\\s+"))
        var tokens = tokenize(text.trim())
        if (tokens.size >= words.size && tokens.take(words.size).map { it.lowercase() } == words.map { it.lowercase() }) {
            tokens = tokens.drop(words.size)
        }

        var d = CommandDraft(command.trim(), emptyList())
        val positionals = specs.filter { it.positional }
        var pos = 0 // the positional the next bare value goes to
        var onlyPositionals = false

        fun addPositional(token: String) {
            if (pos >= positionals.size) {
                d = d.addRaw(token)
                return
            }
            val spec = positionals[pos]
            d = d.add(spec, token)
            if (!spec.repeatable) pos++
        }

        var i = 0
        while (i < tokens.size) {
            val tok = tokens[i]
            i++
            when {
                onlyPositionals -> addPositional(tok)
                tok == "--" -> onlyPositionals = true
                tok.startsWith("--") -> {
                    val name = tok.substringBefore('=')
                    val eq = if ('=' in tok) tok.substringAfter('=') else null
                    val spec = findLong(name, specs)
                    when {
                        spec == null -> d = d.addRaw(tok)
                        spec.isFlag -> d = if (eq == null) d.add(spec) else d.addRaw(tok)
                        eq != null -> d = d.add(spec, eq)
                        i < tokens.size -> d = d.add(spec, tokens[i++])
                        else -> d = d.add(spec)
                    }
                }

                tok.length >= 2 && tok[0] == '-' -> {
                    val parsed = parseShortCluster(tok, specs)
                    if (parsed == null) {
                        d = d.addRaw(tok)
                    } else {
                        for ((spec, attached) in parsed) {
                            d = when {
                                spec.isFlag -> d.add(spec)
                                attached != null -> d.add(spec, attached)
                                i < tokens.size -> d.add(spec, tokens[i++])
                                else -> d.add(spec)
                            }
                        }
                    }
                }

                else -> addPositional(tok)
            }
        }
        return d
    }

    /**
     * `--blk`, or a prefix of exactly one long name, as getopt_long matches it. Each alias is a
     * name of its own there: a prefix of two aliases of the same option is ambiguous too.
     */
    private fun findLong(name: String, specs: List<ParamSpec>): ParamSpec? {
        val options = specs.filter { !it.positional }
        options.firstOrNull { s -> s.long == name || name in s.aliases }?.let { return it }
        val hits = options.flatMap { s -> (listOfNotNull(s.long) + s.aliases).filter { it.startsWith(name) }.map { s } }
        return hits.singleOrNull()
    }

    /**
     * `-abv` (flags), `-k FFFF` / `-kFFFF` (a value option, last in the cluster): the options of
     * the cluster with the value attached to the last one, or null if a letter is unknown.
     */
    private fun parseShortCluster(tok: String, specs: List<ParamSpec>): List<Pair<ParamSpec, String?>>? {
        val out = ArrayList<Pair<ParamSpec, String?>>()
        var j = 1
        while (j < tok.length) {
            val spec = specs.firstOrNull { !it.positional && it.short == "-${tok[j]}" } ?: return null
            if (spec.isFlag) {
                out += spec to null
                j++
            } else {
                val rest = tok.substring(j + 1)
                out += spec to rest.ifEmpty { null }
                return out
            }
        }
        return out
    }
}
