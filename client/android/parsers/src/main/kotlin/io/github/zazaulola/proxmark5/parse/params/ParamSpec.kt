package io.github.zazaulola.proxmark5.parse.params

import io.github.zazaulola.proxmark5.parse.CommandHelp

/**
 * One parameter a command takes, as its own `-h` describes it: a named option (`--blk <dec>`,
 * `-k <hex>`, a flag like `-v`) or a positional value (`<hex>` of `hf 14a raw`).
 */
data class ParamSpec(
    /** Stable within one command: the long name, else the short one, else `<name>` of a positional. */
    val key: String,
    /** `-k`, or null when the option has only a long name (and for positionals). */
    val short: String?,
    /** `--key`, or null when the option has only a short name (and for positionals). */
    val long: String?,
    /** More long names for the same option, as the help lists them. */
    val aliases: List<String>,
    /** The placeholder inside `<...>` (`hex`, `dec`, `fn`, `native|niso|iso`), null for a flag. */
    val placeholder: String?,
    val description: String,
    val required: Boolean,
    /** May be given more than once (`[-k <hex>]...`, `<hex> [<hex>]...`). */
    val repeatable: Boolean,
    val positional: Boolean,
    val type: ValueType,
) {
    val isFlag: Boolean get() = type == ValueType.Flag

    /** What is typed on the command line before the value: `--blk`, `-c`; nothing for a positional. */
    val optionName: String? get() = if (positional) null else (long ?: short)

    /** A short label for the tag: `--blk`, `-c`, or `<hex>`. */
    val label: String get() = optionName ?: "<${placeholder ?: key}>"

    /** Every name the client accepts for this option. */
    val names: List<String> get() = listOfNotNull(short, long) + aliases
}

object ParamSpecs {
    /** The parameters of [command] (`hf mf rdbl`) from its parsed help, positionals after the options. */
    fun of(command: String, help: CommandHelp): List<ParamSpec> {
        val out = ArrayList<ParamSpec>()
        for (o in help.options) {
            val type = ValueTypes.classify(o.arg, o.description, command, positional = false, longName = o.long)
            out += ParamSpec(
                key = o.long ?: o.short ?: continue,
                short = o.short,
                long = o.long,
                aliases = o.aliases,
                placeholder = o.arg,
                description = o.description,
                required = o.required,
                repeatable = o.repeatable,
                positional = false,
                type = type,
            )
        }
        for (p in help.positionals) {
            out += ParamSpec(
                key = "<${p.name}>",
                short = null,
                long = null,
                aliases = emptyList(),
                placeholder = p.name,
                description = p.description,
                required = p.required,
                repeatable = p.repeatable,
                positional = true,
                type = ValueTypes.classify(p.name, p.description, command, positional = true),
            )
        }
        return out
    }
}
