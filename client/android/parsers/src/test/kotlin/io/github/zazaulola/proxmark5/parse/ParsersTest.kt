package io.github.zazaulola.proxmark5.parse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnsiTest {
    @Test
    fun colorsAndReset() {
        val l = Ansi.parse("[\u001b[32m+\u001b[0m] UID: \u001b[1;32m04 A2\u001b[0m done")
        assertEquals("[+] UID: 04 A2 done", l.text)
        assertEquals(2, l.spans.size)
        assertEquals(StyleSpan(1, 2, fg = AnsiColor.GREEN), l.spans[0])
        assertEquals(StyleSpan(9, 14, fg = AnsiColor.GREEN, bold = true), l.spans[1])
    }

    @Test
    fun dropsCursorSequences() {
        assertEquals("ab", Ansi.parse("a\u001b[2Kb\u001b[?25l").text)
        assertEquals("plain", Ansi.strip("\u001b[33mplain\u001b[0m"))
    }
}

class TerminalBufferTest {
    @Test
    fun carriageReturnOverwritesLine() {
        val t = TerminalBuffer()
        t.append("[=] start\n\r[|] Searching for Topaz tag...")
        t.append("\r[/] Searching for LTO")
        t.append("-CM tag...\r                    \r[+] \n[+] Valid ISO 14443-A tag found\n")
        assertEquals(listOf("[=] start", "[+] ", "[+] Valid ISO 14443-A tag found"), t.snapshot())
    }

    @Test
    fun escapeSplitAcrossChunks() {
        val t = TerminalBuffer()
        t.append("[\u001b[3")
        t.append("2m+\u001b[0m] ok\r\nnext")
        assertEquals(listOf("[\u001b[32m+\u001b[0m] ok", "next"), t.snapshot())
    }

    @Test
    fun keepsLastLines() {
        val t = TerminalBuffer(maxLines = 3)
        repeat(10) { t.append("line $it\n") }
        assertEquals(listOf("line 7", "line 8", "line 9"), t.snapshot())
    }
}

class HwVersionTest {
    private val pm5 = """

 [ Proxmark5 ]

 [ Client ]
  Iceman/master/v4.20728-123-gabcdef12 2026-10-23 20:00:00 1234abcd
  Compiler.................. Clang/LLVM 18.0.3
  Platform.................. Android / aarch64
  Lua script support........ present ( 5.4.7 )

 [ Model ]
  Firmware.................. PM5
  External flash............ present

 [ ARM ]
  Bootrom.... Iceman/master/v4.20728-120-g11111111 2026-10-20 10:00:00 deadbeef
  OS......... Iceman/master/v4.20728-120-g11111111 2026-10-20 10:00:01 deadbeef
  Compiler... GCC 13.2.1 20231009

 [ Hardware ]
  --= uC: AT32F437
  --= Nonvolatile Program Memory Size: 4032 KB, Used: 512000 bytes (12%)
  --= Processor Unique ID: 0x0102030405

[!] ARM firmware does not match the source at the time the client was compiled
[!] Make sure to flash a correct and up-to-date version
"""

    @Test
    fun parsesSections() {
        val v = HwVersion.parse(pm5)
        assertEquals("Proxmark5", v.model)
        assertEquals("Iceman/master/v4.20728-123-gabcdef12 2026-10-23 20:00:00 1234abcd", v.client)
        assertEquals("Iceman/master/v4.20728-120-g11111111 2026-10-20 10:00:01 deadbeef", v.os)
        assertTrue(v.bootrom!!.startsWith("Iceman/master"))
        assertTrue(v.firmwareMismatch)
        assertEquals(listOf("Client", "Model", "ARM", "Hardware"), v.sections.map { it.title })
        val hw = v.sections.last()
        assertEquals(Field("uC", "AT32F437"), hw.fields[0])
        assertEquals("Processor Unique ID", hw.fields[2].key)
        assertEquals("PM5", v.sections[1].fields[0].value)
    }

    @Test
    fun offline() {
        val v = HwVersion.parse("\n [ Proxmark3 ]\n\n [ Client ]\n  Iceman/master/release (git)\n  Compiler.................. GCC 13.3.0\n")
        assertEquals("Proxmark3", v.model)
        assertNull(v.os)
        assertFalse(v.firmwareMismatch)
    }
}

class CardSearchTest {
    @Test
    fun mifareClassic() {
        val out = """
[+]  UID: 3A F7 35 01   ( ONUID, re-used )
[+] ATQA: 00 04
[+]  SAK: 08 [2]
[+] Possible types:
[+]    MIFARE Classic 1K
[=]
[=] Proprietary non iso14443-4 card found
[=] RATS not supported
[+] Magic capabilities... Gen 1a
[+] Prng detection..... weak

[+] Valid ISO 14443-A tag found

[?] Hint: try `hf mf info`
"""
        val r = CardSearch.parse(out, Band.HF)
        assertTrue(r.found)
        val h = r.hits.single()
        assertEquals("ISO 14443-A tag", h.title)
        assertEquals("3A F7 35 01", h.id)
        assertEquals(listOf("MIFARE Classic 1K"), h.types)
        assertEquals(listOf("ATQA", "SAK", "Magic capabilities", "Prng detection"), h.fields.map { it.key })
        assertEquals("08 [2]", h.fields[1].value)
        assertEquals(listOf("Proprietary non iso14443-4 card found", "RATS not supported"), h.notes)
    }

    @Test
    fun em410x() {
        val out = """
[=] NOTE: some demods output possible binary
[=] if it finds something that looks like a tag
[=] False Positives ARE possible
[=]
[=] Checking for known tags...
[=]
[+] EM 410x ID 0F0368568B
[+] EM410x ( RF/64 )
[=] -------- Possible de-scramble patterns ---------
[+] Unique TAG ID      : F0C06A16D1
[=] HoneyWell IdentKey
[+]     DEZ 8          : 06837899

[+] Valid EM410x ID found!

[+] Chipset detection: EM4x05 / EM4x69
"""
        val r = CardSearch.parse(out, Band.LF)
        val h = r.hits.single()
        assertEquals("EM410x ID", h.title)
        assertEquals("0F0368568B", h.id)
        assertTrue(h.fields.any { it.key == "Unique TAG ID" && it.value == "F0C06A16D1" })
        assertTrue(h.fields.any { it.key == "Chipset detection" && it.value == "EM4x05 / EM4x69" })
    }

    @Test
    fun nothingFound() {
        val r = CardSearch.parse("\n[!] No known/supported 13.56 MHz tags found\n", Band.HF)
        assertFalse(r.found)
        val e = CardSearch.parse("[!!] Failed to communicate\n[-] No known 125/134 kHz tags found!\n", Band.LF)
        assertFalse(e.found)
        assertEquals(listOf("Failed to communicate"), e.errors)
    }
}
