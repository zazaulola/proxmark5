package io.github.zazaulola.proxmark5.parse

enum class Band { HF, LF }

/** One "Valid ... found" of `hf search` / `lf search` with the lines printed for it. */
data class CardHit(
    val band: Band,
    val title: String,
    /** UID for HF, the decoded ID for LF, as printed (hex) */
    val id: String?,
    val types: List<String>,
    val fields: List<Field>,
    val notes: List<String>,
)

data class SearchResult(val band: Band, val hits: List<CardHit>, val errors: List<String>) {
    val found get() = hits.isNotEmpty()
}

object CardSearch {
    private val VALID = Regex("^\\s*Valid (.+?) found!?\\s*$")
    private val HEX_BYTES = Regex("^([0-9A-Fa-f]{2}(?: [0-9A-Fa-f]{2})*)")
    private val LF_ID = Regex("\\bID\\s*:?\\s+([0-9A-Fa-f]{6,})\\b")

    /** Parses the grabbed output of `hf search` or `lf search`. */
    fun parse(grabbed: String, band: Band): SearchResult {
        val hits = ArrayList<CardHit>()
        val errors = ArrayList<String>()
        var pending = ArrayList<OutputLine>()

        for (line in ClientOutput.lines(grabbed)) {
            if (line.level == Level.ERROR) {
                errors += line.text.trim()
                continue
            }
            if (line.text.isBlank() || line.level == Level.DEBUG || line.level == Level.HINT) continue
            val valid = VALID.find(line.text)
            if (valid != null) {
                hits += hit(band, valid.groupValues[1].trim(), pending)
                pending = ArrayList()
                continue
            }
            pending += line
        }
        // what comes after the last "Valid ... found" still describes that card
        if (hits.isNotEmpty() && pending.isNotEmpty()) {
            val last = hits.removeAt(hits.size - 1)
            val more = hit(band, last.title, pending)
            hits += last.copy(
                id = last.id ?: more.id,
                types = last.types + more.types,
                fields = last.fields + more.fields,
                notes = last.notes + more.notes,
            )
        }
        return SearchResult(band, hits, errors)
    }

    private fun hit(band: Band, title: String, lines: List<OutputLine>): CardHit {
        val types = ArrayList<String>()
        val fields = ArrayList<Field>()
        val notes = ArrayList<String>()
        var id: String? = null
        var inTypes = false

        for (line in lines) {
            val text = line.text
            if (text.trimStart().startsWith("Searching for")) continue
            if (text.trim().equals("Possible types:", true)) {
                inTypes = true
                continue
            }
            if (inTypes && text.startsWith("  ") && ClientOutput.field(text) == null) {
                types += text.trim()
                continue
            }
            inTypes = false

            val f = ClientOutput.field(text)
            if (f != null && band == Band.HF && f.key.equals("UID", true) && id == null) {
                id = HEX_BYTES.find(f.value)?.groupValues?.get(1)
                continue
            }
            if (band == Band.LF && id == null) {
                LF_ID.find(text)?.let { id = it.groupValues[1] }
            }
            if (f != null) fields += f else notes += text.trim()
        }
        return CardHit(band, title, id, types, fields, notes.filter { it.isNotEmpty() && !it.all { c -> c == '-' } })
    }
}
