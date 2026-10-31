package io.github.zazaulola.proxmark5.parse.params

/**
 * A parameter placed on a command: [spec] with its [value] (empty for a flag). A tag whose
 * [spec] is null is a raw token that reading a typed line did not recognise; it is kept as is so
 * that nothing the user wrote gets lost.
 */
data class ParamTag(val id: Long, val spec: ParamSpec?, val value: String, val raw: String? = null) {
    val label: String get() = spec?.label ?: raw.orEmpty()
}

/** The tags of one command in the order the user put them. Immutable: every edit returns a new draft. */
data class CommandDraft(val command: String, val tags: List<ParamTag>, val nextId: Long = tags.maxOfOrNull { it.id + 1 } ?: 1L) {

    /** Appends a tag for [spec]; the user moves it where they want. */
    fun add(spec: ParamSpec, value: String = ""): CommandDraft {
        val tag = ParamTag(nextId, spec, value)
        return copy(tags = tags + tag, nextId = nextId + 1)
    }

    fun addRaw(token: String): CommandDraft = copy(tags = tags + ParamTag(nextId, null, "", token), nextId = nextId + 1)

    fun remove(id: Long): CommandDraft = copy(tags = tags.filterNot { it.id == id })

    fun setValue(id: Long, value: String): CommandDraft = copy(tags = tags.map { if (it.id == id) it.copy(value = value) else it })

    /** Changes the text of the raw tag [id]; an empty text takes the tag away. */
    fun setRaw(id: Long, raw: String): CommandDraft =
        if (raw.isBlank()) remove(id) else copy(tags = tags.map { if (it.id == id && it.spec == null) it.copy(raw = raw.trim()) else it })

    /** Moves the tag [id] to position [to] (clamped). */
    fun move(id: Long, to: Int): CommandDraft {
        val from = tags.indexOfFirst { it.id == id }
        if (from < 0) return this
        val list = tags.toMutableList()
        val tag = list.removeAt(from)
        list.add(to.coerceIn(0, list.size), tag)
        return copy(tags = list)
    }

    fun moveBy(id: Long, delta: Int): CommandDraft {
        val from = tags.indexOfFirst { it.id == id }
        return if (from < 0) this else move(id, from + delta)
    }

    fun has(spec: ParamSpec): Boolean = tags.any { it.spec?.key == spec.key }

    companion object {
        /**
         * A new draft with an empty tag for every required parameter that takes a value, in the
         * help's order. A required FLAG is not put in: it is a confirmation (`-y` of an operation
         * that cannot be undone, `--force`), so the user adds it themselves; the check reports it missing.
         */
        fun start(command: String, specs: List<ParamSpec>): CommandDraft {
            var d = CommandDraft(command, emptyList())
            for (s in specs) if (s.required && !s.isFlag) d = d.add(s)
            return d
        }
    }
}
