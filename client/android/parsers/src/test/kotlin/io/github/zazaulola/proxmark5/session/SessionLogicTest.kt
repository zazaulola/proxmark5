package io.github.zazaulola.proxmark5.session

import io.github.zazaulola.proxmark5.parse.ActivityKind
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogTailTest {
    private fun tmp(): File = Files.createTempFile("pm3tail", ".log").toFile().also { it.deleteOnExit() }

    private fun LogTail.text(max: Int = 64 * 1024): String? = read(max)?.let { String(it.bytes, Charsets.UTF_8) }

    @Test
    fun aMissingFileIsJustNothingYet() {
        val f = File(Files.createTempDirectory("pm3tail").toFile(), "out.log")
        val tail = LogTail(f)
        assertNull(tail.read())
        f.writeText("hello\n")
        assertEquals("hello\n", tail.text())
    }

    @Test
    fun returnsOnlyWhatWasAppendedSinceTheLastRead() {
        val f = tmp()
        val tail = LogTail(f)
        assertNull(tail.read())
        f.appendText("one\n")
        assertEquals("one\n", tail.text())
        assertNull(tail.read())
        f.appendText("two\nthree\n")
        assertEquals("two\nthree\n", tail.text())
        assertEquals(f.length(), tail.position)
    }

    @Test
    fun bigAppendComesInPiecesOfAtMostMax() {
        val f = tmp()
        f.writeText("x".repeat(1000))
        val tail = LogTail(f)
        var total = 0
        while (true) {
            val c = tail.read(300) ?: break
            assertTrue(c.bytes.size <= 300)
            total += c.bytes.size
        }
        assertEquals(1000, total)
    }

    @Test
    fun aShrunkFileIsReadAgainFromItsStartAndSaysSo() {
        val f = tmp()
        val tail = LogTail(f)
        f.writeText("a long first life of the log\n")
        tail.read()
        f.writeText("new\n") // cleared and written again, shorter than the position
        val c = tail.read()
        assertNotNull(c)
        assertTrue(c.truncated)
        assertEquals("new\n", String(c.bytes))
        f.appendText("more\n")
        val d = tail.read()
        assertNotNull(d)
        assertFalse(d.truncated)
        assertEquals("more\n", String(d.bytes))
    }

    @Test
    fun aLongBacklogIsCutToTheTailAtALineStart() {
        val f = tmp()
        val sb = StringBuilder()
        for (i in 0 until 200) sb.append("line-").append(i).append('\n')
        f.writeText(sb.toString())
        val tail = LogTail(f, maxBacklog = 100)
        val out = StringBuilder()
        var skipped = 0L
        while (true) {
            val c = tail.read(40) ?: break
            skipped += 0
            out.append(String(c.bytes))
            if (c.skipped > 0) skipped = c.skipped
        }
        val text = out.toString()
        assertTrue(skipped > 0)
        assertTrue(text.startsWith("line-"), "starts at a line, not in the middle of one: " + text.take(12))
        assertTrue(text.endsWith("line-199\n"))
        assertTrue(text.length <= 100)
    }

    @Test
    fun theSkippedCountSaysHowMuchOfTheBacklogWasLeftOut() {
        val f = tmp()
        f.writeText("0123456789\n".repeat(50))
        val tail = LogTail(f, maxBacklog = 60)
        val first = tail.read(1024)
        assertNotNull(first)
        assertTrue(first.skipped >= f.length() - 60)
        assertEquals(f.length(), tail.position)
    }
}

class Utf8ChunkerTest {
    @Test
    fun aGlyphSplitBetweenPiecesIsFinishedByTheNextOne() {
        val text = "Привет, мир ✓ 😀 done"
        val raw = text.toByteArray(Charsets.UTF_8)
        for (cut in 1 until raw.size) {
            val c = Utf8Chunker()
            val got = c.feed(raw.copyOfRange(0, cut)) + c.feed(raw.copyOfRange(cut, raw.size))
            assertEquals(text, got, "cut at $cut")
        }
    }

