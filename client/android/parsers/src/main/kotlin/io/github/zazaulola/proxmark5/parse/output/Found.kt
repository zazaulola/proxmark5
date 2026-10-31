package io.github.zazaulola.proxmark5.parse.output

/** What a piece of client output is, for the actions the terminal offers on it. */
enum class FoundKind {
    /** A file the client saved or loaded: share it, put its path on a command line. */
    FILE,

    /** A card's identifier: UID, CSN, IDm, an LF tag id. */
    UID,

    /** A key or password that worked. */
    KEY,

    /** Bytes of a block, a page, ATQA/SAK/ATS, raw data: copy as hex. */
    HEX,

    /** A command the client suggests (`Hint: Try \`hf mf autopwn\``): put it in the input, run it. */
    COMMAND,
}

/**
 * One item found in a line of output: [start] to [end] (exclusive) in the line's text without
 * ANSI codes, [value] what a copy takes (hex without spaces, the path as printed), [label] what the
 * client called it ("UID", "Key A", "FC") when the line says.
 */
data class Found(val kind: FoundKind, val start: Int, val end: Int, val value: String, val label: String? = null) {
    fun ref(): FoundRef = FoundRef(kind, value, label)
}

/** A found item without its place in a line: what history keeps of a command's output. */
data class FoundRef(val kind: FoundKind, val value: String, val label: String? = null)
