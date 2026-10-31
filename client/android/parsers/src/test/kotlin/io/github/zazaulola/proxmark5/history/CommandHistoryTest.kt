package io.github.zazaulola.proxmark5.history

import io.github.zazaulola.proxmark5.parse.CommandTree
import io.github.zazaulola.proxmark5.parse.output.FoundKind
import io.github.zazaulola.proxmark5.parse.output.FoundRef
import io.github.zazaulola.proxmark5.session.ResultCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandHistoryTest {
    private val uid = FoundRef(FoundKind.UID, "04A23B1A6F5E80", "UID")

    @Test
    fun runningALineAgainMovesItUpAndCountsIt() {
        val h = CommandHistory.EMPTY
            .record("hw status", 1)
            .record("hf 14a info", 2)
            .record("hw   status ", 3)
        assertEquals(listOf("hw status", "hf 14a info"), h.entries.map { it.line })
        assertEquals(2, h.entries[0].count)
        assertEquals(3, h.entries[0].lastAt)
        assertEquals(RunOutcome.PENDING, h.entries[0].outcome)
    }

    @Test
    fun blanksInsideQuotesStay() {
        assertEquals("""data save -f "my dump.bin"""", HistoryLine.normalize("""  data  save -f "my dump.bin"  """))
        assertEquals("""x 'a  b' c""", HistoryLine.normalize("""x   'a  b'   c"""))
        assertEquals("", HistoryLine.normalize("   "))
        assertTrue(CommandHistory.EMPTY.record("  ", 1).isEmpty)
    }

    @Test
    fun outcomeAndFoundComeWithTheResult() {
        var h = CommandHistory.EMPTY.record("hf 14a info", 1).finish("hf 14a info", 0, listOf(uid))
        assertEquals(RunOutcome.OK, h.entries[0].outcome)
        assertEquals(listOf(uid), h.entries[0].found)
        // a run cut short keeps what the last good run found
        h = h.record("hf 14a info", 2).finish("hf 14a info", ResultCode.DROPPED, emptyList())
        assertEquals(RunOutcome.STOPPED, h.entries[0].outcome)
        assertNull(h.entries[0].code)
        assertEquals(listOf(uid), h.entries[0].found)
        // a failed run that found nothing keeps it too; an OK run that found nothing clears it
        h = h.record("hf 14a info", 3).finish("hf 14a info", -7, emptyList())
        assertEquals(RunOutcome.FAILED, h.entries[0].outcome)
        assertEquals(-7, h.entries[0].code)
        assertEquals(listOf(uid), h.entries[0].found)
        h = h.record("hf 14a info", 4).finish("hf 14a info", 0, emptyList())
        assertEquals(emptyList(), h.entries[0].found)
    }

    @Test
    fun aRemovedLineStaysRemovedWhenItsResultArrives() {
        val h = CommandHistory.EMPTY.record("hw status", 1).remove("hw status").finish("hw status", 0, listOf(uid))
        assertTrue(h.isEmpty)
    }

    @Test
    fun pinnedLinesComeFirstAndOutliveTheLimit() {
        var h = CommandHistory.EMPTY.record("hw version", 0).pin("hw version", true)
        for (i in 1..CommandHistory.MAX_ENTRIES + 20) h = h.record("hf mf rdbl --blk $i", i.toLong())
        assertEquals(CommandHistory.MAX_ENTRIES + 1, h.entries.size)
        assertTrue(h.entries.any { it.line == "hw version" && it.pinned })
        assertEquals("hw version", h.quick(3)[0].line)
        assertEquals("hf mf rdbl --blk ${CommandHistory.MAX_ENTRIES + 20}", h.quick(3)[1].line)
        assertEquals(listOf("hw version"), h.clearUnpinned().entries.map { it.line })
    }

    @Test
    fun linesOfOneCommand() {
        val h = CommandHistory.EMPTY
            .record("hf mf rdbl --blk 0", 1)
            .record("hf mf rdsc -s 1", 2)
            .record("HF MF RDBL --blk 4 -k FFFFFFFFFFFF", 3)
            .record("hf mf", 4)
        assertEquals(listOf("HF MF RDBL --blk 4 -k FFFFFFFFFFFF", "hf mf rdbl --blk 0"), h.forCommand("hf mf rdbl").map { it.line })
    }

    @Test
    fun searchLooksAtTheLineAndWhatItFound() {
        val h = CommandHistory.EMPTY
            .record("hf 14a info", 1).finish("hf 14a info", 0, listOf(uid))
            .record("lf search", 2)
        assertEquals(listOf("hf 14a info"), h.search("04a23b").map { it.line })
        assertEquals(listOf("lf search"), h.search("LF sea").map { it.line })
        assertEquals(2, h.search("  ").size)
    }

    @Test
    fun jsonRoundTripAndPendingBecomesUnknown() {
        val h = CommandHistory.EMPTY
            .record("hf 14a info", 1).finish("hf 14a info", 0, listOf(uid, FoundRef(FoundKind.FILE, "/x/hf-mf-01020304-dump.bin")))
            .record("hw version", 2).pin("hw version", true)
            .record("""data save -f "a b.pm3"""", 3)
        val back = CommandHistory.fromJson(h.toJson())
        assertEquals(h.entries.map { it.line }, back.entries.map { it.line })
        assertEquals(h.entries.first { it.line == "hf 14a info" }, back.entries.first { it.line == "hf 14a info" })
        assertTrue(back.entries.first { it.line == "hw version" }.pinned)
        assertEquals(RunOutcome.UNKNOWN, back.entries.first { it.line == "hw version" }.outcome)
    }

    @Test
    fun brokenJsonGivesAnEmptyHistory() {
        assertTrue(CommandHistory.fromJson("").isEmpty)
        assertTrue(CommandHistory.fromJson("{\"v\":1,\"entries\":[{\"x\":1},{\"line\":\"hw status\",\"found\":[{\"k\":\"NOPE\",\"v\":\"1\"}]}]}").entries.size == 1)
    }

    @Test
    fun theCommandOfALine() {
        val root = CommandTree.parse(
            listOf(
                "hf\tHF\t1\tmf\tMF\t1\trdbl\tRead block\t1",
                "hf\tHF\t1\t14a\t14A\t1\traw\tRaw\t1",
                "hw\tHW\t1\tstatus\tStatus\t1",
            ).joinToString("\n"),
        )
        assertEquals("hf mf rdbl", CommandTree.commandOf(root, "hf mf rdbl --blk 0")?.line)
        assertEquals("hf 14a raw", CommandTree.commandOf(root, "HF 14A raw 3000")?.line)
        assertEquals("hw status", CommandTree.commandOf(root, "  hw   status")?.line)
        assertNull(CommandTree.commandOf(root, "hf mf"))
        assertNull(CommandTree.commandOf(root, "nope"))
    }
}
