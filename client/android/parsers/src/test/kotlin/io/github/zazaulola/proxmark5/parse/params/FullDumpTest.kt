package io.github.zazaulola.proxmark5.parse.params

import io.github.zazaulola.proxmark5.parse.HelpText
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Runs the editor's model over the whole `proxmark3 --fulltext` dump when one is given in the
 * PM3_HELP_DUMP environment variable (CI builds it for the APK); skipped otherwise. Every example
 * the help gives must read into known parameters, and the line built from it must read back the same.
 */
class FullDumpTest {
    // examples the client itself refuses, checked against its source (see ParamsTest)
    private val brokenExamples = setOf(
        "emv genac -Daac 01020304",
        "hf 14a apdu -sd -d 00A404000E325041592E5359532E444446303100",
        // the help lists --mfc-license/--ka/--kb, not --mfc-keys/--mfc-blocks
        "hf mfdes createmfcmapping --aid 123456 --fid 01 --mfc-keys FFFFFFFFFFFF111111111111222222222222 --mfc-blocks 4,5,6,8",
    )

    @Test
    fun everyExampleOfTheClientReadsBack() {
        val path = System.getenv("PM3_HELP_DUMP") ?: return
        val dump = HelpText.parseDump(File(path).readText())
        assertTrue(dump.size > 900, "only ${dump.size} commands in $path")

        var examples = 0
        val unknown = ArrayList<String>()
        val unstable = ArrayList<String>()
        val types = HashMap<String, Int>()
        for ((cmd, help) in dump) {
            val specs = ParamSpecs.of(cmd, help)
            for (s in specs) types.merge(s.type.javaClass.simpleName, 1, Int::plus)
            for (ex in help.examples) {
                if (!ex.text.startsWith("$cmd ") || ex.text in brokenExamples) continue
                examples++
                val d = CommandLine.parse(cmd, ex.text, specs)
                val raw = d.tags.filter { it.spec == null && it.raw != "-h" }
                if (raw.isNotEmpty()) unknown += "$cmd: `${ex.text}` -> ${raw.map { it.raw }}"
                val again = CommandLine.parse(cmd, CommandLine.build(d), specs)
                val a = d.tags.map { t -> t.spec?.key to (t.spec?.let { Values.normalize(it.type, t.value) } ?: t.raw) }
                val b = again.tags.map { t -> t.spec?.key to (if (t.spec != null) t.value else t.raw) }
                if (a != b) unstable += "$cmd: `${ex.text}`"
            }
        }
        val report = buildString {
            appendLine("commands ${dump.size}, examples $examples, unknown ${unknown.size}, unstable ${unstable.size}")
            appendLine("types $types")
            unknown.forEach { appendLine("UNKNOWN $it") }
            unstable.forEach { appendLine("UNSTABLE $it") }
        }
        System.getenv("PM3_HELP_REPORT")?.let { File(it).writeText(report) }
        // every parameter as a TSV row, to compare with other sources by hand
        System.getenv("PM3_SPECS_OUT")?.let { out ->
            File(out).printWriter().use { w ->
                for ((cmd, help) in dump) for (s in ParamSpecs.of(cmd, help)) {
                    w.println(listOf(cmd, s.short, s.long, s.placeholder, s.required, s.repeatable, s.positional, s.type).joinToString("\t"))
                }
            }
        }
        assertTrue(unstable.isEmpty(), report)
    }
}