    @Test
    fun feedUsesOnlyTheGivenPrefix() {
        val c = Utf8Chunker()
        val buf = "abcdef".toByteArray()
        assertEquals("abc", c.feed(buf, 3))
        assertEquals("d", c.feed(buf.copyOfRange(3, 4)))
    }

    @Test
    fun malformedBytesBecomeReplacementCharacters() {
        val c = Utf8Chunker()
        assertEquals("a�b", c.feed(byteArrayOf('a'.code.toByte(), 0xFF.toByte(), 'b'.code.toByte())))
    }

    @Test
    fun bigPiecesWorkToo() {
        val c = Utf8Chunker()
        val text = "я".repeat(40_000)
        assertEquals(text, c.feed(text.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun resetForgetsAHalfGlyph() {
        val c = Utf8Chunker()
        val raw = "я".toByteArray(Charsets.UTF_8)
        c.feed(raw, 1)
        c.reset()
        assertEquals("ok", c.feed("ok".toByteArray()))
    }
}

class SessionSnapshotTest {
    @Test
    fun roundTripsEveryField() {
        val s = SessionSnapshot(
            epoch = 0x1234_5678_9abc_def0L,
            pid = 4242,
            ready = true,
            error = "",
            clientVersion = "Iceman/master/v4.20728",
            clientArmSrc = "a1b2c3d4e",
            link = LinkState.CONNECTED,
            linkDetail = "",
            info = "[ Proxmark3 ]\n  prox: «тест»",
            flashLease = false,
            activity = ActivitySnapshot(ActivityKind.SNIFFER, "hf 14a sniff", 1_700_000_000_000L, 2, 77L, stopping = true, stuck = false),
        )
        assertEquals(s, SessionSnapshot.parse(s.toJson()))
    }

    @Test
    fun garbageAndOtherVersionsAreNull() {
        assertNull(SessionSnapshot.parse(null))
        assertNull(SessionSnapshot.parse(""))
        assertNull(SessionSnapshot.parse("not json"))
        assertNull(SessionSnapshot.parse("""{"v":2,"epoch":1}"""))
        assertNull(SessionSnapshot.parse("""{"epoch":1}"""))
    }

    @Test
    fun unknownLinkCodeFallsBackToIdleAndUnknownKindToCommand() {
        val s = SessionSnapshot.parse("""{"v":1,"link":99,"kind":42}""")
        assertNotNull(s)
        assertEquals(LinkState.IDLE, s.link)
        assertEquals(ActivityKind.COMMAND, s.activity.kind)
    }

    @Test
    fun infoIsCutToTheLimit() {
        val s = SessionSnapshot(info = "x".repeat(IpcLimits.MAX_INFO_CHARS * 2))
        assertEquals(IpcLimits.MAX_INFO_CHARS, SessionSnapshot.parse(s.toJson())!!.info.length)
    }

    @Test
    fun exitMarkerRoundTripsAndRejectsGarbage() {
        val m = ExitMarker(epoch = 99L, pid = 1234, reason = "команда не остановилась", reconnect = true)
        assertEquals(m, ExitMarker.parse(m.toJson()))
        assertNull(ExitMarker.parse(null))
        assertNull(ExitMarker.parse(""))
        assertNull(ExitMarker.parse("{}"))
        assertNull(ExitMarker.parse("nonsense"))
    }

    @Test
    fun linkStateCodesAreStable() {
        assertEquals(
            listOf(0, 1, 2, 3, 4, 5, 6, 7),
            listOf(
                LinkState.IDLE, LinkState.OPENING, LinkState.CONNECTED, LinkState.FAILED,
                LinkState.LOST, LinkState.FLASHING, LinkState.CLOSING, LinkState.LEFT,
            ).map { it.code },
        )
        LinkState.entries.forEach { assertEquals(it, LinkState.of(it.code)) }
        assertEquals(LinkState.IDLE, LinkState.of(-5))
    }
}

class IpcLimitsTest {
    @Test
    fun smallResultsPassThroughUntouched() {
        val b = "hello".toByteArray()
        assertContentEquals(b, IpcLimits.clampResult(b))
    }

    @Test
    fun bigResultsAreCutToTheLimitWithAMarkAndNeverInsideAGlyph() {
        for (pad in 0..4) {
            val text = "a".repeat(pad) + "я".repeat(IpcLimits.MAX_RESULT_BYTES)
            val out = IpcLimits.clampResult(text.toByteArray(Charsets.UTF_8))
            assertTrue(out.size <= IpcLimits.MAX_RESULT_BYTES)
            val s = String(out, Charsets.UTF_8)
            assertFalse(s.contains('�'), "no broken glyph (pad $pad)")
            assertTrue(s.endsWith(IpcLimits.TRUNCATED_MARK))
        }
    }

    @Test
    fun exactlyTheLimitIsNotCut() {
        val b = ByteArray(IpcLimits.MAX_RESULT_BYTES) { 'x'.code.toByte() }
        assertEquals(b.size, IpcLimits.clampResult(b).size)
    }

    @Test
    fun idsKeepTheirOwnerAndDifferAcrossOwners() {
        val a = SessionIds.make(nonce = 12_345_678L, counter = 7)
        val b = SessionIds.make(nonce = 12_345_679L, counter = 7)
        assertEquals(SessionIds.owner(a), SessionIds.owner(SessionIds.make(12_345_678L, 99)))
        assertTrue(SessionIds.owner(a) != SessionIds.owner(b))
        assertTrue(a != b)
        assertEquals(12_345_678L, SessionIds.owner(a))
    }

    @Test
    fun transportCodesAreNotClientCodes() {
        assertTrue(ResultCode.isTransport(ResultCode.DROPPED))
        assertTrue(ResultCode.isTransport(ResultCode.TRANSPORT_LOST))
        assertFalse(ResultCode.isTransport(0))
        assertFalse(ResultCode.isTransport(ResultCode.BUSY))
        assertFalse(ResultCode.isTransport(-8)) // a PM3_E* value
    }
}

class StopPolicyTest {
    @Test
    fun cooperativeCommandsGetTheLongGraceAndAreNeverKilledUnasked() {
        assertEquals(StopPolicy.GRACE_MS, StopPolicy.graceMs("lf sniff"))
        assertEquals(StopPolicy.GRACE_MS, StopPolicy.graceMs("hf mf autopwn"))
        assertEquals(StopPolicy.GRACE_MS, StopPolicy.graceMs("hf 14a sim -t 1"))
        assertEquals(StopPolicy.GRACE_MS, StopPolicy.graceMs(""))
        assertFalse(StopPolicy.mayKillAutomatically("hf mf rdbl --blk 0"))
    }

    @Test
    fun commandsThatIgnoreEnterGetTheShortGraceAndMayBeKilled() {
        assertTrue(StopPolicy.isStubborn("hf mf hardnested --blk 0 -a -k FFFFFFFFFFFF --tblk 4 --ta"))
        assertTrue(StopPolicy.isStubborn("  HF  MFDES  chk  -d x"))
        assertTrue(StopPolicy.isStubborn("hf mfdes detect"))
        assertEquals(StopPolicy.STUBBORN_GRACE_MS, StopPolicy.graceMs("hf mf hardnested"))
        assertTrue(StopPolicy.mayKillAutomatically("hf mf hardnested"))
    }

    @Test
    fun onlyTheWordsBeforeTheFirstOptionNameTheCommand() {
        assertFalse(StopPolicy.isStubborn("hf mf dump -f hardnested"))
        assertFalse(StopPolicy.isStubborn("hf mf rdbl --blk 0"))
        assertFalse(StopPolicy.isStubborn("hf mf"))
    }

    @Test
    fun nothingInterruptsAFlash() {
        assertFalse(StopPolicy.mayInterrupt(LinkState.FLASHING))
        for (s in LinkState.entries.filter { it != LinkState.FLASHING }) assertTrue(StopPolicy.mayInterrupt(s), s.name)
    }

    @Test
    fun theServiceStaysUpInTheStatesThatNeedIt() {
        val up = LinkState.entries.filter { it.holdsService }.toSet()
        assertEquals(setOf(LinkState.OPENING, LinkState.CONNECTED, LinkState.FLASHING, LinkState.CLOSING), up)
    }
}
