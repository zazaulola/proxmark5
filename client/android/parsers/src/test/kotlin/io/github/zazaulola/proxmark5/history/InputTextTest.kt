package io.github.zazaulola.proxmark5.history

import kotlin.test.Test
import kotlin.test.assertEquals

class InputTextTest {
    @Test
    fun aPieceStandsAsAWordOfItsOwn() {
        assertEquals(Edited("hf mf rdbl -k FFFFFFFFFFFF ", 27), InputText.insert("hf mf rdbl -k", 13, 13, "FFFFFFFFFFFF"))
        assertEquals(Edited("hf mf rdbl -k FFFFFFFFFFFF ", 27), InputText.insert("hf mf rdbl -k ", 14, 14, "FFFFFFFFFFFF"))
        assertEquals(Edited("04A2 ", 5), InputText.insert("", 0, 0, " 04A2 "))
        // in the middle, before a word: one space on each side
        assertEquals(Edited("a X b", 4), InputText.insert("a b", 2, 2, "X"))
        assertEquals(Edited("a X b", 4), InputText.insert("ab", 1, 1, "X"))
    }

    @Test
    fun theSelectionIsReplaced() {
        assertEquals(Edited("hf 14a info --uid 0102 ", 23), InputText.insert("hf 14a info --uid XXXX", 18, 22, "0102"))
        assertEquals(Edited("x Y z", 4), InputText.insert("x old z", 6, 2, "Y"))
    }

    @Test
    fun aPathWithABlankIsQuoted() {
        assertEquals(Edited("""hf mf view -f "/x/my dump.bin" """, 31), InputText.insert("hf mf view -f", 13, 13, "/x/my dump.bin"))
    }

    @Test
    fun nothingToInsertLeavesTheLine() {
        assertEquals(Edited("abc", 2), InputText.insert("abc", 2, 2, "  "))
    }

    @Test
    fun ago() {
        val now = 1_800_000_000_000L
        assertEquals("только что", HistoryTime.ago(now, now - 5_000))
        assertEquals("5 мин", HistoryTime.ago(now, now - 5 * 60_000))
        assertEquals("3 ч", HistoryTime.ago(now, now - 3 * 3_600_000))
        assertEquals("вчера", HistoryTime.ago(now, now - 30 * 3_600_000L))
        assertEquals("4 дн", HistoryTime.ago(now, now - 4 * 86_400_000L))
    }
}
