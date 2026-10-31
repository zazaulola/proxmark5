package io.github.zazaulola.proxmark5.parse.output

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OutputScanTest {
    private fun scan(line: String) = OutputScan.scanLine(line)
    private fun kinds(line: String) = scan(line).map { it.kind }
    private fun one(line: String, kind: FoundKind): Found {
        val f = scan(line).filter { it.kind == kind }
        assertEquals(1, f.size, "expected one $kind in: $line -> ${scan(line)}")
        return f[0]
    }

    // offsets must point at the value as it stands in the line
    private fun assertSpan(line: String, f: Found, expected: String) =
        assertEquals(expected, line.substring(f.start, f.end), "span of $f in: $line")

    @Test
    fun filePathsInBackticks() {
        val a = "[+] loaded `/data/user/0/app/files/dumps/hf-mf-01A062BD-dump.json`"
        assertEquals(FoundKind.FILE, one(a, FoundKind.FILE).kind)
        assertEquals("/data/user/0/app/files/dumps/hf-mf-01A062BD-dump.json", one(a, FoundKind.FILE).value)

        val b = "[+] Saved 192 bytes to binary file `hf-mf-01A062BD-key-001.bin`"
        assertEquals("hf-mf-01A062BD-key-001.bin", one(b, FoundKind.FILE).value)

        val c = "[-] Error - can't find `/nonexistent/nope.bin`"
        assertEquals("/nonexistent/nope.bin", one(c, FoundKind.FILE).value)

        val d = "[+] Saved to json file /tmp/x/dumps/em_graph.pm3"
        assertEquals("/tmp/x/dumps/em_graph.pm3", one(d, FoundKind.FILE).value)
    }

    @Test
    fun hintsAreCommands() {
        val a = "[?] Hint: Try `hf mfu ndefread`"
        val f = one(a, FoundKind.COMMAND)
        assertEquals("hf mfu ndefread", f.value)
        assertSpan(a, f, "hf mfu ndefread")

        val b = "[?] Hint: Try `trace list -1 -t ...` to view trace.  Remember the `-1` param"
        // the command is a COMMAND; the `-1` backtick is not
        assertEquals(listOf(FoundKind.COMMAND), kinds(b))
        assertEquals("trace list -1 -t ...", scan(b)[0].value)
    }

    @Test
    fun uidsByLabel() {
        val a = "[+] UID.............. 04268512A25680"
        val f = one(a, FoundKind.UID)
        assertEquals("04268512A25680", f.value)
        assertEquals("UID", f.label)

        val b = "[+]     CSN... 6D C2 5B 15 FE FF 12 E0  uid"
        val g = one(b, FoundKind.UID)
        assertEquals("6DC25B15FEFF12E0", g.value)
        assertSpan(b, g, "6D C2 5B 15 FE FF 12 E0")

        val c = "[+] EM 410x ID 010872E77C"
        assertEquals("010872E77C", one(c, FoundKind.UID).value)

        val d = "[+] Indala (len 64)  Raw: a0000000a084f052"
        assertEquals("A0000000A084F052", one(d, FoundKind.UID).value)
    }

    @Test
    fun keysByLabelAndTable() {
        val row = "[+]  000 | 003 | D1E2AA68E39A | 1 | FFFFFFFFFFFF | 1 "
        val keys = scan(row).filter { it.kind == FoundKind.KEY }
        assertEquals(2, keys.size)
        assertEquals(listOf("D1E2AA68E39A", "FFFFFFFFFFFF"), keys.map { it.value })
        assertSpan(row, keys[0], "D1E2AA68E39A")

        val k = "[+]     Key 00 (ver 00). 0102030405060708090A0B0C0D0E0F10"
        val f = one(k, FoundKind.KEY)
        assertEquals("0102030405060708090A0B0C0D0E0F10", f.value)
    }

    @Test
    fun hexValuesNotMistakenForIds() {
        // ATQA / SAK: hex, not a UID
        assertEquals(FoundKind.HEX, one("[+] ATQA............. 03 44", FoundKind.HEX).kind)
        assertEquals("0344", one("[+] ATQA............. 03 44", FoundKind.HEX).value)
        // a run of zeros is not offered
        assertTrue(scan("[=]      |   1 | 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 | ................").isEmpty())
    }

    @Test
    fun echoLineGivesTheCommandOnly() {
        val e = "[offline|script] pm3 --> hf mf view -f traces/mifare/s50-empty.bin"
        assertEquals(listOf(FoundKind.COMMAND), kinds(e))
        assertEquals("hf mf view -f traces/mifare/s50-empty.bin", scan(e)[0].value)
        val u = "[usb] pm3 --> lf search"
        assertEquals("lf search", scan(u)[0].value)
    }

    @Test
    fun theBlockRowOfAViewer() {
        val row = "[=]    0 |   0 | 01 A0 62 BD 7E 08 04 00 01 1B 8C C2 D5 10 7E 1D | ..b.~.........~."
        // the 16-byte data is offered as hex (labelled form does not apply; it is a table row)
        // we accept zero or more HEX here, but must not call it a UID or KEY
        assertTrue(scan(row).none { it.kind == FoundKind.UID || it.kind == FoundKind.KEY })
    }

    @Test
    fun foundForTheLastRunOfACommand() {
        val lines = listOf(
            "[usb] pm3 --> hf 14a info",
            "[+] UID.............. 11223344",
            "[usb] pm3 --> lf search",
            "[+] EM 410x ID 010872E77C",
            "[usb] pm3 --> hf 14a info",
            "[+] UID.............. 04268512A25680",
            "[+] ATQA............. 03 44",
            "[usb] pm3 --> hw status",
        )
        val found = OutputScan.foundForCommand(lines, "hf 14a info")
        // the LAST run's output, not the first
        assertEquals(listOf("04268512A25680", "0344"), found.map { it.value })
        assertTrue(found.none { it.kind == FoundKind.COMMAND })
        assertEquals(listOf("010872E77C"), OutputScan.foundForCommand(lines, "lf search").map { it.value })
        assertTrue(OutputScan.foundForCommand(lines, "nope").isEmpty())
    }

    @Test
    fun wiegandEncoded() {
        assertEquals("002F623AE", one("[+] Wiegand: 002F623AE", FoundKind.HEX).value)
    }
}
