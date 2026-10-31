package io.github.zazaulola.proxmark5.parse.params

/** Byte lengths a hex value may have: one or more ranges (`4 or 7 bytes`, `1-31 bytes`). */
data class ByteLengths(val ranges: List<IntRange>) {
    fun allows(n: Int): Boolean = ranges.any { n in it }

    /** "6", "4 или 7", "1–31", "8, 16 или 24" */
    fun describe(): String {
        val parts = ranges.map { if (it.first == it.last) "${it.first}" else "${it.first}–${it.last}" }
        return when (parts.size) {
            1 -> parts[0]
            else -> parts.dropLast(1).joinToString(", ") + " или " + parts.last()
        }
    }
}

/** What a file parameter is for; it only decides what the editor offers first. */
enum class FileRole { LOAD, SAVE, DICTIONARY, ANY }

/** The kind of value a parameter takes; it picks the editor and the checks. */
sealed interface ValueType {
    /** No value: the option alone switches something on. */
    data object Flag : ValueType

    /**
     * A number. The client's integer options also take `0x..`, `0b..` and `KB`, but some
     * `<dec>` values are read as plain text, so the app always sends decimal.
     */
    data class Number(val min: Long? = null, val max: Long? = null, val fraction: Boolean = false, val unit: String? = null) : ValueType

    /**
     * Bytes in hexadecimal. [exact] comes from the placeholder (`<4hex>`, `<hex 16 byte>`) and is
     * trusted; [hinted] comes from the description ("4 or 7 bytes") and is shown as a hint only,
     * because descriptions are sometimes loose ("12 hex bytes" for a 6-byte key). [asInteger]:
     * the option is an integer the help calls `<hex>` (see [Quirks]); it is sent as `0x..`.
     */
    data class Hex(val exact: ByteLengths? = null, val hinted: ByteLengths? = null, val asInteger: Boolean = false) : ValueType

    /** A string of 0 and 1 (`<bin>`). */
    data object Bits : ValueType

    data class File(val role: FileRole) : ValueType

    /** One of a fixed list; the client takes it in any case or by a unique prefix, the app sends it whole. */
    data class Choice(val values: List<String>, val default: String? = null) : ValueType

    /**
     * Free text. [suggestions] are the literal words of a mixed placeholder (`skip` of
     * `<name|cert|pubkey|path|skip>`); [digitsOnly] for `<digits>`; [hint] is the placeholder.
     */
    data class Text(val hint: String? = null, val suggestions: List<String> = emptyList(), val digitsOnly: Boolean = false) : ValueType
}

object ValueTypes {
    // placeholder words that stand for "some value", not a literal choice
    private val GENERIC = setOf(
        "path", "hex", "dec", "name", "cert", "pubkey", "str", "fn", "file", "key", "int", "num", "number",
        "string", "data", "value", "bin", "txt", "text", "pem", "der", "der-b64", "der-hex", "scalar-b64", "scalar-hex",
    )
    private val NUMBER_WORDS = setOf(
        "dec", "decimal", "int", "n", "nr", "num", "number", "index", "idx", "ms", "us", "sec", "s",
        "dbm", "db", "mah", "mv", "u8_data", "dbl", "float", "dec id", "dec, ms",
    )
    private val FRACTION_WORDS = setOf("dbl", "float", "db")
    private val FILE_WORDS = setOf("fn", "path", "filename", "fn w/o ext", "file")
    private val RANGE = Regex("""^(-?\d+)\s*(?:\.\.|-)\s*(-?\d+)?$""")
    private val KEY_RANGE = Regex("""^([a-z]+)(\d+)\.\.(?:[a-z]+)?(\d+)$""", RegexOption.IGNORE_CASE)

