package io.github.zazaulola.proxmark5.parse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HelpTextTest {
    // a few commands copied from `proxmark3 --fulltext`, each with a different quirk
    private val dump: Map<String, CommandHelp> = HelpText.parseDump(
        checkNotNull(javaClass.getResource("/help/sample-dump.txt")) { "fixture missing" }.readText()
    )

    private fun help(name: String) = assertNotNull(dump[name], "no section for $name")

    @Test
    fun dumpIsSplitIntoCommandsWithOfflineFlag() {
        assertEquals(10, dump.size)
        assertEquals(false, help("hf mf dump").offline)
    }

    @Test
    fun readsDescriptionUsageAndExamples() {
        val h = help("hf mf dump")
        assertTrue(h.description.startsWith("Dump MIFARE Classic tag to file"))
        assertTrue("UID will be used as filename" in h.description)
        assertEquals(1, h.usage.size)
        assertTrue(h.usage[0].startsWith("hf mf dump [-hv]"))
        val mini = h.examples.first { it.text == "hf mf dump --mini" }
        assertEquals("MIFARE Mini", mini.note)
    }

    @Test
    fun optionsKeepBothNamesAndTheirArgument() {
        val h = help("hf mf dump")
        val file = h.options.first { it.short == "-f" }
        assertEquals("--file", file.long)
        assertEquals("fn", file.arg)
        assertEquals(ArgKind.FILE, file.kind)
        assertEquals("--file", file.name)
        assertEquals("Specify a filename for dump file", file.description)

        val mini = h.options.first { it.long == "--mini" }
        assertNull(mini.short)
        assertEquals(ArgKind.FLAG, mini.kind)
        assertEquals("--mini", mini.name)
    }

    @Test
    fun helpOptionIsLeftOut() {
        assertTrue(help("hf mf dump").options.none { it.long == "--help" || it.short == "-h" })
        assertFalse(help("hw tune").hasParameters)
    }

    @Test
    fun requiredOptionsAreTheOnesOutsideBrackets() {
        val h = help("hf mf rdbl") // [-habv] --blk <dec> [-c <dec>] [-k <hex>]
        assertTrue(h.options.first { it.long == "--blk" }.required)
        assertFalse(h.options.first { it.short == "-c" }.required)
        assertFalse(h.options.first { it.short == "-k" }.required)
        assertEquals(ArgKind.HEX, h.options.first { it.short == "-k" }.kind)
        assertEquals(ArgKind.NUMBER, h.options.first { it.long == "--blk" }.kind)
    }

    @Test
    fun choiceOptionsListTheirValues() {
        val h = help("hf mfdes auth")
        val algo = h.options.first { it.long == "--algo" }
        assertEquals(ArgKind.CHOICE, algo.kind)
        assertEquals(listOf("DES", "2TDEA", "3TDEA", "AES"), algo.choices)
        // the placeholder is wider than the column, so the description follows after one space
        assertEquals("Crypt algo", algo.description)
        assertEquals(listOf("native", "niso", "iso"), h.options.first { it.long == "--ccset" }.choices)
    }

    @Test
    fun usageWrappedOverSeveralLinesIsOneLine() {
        val h = help("hf mfdes auth")
        assertEquals(1, h.usage.size)
        assertTrue("[--aid <hex>]" in h.usage[0] && h.usage[0].endsWith("[--save]"))
        val fft = help("data fft")
        assertEquals(1, fft.usage.size)
        assertTrue(fft.usage[0].endsWith("[--bins <int>]"))
    }

    @Test
    fun positionalArgumentsComeFromTheOptionsRows() {
        val h = help("hf felica raw") // [-hackprs] [-n <dec>] <hex>
        val hex = h.positionals.single()
        assertEquals("hex", hex.name)
        assertEquals("raw bytes to send", hex.description)
        assertTrue(hex.required)
        // the <dec> in the usage belongs to -n and is not a second positional
        assertEquals(ArgKind.NUMBER, h.options.first { it.short == "-n" }.kind)
    }

    @Test
    fun positionalNamedOnlyInTheUsageIsKept() {
        val p = help("hf ksx6924 init").positionals.single()
        assertEquals("Mpda 4 bytes hex", p.name)
        assertTrue(p.required)
    }

    @Test
    fun commandsWithoutAnArgumentTableHaveNoParameters() {
        val h = help("hf 14a config")
        assertFalse(h.hasParameters)
        assertTrue(h.usage.isEmpty())
    }

    @Test
    fun parsesTheTextOfASingleHelpRun() {
        val text = """
            |Sniff the communication between reader and tag
            |
            |usage:
            |    hf 14a sniff [-hcri]
            |
            |options:
            |    -h, --help                     This help
            |    -c, --card                     triggered by first data from card
            |    -i, --interactive              Console will not be returned until sniff finishes
            |                                   or is aborted
            |
            |examples/notes:
            |     hf 14a sniff -c -r
        """.trimMargin()
        val h = HelpText.parse("\u001b[32m$text\u001b[0m")
        assertNull(h.offline)
        assertEquals(listOf("--card", "--interactive"), h.options.map { it.name })
        // a wrapped description is joined onto its option
        assertEquals("Console will not be returned until sniff finishes or is aborted", h.options[1].description)
        assertEquals("hf 14a sniff -c -r", h.examples.single().text)
    }
}
