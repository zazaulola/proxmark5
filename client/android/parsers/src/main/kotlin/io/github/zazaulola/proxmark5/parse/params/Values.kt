package io.github.zazaulola.proxmark5.parse.params

import java.math.BigDecimal

enum class Severity { ERROR, WARNING }

/**
 * What is wrong with a value. ERROR: the value cannot be put on a command line at all, so the
 * line is not sent. WARNING: the help suggests the client will refuse it; the user may still
 * send it, because the help's placeholders are sometimes wrong (a `<dec>` that takes `W3000`, a
 * `<hex>` that takes an odd number of digits or a file name).
 */
data class Problem(val severity: Severity, val message: String)

/**
 * What the client accepts, checked on the phone before anything is sent. The rules come from
 * the client itself (cliparser.c, argtable3.c), tried on a host build:
 * - a quote opens quoting only at the start of a token and either quote closes it; there is no
 *   escape, so a value can not start with a quote, nor hold both a quote and a space;
 * - an empty value, or two spaces in a row, reach the option as an empty argument;
 * - one value, or the joined pieces of a repeated one, holds at most 2048 characters;
 * - hex read by the usual helper is pairs of 0-9A-F (spaces inside a quoted value are fine).
 */
object Values {
    /** cliparser.c CLIParamStrToBuf: half of its 4096+1 byte buffer. */
    const val MAX_VALUE_CHARS = 2048

    private val HEX_SEPARATORS = Regex("""[\s:\-]""")
    private val INTEGER = Regex("""^[+-]?\d+$""")
    private val FRACTION = Regex("""^[+-]?\d+(\.\d+)?$""")
    private val HEX_NUMBER = Regex("""^(?i)0x[0-9a-f]+$""")
    private val BIN_NUMBER = Regex("""^(?i)0b[01]+$""")
    private val HEX_ONLY = Regex("""^[0-9A-Fa-f]*$""")

    /**
     * The value as it goes onto the command line. Only reformats what is clearly meant:
     * hex loses its separators and is upper-cased only when what is left is all hex digits;
     * `0x1F` in a number field becomes 31; a decimal comma becomes a dot.
     */
    fun normalize(type: ValueType, input: String): String {
        val v = input.trim()
        return when (type) {
            is ValueType.Hex -> {
                val bare = v.replace(HEX_SEPARATORS, "")
                when {
                    !HEX_ONLY.matches(bare) -> v
                    type.asInteger -> if (bare.isEmpty()) "" else "0x" + bare.uppercase()
                    else -> bare.uppercase()
                }
            }
            is ValueType.Bits -> v.replace(Regex("""\s"""), "")
            is ValueType.Number -> {
                val dotted = if (type.fraction) v.replace(',', '.') else v
                numberToDecimal(dotted) ?: dotted
            }
            is ValueType.Choice -> type.values.firstOrNull { it.equals(v, ignoreCase = true) } ?: v
            else -> v
        }
    }

    /** `0x1F` / `0b101` -> "31" / "5"; null when it is not written one of these ways. */
    fun numberToDecimal(v: String): String? = when {
        HEX_NUMBER.matches(v) -> v.substring(2).toBigInteger(16).toString()
        BIN_NUMBER.matches(v) -> v.substring(2).toBigInteger(2).toString()
        else -> null
    }

    /** Whether the client's tokenizer can carry [v] as one argument (see [CommandLine.build]). */
    fun representable(v: String): Problem? {
        if (v.any { it == '\n' || it == '\r' || (it.isISOControl() && it != '\t') }) return error("перевод строки в значении")
        if (v.startsWith('"') || v.startsWith('\'')) return error("значение не может начинаться с кавычки")
        if ((v.contains('"') || v.contains('\'')) && v.any { it == ' ' || it == '\t' }) {
            return error("кавычка и пробел в одном значении: клиент так не примет")
        }
        if (v.length > MAX_VALUE_CHARS) return error("длиннее $MAX_VALUE_CHARS символов")
        return null
    }