    fun classify(placeholder: String?, description: String, command: String, positional: Boolean, longName: String? = null): ValueType {
        val raw = placeholder?.trim() ?: return if (positional) ValueType.Text() else ValueType.Flag
        val p = raw.lowercase().removePrefix("[").removeSuffix("]").trim()

        if (p in FILE_WORDS) return ValueType.File(fileRole(command, description))
        // `lf em 410x brute -f, --file <hex>  file with EM Tag IDs`: the name and description win over the placeholder
        if (longName == "--file" && Regex("""(?i)\bfile\b""").containsMatchIn(description)) {
            return ValueType.File(fileRole(command, description))
        }
        if (Quirks.isHexInteger(command, longName)) return ValueType.Hex(asInteger = true)

        // `<0|1|...>`: any number, the list is only a sample
        if (p.endsWith("|...") || p.endsWith(",...")) return ValueType.Number()

        // `<num>[,<num>[,...]]`: a comma separated list
        if ("[," in p) return ValueType.Text(hint = raw)

        if ('|' in p || (p.count { it == '/' } >= 2 && ' ' !in p) || (',' in p && ' ' !in p)) {
            return choiceOrText(raw.removePrefix("[").removeSuffix("]").trim())
        }

        RANGE.matchEntire(p)?.let { m ->
            return ValueType.Number(m.groupValues[1].toLong(), m.groupValues[2].takeIf { it.isNotEmpty() }?.toLong())
        }
        if (p in NUMBER_WORDS || p.startsWith("dec ") || p.startsWith("dec,")) {
            return ValueType.Number(fraction = p in FRACTION_WORDS, unit = unitOf(p))
        }

        if (p == "bin" || p == "bitstr") return ValueType.Bits

        if ("hex" in p && ':' !in p) {
            return ValueType.Hex(exact = lengthInPlaceholder(p), hinted = HexLengths.fromDescription(description))
        }

        if (p == "digits" || p.endsWith(" digits")) return ValueType.Text(hint = raw, digitsOnly = true)
        return ValueType.Text(hint = raw)
    }

    private fun unitOf(p: String): String? = when (p) {
        "ms", "dec, ms" -> "мс"
        "us" -> "мкс"
        "sec", "s" -> "с"
        "dbm" -> "дБм"
        "db" -> "дБ"
        "mah" -> "мА·ч"
        "mv" -> "мВ"
        else -> null
    }

    /** `<a|b(default)|c>`, `<get/credit/debit>`, `<key0..key13|free|deny>`: a choice, unless a generic word is among them. */
    private fun choiceOrText(list: String): ValueType {
        val sep = when {
            '|' in list -> '|'
            '/' in list -> '/'
            else -> ','
        }
        var default: String? = null
        val values = ArrayList<String>()
        for (item in list.split(sep)) {
            var v = item.trim()
            if (v.endsWith("(default)", ignoreCase = true)) {
                v = v.dropLast("(default)".length).trim()
                default = v
            }
            if (v.isEmpty()) continue
            val range = KEY_RANGE.matchEntire(v)
            if (range != null) {
                val prefix = range.groupValues[1]
                val from = range.groupValues[2].toInt()
                val to = range.groupValues[3].toInt()
                if (to >= from && to - from <= 64) {
                    for (i in from..to) values += "$prefix$i"
                    continue
                }
            }
            values += v
        }
        val literal = values.filter { it.lowercase() !in GENERIC }
        return if (literal.size == values.size && values.isNotEmpty()) {
            ValueType.Choice(values, default)
        } else {
            ValueType.Text(hint = list, suggestions = literal)
        }
    }

    // `<4hex>` = 4 hex digits; `<hex 16 byte>`, `<hex 1..16 bytes>`, `<mpda 4 bytes hex>`, `<record 1byte hex>`
    private val DIGITS_HEX = Regex("""^(\d+)hex$""")
    private val BYTES_IN = Regex("""(\d+)\s*(?:\.\.|-)?\s*(\d+)?\s*bytes?""")

    private fun lengthInPlaceholder(p: String): ByteLengths? {
        DIGITS_HEX.matchEntire(p)?.let { m ->
            val digits = m.groupValues[1].toInt()
            return if (digits % 2 == 0) ByteLengths(listOf(digits / 2..digits / 2)) else null
        }
        val m = BYTES_IN.find(p) ?: return null
        val a = m.groupValues[1].toInt()
        val b = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: a
        return if (b >= a) ByteLengths(listOf(a..b)) else null
    }

