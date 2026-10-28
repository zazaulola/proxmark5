package io.github.zazaulola.proxmark5.parse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandTreeTest {
    // name, help, available per level; levels joined by TABs; one command per line
    private val sample = listOf(
        "help\tUse help\t1",
        "clear\tClear screen\t1",
        "hf\t{ High frequency commands... }\t1\tsearch\tSearch for known HF tags\t1",
        "hf\t{ High frequency commands... }\t1\tmf\t{ MIFARE Classic RFIDs... }\t1\tdump\tDump MIFARE Classic tag to file\t1",
        "hf\t{ High frequency commands... }\t1\tmf\t{ MIFARE Classic RFIDs... }\t1\tsim\tSimulate MIFARE card\t0",
        "hw\t{ Hardware commands... }\t0\tstatus\t\u001b[32mShow status\u001b[0m\t1",
        "reveng\tCRC calculations\t1",
        "",
    ).joinToString("\n")

    @Test
    fun buildsHierarchyInWalkOrder() {
        val root = CommandTree.parse(sample)
        assertEquals(listOf("help", "clear", "hf", "hw", "reveng"), root.children.map { it.name })
        val mf = root.find(listOf("hf", "mf"))
        assertNotNull(mf)
        assertTrue(mf.isGroup)
        assertEquals(listOf("dump", "sim"), mf.children.map { it.name })
        assertEquals("hf mf dump", root.find(listOf("hf", "mf", "dump"))?.line)
    }

    @Test
    fun cleansCategoryAndAnsiHelp() {
        val root = CommandTree.parse(sample)
        assertEquals("High frequency commands", root.child("hf")?.help)
        assertEquals("MIFARE Classic RFIDs", root.find(listOf("hf", "mf"))?.help)
        assertEquals("Show status", root.find(listOf("hw", "status"))?.help)
    }

    @Test
    fun availabilityIsInheritedFromCategories() {
        val root = CommandTree.parse(sample)
        assertTrue(root.find(listOf("hf", "mf", "dump"))!!.available)
        assertFalse(root.find(listOf("hf", "mf", "sim"))!!.available)
        // hw is unavailable, so is everything below it even though the command says 1
        assertFalse(root.child("hw")!!.available)
        assertFalse(root.find(listOf("hw", "status"))!!.available)
    }

    @Test
    fun commandsThatLookLikeCategoriesStayLeaves() {
        val root = CommandTree.parse(sample)
        val reveng = root.child("reveng")
        assertNotNull(reveng)
        assertFalse(reveng.isGroup)
    }

    @Test
    fun ignoresMalformedRows() {
        val root = CommandTree.parse("only\ttwo\nok\thelp\t1\n\t\t\n")
        assertEquals(listOf("ok"), root.children.map { it.name })
    }

    @Test
    fun searchMatchesLineAndDescription() {
        val root = CommandTree.parse(sample)
        assertEquals(listOf("hf mf dump"), CommandTree.search(root, "mf dump").map { it.line })
        assertEquals(listOf("hf mf sim"), CommandTree.search(root, "simulate").map { it.line })
        assertEquals(listOf("hf search"), CommandTree.search(root, "SEARCH HF").map { it.line })
        assertTrue(CommandTree.search(root, "  ").isEmpty())
        assertEquals(7, root.leaves().count())
    }

    @Test
    fun findOutsideTreeIsNull() {
        assertNull(CommandTree.parse(sample).find(listOf("hf", "nope")))
    }
}

class ActivitiesTest {
    @Test
    fun classifiesSniffingAndEmulation() {
        assertEquals(ActivityKind.SNIFFER, Activities.of("hf 14a sniff"))
        assertEquals(ActivityKind.SNIFFER, Activities.of("  LF SNIFF  "))
        assertEquals(ActivityKind.SNIFFER, Activities.of("hf iclass sniff"))
        assertEquals(ActivityKind.EMULATION, Activities.of("hf 14a sim -t 1"))
        assertEquals(ActivityKind.EMULATION, Activities.of("lf em 410x sim --id 1234"))
        assertEquals(ActivityKind.EMULATION, Activities.of("lf simask"))
        assertEquals(ActivityKind.COMMAND, Activities.of("hw version"))
        assertEquals(ActivityKind.COMMAND, Activities.of("hf mf autopwn"))
        assertEquals(ActivityKind.COMMAND, Activities.of(""))
    }

    @Test
    fun argumentsDoNotChangeTheKind() {
        // "sim" far past the command words is a file name, not the sim command
        assertEquals(ActivityKind.COMMAND, Activities.of("hf mf dump -f sim"))
        assertEquals(ActivityKind.COMMAND, Activities.of("data load -f a b sniff"))
    }

    @Test
    fun readsPercentFromProgressLines() {
        assertEquals(42, Activities.percent("[=] Reading ... 42%"))
        assertEquals(7, Activities.percent("\u001b[33m[|]\u001b[0m 7 %"))
        assertEquals(100, Activities.percent("done 100%"))
        assertNull(Activities.percent("[=] no progress here"))
        assertNull(Activities.percent("150% of nothing"))
        assertNull(Activities.percent("version 1.250% x"))
    }

    @Test
    fun formatsElapsedTime() {
        assertEquals("0:00", Activities.elapsed(0))
        assertEquals("1:15", Activities.elapsed(75_000))
        assertEquals("1:02:05", Activities.elapsed(3_725_000))
        assertEquals("0:00", Activities.elapsed(-5))
    }
}

class LibraryTest {
    @Test
    fun sortsFilesOntoShelves() {
        assertEquals(LibraryKind.KEYS, Library.classify("hf-mf-04A2B3C4-key.bin"))
        assertEquals(LibraryKind.KEYS, Library.classify("hf-mf-04A2B3C4-key-001.bin"))
        assertEquals(LibraryKind.KEYS, Library.classify("mfc_default_keys.dic"))
        assertEquals(LibraryKind.KEYS, Library.classify("mine.KEYS"))
        assertEquals(LibraryKind.DUMPS, Library.classify("hf-mf-04A2B3C4-dump.bin"))
        assertEquals(LibraryKind.DUMPS, Library.classify("hf-mf-04A2B3C4-dump.json"))
        assertEquals(LibraryKind.DUMPS, Library.classify("card.eml"))
        assertEquals(LibraryKind.DUMPS, Library.classify("keyboard-dump.bin"))
        assertEquals(LibraryKind.LOGS, Library.classify("session-20261028-011530.log"))
        assertEquals(LibraryKind.LOGS, Library.classify("hf-14a-trace.trace"))
        assertNull(Library.classify(".hidden"))
        assertNull(Library.classify(""))
    }

    @Test
    fun formatsSizes() {
        assertEquals("0 Б", Library.humanSize(0))
        assertEquals("1023 Б", Library.humanSize(1023))
        assertEquals("1.0 КБ", Library.humanSize(1024))
        assertEquals("1.5 КБ", Library.humanSize(1536))
        assertEquals("2.0 МБ", Library.humanSize(2L * 1024 * 1024))
    }

    @Test
    fun namesLogFiles() {
        assertEquals("session-20261028-011530.log", Library.logFileName(2026, 10, 28, 1, 15, 30))
    }
}