    /** The first problem of [value] for [spec] in [command], or null when it is fine. */
    fun check(spec: ParamSpec, value: String, command: String = ""): Problem? {
        val type = spec.type
        if (type == ValueType.Flag) return null
        val v = normalize(type, value)
        if (v.isEmpty()) return error("нужно значение")
        representable(v)?.let { return it }
        if (spec.positional && v.startsWith("-") && Quirks.readsItsOwnArguments(command)) {
            return warning("значение с «-» в начале клиент может принять за опцию")
        }
        return when (type) {
            is ValueType.Number -> checkNumber(type, v)
            is ValueType.Hex -> checkHex(type, v)
            is ValueType.Bits -> if (v.all { it == '0' || it == '1' }) null else warning("ожидаются только 0 и 1")
            is ValueType.Choice -> if (type.values.any { it.equals(v, ignoreCase = true) }) null else warning("нет в списке: ${type.values.joinToString(", ")}")
            is ValueType.Text -> if (type.digitsOnly && !v.all { it.isDigit() }) warning("ожидаются только цифры") else null
            is ValueType.File, ValueType.Flag -> null
        }
    }

    private fun checkNumber(type: ValueType.Number, v: String): Problem? {
        val ok = if (type.fraction) FRACTION.matches(v) else INTEGER.matches(v)
        if (!ok) return warning(if (type.fraction) "ожидается число, например 12 или 1.5" else "ожидается целое число")
        val n = runCatching { BigDecimal(v) }.getOrNull() ?: return warning("ожидается число")
        if (!type.fraction && n.abs() > BigDecimal("18446744073709551615")) return warning("слишком большое число")
        type.min?.let { if (n < BigDecimal(it)) return warning("не меньше $it") }
        type.max?.let { if (n > BigDecimal(it)) return warning("не больше $it") }
        // u64 options read "-5" with strtoull and get 18446744073709551611 without a word
        if (type.min == null && n.signum() < 0) return warning("отрицательное: клиент может прочитать его как огромное число")
        return null
    }

    private fun checkHex(type: ValueType.Hex, v: String): Problem? {
        val digits = if (type.asInteger) v.removePrefix("0x") else v
        val bad = digits.firstOrNull { it !in '0'..'9' && it !in 'A'..'F' && it !in 'a'..'f' }
        if (bad != null) return warning("«$bad» — не hex-символ")
        if (type.asInteger) return null
        if (digits.length % 2 != 0) return warning("нечётное число цифр: обычно нужны целые байты")
        val bytes = digits.length / 2
        type.exact?.let { if (!it.allows(bytes)) return warning("$bytes байт, ожидается ${it.describe()}") }
        return null
    }

    private fun error(message: String) = Problem(Severity.ERROR, message)
    private fun warning(message: String) = Problem(Severity.WARNING, message)
}

/** A problem of the whole draft: something missing or doubled, or the line too long. */
data class DraftProblem(val tagId: Long?, val problem: Problem)

object DraftCheck {
    /**
     * The client copies the arguments into a 4096 + 60 byte buffer (cliparser.h, MAX_INPUT_ARG_LENGTH)
     * and refuses a longer line; keep a margin for the program name it puts in front.
     */
    const val MAX_ARGS_CHARS = 4000

    fun problems(draft: CommandDraft, specs: List<ParamSpec>): List<DraftProblem> {
        val out = ArrayList<DraftProblem>()
        for (t in draft.tags) {
            val spec = t.spec
            if (spec == null) {
                t.raw?.let { raw -> Values.representable(raw)?.let { out += DraftProblem(t.id, it) } }
                continue
            }
            Values.check(spec, t.value, draft.command)?.let { out += DraftProblem(t.id, it) }
        }
        for (s in specs) {
            if (s.required && draft.tags.none { it.spec?.key == s.key }) {
                out += DraftProblem(null, Problem(Severity.WARNING, "нет обязательного ${s.label}"))
            }
        }
        val seen = HashSet<String>()
        for (t in draft.tags) {
            val spec = t.spec ?: continue
            // a few flags take a second copy (-vv) without the help saying so: only a warning
            if (!spec.repeatable && !seen.add(spec.key)) {
                out += DraftProblem(t.id, Problem(Severity.WARNING, "${spec.label} уже есть; по справке повторять нельзя"))
            }
        }
        val args = CommandLine.build(draft).removePrefix(draft.command.trim()).trim()
        if (args.length > MAX_ARGS_CHARS) {
            out += DraftProblem(null, Problem(Severity.ERROR, "строка длиннее $MAX_ARGS_CHARS символов, клиент её не примет"))
        }
        return out
    }

    fun canRun(problems: List<DraftProblem>): Boolean = problems.none { it.problem.severity == Severity.ERROR }
}