    private val SAVE_VERBS = setOf("dump", "esave", "csave", "gsave", "save", "psave", "isen", "hardnested", "sniff")
    private val LOAD_VERBS = setOf(
        "load", "eload", "cload", "gload", "restore", "view", "sim", "clone", "decrypt", "encrypt", "upload",
        "loclass", "migrate", "ndefwrite", "verify", "run", "eview",
    )

    /** Save or load, judged by the command's last word and the description. */
    fun fileRole(command: String, description: String): FileRole {
        val d = description.lowercase()
        if ("dictionary" in d || ".dic" in d) return FileRole.DICTIONARY
        if (Regex("""\bkeys?\b|key ?file""").containsMatchIn(d)) return FileRole.LOAD
        val verb = command.trim().substringAfterLast(' ').lowercase()
        if (verb in SAVE_VERBS || Regex("""\b(save|output|write to file)\b""").containsMatchIn(d)) return FileRole.SAVE
        if (verb in LOAD_VERBS || Regex("""\b(load|input|read from|source|from file)\b""").containsMatchIn(d)) return FileRole.LOAD
        return FileRole.ANY
    }
}

/** Byte lengths named in a description: "6 bytes", "4 or 7 hex bytes", "1-31 hex bytes", "12 hex symbols", "2-byte". */
object HexLengths {
    private val SYMBOLS = Regex("""(?i)\b(\d+)\s*hex\s*(?:symbols|chars|characters|digits)\b""")
    private val SET = Regex("""(?i)<?\b(\d+(?:\s*(?:\||/|,|or)\s*\d+)*)>?\s*(?:hex\s*)?bytes?\b""")
    private val SPAN = Regex("""(?i)<?\b(\d+)\s*-\s*(\d+)>?\s*(?:hex\s*)?bytes?\b""")
    private val HYPHEN = Regex("""(?i)\b(\d+)-byte\b""")

    fun fromDescription(text: String): ByteLengths? {
        val ranges = ArrayList<IntRange>()
        SYMBOLS.findAll(text).forEach { m ->
            val n = m.groupValues[1].toInt()
            if (n % 2 == 0 && n > 0) ranges += n / 2..n / 2
        }
        SPAN.findAll(text).forEach { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            if (b >= a) ranges += a..b
        }
        if (ranges.isEmpty()) {
            SET.findAll(text).forEach { m ->
                m.groupValues[1].split(Regex("""\s*(?:\||/|,|or)\s*""")).mapNotNull { it.trim().toIntOrNull() }
                    .filter { it > 0 }.forEach { ranges += it..it }
            }
            HYPHEN.findAll(text).forEach { m -> m.groupValues[1].toInt().takeIf { it > 0 }?.let { ranges += it..it } }
        }
        if (ranges.isEmpty()) return null
        return ByteLengths(ranges.distinct().sortedBy { it.first })
    }
}

/**
 * What the help text gets wrong or leaves out, found by reading the client's source and trying a
 * host build. Kept small and explicit: everything else is read from the help.
 */
object Quirks {
    // integer options (arg_int) whose help says `<hex>`: plain "10" would be read as decimal ten
    private val HEX_INTEGERS = setOf(
        "data hexsamples" to "--offset", // cmddata.c
        "hf mfp chconf" to "--conf",
        "hw lcd" to "--raw",
    )

    // commands that read their arguments themselves, in their own order (cmddata.c rawdemod looks
    // at the first characters, cmdscript.c script run uses sscanf): never add `--` or reorder
    private val OWN_PARSER = setOf("data rawdemod", "script run")

    fun isHexInteger(command: String, longName: String?): Boolean = longName != null && (command.trim() to longName) in HEX_INTEGERS

    fun readsItsOwnArguments(command: String): Boolean = command.trim() in OWN_PARSER
}
