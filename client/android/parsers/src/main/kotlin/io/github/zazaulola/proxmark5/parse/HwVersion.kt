package io.github.zazaulola.proxmark5.parse

data class InfoSection(val title: String, val fields: List<Field>, val notes: List<String>)

/** What `hw version` says, see pm3_version() in client/src/cmdhw.c. */
data class HwVersion(
    /** "Proxmark5" or "Proxmark3" */
    val model: String?,
    val client: String?,
    val bootrom: String?,
    val os: String?,
    val firmwareMismatch: Boolean,
    val sections: List<InfoSection>,
) {
    companion object {
        fun parse(grabbed: String): HwVersion {
            val sections = ArrayList<InfoSection>()
            var title: String? = null
            var fields = ArrayList<Field>()
            var notes = ArrayList<String>()
            var model: String? = null
            var mismatch = false

            fun close() {
                val t = title ?: return
                if (fields.isNotEmpty() || notes.isNotEmpty()) {
                    sections += InfoSection(t, fields, notes)
                }
                fields = ArrayList()
                notes = ArrayList()
            }

            for (line in ClientOutput.lines(grabbed)) {
                val text = line.text
                if (text.isBlank()) continue
                if (text.contains("does not match the source")) {
                    mismatch = true
                }
                if (line.level == Level.WARNING || line.level == Level.ERROR) {
                    continue
                }
                val header = ClientOutput.section(text)
                if (header != null) {
                    close()
                    if (model == null && header.startsWith("Proxmark")) {
                        model = header
                        title = null
                    } else {
                        title = header
                    }
                    continue
                }
                if (title == null) continue
                val f = ClientOutput.field(text)
                if (f != null) fields += f else notes += text.trim()
            }
            close()

            fun find(section: String, key: String) =
                sections.firstOrNull { it.title.equals(section, true) }
                    ?.fields?.firstOrNull { it.key.equals(key, true) }?.value

            val client = sections.firstOrNull { it.title.equals("Client", true) }?.notes?.firstOrNull()
            return HwVersion(model, client, find("ARM", "Bootrom"), find("ARM", "OS"), mismatch, sections)
        }
    }
}
