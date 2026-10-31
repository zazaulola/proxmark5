package io.github.zazaulola.proxmark5.parse.params

import io.github.zazaulola.proxmark5.parse.HelpText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TagUiTest {
    // two rows: [0 1 2] at y 0..40, [3 4] at y 48..88; tags of different widths
    private val boxes = listOf(
        Box(0f, 0f, 100f, 40f), Box(108f, 0f, 160f, 40f), Box(168f, 0f, 300f, 40f),
        Box(0f, 48f, 60f, 88f), Box(68f, 48f, 200f, 88f),
    )

    @Test
    fun slotIsTheGapUnderTheFinger() {
        assertEquals(0, Reorder.slot(boxes, 10f, 20f))
        assertEquals(1, Reorder.slot(boxes, 120f, 20f))
        assertEquals(3, Reorder.slot(boxes, 290f, 20f)) // right half of the last tag of row 1
        assertEquals(3, Reorder.slot(boxes, 5f, 60f))
        assertEquals(5, Reorder.slot(boxes, 400f, 60f))
        // above everything / below everything / in the gap between rows
        assertEquals(1, Reorder.slot(boxes, 120f, -30f))
        assertEquals(4, Reorder.slot(boxes, 90f, 500f))
        assertEquals(4, Reorder.slot(boxes, 90f, 44f))
        assertEquals(0, Reorder.slot(emptyList(), 1f, 1f))
    }

    @Test
    fun targetAccountsForTheTagLeavingItsPlace() {
        assertEquals(0, Reorder.target(from = 2, slot = 0))
        assertEquals(2, Reorder.target(from = 2, slot = 2))
        assertEquals(2, Reorder.target(from = 2, slot = 3)) // just after itself: no move
        assertEquals(4, Reorder.target(from = 0, slot = 5))
    }

    @Test
    fun middleEllipsis() {
        assertEquals("ABCDEF", TagText.middle("ABCDEF", 10))
        assertEquals("ABCD…WXYZ", TagText.middle("ABCDEFGHIJKLMNOPQRSTUVWXYZ", 9))
        assertEquals(9, TagText.middle("A".repeat(64), 9).length)
    }

    @Test
    fun hexInputIsCleaned() {
        assertEquals("A0B1C2E3", TagText.cleanHex("a0 b1:c2-e3"))
        assertEquals("ABCE", TagText.cleanHex("АВСЕ")) // Cyrillic look-alikes
        assertEquals("1F2E", TagText.cleanHex("0x1F 0x2E"))
        assertEquals("", TagText.cleanHex("xyz"))
        assertEquals("A0 B1 C2", TagText.groupBytes("A0B1C2"))
        assertEquals("A0 B", TagText.groupBytes("A0B"))
    }

    @Test
    fun draftsSurviveEncoding() {
        val help = assertNotNull(
            HelpText.parseDump(checkNotNull(javaClass.getResource("/help/params-dump.txt")).readText())["hf mf rdbl"],
        )
        val specs = ParamSpecs.of("hf mf rdbl", help)
        val d = CommandDraft.start("hf mf rdbl", specs).let { it.setValue(it.tags[0].id, "4") }
            .add(specs.first { it.short == "-k" }, "FFFFFFFFFFFF").addRaw("--gone")
        val back = DraftCodec.decode("hf mf rdbl", DraftCodec.encode(d), specs)
        assertEquals(CommandLine.build(d), CommandLine.build(back))
        assertEquals(d.tags.map { it.id }, back.tags.map { it.id })
        // a parameter the help no longer has comes back as raw text
        val fewer = DraftCodec.decode("hf mf rdbl", DraftCodec.encode(d), specs.filter { it.short != "-k" })
        assertEquals("hf mf rdbl --blk 4 --key FFFFFFFFFFFF --gone", CommandLine.build(fewer))
    }

    @Test
    fun savedDraftKeepsTagIdsAfterRemovalsAndMoves() {
        // the screen saves "the editor of tag N is open": N must name the same tag after a restore
        val help = assertNotNull(
            HelpText.parseDump(checkNotNull(javaClass.getResource("/help/params-dump.txt")).readText())["hf mf rdbl"],
        )
        val specs = ParamSpecs.of("hf mf rdbl", help)
        var d = CommandDraft("hf mf rdbl", emptyList())
            .add(specs.first { it.long == "--blk" }, "1")
            .add(specs.first { it.short == "-k" }, "FFFFFFFFFFFF")
            .addRaw("--odd")
        d = d.remove(d.tags[0].id).move(d.tags[2].id, 0)
        val back = DraftCodec.decode("hf mf rdbl", DraftCodec.encode(d), specs)
        assertEquals(d.tags, back.tags)
        // new tags after the restore do not take a saved id
        val added = back.add(specs.first { it.long == "--blk" }, "2")
        assertEquals(added.tags.size, added.tags.map { it.id }.toSet().size)
    }

    @Test
    fun indexedDumpParsesTheSameAsTheWholeDump() {
        val text = HelpText.cleanDump(checkNotNull(javaClass.getResource("/help/params-dump.txt")).readText())
        val whole = HelpText.parseDump(text)
        val index = HelpText.indexDump(text)
        assertEquals(whole.keys, index.keys)
        for ((name, range) in index) {
            val one = assertNotNull(HelpText.parseIndexed(text, range))
            val all = whole.getValue(name)
            assertEquals(all.options.map { it.name to it.arg }, one.options.map { it.name to it.arg }, name)
            assertEquals(all.offline, one.offline, name)
        }
    }
}
